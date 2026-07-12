# FR-AT-03 조건 분기 (if-else, 표현식)

> slug: fr-at-03-automation-conditions
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-12

## Brief

FR-AT-03 — automation BC 조건 평가 엔진. 자동화 룰이 트리거 발화 후 액션 실행 전에
조건(Condition/Expression)을 평가하여 분기(if-else)한다.

원문 요청: "fr-at-03 진행해줘"

classify 결과:
- type: backend
- agent: backend-engineer
- primary_bc: automation

product doc §2.3 스코프:
- D1. 도메인 — Condition + Expression
- D2. 명세 — 표현식 문법
- D3. 데이터 모델 — automation_conditions(expression)
- D4. 백엔드 — 표현식 평가 엔진 (Spring SpEL 또는 자체)
- D5. 백엔드 테스트 — 표현식 케이스 50개
- D6. 프론트 UI — 조건 빌더 (후속 PR 예상)
- D7. E2E (후속 PR 예상)

선행: FR-AT-01(트리거) 완료, FR-AT-02(액션) 완료.
분할 방침(선례): 백엔드 D1~D5 이번 PR, 프론트 D6/D7 후속 PR. (spec/plan에서 확정)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
