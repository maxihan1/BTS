# 하네스 다이어트 — /bts 7단계를 4단계로

티어: T2
타입: chore

FR 없음 — 하네스(작업 절차) 변경이다. 참고 원형은 `~/Projects/test_platform` 의 `/tpx`(2026-10-09 다이어트, PR #184–186).

## 왜

- 체인이 skill 8개 · 1,407줄이고, sub-agent 6개가 전부 opus 다. 시작부터 merge 까지 사람 정지가 2회(게이트 1·2)다.
- skill 본문을 파싱하는 판별식이 있어 문구 한 줄을 고치려면 테스트 상수도 같이 고쳐야 한다. 하네스가 굳는다.
- 낡은 문구가 남아 있다 — 비워진 `permissions.deny` 를 근거로 드는 문장, 「CI 가 막는다」는 문장(CI 는 Jenkins 로 옮겼다).
- test_platform 은 같은 문제를 「절차를 빼고, 기계가 판정할 수 있는 것만 기계로 막는다」로 풀었다.

## Jira 대조

대응 없음 — 화면 · 제품 기능 변경이 없는 개발 절차(하네스) 변경이라 대조할 Jira 화면이 없다. 기존 BTS 하네스 패턴 준용.

## Maxi 결정 (2026-10-20)

| 항목 | 결정 |
|---|---|
| 체인 | 4단계 하나로 — 시작 → 구현 → 끝 리뷰 → merge. spec · plan · review-plan 서브 skill 없음. T3 만 ADR 1파일 |
| 사람 개입 | T2+ 시작 질문 1회 · 그 뒤 자동 merge |
| 판별식 | **스펙 정합만 남기고 전부 삭제** (같은 날 정정 — 처음엔 문구 테스트만이었다). `scripts/workflow/*.test.ts` · `build-dashboard.test.mjs` · `.husky/pre-push` 삭제. 남는 것은 `verify-master-plan.sh`(FR·스펙 정합)와 doc-index(스펙 색인 · pre-commit) |
| CI | 없음 (Jenkins 철거됨). merge 근거는 `select-test-scope.ts` 가 낸 로컬 테스트 `EXIT=0` |
| sub-agent | `bts-implementer`(sonnet, 보안·마이그레이션은 opus) + `code-reviewer` 2개 |

## 할 일

- [x] `/bts` SKILL.md 를 4단계로 다시 쓴다. 서브 skill 7개 삭제
- [x] agent 6개 → 2개. `classify-task.ts` 의 agent 이름 매핑 갱신
- [x] 판별식 전부 삭제 (스펙 정합만 유지)
- [x] CLAUDE.md 티어 표 · `docs/rules/*` 를 4단계 기준으로. 낡은 deny · CI 문구 수정
- [x] `.claude/MIGRATION.md` · `pilot-log.md` · `trap-to-test-transitions.md` 를 `_archive/` 로
- [ ] `build-doc-index.mjs --check` · `verify-master-plan.sh` 초록

## 결정 노트

- **자동 merge 의 차단자는 로컬 테스트다.** CI 가 없으므로 리뷰 수정 뒤 `select-test-scope.ts` 명령을 다시 돌려 전부 `EXIT=0` 일 때만 merge 한다.
- **판별식이 지키던 것은 지침으로 내려갔다** — 보안 T2 하한 · 마이그레이션 T3 · `test:` 선행 · Jira 대조. 3단계 실측 티어(`detect-tier.ts`)와 끝 리뷰가 대신 본다. 기계 차단은 pre-commit(doc-index · lint)과 `verify-master-plan.sh` 뿐이다(`docs/rules/behavior-rules.md` §4).
- **역할 에이전트 본문은 버리지 않았다.** 체크리스트 · 회귀 방지 교훈을 `docs/rules/code-checklists.md` 로 옮겼다.
- **실측 티어가 선언보다 높으면 멈춘다.** 종전 「게이트 2 에서 사람이 결정」의 대체 — 자동 merge 를 하지 않고 `AskUserQuestion` 한 번.
- **리뷰는 끝에서 한 번.** 강도는 티어로 — T0/T1 `code-reviewer` · T2 `code-reviewer` + `/review` · T3 · auth 는 + `plan-ceo-review`. 고친 뒤 재리뷰 없음.
- **plan 파일은 스펙으로 남기되 짧게.** T2+ 는 `docs/plans/<날짜>-<slug>.md` 에 `티어:` · `타입:` 줄 + 「무엇을 왜」 한 문단 + `## Jira 대조` + 「할 일」 3–7줄(`**검증**:` 하위 줄)만 적는다. spec 9섹션 · task 메타 계약 · 리뷰 결과 절은 없다. T3 는 ADR 을 더한다.
