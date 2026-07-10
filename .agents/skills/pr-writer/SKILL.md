---
name: pr-writer
description: "PR 작성/올리기/만들기 및 '커밋하고 PR 올려줘' 요청을 처리한다. PR 작성은 제목·본문만 작성하고, PR 올리기는 clean worktree의 미push 커밋을 push한 뒤 PR을 생성한다. 명시적 커밋 요청이 있으면 변경사항을 의미 단위로 커밋 후 push+PR 생성한다. dirty worktree는 명시적 커밋 요청 없이는 자동 커밋/stage하지 않는다. 트리거: 'PR 작성해줘', 'PR 올려줘', 'PR 만들어줘', '커밋하고 PR 올려줘', '현재 변경사항 전부 커밋해서 PR 올려줘'."
---

## 역할

현재 브랜치의 실제 커밋과 변경사항을 기준으로 PR 제목과 본문을 작성한다.
사용자가 PR 생성까지 명확히 요청한 경우에만 `gh pr create`로 생성한다.
코드 수정은 하지 않는다.

## 기본 규칙

- 실제 커밋/변경 파일/diff에 근거해서만 작성한다. 추측·향후 계획은 넣지 않는다.
- 확인하지 않은 테스트를 완료로 표시하지 않는다.
- 이슈 번호는 추측하지 않는다. 확인된 번호가 있으면 `Closes #N`, 없으면 `관련 이슈 없음: 확인된 이슈 번호 없음`으로 쓴다.
- PR 본문 구조의 정본은 `.github/PULL_REQUEST_TEMPLATE.md`다. 스킬에 기억된 섹션명을 사용하지 말고, PR 작성 직전에 이 파일을 읽고 그 섹션명과 순서를 그대로 따른다.
- base 브랜치는 지시 없으면 `origin/develop` → `origin/main` 순으로 존재 여부를 확인한다.
- push와 PR 생성 여부는 아래 커밋 / Push 경계를 따른다.

## 커밋 / Push 경계

- `PR 작성해줘`: PR 제목과 본문만 작성한다. 커밋, push, PR 생성은 하지 않는다.
- `PR 올려줘`, `PR 만들어줘`: worktree가 clean이면 미push 브랜치를 push한 뒤 PR을 생성한다. dirty worktree가 있으면 자동 커밋하지 말고 변경 파일과 추천 커밋 분할안을 제시한다.
- `커밋하고 PR 올려줘`, `현재 변경사항 전부 커밋해서 PR 올려줘`: 변경사항을 검토해 의미 단위로 커밋하고, push 후 PR을 생성한다.
- `현재 변경사항 전부`처럼 전체 범위가 명시된 경우에만 전체 stage를 허용한다. 그 외 dirty worktree는 자동으로 모두 stage하지 않는다.
- 이미 커밋된 브랜치만 push하는 것은 `PR 올려줘` 범위에 포함한다.

## 확인 절차

```shell
git branch --show-current
git status --short
git log --oneline -n 10
git diff --stat <base>...HEAD
git diff --name-only <base>...HEAD
Get-Content -Encoding UTF8 .github/PULL_REQUEST_TEMPLATE.md
```

전체 diff는 파일 목록/통계로 부족할 때만 `git diff <base>...HEAD -- <file>`로 좁혀서 본다.
이슈 번호는 브랜치명, 커밋 메시지, 사용자 요청, 관련 GitHub 이슈에서 확인한다.
번호가 보이면 `gh issue view <이슈번호>`로 실제 이슈를 확인하고, 번호가 불명확하면 `gh issue list --state open --limit 30`으로 후보를 찾는다.
그래도 불명확하면 이슈 번호를 추측하지 않고 PR 생성을 계속한다.

## PR 제목

태그 하나 사용: `[feat]` `[fix]` `[refactor]` `[docs]` `[test]` `[chore]`
예: `[feat] 주문 생성 기능 구현`

## PR 본문

`.github/PULL_REQUEST_TEMPLATE.md`를 정본으로 사용한다.
템플릿 파일의 heading을 추가·삭제·변경하지 않고, 각 섹션의 항목만 실제 커밋/변경사항/검증 결과로 채운다.
템플릿 파일이 없거나 읽을 수 없으면 PR을 생성하지 말고 템플릿 누락을 보고한다.
실행한 OS에 맞는 실제 테스트 명령만 체크한다.
(macOS/Linux `./gradlew test`, Windows `.\gradlew.bat test`)

## PR 생성

"pr 올려줘", "PR 만들어줘"처럼 생성을 명확히 요청한 경우에만 실행한다. 여러 줄 명령은 한 줄로 작성한다.
dirty worktree가 있고 커밋까지 명확히 요청하지 않았다면 PR을 생성하지 않는다.

```shell
gh pr create --base <base> --head <current> --title "<title>" --body "<body>"
```

`gh` 미설치/미인증이면 제목·본문만 제공하고 사유를 설명한다.
