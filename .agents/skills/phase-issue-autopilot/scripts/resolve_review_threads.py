#!/usr/bin/env python3
"""Safely resolve fixed phase-issue-autopilot GitHub review threads."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterator


def _load_publish_review() -> Any:
    path = Path(__file__).with_name("publish_review.py")
    spec = importlib.util.spec_from_file_location("phase_autopilot_publish_review", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"publish_review module을 불러오지 못했습니다: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


publish_review = _load_publish_review()

API_VERSION = "2026-03-10"
MAX_GRAPHQL_PAGES = 100
FINDING_MARKER_RE = re.compile(
    r"<!-- phase-issue-autopilot-finding:v1 "
    r"repo=(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+) "
    r"issue=(?P<issue>\d+) pr=(?P<pr>\d+) base=(?P<base>[0-9a-f]{40}) "
    r"head=(?P<head>[0-9a-f]{40}) digest=(?P<digest>[0-9a-f]{64}) "
    r"key=(?P<key>[A-Za-z0-9_.:-]+) -->"
)

PULL_QUERY = """
query PullSnapshot($owner: String!, $name: String!, $number: Int!) {
  viewer { login }
  repository(owner: $owner, name: $name) {
    nameWithOwner
    pullRequest(number: $number) {
      id body state merged baseRefName baseRefOid headRefName headRefOid
      headRepository { nameWithOwner }
    }
  }
}
"""
THREADS_QUERY = """
query ReviewThreads($owner: String!, $name: String!, $number: Int!, $after: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      reviewThreads(first: 100, after: $after) {
        nodes {
          id isResolved isOutdated viewerCanResolve
          comments(first: 100) {
            nodes { id body author { login } }
            pageInfo { hasNextPage endCursor }
          }
        }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""
THREAD_COMMENTS_QUERY = """
query ReviewThreadComments($id: ID!, $after: String) {
  node(id: $id) {
    ... on PullRequestReviewThread {
      comments(first: 100, after: $after) {
        nodes { id body author { login } }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""
REVIEWS_QUERY = """
query Reviews($owner: String!, $name: String!, $number: Int!, $after: String) {
  repository(owner: $owner, name: $name) {
    pullRequest(number: $number) {
      reviews(first: 100, after: $after) {
        nodes { id body state author { login } commit { oid } }
        pageInfo { hasNextPage endCursor }
      }
    }
  }
}
"""
RESOLVE_MUTATION = """
mutation ResolveReviewThread($threadId: ID!) {
  resolveReviewThread(input: {threadId: $threadId}) {
    thread { id isResolved }
  }
}
"""


class ResolveError(RuntimeError):
    exit_code = 30


class SnapshotError(ResolveError):
    exit_code = 31


class OwnershipError(ResolveError):
    exit_code = 32


class GitHubError(ResolveError):
    exit_code = 33


class PostflightError(ResolveError):
    exit_code = 34


class IdempotencyError(ResolveError):
    exit_code = 35


class GhGraphQLClient:
    def _graphql(self, query: str, variables: dict[str, Any]) -> dict[str, Any]:
        command = [
            "gh",
            "api",
            "graphql",
            "--method",
            "POST",
            "-H",
            "Accept: application/vnd.github+json",
            "-H",
            f"X-GitHub-Api-Version: {API_VERSION}",
            "--input",
            "-",
        ]
        input_text = json.dumps(
            {"query": query, "variables": variables},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        try:
            result = subprocess.run(
                command,
                input=input_text,
                capture_output=True,
                text=True,
                timeout=60,
                shell=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise GitHubError(f"GitHub GraphQL 실행 실패: {exc}") from exc
        if result.returncode != 0:
            message = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
            raise GitHubError(f"GitHub GraphQL 실패: {message[:1000]}")
        try:
            value = json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise GitHubError("GitHub GraphQL이 JSON을 반환하지 않았습니다.") from exc
        if not isinstance(value, dict) or value.get("errors"):
            raise GitHubError(f"GitHub GraphQL 오류: {str(value.get('errors'))[:1000]}")
        data = value.get("data")
        if not isinstance(data, dict):
            raise GitHubError("GitHub GraphQL data가 객체가 아닙니다.")
        return data

    @staticmethod
    def _repo_parts(repo: str) -> tuple[str, str]:
        owner, name = repo.split("/", 1)
        return owner, name

    @staticmethod
    def _connection(value: Any, label: str) -> tuple[list[dict[str, Any]], bool, str | None]:
        if not isinstance(value, dict) or not isinstance(value.get("nodes"), list):
            raise GitHubError(f"{label} connection이 올바르지 않습니다.")
        page_info = value.get("pageInfo")
        if not isinstance(page_info, dict) or not isinstance(page_info.get("hasNextPage"), bool):
            raise GitHubError(f"{label} pageInfo가 올바르지 않습니다.")
        nodes = value["nodes"]
        if any(not isinstance(node, dict) for node in nodes):
            raise GitHubError(f"{label} node가 객체가 아닙니다.")
        cursor = page_info.get("endCursor")
        if page_info["hasNextPage"] and not isinstance(cursor, str):
            raise GitHubError(f"{label} 다음 cursor가 없습니다.")
        if page_info["hasNextPage"] and not nodes:
            raise GitHubError(f"{label} 다음 page가 있지만 현재 page가 비어 있습니다.")
        return nodes, page_info["hasNextPage"], cursor

    @staticmethod
    def _record_next_cursor(cursor: str | None, seen: set[str], label: str) -> None:
        if not isinstance(cursor, str) or not cursor:
            raise GitHubError(f"{label} 다음 cursor가 없습니다.")
        if cursor in seen:
            raise GitHubError(f"{label} cursor가 반복되었습니다: {cursor}")
        seen.add(cursor)

    def _thread_comments(self, thread: dict[str, Any]) -> list[dict[str, Any]]:
        nodes, has_next, cursor = self._connection(thread.get("comments"), "thread comments")
        comments = list(nodes)
        seen_ids = {str(node.get("id")) for node in comments}
        seen_cursors: set[str] = set()
        page_count = 1
        while has_next:
            if page_count >= MAX_GRAPHQL_PAGES:
                raise GitHubError("thread comments 페이지 제한을 초과했습니다.")
            self._record_next_cursor(cursor, seen_cursors, "thread comments")
            data = self._graphql(THREAD_COMMENTS_QUERY, {"id": thread.get("id"), "after": cursor})
            node = data.get("node")
            if not isinstance(node, dict):
                raise GitHubError("review thread node를 찾지 못했습니다.")
            page, has_next, cursor = self._connection(node.get("comments"), "thread comments")
            page_count += 1
            for comment in page:
                comment_id = str(comment.get("id"))
                if comment_id in seen_ids:
                    raise GitHubError(f"중복 review comment id: {comment_id}")
                seen_ids.add(comment_id)
                comments.append(comment)
        return comments

    def get_pull_state(self, repo: str, pr_number: int) -> dict[str, Any]:
        owner, name = self._repo_parts(repo)
        variables = {"owner": owner, "name": name, "number": pr_number}
        data = self._graphql(PULL_QUERY, variables)
        repository = data.get("repository")
        viewer = data.get("viewer")
        if not isinstance(repository, dict) or not isinstance(viewer, dict):
            raise GitHubError("repository 또는 viewer를 찾지 못했습니다.")
        pull = repository.get("pullRequest")
        if not isinstance(pull, dict):
            raise GitHubError("pull request를 찾지 못했습니다.")

        threads: list[dict[str, Any]] = []
        thread_ids: set[str] = set()
        thread_cursors: set[str] = set()
        cursor: str | None = None
        for _ in range(MAX_GRAPHQL_PAGES):
            page_data = self._graphql(THREADS_QUERY, {**variables, "after": cursor})
            connection = page_data["repository"]["pullRequest"]["reviewThreads"]
            page, has_next, cursor = self._connection(connection, "reviewThreads")
            for thread in page:
                thread_id = str(thread.get("id"))
                if not thread_id or thread_id in thread_ids:
                    raise GitHubError(f"중복 또는 누락 review thread id: {thread_id}")
                thread_ids.add(thread_id)
                item = dict(thread)
                item["comments"] = self._thread_comments(thread)
                threads.append(item)
            if not has_next:
                break
            self._record_next_cursor(cursor, thread_cursors, "reviewThreads")
        else:
            raise GitHubError("reviewThreads 페이지 제한을 초과했습니다.")

        reviews: list[dict[str, Any]] = []
        review_ids: set[str] = set()
        review_cursors: set[str] = set()
        cursor = None
        for _ in range(MAX_GRAPHQL_PAGES):
            page_data = self._graphql(REVIEWS_QUERY, {**variables, "after": cursor})
            connection = page_data["repository"]["pullRequest"]["reviews"]
            page, has_next, cursor = self._connection(connection, "reviews")
            for review in page:
                review_id = str(review.get("id"))
                if not review_id or review_id in review_ids:
                    raise GitHubError(f"중복 또는 누락 review id: {review_id}")
                review_ids.add(review_id)
                reviews.append(review)
            if not has_next:
                break
            self._record_next_cursor(cursor, review_cursors, "reviews")
        else:
            raise GitHubError("reviews 페이지 제한을 초과했습니다.")
        return {
            "viewer_login": viewer.get("login"),
            "repository": repository.get("nameWithOwner"),
            "pull": pull,
            "threads": threads,
            "reviews": reviews,
        }

    def resolve_thread(self, thread_id: str) -> dict[str, Any]:
        data = self._graphql(RESOLVE_MUTATION, {"threadId": thread_id})
        payload = data.get("resolveReviewThread")
        if not isinstance(payload, dict) or not isinstance(payload.get("thread"), dict):
            raise GitHubError("resolveReviewThread 응답에 thread가 없습니다.")
        return payload["thread"]


def _author_login(value: Any) -> str:
    return str(value.get("login") or "") if isinstance(value, dict) else ""


def _marker_matches(marker: dict[str, str], envelope: dict[str, Any], digest: str) -> bool:
    return marker == {
        "repo": envelope["repository"],
        "issue": str(envelope["issue_number"]),
        "pr": str(envelope["pull_number"]),
        "base": envelope["base"]["sha"],
        "head": envelope["head"]["sha"],
        "digest": digest,
    }


def _finding_marker_matches(
    marker: dict[str, str], envelope: dict[str, Any], digest: str, key: str
) -> bool:
    return marker == {
        "repo": envelope["repository"],
        "issue": str(envelope["issue_number"]),
        "pr": str(envelope["pull_number"]),
        "base": envelope["base"]["sha"],
        "head": envelope["head"]["sha"],
        "digest": digest,
        "key": key,
    }


def _verify_pair(previous: dict[str, Any], current: dict[str, Any]) -> None:
    immutable = ("repository", "issue_number", "pull_number", "phase", "step")
    for key in immutable:
        if previous[key] != current[key]:
            raise SnapshotError(f"previous/current {key}가 다릅니다.")
    if current["attempt"] <= previous["attempt"]:
        raise SnapshotError("current review attempt는 previous보다 커야 합니다.")
    if current["head"]["ref"] != previous["head"]["ref"]:
        raise SnapshotError("fix review의 head branch가 previous와 다릅니다.")
    if current["head"]["sha"] == previous["head"]["sha"]:
        raise SnapshotError("fix review의 head SHA가 previous와 같습니다.")


def _verify_pull(state: dict[str, Any], envelope: dict[str, Any], expected_base: str) -> str:
    viewer = state.get("viewer_login")
    if not isinstance(viewer, str) or not viewer:
        raise OwnershipError("현재 GitHub viewer login을 확인하지 못했습니다.")
    if str(state.get("repository") or "").lower() != envelope["repository"].lower():
        raise SnapshotError("GraphQL repository가 review envelope와 다릅니다.")
    pull = state.get("pull")
    if not isinstance(pull, dict):
        raise SnapshotError("PR snapshot이 없습니다.")
    head_repo = pull.get("headRepository")
    if not isinstance(head_repo, dict) or str(head_repo.get("nameWithOwner") or "").lower() != envelope["repository"].lower():
        raise SnapshotError("fork 또는 다른 repository의 PR head는 허용하지 않습니다.")
    if pull.get("state") != "OPEN" or pull.get("merged") is True:
        raise SnapshotError("PR이 open/unmerged 상태가 아닙니다.")
    if pull.get("baseRefName") != expected_base or pull.get("baseRefName") != envelope["base"]["ref"]:
        raise SnapshotError("PR base ref가 review envelope와 다릅니다.")
    if pull.get("baseRefOid") != envelope["base"]["sha"]:
        raise SnapshotError("PR base SHA가 current review와 다릅니다.")
    if pull.get("headRefName") != envelope["head"]["ref"] or pull.get("headRefOid") != envelope["head"]["sha"]:
        raise SnapshotError("PR head ref/SHA가 current review와 다릅니다.")
    closing = re.compile(
        rf"(?im)\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#0*{envelope['issue_number']}\b"
    )
    if closing.search(str(pull.get("body") or "")) is None:
        raise SnapshotError("PR 본문이 canonical Issue를 종료하도록 연결하지 않습니다.")
    return viewer


def _verify_review_marker(
    reviews: list[Any], envelope: dict[str, Any], digest: str, viewer: str
) -> None:
    owned: list[dict[str, Any]] = []
    for review in reviews:
        if not isinstance(review, dict):
            raise SnapshotError("review node가 객체가 아닙니다.")
        body = review.get("body")
        matches = list(publish_review.MARKER_RE.finditer(body)) if isinstance(body, str) else []
        expected = [match for match in matches if _marker_matches(match.groupdict(), envelope, digest)]
        if _author_login(review.get("author")) == viewer and expected:
            if len(expected) != 1 or len(matches) != 1:
                raise OwnershipError("autopilot review marker가 한 review에 중복되었습니다.")
            owned.append(review)
    if len(owned) != 1:
        raise OwnershipError(f"소유한 autopilot review marker 수가 1이 아닙니다: {len(owned)}")
    review = owned[0]
    if review.get("state") != "COMMENTED":
        raise SnapshotError("autopilot review가 COMMENTED 상태가 아닙니다.")
    commit = review.get("commit")
    if not isinstance(commit, dict) or commit.get("oid") != envelope["head"]["sha"]:
        raise SnapshotError("autopilot review commit SHA가 marker와 다릅니다.")


def _index_threads(
    threads: list[Any], envelope: dict[str, Any], digest: str, viewer: str
) -> dict[str, dict[str, Any]]:
    expected_keys = {finding["finding_key"] for finding in envelope["findings"]}
    result: dict[str, dict[str, Any]] = {}
    for thread in threads:
        if not isinstance(thread, dict):
            raise SnapshotError("review thread가 객체가 아닙니다.")
        comments = thread.get("comments")
        if not isinstance(comments, list) or not comments:
            raise SnapshotError("review thread root comment가 없습니다.")
        root = comments[0]
        if not isinstance(root, dict) or _author_login(root.get("author")) != viewer:
            continue
        all_matches = []
        for comment in comments:
            body = comment.get("body") if isinstance(comment, dict) else None
            if isinstance(body, str):
                all_matches.extend(FINDING_MARKER_RE.finditer(body))
        relevant = [
            match
            for match in all_matches
            if match.group("key") in expected_keys
            and _finding_marker_matches(
                match.groupdict(), envelope, digest, match.group("key")
            )
        ]
        if not relevant:
            continue
        root_body = root.get("body")
        root_matches = list(FINDING_MARKER_RE.finditer(root_body)) if isinstance(root_body, str) else []
        root_relevant = [
            match
            for match in root_matches
            if match.group("key") in expected_keys
            and _finding_marker_matches(
                match.groupdict(), envelope, digest, match.group("key")
            )
        ]
        if len(relevant) != 1 or len(root_relevant) != 1 or len(root_matches) != 1:
            raise OwnershipError("autopilot finding marker가 thread에서 중복되거나 root 밖에 있습니다.")
        key = root_relevant[0].group("key")
        if key in result:
            raise OwnershipError(f"같은 finding marker를 가진 thread가 중복되었습니다: {key}")
        thread_id = thread.get("id")
        if not isinstance(thread_id, str) or not thread_id:
            raise SnapshotError("review thread id가 없습니다.")
        for field in ("isResolved", "isOutdated", "viewerCanResolve"):
            if not isinstance(thread.get(field), bool):
                raise SnapshotError(f"review thread {field}가 boolean이 아닙니다.")
        result[key] = thread
    missing = expected_keys - set(result)
    if missing:
        raise OwnershipError(f"autopilot finding thread marker가 없습니다: {sorted(missing)}")
    return result


def _load_verified_state(
    client: Any,
    previous: dict[str, Any],
    current: dict[str, Any],
    expected_base: str,
) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    state = client.get_pull_state(current["repository"], current["pull_number"])
    viewer = _verify_pull(state, current, expected_base)
    previous_digest = publish_review.build_payload(previous)[1]
    current_digest = publish_review.build_payload(current)[1]
    reviews = state.get("reviews")
    threads = state.get("threads")
    if not isinstance(reviews, list) or not isinstance(threads, list):
        raise SnapshotError("review 또는 thread 목록이 없습니다.")
    _verify_review_marker(reviews, previous, previous_digest, viewer)
    _verify_review_marker(reviews, current, current_digest, viewer)
    return (
        _index_threads(threads, previous, previous_digest, viewer),
        _index_threads(threads, current, current_digest, viewer),
    )


def _plan(
    previous_threads: dict[str, dict[str, Any]],
    current_threads: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    candidates: list[dict[str, str]] = []
    remaining: list[str] = []
    already_resolved: list[str] = []
    for key, thread in sorted(previous_threads.items()):
        if thread["isResolved"]:
            already_resolved.append(key)
            continue
        if key not in current_threads:
            candidates.append({"key": key, "thread_id": thread["id"], "reason": "fixed"})
        elif thread["isOutdated"] and current_threads[key]["id"] != thread["id"]:
            candidates.append({"key": key, "thread_id": thread["id"], "reason": "reanchored"})
        else:
            remaining.append(key)
    return {
        "candidates": candidates,
        "remaining": remaining,
        "already_resolved": already_resolved,
    }


@contextmanager
def _same_host_resolve_lock(repo: str, pr_number: int) -> Iterator[None]:
    key = hashlib.sha256(f"{repo.lower()}:{pr_number}".encode()).hexdigest()
    lock_path = Path(tempfile.gettempdir()) / f"phase-review-resolver-{key}.lock"
    try:
        handle = lock_path.open("a+b")
    except OSError as exc:
        raise IdempotencyError(f"review resolve lock을 열지 못했습니다: {exc}") from exc
    locked = False
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (ImportError, OSError) as exc:
            raise IdempotencyError("같은 PR의 다른 로컬 thread resolver가 실행 중입니다.") from exc
        locked = True
        yield
    finally:
        if locked:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def resolve(
    client: Any,
    previous: dict[str, Any],
    current: dict[str, Any],
    *,
    expected_base: str,
    apply: bool,
) -> dict[str, Any]:
    _verify_pair(previous, current)
    with _same_host_resolve_lock(current["repository"], current["pull_number"]):
        previous_threads, current_threads = _load_verified_state(
            client, previous, current, expected_base
        )
        initial_plan = _plan(previous_threads, current_threads)
        denied = [
            item["thread_id"]
            for item in initial_plan["candidates"]
            if not previous_threads[item["key"]]["viewerCanResolve"]
        ]
        if denied:
            raise OwnershipError(
                f"thread를 resolve할 권한이 없습니다: {sorted(denied)}"
            )
        if not apply:
            return {"action": "dry-run", **initial_plan}

        resolved: list[dict[str, str]] = []
        for candidate in initial_plan["candidates"]:
            previous_threads, current_threads = _load_verified_state(
                client, previous, current, expected_base
            )
            fresh_plan = _plan(previous_threads, current_threads)
            fresh = next(
                (
                    item
                    for item in fresh_plan["candidates"]
                    if item["thread_id"] == candidate["thread_id"]
                    and item["key"] == candidate["key"]
                ),
                None,
            )
            thread = previous_threads[candidate["key"]]
            if thread["isResolved"]:
                resolved.append({**candidate, "result": "already-resolved"})
                continue
            if fresh is None:
                raise SnapshotError("resolve 직전 candidate 상태가 바뀌었습니다.")
            if not thread["viewerCanResolve"]:
                raise OwnershipError(f"thread를 resolve할 권한이 없습니다: {thread['id']}")
            try:
                response = client.resolve_thread(thread["id"])
            except GitHubError:
                recovered_previous, _ = _load_verified_state(
                    client, previous, current, expected_base
                )
                if not recovered_previous[candidate["key"]]["isResolved"]:
                    raise
                resolved.append({**candidate, "result": "recovered"})
                continue
            if not isinstance(response, dict) or response.get("id") != thread["id"]:
                raise PostflightError("resolveReviewThread 응답의 thread id가 다릅니다.")
            post_previous, _ = _load_verified_state(client, previous, current, expected_base)
            if not post_previous[candidate["key"]]["isResolved"]:
                raise PostflightError(f"thread postflight isResolved=true를 확인하지 못했습니다: {thread['id']}")
            resolved.append({**candidate, "result": "resolved"})

        final_previous, final_current = _load_verified_state(
            client, previous, current, expected_base
        )
        final_plan = _plan(final_previous, final_current)
        unresolved_ids = {
            item["thread_id"] for item in final_plan["candidates"]
        }
        if unresolved_ids:
            raise PostflightError(f"resolve되지 않은 candidate thread가 있습니다: {sorted(unresolved_ids)}")
        return {
            "action": "applied",
            "resolved": resolved,
            "remaining": final_plan["remaining"],
            "already_resolved": final_plan["already_resolved"],
        }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous-input", required=True, help="이전 review envelope JSON")
    parser.add_argument("--current-input", required=True, help="fresh review envelope JSON")
    parser.add_argument("--repo", required=True, help="OWNER/REPO")
    parser.add_argument("--pr", required=True, type=int, help="pull request number")
    parser.add_argument("--expected-base", default="develop")
    parser.add_argument("--apply", action="store_true", help="실제 thread resolve mutation 수행")
    return parser


def main(
    argv: list[str] | None = None,
    *,
    client_factory: Callable[[], Any] = GhGraphQLClient,
) -> int:
    args = build_parser().parse_args(argv)
    try:
        previous = publish_review.validate_envelope(
            publish_review._load_json(args.previous_input),
            expected_repo=args.repo,
            expected_pr=args.pr,
            expected_base=args.expected_base,
        )
        current = publish_review.validate_envelope(
            publish_review._load_json(args.current_input),
            expected_repo=args.repo,
            expected_pr=args.pr,
            expected_base=args.expected_base,
        )
        result = resolve(
            client_factory(),
            previous,
            current,
            expected_base=args.expected_base,
            apply=args.apply,
        )
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
        return 0
    except (publish_review.ReviewError, ResolveError) as exc:
        print(f"RESOLVE_ERROR: {exc}", file=sys.stderr)
        return getattr(exc, "exit_code", 30)


if __name__ == "__main__":
    raise SystemExit(main())
