# FR-AT-04 D6/D7 — 규칙 충돌 경고 모달 UI + E2E

> slug: fr-at-04-d6-d7-conflict-warning-ui
> type: ui
> agent: frontend-engineer (D6 구현) + qa-engineer (D7 E2E)
> primary_bc: automation
> 생성: 2026-07-14

## Brief

FR-AT-04 D6/D7 마무리. automation 규칙 저장 응답의 `conflicts`(충돌 4종 soft WARNING)를 사용자에게 경고 모달로 표시하는 프론트 UI(D6) + 그 흐름을 검증하는 E2E 시나리오(D7).

백엔드 D1~D5는 PR #268로 이미 머지 완료 (`fbb52941d`). 규칙 저장(create/patch) 성공 응답에 `conflicts` 배열이 실려 오며(`@JsonInclude(NON_NULL)`), 충돌 없으면 필드 자체가 빠짐. 저장은 어떤 충돌에도 차단되지 않음 — 모달은 순수 정보성.

- classify: type=ui, agent=frontend-engineer (원래 qa 오판을 Maxi 확인 후 정정), primary_bc=automation
- 워크플로우 깊이: **경량 진행** (Maxi 확정) — domain 간략 + spec 간단(백엔드 계약 기반) + plan + eng/design 집중리뷰 + impl + codereview + gate2

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
