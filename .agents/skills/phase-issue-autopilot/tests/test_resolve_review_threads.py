from __future__ import annotations

import copy
import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "resolve_review_threads.py"
SPEC = importlib.util.spec_from_file_location("resolve_review_threads", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
resolver = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = resolver
SPEC.loader.exec_module(resolver)

publish_review = resolver.publish_review
REPO = "owner/repo"
PR_NUMBER = 11
BASE_SHA = "a" * 40
OLD_HEAD = "b" * 40
NEW_HEAD = "c" * 40


def finding(
    key: str = "finding-1", *, line: int = 1, severity: str = "major"
) -> dict[str, object]:
    return {
        "finding_key": key,
        "dimension": "correctness",
        "severity": severity,
        "title": "제목",
        "tldr": "설명",
        "good": "좋은 점",
        "fix_markdown": "수정하세요.",
        "path": "src/example.py",
        "line": line,
        "side": "RIGHT",
    }


def envelope(
    *, attempt: int, head: str, findings: list[dict[str, object]] | None = None
) -> dict[str, object]:
    values = findings or []
    return {
        "schema_version": 2,
        "repository": REPO,
        "issue_number": 7,
        "pull_number": PR_NUMBER,
        "phase": "phase-1",
        "step": 1,
        "attempt": attempt,
        "base": {"ref": "develop", "sha": BASE_SHA},
        "head": {"ref": "codex/phase-1-step-01-foundation", "sha": head},
        "verdict": publish_review.expected_verdict(values),
        "summary": {
            "walkthrough": "첫 번째 줄\n두 번째 줄",
            "strengths": ["좋은 점"],
            "next_actions": ["다음 작업"],
        },
        "findings": values,
    }


def review(value: dict[str, object], *, author: str = "reviewer") -> dict[str, object]:
    payload, _ = publish_review.build_payload(value)
    return {
        "id": f"R-{value['attempt']}-{author}",
        "body": payload["body"],
        "state": "COMMENTED",
        "author": {"login": author},
        "commit": {"oid": value["head"]["sha"]},
    }


def thread(
    value: dict[str, object],
    key: str,
    *,
    thread_id: str,
    author: str = "reviewer",
    resolved: bool = False,
    outdated: bool = False,
    can_resolve: bool = True,
) -> dict[str, object]:
    payload, _ = publish_review.build_payload(value)
    comment = next(
        item for item in payload["comments"] if f"key={key} -->" in item["body"]
    )
    return {
        "id": thread_id,
        "isResolved": resolved,
        "isOutdated": outdated,
        "viewerCanResolve": can_resolve,
        "comments": [
            {"id": f"C-{thread_id}", "body": comment["body"], "author": {"login": author}}
        ],
    }


def pull_state(
    previous: dict[str, object],
    current: dict[str, object],
    threads: list[dict[str, object]],
) -> dict[str, object]:
    return {
        "viewer_login": "reviewer",
        "repository": REPO,
        "pull": {
            "id": "PR-id",
            "body": "Closes #7",
            "state": "OPEN",
            "merged": False,
            "baseRefName": "develop",
            "baseRefOid": BASE_SHA,
            "headRefName": current["head"]["ref"],
            "headRefOid": current["head"]["sha"],
            "headRepository": {"nameWithOwner": REPO},
        },
        "threads": threads,
        "reviews": [review(previous), review(current)],
    }


class FakeGhClient:
    def __init__(
        self,
        state: dict[str, object],
        *,
        lose_response: bool = False,
        persist_resolve: bool = True,
        drift_at: int | None = None,
    ) -> None:
        self.state = copy.deepcopy(state)
        self.lose_response = lose_response
        self.persist_resolve = persist_resolve
        self.drift_at = drift_at
        self.reads = 0
        self.mutations: list[str] = []

    def get_pull_state(self, repo: str, pr_number: int) -> dict[str, object]:
        self.assert_scope(repo, pr_number)
        self.reads += 1
        value = copy.deepcopy(self.state)
        if self.reads == self.drift_at:
            value["pull"]["headRefOid"] = "d" * 40
        return value

    @staticmethod
    def assert_scope(repo: str, pr_number: int) -> None:
        if repo != REPO or pr_number != PR_NUMBER:
            raise AssertionError("wrong fake scope")

    def resolve_thread(self, thread_id: str) -> dict[str, object]:
        self.mutations.append(thread_id)
        target = next(item for item in self.state["threads"] if item["id"] == thread_id)
        if self.persist_resolve:
            target["isResolved"] = True
        if self.lose_response:
            self.lose_response = False
            raise resolver.GitHubError("mutation 응답 유실")
        return {"id": thread_id, "isResolved": True}


class ResolveStateMachineTests(unittest.TestCase):
    def values(
        self, *, current_findings: list[dict[str, object]] | None = None
    ) -> tuple[dict[str, object], dict[str, object]]:
        return (
            envelope(attempt=0, head=OLD_HEAD, findings=[finding()]),
            envelope(attempt=1, head=NEW_HEAD, findings=current_findings),
        )

    def run_apply(
        self, client: FakeGhClient, previous: dict[str, object], current: dict[str, object]
    ) -> dict[str, object]:
        return resolver.resolve(
            client, previous, current, expected_base="develop", apply=True
        )

    def test_fixed_own_thread_resolves_and_human_thread_is_untouched(self) -> None:
        previous, current = self.values()
        own = thread(previous, "finding-1", thread_id="T-own")
        human = thread(previous, "finding-1", thread_id="T-human", author="human")
        client = FakeGhClient(pull_state(previous, current, [human, own]))

        result = self.run_apply(client, previous, current)

        self.assertEqual(["T-own"], client.mutations)
        self.assertEqual("resolved", result["resolved"][0]["result"])
        human_after = next(item for item in client.state["threads"] if item["id"] == "T-human")
        self.assertFalse(human_after["isResolved"])

    def test_remaining_findings_of_every_severity_are_never_resolved(self) -> None:
        for severity in ("critical", "major", "minor", "nit"):
            with self.subTest(severity=severity):
                previous = envelope(
                    attempt=0,
                    head=OLD_HEAD,
                    findings=[finding(severity=severity)],
                )
                current = envelope(
                    attempt=1,
                    head=NEW_HEAD,
                    findings=[finding(line=2, severity=severity)],
                )
                old = thread(previous, "finding-1", thread_id="T-old", outdated=False)
                new = thread(current, "finding-1", thread_id="T-new")
                client = FakeGhClient(pull_state(previous, current, [old, new]))

                result = self.run_apply(client, previous, current)

                self.assertEqual([], client.mutations)
                self.assertEqual(["finding-1"], result["remaining"])

    def test_outdated_old_thread_resolves_but_new_occurrence_stays_open(self) -> None:
        previous, current = self.values(current_findings=[finding(line=2)])
        old = thread(previous, "finding-1", thread_id="T-old", outdated=True)
        new = thread(current, "finding-1", thread_id="T-new")
        client = FakeGhClient(pull_state(previous, current, [old, new]))

        result = self.run_apply(client, previous, current)

        self.assertEqual(["T-old"], client.mutations)
        self.assertEqual("reanchored", result["resolved"][0]["reason"])
        new_after = next(item for item in client.state["threads"] if item["id"] == "T-new")
        self.assertFalse(new_after["isResolved"])

    def test_already_resolved_is_skipped(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old", resolved=True)
        client = FakeGhClient(pull_state(previous, current, [old]))

        result = self.run_apply(client, previous, current)

        self.assertEqual([], client.mutations)
        self.assertEqual(["finding-1"], result["already_resolved"])

    def test_viewer_without_resolve_permission_blocks(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old", can_resolve=False)
        client = FakeGhClient(pull_state(previous, current, [old]))

        with self.assertRaises(resolver.OwnershipError):
            self.run_apply(client, previous, current)
        self.assertEqual([], client.mutations)

    def test_lost_mutation_response_recovers_by_reading_state(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old")
        client = FakeGhClient(
            pull_state(previous, current, [old]), lose_response=True
        )

        result = self.run_apply(client, previous, current)

        self.assertEqual(["T-old"], client.mutations)
        self.assertEqual("recovered", result["resolved"][0]["result"])

    def test_sha_drift_before_mutation_blocks(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old")
        client = FakeGhClient(pull_state(previous, current, [old]), drift_at=2)

        with self.assertRaises(resolver.SnapshotError):
            self.run_apply(client, previous, current)
        self.assertEqual([], client.mutations)

    def test_duplicate_owned_finding_marker_blocks(self) -> None:
        previous, current = self.values()
        first = thread(previous, "finding-1", thread_id="T-one")
        second = thread(previous, "finding-1", thread_id="T-two")
        client = FakeGhClient(pull_state(previous, current, [first, second]))

        with self.assertRaises(resolver.OwnershipError):
            self.run_apply(client, previous, current)
        self.assertEqual([], client.mutations)

    def test_postflight_not_resolved_blocks(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old")
        client = FakeGhClient(
            pull_state(previous, current, [old]), persist_resolve=False
        )

        with self.assertRaises(resolver.PostflightError):
            self.run_apply(client, previous, current)
        self.assertEqual(["T-old"], client.mutations)

    def test_dry_run_performs_no_write(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old")
        client = FakeGhClient(pull_state(previous, current, [old]))

        result = resolver.resolve(
            client, previous, current, expected_base="develop", apply=False
        )

        self.assertEqual("dry-run", result["action"])
        self.assertEqual([], client.mutations)

    def test_dry_run_without_resolve_permission_blocks(self) -> None:
        previous, current = self.values()
        old = thread(previous, "finding-1", thread_id="T-old", can_resolve=False)
        client = FakeGhClient(pull_state(previous, current, [old]))

        with self.assertRaises(resolver.OwnershipError):
            resolver.resolve(
                client, previous, current, expected_base="develop", apply=False
            )

        self.assertEqual([], client.mutations)

    def test_same_pr_concurrent_resolver_cannot_acquire_lock(self) -> None:
        with resolver._same_host_resolve_lock(REPO, PR_NUMBER):
            with self.assertRaises(resolver.IdempotencyError):
                with resolver._same_host_resolve_lock(REPO, PR_NUMBER):
                    self.fail("두 번째 resolver가 lock을 획득했습니다.")


class PaginatedGraphQLClient(resolver.GhGraphQLClient):
    def _graphql(self, query: str, variables: dict[str, object]) -> dict[str, object]:
        if query == resolver.PULL_QUERY:
            return {
                "viewer": {"login": "reviewer"},
                "repository": {
                    "nameWithOwner": REPO,
                    "pullRequest": {"id": "PR-id"},
                },
            }
        if query == resolver.THREADS_QUERY:
            after = variables["after"]
            if after is None:
                nodes = [self.thread_node("T-1", comments_next=True)]
                page = {"hasNextPage": True, "endCursor": "threads-2"}
            else:
                nodes = [self.thread_node("T-2")]
                page = {"hasNextPage": False, "endCursor": None}
            return {
                "repository": {
                    "pullRequest": {
                        "reviewThreads": {"nodes": nodes, "pageInfo": page}
                    }
                }
            }
        if query == resolver.THREAD_COMMENTS_QUERY:
            return {
                "node": {
                    "comments": {
                        "nodes": [
                            {"id": "C-2", "body": "reply", "author": {"login": "human"}}
                        ],
                        "pageInfo": {"hasNextPage": False, "endCursor": None},
                    }
                }
            }
        if query == resolver.REVIEWS_QUERY:
            after = variables["after"]
            node_id = "R-1" if after is None else "R-2"
            return {
                "repository": {
                    "pullRequest": {
                        "reviews": {
                            "nodes": [{"id": node_id}],
                            "pageInfo": {
                                "hasNextPage": after is None,
                                "endCursor": "reviews-2" if after is None else None,
                            },
                        }
                    }
                }
            }
        raise AssertionError("unexpected query")

    @staticmethod
    def thread_node(thread_id: str, *, comments_next: bool = False) -> dict[str, object]:
        return {
            "id": thread_id,
            "isResolved": False,
            "isOutdated": False,
            "viewerCanResolve": True,
            "comments": {
                "nodes": [
                    {
                        "id": "C-1" if thread_id == "T-1" else "C-3",
                        "body": "root",
                        "author": {"login": "reviewer"},
                    }
                ],
                "pageInfo": {
                    "hasNextPage": comments_next,
                    "endCursor": "comments-2" if comments_next else None,
                },
            },
        }


class GraphQLPaginationTests(unittest.TestCase):
    def test_threads_reviews_and_nested_comments_are_fully_paginated(self) -> None:
        state = PaginatedGraphQLClient().get_pull_state(REPO, PR_NUMBER)

        self.assertEqual(["T-1", "T-2"], [item["id"] for item in state["threads"]])
        self.assertEqual(2, len(state["threads"][0]["comments"]))
        self.assertEqual(["R-1", "R-2"], [item["id"] for item in state["reviews"]])

    def test_repeated_thread_cursor_blocks(self) -> None:
        class RepeatedCursorClient(PaginatedGraphQLClient):
            def __init__(self) -> None:
                self.thread_page = 0

            def _graphql(self, query: str, variables: dict[str, object]) -> dict[str, object]:
                if query != resolver.THREADS_QUERY:
                    return super()._graphql(query, variables)
                self.thread_page += 1
                return {
                    "repository": {
                        "pullRequest": {
                            "reviewThreads": {
                                "nodes": [self.thread_node(f"T-{self.thread_page}")],
                                "pageInfo": {
                                    "hasNextPage": True,
                                    "endCursor": "same-cursor",
                                },
                            }
                        }
                    }
                }

        with self.assertRaises(resolver.GitHubError):
            RepeatedCursorClient().get_pull_state(REPO, PR_NUMBER)

    def test_review_page_limit_blocks(self) -> None:
        class PageLimitClient(PaginatedGraphQLClient):
            def __init__(self) -> None:
                self.review_page = 0

            def _graphql(self, query: str, variables: dict[str, object]) -> dict[str, object]:
                if query == resolver.THREADS_QUERY:
                    return {
                        "repository": {
                            "pullRequest": {
                                "reviewThreads": {
                                    "nodes": [],
                                    "pageInfo": {
                                        "hasNextPage": False,
                                        "endCursor": None,
                                    },
                                }
                            }
                        }
                    }
                if query != resolver.REVIEWS_QUERY:
                    return super()._graphql(query, variables)
                self.review_page += 1
                return {
                    "repository": {
                        "pullRequest": {
                            "reviews": {
                                "nodes": [{"id": f"R-{self.review_page}"}],
                                "pageInfo": {
                                    "hasNextPage": True,
                                    "endCursor": f"cursor-{self.review_page}",
                                },
                            }
                        }
                    }
                }

        original_limit = resolver.MAX_GRAPHQL_PAGES
        resolver.MAX_GRAPHQL_PAGES = 2
        try:
            with self.assertRaises(resolver.GitHubError):
                PageLimitClient().get_pull_state(REPO, PR_NUMBER)
        finally:
            resolver.MAX_GRAPHQL_PAGES = original_limit


if __name__ == "__main__":
    unittest.main()
