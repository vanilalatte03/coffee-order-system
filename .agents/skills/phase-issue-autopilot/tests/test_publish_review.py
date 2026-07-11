from __future__ import annotations

import copy
import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "publish_review.py"
SPEC = importlib.util.spec_from_file_location("publish_review", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
publish_review = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = publish_review
SPEC.loader.exec_module(publish_review)

BASE_SHA = "a" * 40
HEAD_SHA = "b" * 40
REPO = "owner/repo"
PR_NUMBER = 11


def envelope(*, findings: list[dict[str, object]] | None = None) -> dict[str, object]:
    values = findings or []
    return {
        "schema_version": 2,
        "repository": REPO,
        "issue_number": 7,
        "pull_number": PR_NUMBER,
        "phase": "phase-1",
        "step": 1,
        "attempt": 0,
        "base": {"ref": "develop", "sha": BASE_SHA},
        "head": {"ref": "codex/phase-1-step-01-foundation", "sha": HEAD_SHA},
        "verdict": publish_review.expected_verdict(values),
        "summary": {
            "walkthrough": "첫 번째 줄\n두 번째 줄",
            "strengths": ["좋은 점"],
            "next_actions": ["다음 작업"],
        },
        "findings": values,
    }


def pr_snapshot(*, head_sha: str = HEAD_SHA, changed_files: int = 0) -> dict[str, object]:
    return {
        "state": "open",
        "merged": False,
        "body": "Closes #7",
        "changed_files": changed_files,
        "base": {
            "ref": "develop",
            "sha": BASE_SHA,
            "repo": {"full_name": REPO},
        },
        "head": {
            "ref": "codex/phase-1-step-01-foundation",
            "sha": head_sha,
            "repo": {"full_name": REPO},
        },
    }


class FakeGhClient:
    def __init__(
        self,
        review_envelope: dict[str, object],
        *,
        existing_state: str | None = None,
        lose_create_response: bool = False,
        lose_submit_response: bool = False,
        drift_on_pr_read: int | None = None,
        files: list[dict[str, object]] | None = None,
        changed_files: int = 0,
    ) -> None:
        self.payload, _ = publish_review.build_payload(review_envelope)
        self.lose_create_response = lose_create_response
        self.lose_submit_response = lose_submit_response
        self.drift_on_pr_read = drift_on_pr_read
        self.files = files or []
        self.changed_files = changed_files
        self.pr_reads = 0
        self.posts: list[tuple[str, dict[str, object]]] = []
        self.deletes: list[str] = []
        self.reviews: list[dict[str, object]] = []
        self.comments: dict[int, list[dict[str, object]]] = {}
        if existing_state is not None:
            self._create_review(existing_state)

    def _create_review(self, state: str) -> dict[str, object]:
        review_id = 100 + len(self.reviews)
        review = {
            "id": review_id,
            "state": state,
            "commit_id": self.payload["commit_id"],
            "body": self.payload["body"],
            "user": {"login": "reviewer"},
        }
        self.reviews.append(review)
        self.comments[review_id] = copy.deepcopy(self.payload["comments"])
        return review

    def get(self, endpoint: str) -> object:
        if endpoint == "user":
            return {"login": "reviewer"}
        if endpoint == f"repos/{REPO}/pulls/{PR_NUMBER}":
            self.pr_reads += 1
            if self.pr_reads == self.drift_on_pr_read:
                return pr_snapshot(head_sha="c" * 40, changed_files=self.changed_files)
            return pr_snapshot(changed_files=self.changed_files)
        raise AssertionError(f"예상하지 못한 GET: {endpoint}")

    def get_pages(self, endpoint: str, *, max_pages: int = 100) -> list[object]:
        del max_pages
        if endpoint == f"repos/{REPO}/pulls/{PR_NUMBER}/files":
            return copy.deepcopy(self.files)
        if endpoint == f"repos/{REPO}/pulls/{PR_NUMBER}/reviews":
            return copy.deepcopy(self.reviews)
        prefix = f"repos/{REPO}/pulls/{PR_NUMBER}/reviews/"
        if endpoint.startswith(prefix) and endpoint.endswith("/comments"):
            review_id = int(endpoint.removeprefix(prefix).removesuffix("/comments"))
            return copy.deepcopy(self.comments[review_id])
        raise AssertionError(f"예상하지 못한 pagination GET: {endpoint}")

    def post(self, endpoint: str, payload: dict[str, object]) -> object:
        self.posts.append((endpoint, copy.deepcopy(payload)))
        if endpoint == f"repos/{REPO}/pulls/{PR_NUMBER}/reviews":
            review = self._create_review("PENDING")
            if self.lose_create_response:
                self.lose_create_response = False
                raise publish_review.GitHubError("create 응답 유실")
            return copy.deepcopy(review)
        if endpoint.endswith("/events"):
            review_id = int(endpoint.split("/")[-2])
            review = next(item for item in self.reviews if item["id"] == review_id)
            review["state"] = "COMMENTED"
            if self.lose_submit_response:
                self.lose_submit_response = False
                raise publish_review.GitHubError("submit 응답 유실")
            return copy.deepcopy(review)
        raise AssertionError(f"예상하지 못한 POST: {endpoint}")

    def delete(self, endpoint: str) -> None:
        self.deletes.append(endpoint)
        review_id = int(endpoint.split("/")[-1])
        self.reviews = [item for item in self.reviews if item["id"] != review_id]
        self.comments.pop(review_id, None)


class PublishStateMachineTests(unittest.TestCase):
    def publish(self, client: FakeGhClient, value: dict[str, object]) -> tuple[dict[str, object], int]:
        return publish_review._publish_locked(
            client,
            value,
            expected_base="develop",
            apply=True,
        )

    def test_new_snapshot_creates_one_pending_and_submits_once(self) -> None:
        value = envelope()
        client = FakeGhClient(value)

        result, code = self.publish(client, value)

        self.assertEqual(0, code)
        self.assertEqual("posted", result["action"])
        self.assertEqual(2, len(client.posts))
        self.assertTrue(client.posts[0][0].endswith("/reviews"))
        self.assertTrue(client.posts[1][0].endswith("/events"))

    def test_pr_must_close_envelope_canonical_issue(self) -> None:
        value = envelope()
        pr = pr_snapshot()
        pr["body"] = "Closes #8"

        with self.assertRaises(publish_review.SnapshotError):
            publish_review._verify_pr_snapshot(pr, value, "develop")

    def test_existing_commented_marker_performs_no_write(self) -> None:
        value = envelope()
        client = FakeGhClient(value, existing_state="COMMENTED")

        result, _ = self.publish(client, value)

        self.assertEqual("existing", result["action"])
        self.assertEqual([], client.posts)

    def test_existing_pending_marker_only_submits(self) -> None:
        value = envelope()
        client = FakeGhClient(value, existing_state="PENDING")

        result, _ = self.publish(client, value)

        self.assertEqual("posted", result["action"])
        self.assertEqual(1, len(client.posts))
        self.assertTrue(client.posts[0][0].endswith("/events"))

    def test_lost_create_response_recovers_without_duplicate_post(self) -> None:
        value = envelope()
        client = FakeGhClient(value, lose_create_response=True)

        result, _ = self.publish(client, value)

        self.assertEqual("posted", result["action"])
        review_posts = [endpoint for endpoint, _ in client.posts if endpoint.endswith("/reviews")]
        self.assertEqual(1, len(review_posts))

    def test_lost_submit_response_recovers_server_success(self) -> None:
        value = envelope()
        client = FakeGhClient(value, lose_submit_response=True)

        result, _ = self.publish(client, value)

        self.assertEqual("posted", result["action"])
        event_posts = [endpoint for endpoint, _ in client.posts if endpoint.endswith("/events")]
        self.assertEqual(1, len(event_posts))

    def test_sha_drift_before_submit_deletes_pending_without_submit(self) -> None:
        value = envelope()
        client = FakeGhClient(value, drift_on_pr_read=3)

        with self.assertRaises(publish_review.SnapshotError):
            self.publish(client, value)

        event_posts = [endpoint for endpoint, _ in client.posts if endpoint.endswith("/events")]
        self.assertEqual([], event_posts)
        self.assertEqual(1, len(client.deletes))

    def test_invalid_anchor_and_pagination_fail_before_write(self) -> None:
        finding = {
            "finding_key": "bad-anchor",
            "dimension": "correctness",
            "severity": "major",
            "title": "잘못된 anchor",
            "tldr": "현재 diff에 없습니다.",
            "good": "검증 경로는 있습니다.",
            "fix_markdown": "anchor를 수정하세요.",
            "path": "src/example.py",
            "line": 99,
            "side": "RIGHT",
        }
        value = envelope(findings=[finding])
        cases = (
            FakeGhClient(value, changed_files=1, files=[]),
            FakeGhClient(
                value,
                changed_files=1,
                files=[{"filename": "src/example.py", "patch": "@@ -1 +1 @@\n-old\n+new"}],
            ),
        )
        for client in cases:
            with self.subTest(files=client.files):
                with self.assertRaises(publish_review.SnapshotError):
                    self.publish(client, value)
                self.assertEqual([], client.posts)

    def test_same_pr_concurrent_publisher_cannot_acquire_lock(self) -> None:
        value = envelope()
        with publish_review._same_host_review_lock(value):
            with self.assertRaises(publish_review.IdempotencyError):
                with publish_review._same_host_review_lock(value):
                    self.fail("두 번째 publisher가 lock을 획득했습니다.")

    def test_each_finding_has_an_independent_stable_hidden_marker(self) -> None:
        findings = [
            {
                "finding_key": key,
                "dimension": "correctness",
                "severity": "minor",
                "title": f"제목 {key}",
                "tldr": "설명",
                "good": "좋은 점",
                "fix_markdown": "수정하세요.",
                "path": "src/example.py",
                "line": 1,
                "side": "RIGHT",
            }
            for key in ("one", "two")
        ]

        payload, digest = publish_review.build_payload(envelope(findings=findings))

        self.assertEqual(2, len(payload["comments"]))
        for key, comment in zip(("one", "two"), payload["comments"], strict=True):
            self.assertIn(f"digest={digest} key={key} -->", comment["body"])
            self.assertEqual(1, comment["body"].count("phase-issue-autopilot-finding:v1"))


if __name__ == "__main__":
    unittest.main()
