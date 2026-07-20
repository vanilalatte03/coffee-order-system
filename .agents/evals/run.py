#!/usr/bin/env python3
"""Deterministic health checks for repository-local review fixtures."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


EVALS_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = EVALS_DIR.parent.parent
MANIFEST_PATH = EVALS_DIR / "manifest.json"
MANIFEST_SCHEMA_VERSION = 1
CASE_SCHEMA_VERSION = 1
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
PATCH_HEADER = re.compile(r"^diff --git a/([^\r\n]+) b/([^\r\n]+)$", re.MULTILINE)
PATCH_OLD_PATH = re.compile(r"^--- a/([^\r\n]+)$", re.MULTILINE)
PATCH_NEW_PATH = re.compile(r"^\+\+\+ b/([^\r\n]+)$", re.MULTILINE)
FORBIDDEN_PUBLIC_FIELDS = {
    "expected",
    "finding_id",
    "finding_ids",
    "findings",
    "provenance",
    "response_schema",
    "rubric",
    "rubric_id",
}
MANIFEST_FIELDS = {"schema_version", "name", "suite_id", "fixture_kind"}
TOP_LEVEL_CASE_FIELDS = {
    "schema_version",
    "id",
    "suite",
    "title",
    "base_revision",
    "contract_refs",
    "task",
}
TASK_FIELDS = {"review_scope", "instruction", "candidate_diff"}
CONTRACT_REF_FIELDS = {"path", "contains"}


class FixtureHealthError(RuntimeError):
    """Raised when a fixture or its pinned snapshot cannot be validated safely."""


def load_json(path: Path) -> Any:
    try:
        with path.open(encoding="utf-8") as source:
            return json.load(source)
    except FileNotFoundError as error:
        raise FixtureHealthError(f"파일을 찾을 수 없습니다: {path}") from error
    except json.JSONDecodeError as error:
        raise FixtureHealthError(f"JSON 형식이 잘못되었습니다: {path}: {error}") from error


def relative_label(path: Path) -> str:
    try:
        return path.resolve().relative_to(REPOSITORY_ROOT.resolve()).as_posix()
    except ValueError:
        return str(path)


def validate_relative_path(value: Any) -> str:
    if not isinstance(value, str) or not value:
        raise FixtureHealthError("비어 있지 않은 저장소 상대 경로가 필요합니다.")
    if "\\" in value or "\0" in value:
        raise FixtureHealthError(f"POSIX 형식의 저장소 상대 경로만 허용합니다: {value}")
    if value.startswith("/") or re.match(r"^[A-Za-z]:", value):
        raise FixtureHealthError(f"절대 경로는 허용하지 않습니다: {value}")

    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise FixtureHealthError(f"정규화된 저장소 상대 경로만 허용합니다: {value}")
    return value


def git_command(*arguments: str, cwd: Path = REPOSITORY_ROOT) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["git", *arguments],
            cwd=cwd,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as error:
        raise FixtureHealthError(f"git 명령을 실행할 수 없습니다: {error}") from error


def git_output(*arguments: str) -> str:
    result = git_command(*arguments)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise FixtureHealthError(f"git {' '.join(arguments)} 실패: {detail}")
    return result.stdout


def commit_exists(revision: str) -> bool:
    result = git_command("rev-parse", "--verify", f"{revision}^{{commit}}")
    return result.returncode == 0 and result.stdout.strip() == revision


def git_file_content(revision: str, relative_path: str) -> str:
    validate_relative_path(relative_path)
    object_spec = f"{revision}:{relative_path}"
    object_type = git_output("cat-file", "-t", object_spec).strip()
    if object_type != "blob":
        raise FixtureHealthError(f"파일 경로만 허용합니다: {relative_path}")
    return git_output("cat-file", "blob", object_spec)


def load_manifest() -> dict[str, Any]:
    manifest = load_json(MANIFEST_PATH)
    if not isinstance(manifest, dict):
        raise FixtureHealthError("manifest.json의 루트는 객체여야 합니다.")
    return manifest


def load_cases() -> list[tuple[Path, dict[str, Any]]]:
    cases_directory = EVALS_DIR / "cases" / "review-code"
    if not cases_directory.is_dir():
        raise FixtureHealthError(f"fixture 디렉터리를 찾을 수 없습니다: {cases_directory}")

    cases: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted(cases_directory.glob("*.json")):
        case = load_json(path)
        if not isinstance(case, dict):
            raise FixtureHealthError(f"fixture 루트는 객체여야 합니다: {path}")
        cases.append((path, case))
    return cases


def forbidden_fields(value: Any) -> set[str]:
    found: set[str] = set()
    if isinstance(value, dict):
        for key, child in value.items():
            if key in FORBIDDEN_PUBLIC_FIELDS:
                found.add(key)
            found.update(forbidden_fields(child))
    elif isinstance(value, list):
        for child in value:
            found.update(forbidden_fields(child))
    return found


def candidate_diff_paths(candidate_diff: str) -> set[str]:
    if "GIT binary patch" in candidate_diff or "Binary files " in candidate_diff:
        raise FixtureHealthError("binary candidate_diff는 허용하지 않습니다.")

    headers = PATCH_HEADER.findall(candidate_diff)
    if not headers or len(headers) != len(re.findall(r"^diff --git ", candidate_diff, re.MULTILINE)):
        raise FixtureHealthError("candidate_diff의 diff --git header가 없거나 해석할 수 없습니다.")

    paths: set[str] = set()
    for old_path, new_path in headers:
        validate_relative_path(old_path)
        validate_relative_path(new_path)
        if old_path != new_path:
            raise FixtureHealthError("파일 생성, 삭제, rename candidate_diff는 허용하지 않습니다.")
        if old_path in paths:
            raise FixtureHealthError(f"candidate_diff에 경로가 중복됩니다: {old_path}")
        paths.add(old_path)

    old_paths = PATCH_OLD_PATH.findall(candidate_diff)
    new_paths = PATCH_NEW_PATH.findall(candidate_diff)
    if len(old_paths) != len(headers) or len(new_paths) != len(headers):
        raise FixtureHealthError("candidate_diff의 ---/+++ path header가 완전하지 않습니다.")
    for path in [*old_paths, *new_paths]:
        validate_relative_path(path)
    if set(old_paths) != paths or set(new_paths) != paths:
        raise FixtureHealthError("candidate_diff의 path header들이 서로 일치하지 않습니다.")
    return paths


def validate_candidate_diff(
    base_revision: str, candidate_diff: str, review_scope: list[str]
) -> str | None:
    try:
        base_files = {
            relative_path: git_file_content(base_revision, relative_path)
            for relative_path in review_scope
        }
    except FixtureHealthError as error:
        return str(error)

    with tempfile.TemporaryDirectory(prefix="coffee-fixture-health-") as directory:
        temporary_root = Path(directory)
        for relative_path, content in base_files.items():
            target = temporary_root.joinpath(*relative_path.split("/"))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content.encode("utf-8"))

        patch_path = temporary_root / "candidate.patch"
        patch_path.write_bytes(candidate_diff.encode("utf-8"))
        result = git_command(
            "apply",
            "--check",
            "--no-index",
            "--whitespace=nowarn",
            str(patch_path),
            cwd=temporary_root,
        )

    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        return f"base_revision에 candidate_diff를 적용할 수 없습니다: {detail}"
    return None


def validate_manifest(manifest: dict[str, Any]) -> tuple[list[str], str | None]:
    errors: list[str] = []
    for field in MANIFEST_FIELDS - set(manifest):
        errors.append(f"manifest.json에 필수 필드가 없습니다: {field}")
    unexpected_fields = set(manifest) - MANIFEST_FIELDS
    if unexpected_fields:
        errors.append(
            f"manifest.json에 허용되지 않은 필드가 있습니다: {sorted(unexpected_fields)}"
        )
    leaked_fields = forbidden_fields(manifest)
    if leaked_fields:
        errors.append(f"manifest.json에 금지된 필드가 있습니다: {sorted(leaked_fields)}")
    if manifest.get("schema_version") != MANIFEST_SCHEMA_VERSION:
        errors.append(f"manifest.json의 schema_version은 {MANIFEST_SCHEMA_VERSION}이어야 합니다.")
    if manifest.get("fixture_kind") != "deterministic-contract-health":
        errors.append("manifest.json의 fixture_kind는 deterministic-contract-health여야 합니다.")
    suite_id = manifest.get("suite_id")
    if suite_id != "review-code":
        errors.append("manifest.json은 단일 review-code suite만 선언해야 합니다.")
        return errors, None
    return errors, suite_id


def validate_case(path: Path, case: dict[str, Any], suite_id: str) -> list[str]:
    errors: list[str] = []
    label = relative_label(path)
    for field in TOP_LEVEL_CASE_FIELDS:
        if field not in case:
            errors.append(f"{label}: 필수 필드가 없습니다: {field}")
    unexpected_fields = set(case) - TOP_LEVEL_CASE_FIELDS
    if unexpected_fields:
        errors.append(
            f"{label}: fixture에 허용되지 않은 필드가 있습니다: {sorted(unexpected_fields)}"
        )

    leaked_fields = forbidden_fields(case)
    if leaked_fields:
        errors.append(f"{label}: 공개 fixture에 금지된 필드가 있습니다: {sorted(leaked_fields)}")

    if case.get("schema_version") != CASE_SCHEMA_VERSION:
        errors.append(f"{label}: schema_version은 {CASE_SCHEMA_VERSION}이어야 합니다.")

    identifier = case.get("id")
    if not isinstance(identifier, str) or not identifier.strip():
        errors.append(f"{label}: id는 비어 있지 않은 문자열이어야 합니다.")
    if case.get("suite") != suite_id:
        errors.append(f"{label}: suite는 {suite_id}여야 합니다.")
    if not isinstance(case.get("title"), str) or not case["title"].strip():
        errors.append(f"{label}: title은 비어 있지 않은 문자열이어야 합니다.")

    base_revision = case.get("base_revision")
    valid_base = isinstance(base_revision, str) and bool(COMMIT_PATTERN.fullmatch(base_revision))
    if not valid_base:
        errors.append(f"{label}: base_revision은 40자리 commit SHA여야 합니다.")
    elif not commit_exists(base_revision):
        errors.append(f"{label}: base_revision commit을 찾을 수 없습니다: {base_revision}")

    task = case.get("task")
    valid_scope: list[str] = []
    candidate_diff: str | None = None
    if not isinstance(task, dict):
        errors.append(f"{label}: task 객체가 필요합니다.")
    else:
        unexpected_fields = set(task) - TASK_FIELDS
        if unexpected_fields:
            errors.append(
                f"{label}: task에 허용되지 않은 필드가 있습니다: {sorted(unexpected_fields)}"
            )

        instruction = task.get("instruction")
        if not isinstance(instruction, str) or not instruction.strip():
            errors.append(f"{label}: task.instruction은 비어 있지 않은 문자열이어야 합니다.")

        review_scope = task.get("review_scope")
        if not isinstance(review_scope, list) or not review_scope:
            errors.append(f"{label}: task.review_scope에는 하나 이상의 경로가 필요합니다.")
        else:
            for relative_path in review_scope:
                try:
                    valid_scope.append(validate_relative_path(relative_path))
                except FixtureHealthError as error:
                    errors.append(f"{label}: {error}")
            if len(set(valid_scope)) != len(valid_scope):
                errors.append(f"{label}: task.review_scope 경로는 중복될 수 없습니다.")

        candidate = task.get("candidate_diff")
        if not isinstance(candidate, str) or not candidate.strip():
            errors.append(f"{label}: task.candidate_diff는 비어 있지 않은 문자열이어야 합니다.")
        else:
            candidate_diff = candidate
            try:
                diff_paths = candidate_diff_paths(candidate_diff)
                if diff_paths != set(valid_scope):
                    errors.append(
                        f"{label}: candidate_diff 경로와 review_scope가 일치하지 않습니다: "
                        f"diff={sorted(diff_paths)}, scope={sorted(valid_scope)}"
                    )
            except FixtureHealthError as error:
                errors.append(f"{label}: {error}")

    references = case.get("contract_refs")
    valid_references: list[tuple[str, str]] = []
    if not isinstance(references, list) or not references:
        errors.append(f"{label}: contract_refs에는 하나 이상의 참조가 필요합니다.")
    else:
        for reference in references:
            if not isinstance(reference, dict):
                errors.append(f"{label}: contract_refs 항목은 객체여야 합니다.")
                continue
            unexpected_fields = set(reference) - CONTRACT_REF_FIELDS
            if unexpected_fields:
                errors.append(
                    f"{label}: contract_refs 항목에 허용되지 않은 필드가 있습니다: "
                    f"{sorted(unexpected_fields)}"
                )
            expected_text = reference.get("contains")
            try:
                referenced_path = validate_relative_path(reference.get("path"))
            except FixtureHealthError as error:
                errors.append(f"{label}: {error}")
                continue
            if not isinstance(expected_text, str) or not expected_text.strip():
                errors.append(f"{label}: contract_refs.contains는 비어 있지 않은 문자열이어야 합니다.")
                continue
            valid_references.append((referenced_path, expected_text))

    if valid_base:
        for relative_path in valid_scope:
            try:
                content = git_file_content(base_revision, relative_path)
                if not content.strip():
                    errors.append(
                        f"{label}: base_revision의 review_scope 파일이 비어 있습니다: {relative_path}"
                    )
            except FixtureHealthError as error:
                errors.append(
                    f"{label}: base_revision에 review_scope 파일이 없습니다: "
                    f"{relative_path} ({error})"
                )

        for referenced_path, expected_text in valid_references:
            try:
                content = git_file_content(base_revision, referenced_path)
                if expected_text not in content:
                    errors.append(
                        f"{label}: base_revision에서 계약 참조 문구를 찾지 못했습니다: "
                        f"{referenced_path} -> {expected_text}"
                    )
            except FixtureHealthError as error:
                errors.append(
                    f"{label}: base_revision에 계약 참조 파일이 없습니다: "
                    f"{referenced_path} ({error})"
                )

        if candidate_diff is not None and set(valid_scope):
            try:
                paths_match = candidate_diff_paths(candidate_diff) == set(valid_scope)
            except FixtureHealthError:
                paths_match = False
            if paths_match:
                patch_error = validate_candidate_diff(base_revision, candidate_diff, valid_scope)
                if patch_error:
                    errors.append(f"{label}: {patch_error}")
    return errors


def health_errors() -> list[str]:
    errors: list[str] = []
    try:
        manifest = load_manifest()
        manifest_errors, suite_id = validate_manifest(manifest)
        errors.extend(manifest_errors)
        if suite_id is None:
            return errors

        cases = load_cases()
        if not cases:
            errors.append("하나 이상의 review-code fixture가 필요합니다.")
            return errors

        identifiers: set[str] = set()
        for path, case in cases:
            errors.extend(validate_case(path, case, suite_id))
            identifier = case.get("id")
            if isinstance(identifier, str):
                if identifier in identifiers:
                    errors.append(f"중복 fixture id입니다: {identifier}")
                identifiers.add(identifier)
    except FixtureHealthError as error:
        errors.append(str(error))
    return errors


def find_case(case_id: str) -> dict[str, Any]:
    matches = [case for _, case in load_cases() if case.get("id") == case_id]
    if not matches:
        raise FixtureHealthError(f"fixture를 찾을 수 없습니다: {case_id}")
    if len(matches) > 1:
        raise FixtureHealthError(f"중복 fixture id입니다: {case_id}")
    return matches[0]


def public_case_packet(case: dict[str, Any]) -> dict[str, Any]:
    return {
        "case_id": case["id"],
        "suite": case["suite"],
        "title": case["title"],
        "base_revision": case["base_revision"],
        "contract_refs": case["contract_refs"],
        "task": case["task"],
    }


def ensure_healthy() -> None:
    errors = health_errors()
    if errors:
        raise FixtureHealthError("\n".join(errors))


def command_health(_: argparse.Namespace) -> int:
    errors = health_errors()
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"fixture health: ok ({len(load_cases())} cases)")
    return 0


def command_list(_: argparse.Namespace) -> int:
    ensure_healthy()
    packets = [
        {
            "case_id": case["id"],
            "title": case["title"],
            "base_revision": case["base_revision"],
        }
        for _, case in load_cases()
    ]
    print(json.dumps(packets, ensure_ascii=False, indent=2))
    return 0


def command_show(arguments: argparse.Namespace) -> int:
    ensure_healthy()
    print(json.dumps(public_case_packet(find_case(arguments.case_id)), ensure_ascii=False, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="고정 Git snapshot에 대해 review fixture 계약 health를 검사합니다."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    health_parser = subparsers.add_parser("health", help="모든 fixture의 결정적 정합성을 검사합니다.")
    health_parser.set_defaults(handler=command_health)

    list_parser = subparsers.add_parser("list", help="fixture와 pinned base를 나열합니다.")
    list_parser.set_defaults(handler=command_list)

    show_parser = subparsers.add_parser("show", help="공개 task packet을 출력합니다.")
    show_parser.add_argument("case_id")
    show_parser.set_defaults(handler=command_show)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        return arguments.handler(arguments)
    except FixtureHealthError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
