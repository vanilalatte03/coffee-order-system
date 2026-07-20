# Review fixture health

이 디렉터리는 `review-code`용 공개 fixture가 고정 Git snapshot에서 재현되는지만
결정적으로 검사한다. 모델을 호출하거나 응답을 평가하지 않으며 score, pass-rate, skill lift,
A/B 비교를 만들지 않는다.

`health`가 보장하는 범위는 다음과 같다.

- manifest와 case 필수 schema, case ID 유일성, 40자리 `base_revision` commit의 존재와
  `origin/develop` 또는 `origin/main` 도달 가능성
- `review_scope` 파일과 `contract_refs` 파일·문구가 모두 같은 pinned base snapshot에 존재함
- `candidate_diff` 경로가 `review_scope`와 정확히 같고 저장소 밖 경로를 사용하지 않음
- `candidate_diff`가 현재 working tree가 아니라 pinned base snapshot에 적용 가능함
- 공개 fixture에 정답, rubric, finding ID, 응답 provenance 필드가 없음

이 검사는 candidate 변경의 옳고 그름, 리뷰 품질, 모델 성능을 보장하지 않는다. 실제 모델
evaluator와 비공개 정답/rubric은 이 v0 하네스의 범위 밖이다.

```text
python -m unittest discover -s .agents/evals/tests -p "test_*.py"
python .agents/evals/run.py health
python .agents/evals/run.py list
python .agents/evals/run.py show review-code-order-transaction-boundary
```
