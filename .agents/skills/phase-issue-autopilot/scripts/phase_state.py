#!/usr/bin/env python3
"""Render and inspect Phase Step GitHub Issue state without a local state file."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterator


DEFAULT_TEMPLATE = Path(".github/ISSUE_TEMPLATE/phase-step.md")
DEFAULT_BASE = "develop"
MARKER_PREFIX = "<!-- phase-step:v1"
MARKER_RE = re.compile(
    r"<!-- phase-step:v1 phase=(?P<phase>[a-z0-9][a-z0-9-]*) "
    r"step=(?P<step>\d{2}) doc=(?P<doc>[A-Za-z0-9_./-]+) -->"
)
PHASE_RE = re.compile(r"^phase-\d+$")
STEP_DOC_RE = re.compile(
    r"^docs/phases/(?P<phase>phase-\d+)/step-(?P<step>\d{2})-[a-z0-9][a-z0-9-]*\.md$"
)
BRANCH_RE = re.compile(
    r"^codex/(?P<phase>phase-\d+)-step-(?P<step>\d{2})-[a-z0-9][a-z0-9-]*$"
)
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
REQUIRED_FRONTMATTER = {"name", "about", "title", "labels", "assignees"}
REQUIRED_HEADINGS = (
    "Step 계획",
    "선행 Step",
    "작업 추적",
    "완료 체크",
    "검증 결과",
)


class PhaseStateError(RuntimeError):
    """Raised when state is invalid or ambiguous."""


class GitHubError(PhaseStateError):
    """Raised when a GitHub CLI read or write fails."""


@dataclass(frozen=True)
class IssueSpec:
    phase: str
    step: int
    issue_title: str
    step_doc: str
    predecessor: str
    branch: str
    pr: str = "미생성"
    blocker: str = "없음"

    @property
    def marker(self) -> str:
        return (
            f"<!-- phase-step:v1 phase={self.phase} step={self.step:02d} "
            f"doc={self.step_doc} -->"
        )


@contextmanager
def _same_host_creation_lock(repo: str, spec: IssueSpec) -> Iterator[None]:
    """Serialize Issue creation across local worktrees and worker processes."""
    lock_key = hashlib.sha256(
        f"{repo.lower()}:{spec.phase}:{spec.step:02d}".encode("utf-8")
    ).hexdigest()[:24]
    lock_path = Path(tempfile.gettempdir()) / f"phase-issue-autopilot-{lock_key}.lock"
    try:
        handle = lock_path.open("a+b")
    except OSError as exc:
        raise PhaseStateError(f"Issue 생성 lock을 열지 못했습니다: {lock_path}: {exc}") from exc

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
            raise PhaseStateError(
                "동일 Phase/Step의 다른 로컬 실행이 Issue 생성을 선점했습니다."
            ) from exc
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


def _read_utf8(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        raise PhaseStateError(f"UTF-8 파일을 읽지 못했습니다: {path}: {exc}") from exc


def _normalize_newlines(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    normalized = _normalize_newlines(text)
    lines = normalized.splitlines()
    if not lines or lines[0] != "---":
        raise PhaseStateError("Issue template YAML frontmatter가 없습니다.")
    try:
        closing = lines.index("---", 1)
    except ValueError as exc:
        raise PhaseStateError("Issue template YAML frontmatter가 닫히지 않았습니다.") from exc

    metadata: dict[str, str] = {}
    for raw in lines[1:closing]:
        if not raw.strip():
            continue
        if ":" not in raw:
            raise PhaseStateError(f"해석할 수 없는 frontmatter 행: {raw}")
        key, value = raw.split(":", 1)
        key = key.strip()
        if key in metadata:
            raise PhaseStateError(f"중복 frontmatter 키: {key}")
        metadata[key] = value.strip().strip('"').strip("'")

    if set(metadata) != REQUIRED_FRONTMATTER:
        raise PhaseStateError(
            "Issue template frontmatter 키가 예상과 다릅니다: "
            f"expected={sorted(REQUIRED_FRONTMATTER)}, actual={sorted(metadata)}"
        )
    if metadata["name"] != "Phase Step":
        raise PhaseStateError("Phase Step Issue template이 아닙니다.")

    body = "\n".join(lines[closing + 1 :]).strip("\n") + "\n"
    return metadata, body


def _replace_section(body: str, heading: str, replacement: str) -> str:
    pattern = re.compile(
        rf"(?ms)^## {re.escape(heading)}\n.*?(?=^## |\Z)"
    )
    matches = list(pattern.finditer(body))
    if len(matches) != 1:
        raise PhaseStateError(
            f"Issue template heading은 정확히 한 번 필요합니다: {heading} ({len(matches)}개)"
        )
    section = f"## {heading}\n\n{replacement.rstrip()}\n\n"
    return body[: matches[0].start()] + section + body[matches[0].end() :]


def _validate_template_structure(body: str) -> None:
    for heading in REQUIRED_HEADINGS:
        if len(re.findall(rf"(?m)^## {re.escape(heading)}$", body)) != 1:
            raise PhaseStateError(f"Issue template 구조가 변경되었습니다: {heading}")
    for line in ("- branch:", "- PR:", "- blocker: 없음"):
        if body.count(line) != 1:
            raise PhaseStateError(f"Issue template 추적 필드가 변경되었습니다: {line}")


def _validate_spec(spec: IssueSpec) -> None:
    if not PHASE_RE.fullmatch(spec.phase):
        raise PhaseStateError(f"잘못된 Phase slug: {spec.phase}")
    if spec.step < 1 or spec.step > 99:
        raise PhaseStateError("Step 번호는 1..99 범위여야 합니다.")
    if any(char in spec.issue_title for char in "\r\n") or not spec.issue_title.strip():
        raise PhaseStateError("Issue 제목은 비어 있지 않은 한 줄이어야 합니다.")
    if len(spec.issue_title) > 200:
        raise PhaseStateError("Issue 제목은 200자를 넘을 수 없습니다.")

    doc_match = STEP_DOC_RE.fullmatch(spec.step_doc)
    if not doc_match:
        raise PhaseStateError(
            "Step 문서는 docs/phases/phase-N/step-NN-*.md 형식이어야 합니다."
        )
    if doc_match.group("phase") != spec.phase or int(doc_match.group("step")) != spec.step:
        raise PhaseStateError("Phase/Step 인자와 Step 문서 경로가 일치하지 않습니다.")
    doc_path = PurePosixPath(spec.step_doc)
    if doc_path.is_absolute() or ".." in doc_path.parts or "\\" in spec.step_doc:
        raise PhaseStateError("Step 문서는 저장소 내부 POSIX 상대 경로여야 합니다.")

    branch_match = BRANCH_RE.fullmatch(spec.branch)
    if not branch_match:
        raise PhaseStateError(
            "branch는 codex/phase-N-step-NN-slug 형식이어야 합니다."
        )
    if branch_match.group("phase") != spec.phase or int(branch_match.group("step")) != spec.step:
        raise PhaseStateError("Phase/Step 인자와 branch 이름이 일치하지 않습니다.")
    if spec.predecessor != "없음" and not re.fullmatch(r"#\d+", spec.predecessor):
        raise PhaseStateError('선행 Step은 "없음" 또는 #<Issue 번호>여야 합니다.')
    if any(char in spec.pr for char in "\r\n") or any(char in spec.blocker for char in "\r\n"):
        raise PhaseStateError("PR과 blocker 값은 한 줄이어야 합니다.")


def render_issue(template_path: Path, spec: IssueSpec) -> tuple[dict[str, str], str]:
    _validate_spec(spec)
    metadata, body = _parse_frontmatter(_read_utf8(template_path))
    _validate_template_structure(body)
    if MARKER_PREFIX in body:
        raise PhaseStateError("Issue template에 autopilot marker를 직접 넣지 마세요.")

    body = _replace_section(body, "Step 계획", f"- `{spec.step_doc}`")
    body = _replace_section(body, "선행 Step", f"- {spec.predecessor}")
    body = _replace_section(
        body,
        "작업 추적",
        "\n".join(
            (
                f"- branch: `{spec.branch}`",
                f"- PR: {spec.pr}",
                f"- blocker: {spec.blocker}",
            )
        ),
    )
    return metadata, f"{spec.marker}\n\n{body.strip()}\n"


def _validate_repo(repo: str) -> None:
    if not REPO_RE.fullmatch(repo):
        raise PhaseStateError("repository는 OWNER/REPO 형식이어야 합니다.")


def _run_gh(args: list[str], *, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            ["gh", *args],
            input=input_text,
            capture_output=True,
            text=True,
            timeout=60,
            shell=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GitHubError(f"gh 실행 실패: {exc}") from exc
    if result.returncode != 0:
        message = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise GitHubError(f"gh {' '.join(args[:3])} 실패: {message[:1000]}")
    return result


def _run_gh_json(args: list[str]) -> Any:
    result = _run_gh(args)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise GitHubError("gh가 유효한 JSON을 반환하지 않았습니다.") from exc


def _merge_commit_oid(pr: dict[str, Any]) -> str | None:
    merge_commit = pr.get("mergeCommit")
    if isinstance(merge_commit, dict):
        oid = merge_commit.get("oid")
    else:
        oid = merge_commit
    return oid if isinstance(oid, str) and SHA_RE.fullmatch(oid) else None


def _merge_commit_reachable_from_base(repo: str, oid: str) -> bool:
    comparison = _run_gh_json(["api", f"repos/{repo}/compare/{oid}...{DEFAULT_BASE}"])
    if not isinstance(comparison, dict) or not isinstance(comparison.get("status"), str):
        raise GitHubError("merge commit reachability 응답 형식이 올바르지 않습니다.")
    return comparison["status"] in {"ahead", "identical"}


def _parse_marker(body: str) -> list[dict[str, str]]:
    text = body or ""
    matches = [match.groupdict() for match in MARKER_RE.finditer(text)]
    if text.count(MARKER_PREFIX) != len(matches):
        raise PhaseStateError("손상되거나 지원하지 않는 Phase Step marker가 있습니다.")
    return matches


def inspect_state(repo: str, spec: IssueSpec) -> dict[str, Any]:
    _validate_repo(repo)
    _validate_spec(spec)
    issues = _run_gh_json(
        [
            "issue",
            "list",
            "--repo",
            repo,
            "--state",
            "all",
            "--limit",
            "1000",
            "--json",
            "number,state,title,body,url",
        ]
    )
    if not isinstance(issues, list):
        raise GitHubError("Issue 목록 JSON 형식이 올바르지 않습니다.")

    exact: list[dict[str, Any]] = []
    conflicting: list[dict[str, Any]] = []
    legacy: list[dict[str, Any]] = []
    for issue in issues:
        if not isinstance(issue, dict):
            continue
        markers = _parse_marker(str(issue.get("body") or ""))
        if len(markers) > 1:
            raise PhaseStateError(f"Issue #{issue.get('number')}에 marker가 중복되었습니다.")
        if markers:
            marker = markers[0]
            if marker["phase"] == spec.phase and int(marker["step"]) == spec.step:
                if marker["doc"] == spec.step_doc:
                    exact.append(issue)
                else:
                    conflicting.append(issue)
        elif issue.get("title") == spec.issue_title or spec.step_doc in str(issue.get("body") or ""):
            legacy.append(issue)

    prs = _run_gh_json(
        [
            "pr",
            "list",
            "--repo",
            repo,
            "--state",
            "all",
            "--head",
            spec.branch,
            "--limit",
            "100",
            "--json",
            "number,state,isDraft,mergedAt,mergeCommit,headRefName,baseRefName,url,body",
        ]
    )
    if not isinstance(prs, list):
        raise GitHubError("PR 목록 JSON 형식이 올바르지 않습니다.")

    blockers: list[str] = []
    if len(exact) > 1:
        blockers.append("DUPLICATE_ISSUE")
    if conflicting:
        blockers.append("CONFLICTING_STEP_MARKER")
    if not exact and legacy:
        blockers.append("LEGACY_ISSUE_REQUIRES_ADOPTION")
    if len(prs) > 1:
        blockers.append("DUPLICATE_PR")
    if any(pr.get("baseRefName") != DEFAULT_BASE for pr in prs if isinstance(pr, dict)):
        blockers.append("WRONG_PR_BASE")
    if any(
        pr.get("state") == "CLOSED" and not pr.get("mergedAt")
        for pr in prs
        if isinstance(pr, dict)
    ):
        blockers.append("CLOSED_UNMERGED_PR")

    merged_prs = [
        pr for pr in prs if isinstance(pr, dict) and isinstance(pr.get("mergedAt"), str)
    ]
    reachable_merge_commits: list[str] = []
    for pr in merged_prs:
        oid = _merge_commit_oid(pr)
        if oid is not None and _merge_commit_reachable_from_base(repo, oid):
            reachable_merge_commits.append(oid)
        else:
            blockers.append("MERGED_PR_NOT_REACHABLE_FROM_DEVELOP")
    if (
        len(exact) == 1
        and exact[0].get("state") == "CLOSED"
        and not merged_prs
    ):
        blockers.append("CLOSED_ISSUE_WITHOUT_MERGED_PR")

    if blockers:
        next_action = "blocked"
    elif exact:
        issue_open = exact[0].get("state") == "OPEN"
        next_action = "close_issue" if issue_open and reachable_merge_commits else "resume"
    else:
        next_action = "create_issue"
    return {
        "base": DEFAULT_BASE,
        "branch": spec.branch,
        "issue_matches": exact,
        "conflicting_issue_markers": conflicting,
        "legacy_candidates": legacy,
        "next_action": next_action,
        "pr_matches": prs,
        "reachable_merge_commits": reachable_merge_commits,
        "blockers": blockers,
    }


def _json_print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2))


def _spec_from_args(args: argparse.Namespace) -> IssueSpec:
    return IssueSpec(
        phase=args.phase,
        step=args.step,
        issue_title=args.issue_title,
        step_doc=args.step_doc.replace("\\", "/"),
        predecessor=args.predecessor,
        branch=args.branch,
        pr=getattr(args, "pr", "미생성"),
        blocker=getattr(args, "blocker", "없음"),
    )


def _add_spec_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--phase", required=True, help="Phase slug, e.g. phase-1")
    parser.add_argument("--step", required=True, type=int, help="Step number")
    parser.add_argument("--issue-title", required=True, help="Filled GitHub Issue title")
    parser.add_argument("--step-doc", required=True, help="Repo-relative Step document path")
    parser.add_argument("--predecessor", required=True, help='"없음" or #<Issue number>')
    parser.add_argument("--branch", required=True, help="Deterministic Step branch")
    parser.add_argument("--pr", default="미생성", help="PR URL or current state")
    parser.add_argument("--blocker", default="없음", help="Current blocker summary")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    render = subparsers.add_parser("render-issue", help="Render the repository Issue template")
    _add_spec_arguments(render)
    render.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)

    inspect = subparsers.add_parser("inspect", help="Inspect Issue and PR state without writes")
    _add_spec_arguments(inspect)
    inspect.add_argument("--repo", required=True, help="OWNER/REPO")

    create = subparsers.add_parser("create-issue", help="Create one Issue idempotently")
    _add_spec_arguments(create)
    create.add_argument("--repo", required=True, help="OWNER/REPO")
    create.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)
    create.add_argument("--apply", action="store_true", help="Perform the GitHub write")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        spec = _spec_from_args(args)
        if args.command == "render-issue":
            _, body = render_issue(args.template, spec)
            print(body, end="")
            return 0
        if args.command == "inspect":
            state = inspect_state(args.repo, spec)
            _json_print(state)
            return 2 if state["blockers"] else 0

        state = inspect_state(args.repo, spec)
        if state["blockers"]:
            _json_print(state)
            return 2
        if state["issue_matches"]:
            _json_print({"action": "existing", "state": state})
            return 0
        metadata, body = render_issue(args.template, spec)
        if not args.apply:
            _json_print(
                {
                    "action": "would_create",
                    "base": DEFAULT_BASE,
                    "body": body,
                    "labels": metadata.get("labels", ""),
                    "title": spec.issue_title,
                }
            )
            return 0

        with _same_host_creation_lock(args.repo, spec):
            locked_state = inspect_state(args.repo, spec)
            if locked_state["blockers"]:
                _json_print(locked_state)
                return 2
            if locked_state["issue_matches"]:
                _json_print({"action": "existing", "state": locked_state})
                return 0

            command = [
                "issue",
                "create",
                "--repo",
                args.repo,
                "--title",
                spec.issue_title,
                "--body-file",
                "-",
            ]
            if metadata.get("labels"):
                command.extend(("--label", metadata["labels"]))
            if metadata.get("assignees"):
                command.extend(("--assignee", metadata["assignees"]))
            result = _run_gh(command, input_text=body)
            post_state = inspect_state(args.repo, spec)
            if post_state["blockers"] or len(post_state["issue_matches"]) != 1:
                raise PhaseStateError("Issue 생성 후 유일성 검증에 실패했습니다.")
            _json_print(
                {
                    "action": "created",
                    "state": post_state,
                    "url": result.stdout.strip(),
                }
            )
        return 0
    except GitHubError as exc:
        print(f"GITHUB_ERROR: {exc}", file=sys.stderr)
        return 3
    except PhaseStateError as exc:
        print(f"STATE_ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
