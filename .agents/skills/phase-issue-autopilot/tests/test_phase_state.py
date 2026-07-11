from __future__ import annotations

import importlib.util
import subprocess
import sys
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "scripts" / "phase_state.py"
SPEC = importlib.util.spec_from_file_location("phase_state", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
phase_state = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = phase_state
SPEC.loader.exec_module(phase_state)

REPO = "owner/repo"
MERGE_SHA = "a" * 40


def issue_spec() -> phase_state.IssueSpec:
    return phase_state.IssueSpec(
        phase="phase-1",
        step=1,
        issue_title="[Phase 1 / Step 01] 기반 구성",
        step_doc="docs/phases/phase-1/step-01-foundation.md",
        predecessor="없음",
        branch="codex/phase-1-step-01-foundation",
    )


def issue(
    spec: phase_state.IssueSpec,
    *,
    state: str = "OPEN",
    branch: str | None = None,
    pr: str = "미생성",
) -> dict[str, object]:
    tracked_branch = branch if branch is not None else spec.branch
    return {
        "number": 7,
        "state": state,
        "title": spec.issue_title,
        "url": "https://github.com/owner/repo/issues/7",
        "body": (
            f"{spec.marker}\n\n## 작업 추적\n\n"
            f"- branch: `{tracked_branch}`\n"
            f"- PR: {pr}\n"
            "- blocker: 없음\n"
        ),
    }


def pull_request(
    spec: phase_state.IssueSpec,
    *,
    state: str = "OPEN",
    base: str = "develop",
    merged: bool = False,
    closes_issue: bool = True,
) -> dict[str, object]:
    return {
        "number": 11,
        "state": state,
        "isDraft": False,
        "mergedAt": "2026-07-11T00:00:00Z" if merged else None,
        "mergeCommit": {"oid": MERGE_SHA} if merged else None,
        "headRefName": spec.branch,
        "baseRefName": base,
        "url": "https://github.com/owner/repo/pull/11",
        "body": "Closes #7" if closes_issue else "관련 이슈 없음",
    }


def linked_branch(spec: phase_state.IssueSpec, *, name: str | None = None) -> dict[str, str]:
    return {"name": name or spec.branch, "repository": REPO}


def graphql_branches(branches: list[dict[str, str]]) -> dict[str, object]:
    return {
        "data": {
            "repository": {
                "issue": {
                    "linkedBranches": {
                        "nodes": [
                            {
                                "ref": {
                                    "name": branch["name"],
                                    "repository": {"nameWithOwner": branch["repository"]},
                                }
                            }
                            for branch in branches
                        ],
                        "pageInfo": {"hasNextPage": False, "endCursor": None},
                    }
                }
            }
        }
    }


class InspectStateTests(unittest.TestCase):
    def inspect(
        self,
        *,
        issues: list[dict[str, object]],
        prs: list[dict[str, object]],
        branches: list[dict[str, str]] | None = None,
        comparison: str = "ahead",
    ) -> dict[str, object]:
        def fake_run(args: list[str]) -> object:
            if args[:2] == ["issue", "list"]:
                return issues
            if args[:2] == ["pr", "list"]:
                return prs
            if args[:2] == ["api", "graphql"]:
                return graphql_branches(branches or [])
            if args[0] == "api" and "/compare/" in args[1]:
                return {"status": comparison}
            self.fail(f"예상하지 못한 gh 호출: {args}")

        with patch.object(phase_state, "_run_gh_json", side_effect=fake_run):
            return phase_state.inspect_state(REPO, issue_spec())

    def test_completed_step_returns_complete(self) -> None:
        spec = issue_spec()
        pr = pull_request(spec, state="MERGED", merged=True)
        state = self.inspect(
            issues=[issue(spec, state="CLOSED", pr=str(pr["url"]))],
            prs=[pr],
            branches=[],
        )

        self.assertEqual([], state["blockers"])
        self.assertEqual("complete", state["next_action"])
        self.assertEqual(7, state["canonical_issue_number"])

    def test_open_merged_step_returns_close_issue(self) -> None:
        spec = issue_spec()
        pr = pull_request(spec, state="MERGED", merged=True)
        state = self.inspect(
            issues=[issue(spec, pr=str(pr["url"]))],
            prs=[pr],
            branches=[],
        )

        self.assertEqual("close_issue", state["next_action"])

    def test_issue_branch_pr_linkage_is_one_fail_closed_invariant(self) -> None:
        spec = issue_spec()
        pr = pull_request(spec)
        state = self.inspect(
            issues=[issue(spec, branch="codex/phase-1-step-01-wrong", pr="미생성")],
            prs=[pr],
            branches=[linked_branch(spec)],
        )

        self.assertIn("ISSUE_PR_LINK_MISMATCH", state["blockers"])
        self.assertEqual("blocked", state["next_action"])

    def test_open_pr_requires_matching_link_tracking_and_closing_keyword(self) -> None:
        spec = issue_spec()
        pr = pull_request(spec)
        state = self.inspect(
            issues=[issue(spec, pr=str(pr["url"]))],
            prs=[pr],
            branches=[linked_branch(spec)],
        )

        self.assertEqual([], state["blockers"])
        self.assertEqual("resume", state["next_action"])

    def test_wrong_base_closed_unmerged_and_unreachable_merge_have_exact_blockers(self) -> None:
        spec = issue_spec()
        cases = (
            (pull_request(spec, base="main"), "WRONG_PR_BASE", "ahead"),
            (pull_request(spec, state="CLOSED"), "CLOSED_UNMERGED_PR", "ahead"),
            (
                pull_request(spec, state="MERGED", merged=True),
                "MERGED_PR_NOT_REACHABLE_FROM_DEVELOP",
                "behind",
            ),
        )
        for pr, expected, comparison in cases:
            with self.subTest(expected=expected):
                state = self.inspect(
                    issues=[issue(spec, pr=str(pr["url"]))],
                    prs=[pr],
                    branches=[linked_branch(spec)],
                    comparison=comparison,
                )
                self.assertIn(expected, state["blockers"])


class CreateIssueStateMachineTests(unittest.TestCase):
    def argv(self) -> list[str]:
        spec = issue_spec()
        return [
            "create-issue",
            "--repo",
            REPO,
            "--phase",
            spec.phase,
            "--step",
            str(spec.step),
            "--issue-title",
            spec.issue_title,
            "--step-doc",
            spec.step_doc,
            "--predecessor",
            spec.predecessor,
            "--branch",
            spec.branch,
            "--apply",
        ]

    @staticmethod
    def state(*, matches: int = 0, blockers: list[str] | None = None) -> dict[str, object]:
        return {
            "blockers": blockers or [],
            "issue_matches": [{"number": 7}] * matches,
        }

    def test_existing_exact_marker_never_creates(self) -> None:
        with (
            patch.object(phase_state, "inspect_state", return_value=self.state(matches=1)),
            patch.object(phase_state, "_run_gh") as run_gh,
        ):
            code = phase_state.main(self.argv())

        self.assertEqual(0, code)
        run_gh.assert_not_called()

    def test_predecessor_creation_while_waiting_on_lock_is_reobserved(self) -> None:
        with (
            patch.object(
                phase_state,
                "inspect_state",
                side_effect=(self.state(), self.state(matches=1)),
            ),
            patch.object(phase_state, "_same_host_creation_lock", return_value=nullcontext()),
            patch.object(phase_state, "_run_gh") as run_gh,
        ):
            code = phase_state.main(self.argv())

        self.assertEqual(0, code)
        run_gh.assert_not_called()

    def test_successful_creation_posts_once_and_requires_unique_postflight(self) -> None:
        completed = subprocess.CompletedProcess([], 0, "https://github.com/owner/repo/issues/7\n", "")
        with (
            patch.object(
                phase_state,
                "inspect_state",
                side_effect=(self.state(), self.state(), self.state(matches=1)),
            ),
            patch.object(phase_state, "_same_host_creation_lock", return_value=nullcontext()),
            patch.object(phase_state, "_run_gh", return_value=completed) as run_gh,
        ):
            code = phase_state.main(self.argv())

        self.assertEqual(0, code)
        run_gh.assert_called_once()

    def test_zero_or_duplicate_postflight_is_not_reported_as_success(self) -> None:
        completed = subprocess.CompletedProcess([], 0, "created\n", "")
        for matches in (0, 2):
            with self.subTest(matches=matches):
                with (
                    patch.object(
                        phase_state,
                        "inspect_state",
                        side_effect=(self.state(), self.state(), self.state(matches=matches)),
                    ),
                    patch.object(
                        phase_state, "_same_host_creation_lock", return_value=nullcontext()
                    ),
                    patch.object(phase_state, "_run_gh", return_value=completed),
                ):
                    self.assertEqual(2, phase_state.main(self.argv()))

    def test_blocker_never_creates(self) -> None:
        with (
            patch.object(
                phase_state,
                "inspect_state",
                return_value=self.state(blockers=["DUPLICATE_ISSUE"]),
            ),
            patch.object(phase_state, "_run_gh") as run_gh,
        ):
            code = phase_state.main(self.argv())

        self.assertEqual(2, code)
        run_gh.assert_not_called()

    def test_same_step_concurrent_creator_cannot_acquire_lock(self) -> None:
        spec = issue_spec()
        with phase_state._same_host_creation_lock(REPO, spec):
            with self.assertRaises(phase_state.PhaseStateError):
                with phase_state._same_host_creation_lock(REPO, spec):
                    self.fail("두 번째 creator가 lock을 획득했습니다.")


if __name__ == "__main__":
    unittest.main()
