from __future__ import annotations

import copy
import importlib.util
import json
import sys
import unittest
from pathlib import Path
from typing import Callable

from jsonschema import Draft202012Validator


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts" / "publish_review.py"
SCHEMA = ROOT / "references" / "review-envelope.schema.json"
SPEC = importlib.util.spec_from_file_location("publish_review_schema", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
publish_review = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = publish_review
SPEC.loader.exec_module(publish_review)

BASE_SHA = "a" * 40
HEAD_SHA = "b" * 40


def finding(*, key: str = "finding-1", severity: str = "minor") -> dict[str, object]:
    return {
        "finding_key": key,
        "dimension": "correctness",
        "severity": severity,
        "title": "제목",
        "tldr": "한 줄 설명",
        "good": "좋은 점",
        "fix_markdown": "수정 제안",
        "path": "src/example.py",
        "line": 1,
        "side": "RIGHT",
    }


def envelope(*, findings: list[dict[str, object]] | None = None) -> dict[str, object]:
    values = findings or []
    return {
        "schema_version": 2,
        "repository": "owner/repo",
        "issue_number": 7,
        "pull_number": 11,
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


def mutation(change: Callable[[dict[str, object]], None]) -> dict[str, object]:
    value = envelope(findings=[finding()])
    change(value)
    return value


class SchemaParityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        cls.validator = Draft202012Validator(schema)

    def manual_accepts(self, value: dict[str, object]) -> bool:
        try:
            publish_review.validate_envelope(
                copy.deepcopy(value),
                expected_repo="owner/repo",
                expected_pr=11,
                expected_base="develop",
            )
            return True
        except publish_review.ReviewError:
            return False

    def test_schema_and_manual_validator_share_acceptance_corpus(self) -> None:
        valid_cases = (
            envelope(),
            envelope(findings=[finding(severity="minor")]),
            envelope(findings=[finding(severity="major")]),
            envelope(findings=[finding(severity="critical")]),
        )
        invalid_cases = (
            mutation(lambda value: value.pop("issue_number")),
            mutation(lambda value: value.__setitem__("extra", True)),
            mutation(lambda value: value.__setitem__("pull_number", True)),
            mutation(lambda value: value.__setitem__("attempt", 3)),
            mutation(lambda value: value["findings"][0].__setitem__("title", "NUL\x00")),
            mutation(
                lambda value: value["findings"][0].__setitem__(
                    "tldr", "<!-- phase-issue-autopilot-review: 금지"
                )
            ),
            mutation(
                lambda value: value["findings"][0].__setitem__(
                    "good", "<!-- phase-issue-autopilot-finding: 금지"
                )
            ),
            mutation(lambda value: value["findings"][0].__setitem__("title", "두\n줄")),
            mutation(lambda value: value["findings"][0].__setitem__("path", "../secret")),
            mutation(lambda value: value["findings"].append(copy.deepcopy(value["findings"][0]))),
            mutation(lambda value: value.__setitem__("verdict", "CHANGES_REQUESTED")),
            mutation(lambda value: value["base"].__setitem__("ref", "main")),
            mutation(lambda value: value["head"].__setitem__("ref", "feature/wrong")),
        )

        for value in valid_cases:
            with self.subTest(valid=value["verdict"]):
                self.assertTrue(self.validator.is_valid(value))
                self.assertTrue(self.manual_accepts(value))
        for value in invalid_cases:
            with self.subTest(invalid=value):
                self.assertFalse(self.validator.is_valid(value))
                self.assertFalse(self.manual_accepts(value))


if __name__ == "__main__":
    unittest.main()
