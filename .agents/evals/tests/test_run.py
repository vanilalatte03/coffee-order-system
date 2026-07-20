from __future__ import annotations

import copy
import importlib.util
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock


RUNNER_PATH = Path(__file__).resolve().parents[1] / "run.py"
SPEC = importlib.util.spec_from_file_location("fixture_health", RUNNER_PATH)
assert SPEC is not None and SPEC.loader is not None
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class FixtureHealthTest(unittest.TestCase):
    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(RUNNER_PATH), *arguments],
            cwd=HARNESS.REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )

    def sample_case(self) -> dict:
        return {
            "schema_version": 1,
            "id": "sample-case",
            "suite": "review-code",
            "title": "sample",
            "base_revision": "a" * 40,
            "contract_refs": [{"path": "docs/contract.md", "contains": "contract phrase"}],
            "task": {
                "review_scope": ["src/Sample.java"],
                "instruction": "review the candidate",
                "candidate_diff": (
                    "diff --git a/src/Sample.java b/src/Sample.java\n"
                    "--- a/src/Sample.java\n"
                    "+++ b/src/Sample.java\n"
                    "@@ -1 +1 @@\n"
                    "-old\n"
                    "+new\n"
                ),
            },
        }

    def test_repository_fixtures_are_healthy(self) -> None:
        self.assertEqual([], HARNESS.health_errors())
        expected_ids = {
            "review-code-idempotency-replay-metadata",
            "review-code-mysql-concurrency-coverage",
            "review-code-order-safe-observability",
            "review-code-order-transaction-boundary",
        }
        actual_ids = {case["id"] for _, case in HARNESS.load_cases()}
        self.assertTrue(expected_ids.issubset(actual_ids))

    def test_list_cli_returns_only_fixture_identity_and_pinned_base(self) -> None:
        result = self.run_cli("list")
        self.assertEqual(0, result.returncode, result.stderr)
        packets = json.loads(result.stdout)
        self.assertGreater(len(packets), 0)
        for packet in packets:
            self.assertEqual({"case_id", "title", "base_revision"}, set(packet))

        missing = self.run_cli("show", "missing-case")
        self.assertEqual(1, missing.returncode)
        self.assertIn("fixture를 찾을 수 없습니다", missing.stderr)

        error_output = io.StringIO()
        with (
            mock.patch.object(HARNESS, "health_errors", return_value=["broken fixture"]),
            mock.patch.object(HARNESS.sys, "stderr", error_output),
        ):
            self.assertEqual(1, HARNESS.main(["health"]))
        self.assertIn("ERROR: broken fixture", error_output.getvalue())

    def test_show_cli_returns_public_task_packet_without_evaluator_fields(self) -> None:
        case_id = HARNESS.load_cases()[0][1]["id"]
        result = self.run_cli("show", case_id)
        self.assertEqual(0, result.returncode, result.stderr)
        packet = json.loads(result.stdout)
        self.assertEqual(
            {"case_id", "suite", "title", "base_revision", "contract_refs", "task"},
            set(packet),
        )
        self.assertEqual(set(), HARNESS.forbidden_fields(packet))

    def test_health_rejects_duplicate_case_ids(self) -> None:
        case = self.sample_case()
        cases = [
            (HARNESS.EVALS_DIR / "cases/review-code/one.json", case),
            (HARNESS.EVALS_DIR / "cases/review-code/two.json", copy.deepcopy(case)),
        ]
        with (
            mock.patch.object(HARNESS, "load_cases", return_value=cases),
            mock.patch.object(HARNESS, "validate_case", return_value=[]),
        ):
            errors = HARNESS.health_errors()
        self.assertIn("중복 fixture id입니다: sample-case", errors)

    def test_path_safety_rejects_unsafe_and_non_file_paths(self) -> None:
        unsafe_paths = (
            "../secret",
            "/tmp/secret",
            "C:/secret",
            "safe/D:/secret",
            "src/file.txt:stream",
            "src\\Secret.java",
        )
        for unsafe_path in unsafe_paths:
            with self.subTest(path=unsafe_path):
                with self.assertRaises(HARNESS.FixtureHealthError):
                    HARNESS.validate_relative_path(unsafe_path)

        base_revision = HARNESS.load_cases()[0][1]["base_revision"]
        with self.assertRaisesRegex(HARNESS.FixtureHealthError, "파일 경로만 허용합니다"):
            HARNESS.git_file_content(base_revision, "docs")

    def test_case_reads_scope_and_contract_from_same_pinned_base(self) -> None:
        case = self.sample_case()
        snapshot = {
            "src/Sample.java": "old\n",
            "docs/contract.md": "contract phrase\n",
        }

        def base_content(revision: str, relative_path: str) -> str:
            self.assertEqual(case["base_revision"], revision)
            return snapshot[relative_path]

        with (
            mock.patch.object(HARNESS, "commit_exists", return_value=True),
            mock.patch.object(
                HARNESS,
                "stable_base_refs_containing",
                return_value={"refs/remotes/origin/develop"},
            ),
            mock.patch.object(HARNESS, "git_file_content", side_effect=base_content) as read_base,
            mock.patch.object(HARNESS, "validate_candidate_diff", return_value=None),
        ):
            errors = HARNESS.validate_case(
                HARNESS.EVALS_DIR / "cases/review-code/sample.json",
                case,
                "review-code",
            )

        self.assertEqual([], errors)
        requested_paths = {call.args[1] for call in read_base.call_args_list}
        self.assertEqual({"src/Sample.java", "docs/contract.md"}, requested_paths)

        with (
            mock.patch.object(HARNESS, "commit_exists", return_value=True),
            mock.patch.object(HARNESS, "stable_base_refs_containing", return_value=set()),
        ):
            errors = HARNESS.validate_case(
                HARNESS.EVALS_DIR / "cases/review-code/sample.json",
                case,
                "review-code",
            )
        self.assertTrue(any("origin/develop 또는 origin/main" in error for error in errors))

        with mock.patch.object(HARNESS, "commit_exists", return_value=False):
            errors = HARNESS.validate_case(
                HARNESS.EVALS_DIR / "cases/review-code/sample.json",
                case,
                "review-code",
            )
        self.assertTrue(any("commit을 찾을 수 없습니다" in error for error in errors))

        with (
            mock.patch.object(HARNESS, "commit_exists", return_value=True),
            mock.patch.object(
                HARNESS,
                "stable_base_refs_containing",
                return_value={"refs/remotes/origin/develop"},
            ),
            mock.patch.object(HARNESS, "git_file_content", return_value="other content\n"),
            mock.patch.object(HARNESS, "validate_candidate_diff", return_value=None),
        ):
            errors = HARNESS.validate_case(
                HARNESS.EVALS_DIR / "cases/review-code/sample.json",
                case,
                "review-code",
            )
        self.assertTrue(any("계약 참조 문구" in error for error in errors))

    def test_case_rejects_candidate_paths_outside_review_scope(self) -> None:
        case = self.sample_case()
        case["task"]["review_scope"] = ["src/Other.java"]
        with (
            mock.patch.object(HARNESS, "commit_exists", return_value=True),
            mock.patch.object(
                HARNESS,
                "stable_base_refs_containing",
                return_value={"refs/remotes/origin/develop"},
            ),
            mock.patch.object(HARNESS, "git_file_content", return_value="content\n"),
            mock.patch.object(HARNESS, "validate_candidate_diff", return_value=None),
        ):
            errors = HARNESS.validate_case(
                HARNESS.EVALS_DIR / "cases/review-code/sample.json",
                case,
                "review-code",
            )
        self.assertTrue(any("candidate_diff 경로와 review_scope" in error for error in errors))

    def test_closed_schema_rejects_answer_hint_fields(self) -> None:
        manifest = HARNESS.load_manifest()
        manifest["rubric"] = "hidden rubric"
        manifest_errors, _ = HARNESS.validate_manifest(manifest)
        self.assertTrue(any("허용되지 않은 필드" in error for error in manifest_errors))
        self.assertTrue(any("금지된 필드" in error for error in manifest_errors))

        manifest = HARNESS.load_manifest()
        manifest["name"] = {"answer": "hidden answer"}
        manifest_errors, _ = HARNESS.validate_manifest(manifest)
        self.assertTrue(any("name은 비어 있지 않은 문자열" in error for error in manifest_errors))
        self.assertTrue(any("answer" in error for error in manifest_errors))

        for invalid_version in (True, 1.0):
            with self.subTest(manifest_schema_version=invalid_version):
                manifest = HARNESS.load_manifest()
                manifest["schema_version"] = invalid_version
                manifest_errors, _ = HARNESS.validate_manifest(manifest)
                self.assertTrue(any("schema_version" in error for error in manifest_errors))

        cases = []

        task_hint = self.sample_case()
        task_hint["task"]["answer"] = "hidden answer"
        cases.append((task_hint, "task", "answer"))

        contract_hint = self.sample_case()
        contract_hint["contract_refs"][0]["expected_finding"] = "hidden finding"
        cases.append((contract_hint, "contract_refs 항목", "expected_finding"))

        for invalid_version in (True, 1.0):
            version_hint = self.sample_case()
            version_hint["schema_version"] = invalid_version
            cases.append((version_hint, "schema_version", "schema_version"))

        for case, object_label, field in cases:
            with self.subTest(field=field):
                with (
                    mock.patch.object(HARNESS, "commit_exists", return_value=True),
                    mock.patch.object(
                        HARNESS,
                        "stable_base_refs_containing",
                        return_value={"refs/remotes/origin/develop"},
                    ),
                    mock.patch.object(HARNESS, "git_file_content", return_value="contract phrase\n"),
                    mock.patch.object(HARNESS, "validate_candidate_diff", return_value=None),
                ):
                    errors = HARNESS.validate_case(
                        HARNESS.EVALS_DIR / "cases/review-code/sample.json",
                        case,
                        "review-code",
                    )
                self.assertTrue(
                    any(object_label in error and field in error for error in errors),
                    errors,
                )

    def test_patch_uses_pinned_blob_instead_of_checkout(self) -> None:
        patch = (
            "diff --git a/README.md b/README.md\n"
            "--- a/README.md\n"
            "+++ b/README.md\n"
            "@@ -1,4 +1,4 @@\n"
            "-pinned content\n"
            "+candidate content\n"
            " context one\n"
            " context two\n"
            " context three\n"
        )
        pinned = "pinned content\ncontext one\ncontext two\ncontext three\n"

        with mock.patch.object(HARNESS, "git_file_content", return_value=pinned) as read_blob:
            self.assertIsNone(
                HARNESS.validate_candidate_diff("a" * 40, patch, ["README.md"])
            )
        read_blob.assert_called_once_with("a" * 40, "README.md")

        collision_patch = (
            "diff --git a/candidate.patch b/candidate.patch\n"
            "--- a/candidate.patch\n"
            "+++ b/candidate.patch\n"
            "@@ -1 +1 @@\n"
            "-old\n"
            "+new\n"
        )
        with mock.patch.object(HARNESS, "git_file_content", return_value="old\n"):
            self.assertIsNone(
                HARNESS.validate_candidate_diff(
                    "a" * 40,
                    collision_patch,
                    ["candidate.patch"],
                )
            )

        header_like_content_patch = (
            "diff --git a/db/sample.sql b/db/sample.sql\n"
            "--- a/db/sample.sql\n"
            "+++ b/db/sample.sql\n"
            "@@ -1 +1 @@\n"
            "--- a/comment\n"
            "+++ b/comment\n"
        )
        self.assertEqual(
            {"db/sample.sql"},
            HARNESS.candidate_diff_paths(header_like_content_patch),
        )
        with mock.patch.object(HARNESS, "git_file_content", return_value="-- a/comment\n"):
            self.assertIsNone(
                HARNESS.validate_candidate_diff(
                    "a" * 40,
                    header_like_content_patch,
                    ["db/sample.sql"],
                )
            )

        with mock.patch.object(HARNESS, "git_file_content", return_value="different\n"):
            patch_error = HARNESS.validate_candidate_diff("a" * 40, patch, ["README.md"])
        self.assertIsNotNone(patch_error)
        self.assertIn("적용할 수 없습니다", patch_error)

        invalid_patches = (
            "GIT binary patch\n",
            (
                "diff --git a/new.txt b/new.txt\n"
                "--- /dev/null\n"
                "+++ b/new.txt\n"
                "@@ -0,0 +1 @@\n"
                "+new\n"
            ),
            "diff --git a/old.txt b/new.txt\n",
            "--- a/missing.txt\n+++ b/missing.txt\n",
        )
        for invalid_patch in invalid_patches:
            with self.subTest(invalid_patch=invalid_patch.splitlines()[0]):
                with self.assertRaises(HARNESS.FixtureHealthError):
                    HARNESS.candidate_diff_paths(invalid_patch)


if __name__ == "__main__":
    unittest.main()
