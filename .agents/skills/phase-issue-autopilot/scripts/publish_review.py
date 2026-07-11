#!/usr/bin/env python3
"""Validate and atomically publish one GitHub COMMENT review with inline findings."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterator


API_VERSION = "2026-03-10"
MARKER_PREFIX = "<!-- phase-issue-autopilot-review:"
MARKER_RE = re.compile(
    r"<!-- phase-issue-autopilot-review:v2 "
    r"repo=(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+) "
    r"issue=(?P<issue>\d+) pr=(?P<pr>\d+) base=(?P<base>[0-9a-f]{40}) "
    r"head=(?P<head>[0-9a-f]{40}) digest=(?P<digest>[0-9a-f]{64}) -->"
)
HUNK_RE = re.compile(
    r"^@@ -(?P<old_start>\d+)(?:,(?P<old_count>\d+))? "
    r"\+(?P<new_start>\d+)(?:,(?P<new_count>\d+))? @@"
)
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
PHASE_RE = re.compile(r"^[a-z0-9][a-z0-9-]*$")
FINDING_KEY_RE = re.compile(r"^[A-Za-z0-9_.:-]+$")
SEVERITIES = ("critical", "major", "minor", "nit")
SEVERITY_ORDER = {severity: index for index, severity in enumerate(SEVERITIES)}
VERDICTS = ("APPROVE", "CHANGES_REQUESTED", "BLOCKED")
VERDICT_LABELS = {
    "APPROVE": "Approve",
    "CHANGES_REQUESTED": "Changes Requested",
    "BLOCKED": "Blocked",
}
DIMENSIONS = (
    "correctness",
    "security",
    "conventions",
    "performance",
    "test-coverage",
    "architecture",
    "cross-file-consistency",
    "privacy",
    "cpu-perf-patterns",
    "behavioral-correctness",
)
TOP_LEVEL_KEYS = {
    "schema_version",
    "repository",
    "issue_number",
    "pull_number",
    "phase",
    "step",
    "attempt",
    "base",
    "head",
    "verdict",
    "summary",
    "findings",
}
SNAPSHOT_KEYS = {"ref", "sha"}
SUMMARY_KEYS = {"walkthrough", "strengths", "next_actions"}
FINDING_REQUIRED_KEYS = {
    "finding_key",
    "dimension",
    "severity",
    "title",
    "tldr",
    "good",
    "fix_markdown",
    "path",
    "line",
    "side",
}


class ReviewError(RuntimeError):
    exit_code = 20


class SnapshotError(ReviewError):
    exit_code = 21


class IdempotencyError(ReviewError):
    exit_code = 22


class GitHubError(ReviewError):
    exit_code = 23


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReviewError(f"중복 JSON 키: {key}")
        result[key] = value
    return result


def _load_json(path: str) -> Any:
    try:
        if path == "-":
            text = sys.stdin.read()
        else:
            text = Path(path).read_text(encoding="utf-8-sig")
        return json.loads(text, object_pairs_hook=_reject_duplicate_keys)
    except ReviewError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReviewError(f"review JSON을 읽지 못했습니다: {exc}") from exc


def _expect_exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise ReviewError(
            f"{label} 키가 schema와 다릅니다: missing={sorted(expected - actual)}, "
            f"unknown={sorted(actual - expected)}"
        )


def _validate_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ReviewError(f"{label}은 비어 있지 않은 문자열이어야 합니다.")
    if MARKER_PREFIX in value or "\x00" in value:
        raise ReviewError(f"{label}에 금지된 marker 또는 NUL이 있습니다.")
    return value


def _validate_single_line_text(value: Any, label: str) -> str:
    text = _validate_text(value, label).strip()
    if "\n" in text or "\r" in text:
        raise ReviewError(f"{label}은 한 줄 문자열이어야 합니다.")
    return text


def _validate_text_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise ReviewError(f"{label}은 하나 이상의 문자열을 가진 배열이어야 합니다.")
    result = [
        _validate_single_line_text(item, f"{label}[{index}]")
        for index, item in enumerate(value)
    ]
    if len(set(result)) != len(result):
        raise ReviewError(f"{label}에 중복 항목이 있습니다.")
    return result


def _validate_summary(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReviewError("summary는 객체여야 합니다.")
    _expect_exact_keys(value, SUMMARY_KEYS, "summary")
    walkthrough = _validate_text(value["walkthrough"], "summary.walkthrough")
    walkthrough_lines = [line.strip() for line in walkthrough.splitlines() if line.strip()]
    if len(walkthrough_lines) not in (2, 3):
        raise ReviewError("summary.walkthrough는 비어 있지 않은 2~3줄이어야 합니다.")
    return {
        "walkthrough": "\n".join(walkthrough_lines),
        "strengths": _validate_text_list(value["strengths"], "summary.strengths"),
        "next_actions": _validate_text_list(
            value["next_actions"], "summary.next_actions"
        ),
    }


def _validate_snapshot(value: Any, label: str) -> dict[str, str]:
    if not isinstance(value, dict):
        raise ReviewError(f"{label}는 객체여야 합니다.")
    _expect_exact_keys(value, SNAPSHOT_KEYS, label)
    ref = _validate_text(value["ref"], f"{label}.ref")
    sha = value["sha"]
    if not isinstance(sha, str) or not SHA_RE.fullmatch(sha):
        raise ReviewError(f"{label}.sha는 40자리 소문자 SHA여야 합니다.")
    return {"ref": ref, "sha": sha}


def _validate_path(value: Any) -> str:
    path = _validate_text(value, "finding.path")
    pure = PurePosixPath(path)
    if pure.is_absolute() or ".." in pure.parts or "\\" in path or path.startswith(".git/"):
        raise ReviewError(f"안전하지 않은 finding path: {path}")
    return path


def expected_verdict(findings: list[dict[str, Any]]) -> str:
    severities = {finding["severity"] for finding in findings}
    if "critical" in severities:
        return "BLOCKED"
    if "major" in severities:
        return "CHANGES_REQUESTED"
    return "APPROVE"


def validate_envelope(
    value: Any,
    *,
    expected_repo: str | None = None,
    expected_pr: int | None = None,
    expected_base: str | None = None,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReviewError("review envelope는 JSON 객체여야 합니다.")
    _expect_exact_keys(value, TOP_LEVEL_KEYS, "review envelope")
    schema_version = value["schema_version"]
    if (
        isinstance(schema_version, bool)
        or not isinstance(schema_version, (int, float))
        or schema_version != 2
    ):
        raise ReviewError("지원하지 않는 schema_version입니다.")

    repo = value["repository"]
    if not isinstance(repo, str) or not REPO_RE.fullmatch(repo):
        raise ReviewError("repository는 OWNER/REPO 형식이어야 합니다.")
    if expected_repo is not None and repo.lower() != expected_repo.lower():
        raise ReviewError("CLI repository와 envelope repository가 다릅니다.")

    issue_number = value["issue_number"]
    if not isinstance(issue_number, int) or isinstance(issue_number, bool) or issue_number < 1:
        raise ReviewError("issue_number는 양의 정수여야 합니다.")

    pull_number = value["pull_number"]
    if not isinstance(pull_number, int) or isinstance(pull_number, bool) or pull_number < 1:
        raise ReviewError("pull_number는 양의 정수여야 합니다.")
    if expected_pr is not None and pull_number != expected_pr:
        raise ReviewError("CLI PR 번호와 envelope pull_number가 다릅니다.")

    phase = value["phase"]
    if not isinstance(phase, str) or not PHASE_RE.fullmatch(phase):
        raise ReviewError("phase slug가 올바르지 않습니다.")
    step = value["step"]
    if not isinstance(step, int) or isinstance(step, bool) or step < 1:
        raise ReviewError("step은 양의 정수여야 합니다.")
    attempt = value["attempt"]
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt not in (0, 1, 2):
        raise ReviewError("attempt는 0, 1, 2 중 하나여야 합니다.")

    base = _validate_snapshot(value["base"], "base")
    head = _validate_snapshot(value["head"], "head")
    if expected_base is not None and base["ref"] != expected_base:
        raise ReviewError("CLI expected-base와 envelope base.ref가 다릅니다.")
    if base["sha"] == head["sha"]:
        raise ReviewError("base와 head SHA는 달라야 합니다.")
    expected_head_prefix = f"codex/{phase}-step-{step:02d}-"
    if not head["ref"].startswith(expected_head_prefix):
        raise ReviewError(
            f"head.ref는 현재 Phase/Step branch여야 합니다: {expected_head_prefix}*"
        )

    verdict = value["verdict"]
    if verdict not in VERDICTS:
        raise ReviewError(f"지원하지 않는 verdict: {verdict}")
    summary = _validate_summary(value["summary"])

    raw_findings = value["findings"]
    if not isinstance(raw_findings, list):
        raise ReviewError("findings는 배열이어야 합니다.")
    findings: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    for index, raw in enumerate(raw_findings):
        if not isinstance(raw, dict):
            raise ReviewError(f"findings[{index}]는 객체여야 합니다.")
        _expect_exact_keys(raw, FINDING_REQUIRED_KEYS, f"findings[{index}]")
        finding_key = raw["finding_key"]
        if not isinstance(finding_key, str) or not FINDING_KEY_RE.fullmatch(finding_key):
            raise ReviewError(f"findings[{index}].finding_key가 올바르지 않습니다.")
        if finding_key in seen_keys:
            raise ReviewError(f"중복 finding_key: {finding_key}")
        seen_keys.add(finding_key)
        dimension = _validate_text(raw["dimension"], f"findings[{index}].dimension")
        if dimension not in DIMENSIONS:
            raise ReviewError(
                f"findings[{index}].dimension은 review-code 차원 중 하나여야 합니다."
            )
        severity = raw["severity"]
        if severity not in SEVERITIES:
            raise ReviewError(f"findings[{index}].severity가 올바르지 않습니다.")
        title = _validate_single_line_text(raw["title"], f"findings[{index}].title")
        tldr = _validate_single_line_text(raw["tldr"], f"findings[{index}].tldr")
        good = _validate_single_line_text(raw["good"], f"findings[{index}].good")
        fix_markdown = _validate_text(
            raw["fix_markdown"], f"findings[{index}].fix_markdown"
        ).strip()
        path = _validate_path(raw["path"])
        line = raw["line"]
        if not isinstance(line, int) or isinstance(line, bool) or line < 1:
            raise ReviewError(f"findings[{index}].line은 양의 정수여야 합니다.")
        side = raw["side"]
        if side not in ("LEFT", "RIGHT"):
            raise ReviewError(f"findings[{index}].side는 LEFT 또는 RIGHT여야 합니다.")
        findings.append(
            {
                "finding_key": finding_key,
                "dimension": dimension,
                "severity": severity,
                "title": title,
                "tldr": tldr,
                "good": good,
                "fix_markdown": fix_markdown,
                "path": path,
                "line": line,
                "side": side,
            }
        )

    computed = expected_verdict(findings)
    if verdict != computed:
        raise ReviewError(f"verdict 불일치: supplied={verdict}, expected={computed}")

    return {
        "schema_version": 2,
        "repository": repo,
        "issue_number": issue_number,
        "pull_number": pull_number,
        "phase": phase,
        "step": step,
        "attempt": attempt,
        "base": base,
        "head": head,
        "verdict": verdict,
        "summary": summary,
        "findings": findings,
    }


class GhClient:
    def _run(self, endpoint: str, *, method: str = "GET", payload: Any | None = None) -> Any:
        command = [
            "gh",
            "api",
            "--method",
            method,
            "-H",
            "Accept: application/vnd.github+json",
            "-H",
            f"X-GitHub-Api-Version: {API_VERSION}",
            endpoint,
        ]
        input_text = None
        if payload is not None:
            command.extend(("--input", "-"))
            input_text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
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
            raise GitHubError(f"GitHub API 실행 실패: {exc}") from exc
        if result.returncode != 0:
            message = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
            raise GitHubError(f"GitHub API 실패: {endpoint}: {message[:1000]}")
        if not result.stdout.strip() and method == "DELETE":
            return None
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as exc:
            raise GitHubError(f"GitHub API가 JSON을 반환하지 않았습니다: {endpoint}") from exc

    def get(self, endpoint: str) -> Any:
        return self._run(endpoint)

    def post(self, endpoint: str, payload: Any) -> Any:
        return self._run(endpoint, method="POST", payload=payload)

    def delete(self, endpoint: str) -> None:
        self._run(endpoint, method="DELETE")

    def get_pages(self, endpoint: str, *, max_pages: int = 100) -> list[Any]:
        items: list[Any] = []
        separator = "&" if "?" in endpoint else "?"
        for page in range(1, max_pages + 1):
            data = self.get(f"{endpoint}{separator}per_page=100&page={page}")
            if not isinstance(data, list):
                raise GitHubError(f"페이지 API가 배열을 반환하지 않았습니다: {endpoint}")
            items.extend(data)
            if len(data) < 100:
                return items
        raise GitHubError(f"페이지 제한을 초과했습니다: {endpoint}")


def _verify_pr_snapshot(pr: Any, envelope: dict[str, Any], expected_base: str) -> None:
    if not isinstance(pr, dict):
        raise SnapshotError("PR API 응답이 객체가 아닙니다.")
    try:
        full_name = pr["base"]["repo"]["full_name"]
        head_full_name = pr["head"]["repo"]["full_name"]
        base_ref = pr["base"]["ref"]
        base_sha = pr["base"]["sha"]
        head_ref = pr["head"]["ref"]
        head_sha = pr["head"]["sha"]
    except (KeyError, TypeError) as exc:
        raise SnapshotError("PR API 응답에 base/head snapshot이 없습니다.") from exc
    if str(full_name).lower() != envelope["repository"].lower():
        raise SnapshotError("PR repository가 envelope와 다릅니다.")
    if str(head_full_name).lower() != envelope["repository"].lower():
        raise SnapshotError("fork 또는 다른 repository의 PR head는 허용하지 않습니다.")
    if pr.get("state") != "open" or pr.get("merged") is True:
        raise SnapshotError("PR이 open/unmerged 상태가 아닙니다.")
    if base_ref != expected_base or base_ref != envelope["base"]["ref"]:
        raise SnapshotError("PR base ref가 develop 계약과 다릅니다.")
    if base_sha != envelope["base"]["sha"]:
        raise SnapshotError("PR base SHA가 리뷰 snapshot과 다릅니다.")
    if head_ref != envelope["head"]["ref"] or head_sha != envelope["head"]["sha"]:
        raise SnapshotError("PR head ref/SHA가 리뷰 snapshot과 다릅니다.")
    closing = re.compile(
        rf"(?im)\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#0*{envelope['issue_number']}\b"
    )
    if closing.search(str(pr.get("body") or "")) is None:
        raise SnapshotError("PR 본문이 review envelope의 canonical Issue를 종료하도록 연결하지 않습니다.")


def parse_patch(patch: str) -> set[tuple[int, str]]:
    anchors: set[tuple[int, str]] = set()
    old_line = new_line = 0
    old_expected = new_expected = None
    old_seen = new_seen = 0

    def finish_hunk() -> None:
        if old_expected is None or new_expected is None:
            return
        if old_seen != old_expected or new_seen != new_expected:
            raise SnapshotError("PR patch hunk가 잘렸거나 count가 일치하지 않습니다.")

    saw_hunk = False
    for raw in patch.splitlines():
        match = HUNK_RE.match(raw)
        if match:
            finish_hunk()
            saw_hunk = True
            old_line = int(match.group("old_start"))
            new_line = int(match.group("new_start"))
            old_expected = int(match.group("old_count") or "1")
            new_expected = int(match.group("new_count") or "1")
            old_seen = new_seen = 0
            continue
        if not saw_hunk or raw.startswith("\\ No newline at end of file"):
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            anchors.add((new_line, "RIGHT"))
            new_line += 1
            new_seen += 1
        elif raw.startswith("-") and not raw.startswith("---"):
            anchors.add((old_line, "LEFT"))
            old_line += 1
            old_seen += 1
        elif raw.startswith(" "):
            anchors.add((new_line, "RIGHT"))
            old_line += 1
            new_line += 1
            old_seen += 1
            new_seen += 1
        else:
            raise SnapshotError(f"해석할 수 없는 PR patch 행: {raw[:80]}")
    finish_hunk()
    if not saw_hunk:
        raise SnapshotError("PR patch에 hunk가 없습니다.")
    return anchors


def _validate_anchors(files: list[Any], findings: list[dict[str, Any]], changed_files: int) -> None:
    if len(files) != changed_files:
        raise SnapshotError(
            f"PR files pagination 불일치: expected={changed_files}, actual={len(files)}"
        )
    by_name: dict[str, dict[str, Any]] = {}
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("filename"), str):
            raise SnapshotError("PR file 응답이 올바르지 않습니다.")
        filename = item["filename"]
        if filename in by_name:
            raise SnapshotError(f"PR file이 중복되었습니다: {filename}")
        by_name[filename] = item

    anchor_cache: dict[str, set[tuple[int, str]]] = {}
    for finding in findings:
        path = finding["path"]
        item = by_name.get(path)
        if item is None:
            raise SnapshotError(f"finding path가 현재 PR diff에 없습니다: {path}")
        patch = item.get("patch")
        if not isinstance(patch, str) or not patch:
            raise SnapshotError(f"inline anchor를 검증할 patch가 없습니다: {path}")
        anchors = anchor_cache.setdefault(path, parse_patch(patch))
        anchor = (finding["line"], finding["side"])
        if anchor not in anchors:
            raise SnapshotError(
                f"finding anchor가 현재 PR diff에 없습니다: {path}:{finding['line']}:{finding['side']}"
            )


def _finding_comment(finding: dict[str, Any]) -> str:
    emoji = {"critical": "🔴", "major": "🟠", "minor": "🟡", "nit": "⚪"}[
        finding["severity"]
    ]
    lines = [
        f"{emoji} {finding['severity']} | {finding['title']}",
        f"TL;DR: {finding['tldr']}",
        f"Good: {finding['good']}",
        "→ Fix:",
        finding["fix_markdown"],
    ]
    return "\n".join(lines)


def build_payload(envelope: dict[str, Any]) -> tuple[dict[str, Any], str]:
    findings = sorted(
        envelope["findings"],
        key=lambda finding: (
            SEVERITY_ORDER[finding["severity"]],
            finding["path"],
            finding["line"],
            finding["side"],
            finding["finding_key"],
        ),
    )
    grouped: dict[tuple[str, int, str], list[dict[str, Any]]] = defaultdict(list)
    for finding in findings:
        grouped[(finding["path"], finding["line"], finding["side"])].append(finding)

    comments = []
    for (path, line, side), items in sorted(grouped.items()):
        comments.append(
            {
                "path": path,
                "line": line,
                "side": side,
                "body": "\n\n---\n\n".join(_finding_comment(item) for item in items),
            }
        )

    counts = Counter(finding["severity"] for finding in findings)
    summary = envelope["summary"]
    blocking_findings = [
        finding for finding in findings if finding["severity"] in {"critical", "major"}
    ]
    summary_lines = [
        f"## 판정: {VERDICT_LABELS[envelope['verdict']]}",
        (
            "심각도: "
            f"🔴 {counts['critical']}  🟠 {counts['major']}  "
            f"🟡 {counts['minor']}  ⚪ {counts['nit']}"
        ),
        f"Walkthrough: {summary['walkthrough']}",
        "",
        "잘된 점:",
        *(f"- {strength}" for strength in summary["strengths"]),
        "",
        "주요 지적 (critical/major만):",
    ]
    if blocking_findings:
        summary_lines.extend(
            (
                f"- {({'critical': '🔴', 'major': '🟠'})[finding['severity']]} "
                f"{finding['path']}:{finding['line']} — {finding['title']}"
            )
            for finding in blocking_findings
        )
    else:
        summary_lines.append("- 없음")
    summary_lines.extend(
        (
            "",
            "다음 액션:",
            *(f"- {action}" for action in summary["next_actions"]),
        )
    )
    body_without_marker = "\n".join(summary_lines).strip()
    digest_source = json.dumps(
        {"body": body_without_marker, "comments": comments},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    digest = hashlib.sha256(digest_source.encode("utf-8")).hexdigest()
    marker = (
        f"<!-- phase-issue-autopilot-review:v2 repo={envelope['repository']} "
        f"issue={envelope['issue_number']} pr={envelope['pull_number']} "
        f"base={envelope['base']['sha']} "
        f"head={envelope['head']['sha']} digest={digest} -->"
    )
    return {
        "commit_id": envelope["head"]["sha"],
        "body": f"{body_without_marker}\n\n{marker}",
        "comments": comments,
    }, digest


def _review_marker(review: dict[str, Any]) -> dict[str, str] | None:
    body = review.get("body")
    if not isinstance(body, str):
        return None
    match = MARKER_RE.search(body)
    return match.groupdict() if match else None


def _comments_signature(comments: list[Any]) -> list[tuple[str, int, str, str]]:
    signature: list[tuple[str, int, str, str]] = []
    for comment in comments:
        if not isinstance(comment, dict):
            raise IdempotencyError("review comment 응답이 객체가 아닙니다.")
        signature.append(
            (
                str(comment.get("path") or ""),
                int(comment.get("line") or 0),
                str(comment.get("side") or ""),
                str(comment.get("body") or ""),
            )
        )
    return sorted(signature)


def _verify_existing_review(
    client: GhClient,
    repo: str,
    pr: int,
    review: dict[str, Any],
    payload: dict[str, Any],
    viewer_login: str,
    *,
    allowed_states: set[str] | None = None,
) -> None:
    states = allowed_states or {"COMMENTED"}
    if review.get("state") not in states:
        raise IdempotencyError(
            f"기존 marker review 상태가 허용 범위와 다릅니다: {review.get('state')}"
        )
    if review.get("commit_id") != payload["commit_id"] or review.get("body") != payload["body"]:
        raise IdempotencyError("기존 marker review 본문 또는 commit이 다릅니다.")
    if str((review.get("user") or {}).get("login") or "").lower() != viewer_login.lower():
        raise IdempotencyError("기존 marker review 작성자가 현재 인증 계정과 다릅니다.")
    review_id = review.get("id")
    if not isinstance(review_id, int):
        raise IdempotencyError("기존 review id가 없습니다.")
    actual = client.get_pages(f"repos/{repo}/pulls/{pr}/reviews/{review_id}/comments")
    if _comments_signature(actual) != _comments_signature(payload["comments"]):
        raise IdempotencyError("기존 marker review의 inline comments가 다릅니다.")


def _find_existing(
    client: GhClient,
    envelope: dict[str, Any],
    payload: dict[str, Any],
    digest: str,
    viewer_login: str,
) -> dict[str, Any] | None:
    repo = envelope["repository"]
    pr = envelope["pull_number"]
    reviews = client.get_pages(f"repos/{repo}/pulls/{pr}/reviews")
    same_snapshot: list[tuple[dict[str, Any], dict[str, str]]] = []
    for review in reviews:
        if not isinstance(review, dict):
            continue
        author = str((review.get("user") or {}).get("login") or "")
        if author.lower() != viewer_login.lower():
            continue
        marker = _review_marker(review)
        if marker is None:
            continue
        if (
            marker["repo"].lower() == repo.lower()
            and int(marker["issue"]) == envelope["issue_number"]
            and int(marker["pr"]) == pr
            and marker["base"] == envelope["base"]["sha"]
            and marker["head"] == envelope["head"]["sha"]
        ):
            same_snapshot.append((review, marker))
    if len(same_snapshot) > 1:
        raise IdempotencyError("같은 snapshot의 autopilot review가 중복되었습니다.")
    if not same_snapshot:
        return None
    review, marker = same_snapshot[0]
    if marker["digest"] != digest:
        raise IdempotencyError("같은 snapshot에 다른 review payload가 이미 있습니다.")
    _verify_existing_review(
        client,
        repo,
        pr,
        review,
        payload,
        viewer_login,
        allowed_states={"PENDING", "COMMENTED"},
    )
    return review


def _verdict_exit_code(verdict: str) -> int:
    return {"APPROVE": 0, "CHANGES_REQUESTED": 10, "BLOCKED": 11}[verdict]


@contextmanager
def _same_host_review_lock(envelope: dict[str, Any]) -> Iterator[None]:
    """Serialize publication for one PR snapshot across local worker processes."""
    lock_key = hashlib.sha256(
        f"{envelope['repository'].lower()}:{envelope['pull_number']}".encode("utf-8")
    ).hexdigest()[:24]
    lock_path = Path(tempfile.gettempdir()) / f"phase-review-publisher-{lock_key}.lock"
    try:
        handle = lock_path.open("a+b")
    except OSError as exc:
        raise IdempotencyError(f"review 게시 lock을 열지 못했습니다: {lock_path}: {exc}") from exc

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
            raise IdempotencyError(
                "같은 PR snapshot의 다른 로컬 review publisher가 실행 중입니다."
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


def _publish_locked(
    client: GhClient,
    envelope: dict[str, Any],
    *,
    expected_base: str,
    apply: bool,
) -> tuple[dict[str, Any], int]:
    repo = envelope["repository"]
    pr_number = envelope["pull_number"]
    endpoint = f"repos/{repo}/pulls/{pr_number}"
    viewer = client.get("user")
    viewer_login = str((viewer or {}).get("login") or "")
    if not viewer_login:
        raise GitHubError("현재 GitHub 사용자 login을 확인하지 못했습니다.")

    pr = client.get(endpoint)
    _verify_pr_snapshot(pr, envelope, expected_base)
    files = client.get_pages(f"{endpoint}/files", max_pages=30)
    changed_files = pr.get("changed_files")
    if not isinstance(changed_files, int):
        raise SnapshotError("PR changed_files를 확인하지 못했습니다.")
    _validate_anchors(files, envelope["findings"], changed_files)
    payload, digest = build_payload(envelope)
    existing = _find_existing(client, envelope, payload, digest, viewer_login)
    if existing is not None and existing.get("state") == "COMMENTED":
        pr_after_existing = client.get(endpoint)
        _verify_pr_snapshot(pr_after_existing, envelope, expected_base)
        return {
            "action": "existing",
            "digest": digest,
            "review_id": existing.get("id"),
            "verdict": envelope["verdict"],
        }, _verdict_exit_code(envelope["verdict"])

    pr_before_post = client.get(endpoint)
    _verify_pr_snapshot(pr_before_post, envelope, expected_base)
    if not apply:
        return {
            "action": "would_resume_pending" if existing is not None else "would_post_pending",
            "digest": digest,
            "endpoint": f"{endpoint}/reviews",
            "payload": payload,
            "submit_payload": {"event": "COMMENT"},
            "verdict": envelope["verdict"],
        }, _verdict_exit_code(envelope["verdict"])

    pending = existing
    if pending is None:
        try:
            pending = client.post(f"{endpoint}/reviews", payload)
        except GitHubError:
            recovered = _find_existing(client, envelope, payload, digest, viewer_login)
            if recovered is None:
                raise
            pending = recovered

    if not isinstance(pending, dict) or not isinstance(pending.get("id"), int):
        raise GitHubError("생성된 pending review 응답에 id가 없습니다.")
    if pending.get("state") == "COMMENTED":
        _verify_existing_review(client, repo, pr_number, pending, payload, viewer_login)
        pr_after_recovery = client.get(endpoint)
        _verify_pr_snapshot(pr_after_recovery, envelope, expected_base)
        return {
            "action": "existing",
            "digest": digest,
            "review_id": pending["id"],
            "verdict": envelope["verdict"],
        }, _verdict_exit_code(envelope["verdict"])

    _verify_existing_review(
        client,
        repo,
        pr_number,
        pending,
        payload,
        viewer_login,
        allowed_states={"PENDING"},
    )
    pending_endpoint = f"{endpoint}/reviews/{pending['id']}"
    try:
        pr_before_submit = client.get(endpoint)
        _verify_pr_snapshot(pr_before_submit, envelope, expected_base)
    except SnapshotError:
        client.delete(pending_endpoint)
        raise

    try:
        submitted = client.post(f"{pending_endpoint}/events", {"event": "COMMENT"})
    except GitHubError:
        recovered = _find_existing(client, envelope, payload, digest, viewer_login)
        if recovered is None or recovered.get("state") != "COMMENTED":
            raise
        submitted = recovered
    if not isinstance(submitted, dict) or not isinstance(submitted.get("id"), int):
        raise GitHubError("제출된 review 응답에 id가 없습니다.")
    _verify_existing_review(client, repo, pr_number, submitted, payload, viewer_login)
    pr_after_submit = client.get(endpoint)
    _verify_pr_snapshot(pr_after_submit, envelope, expected_base)
    return {
        "action": "posted",
        "digest": digest,
        "review_id": submitted["id"],
        "verdict": envelope["verdict"],
    }, _verdict_exit_code(envelope["verdict"])


def publish(
    client: GhClient,
    envelope: dict[str, Any],
    *,
    expected_base: str,
    apply: bool,
) -> tuple[dict[str, Any], int]:
    with _same_host_review_lock(envelope):
        return _publish_locked(client, envelope, expected_base=expected_base, apply=apply)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="Validate an envelope without GitHub access")
    validate.add_argument("--input", required=True, help="JSON file or - for stdin")

    publish_parser = subparsers.add_parser("publish", help="Validate and publish one COMMENT review")
    publish_parser.add_argument("--input", required=True, help="JSON file or - for stdin")
    publish_parser.add_argument("--repo", required=True, help="OWNER/REPO")
    publish_parser.add_argument("--pr", required=True, type=int, help="Pull request number")
    publish_parser.add_argument("--expected-base", default="develop")
    publish_parser.add_argument("--apply", action="store_true", help="Perform the GitHub write")
    return parser


def _json_print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2))


def main(argv: list[str] | None = None, *, client_factory: Callable[[], GhClient] = GhClient) -> int:
    args = build_parser().parse_args(argv)
    try:
        raw = _load_json(args.input)
        if args.command == "validate":
            envelope = validate_envelope(raw)
            _json_print(
                {
                    "finding_count": len(envelope["findings"]),
                    "status": "valid",
                    "verdict": envelope["verdict"],
                }
            )
            return 0
        envelope = validate_envelope(
            raw,
            expected_repo=args.repo,
            expected_pr=args.pr,
            expected_base=args.expected_base,
        )
        result, code = publish(
            client_factory(), envelope, expected_base=args.expected_base, apply=args.apply
        )
        _json_print(result)
        return code
    except ReviewError as exc:
        print(f"REVIEW_ERROR: {exc}", file=sys.stderr)
        return exc.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
