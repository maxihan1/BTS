# FR-AT-04 규칙 충돌 정적 분석

> slug: fr-at-04-automation-lint
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-13

## Brief

FR-AT-04 규칙 충돌 정적 분석 — automation 규칙 저장 전 사이클/우선순위 모호성/필드 충돌 검출 lint.

automation 규칙 여러 개가 서로 충돌하는지를 저장(생성/수정) 전에 정적으로 검사한다.
- 사이클: 규칙 A의 액션이 규칙 B의 트리거를 유발하고 B가 다시 A를 유발하는 정적 루프 (런타임 가드는 FR-AT-02, 정적 검출은 여기)
- 우선순위 모호성: 같은 트리거에 복수 규칙 매칭 + 실행 순서 미결정
- 필드 충돌: 두 규칙이 같은 필드를 다른 값으로 SET

product 문서: docs/plan/product/automation.md §2.4
D1 도메인(RuleConflict) · D2 명세 · D3 데이터(활용) · D4 백엔드(저장 전 lint) · D5 테스트 · D6 UI(경고 모달) · D7 E2E

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
