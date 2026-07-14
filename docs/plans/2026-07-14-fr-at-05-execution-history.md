# FR-AT-05 실행 이력 + 디버깅 (재실행, 단계별 추적)

> slug: fr-at-05-execution-history
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-14

## Brief

FR-AT-05 실행 이력 + 디버깅 (automation BC). 자동화 룰이 언제·어떤 트리거로·어떤
결과(SUCCESS/PARTIAL/FAILED)로 실행됐는지 이력을 남기고(`rule_executions`), 단계별
추적(trace)과 재실행(replay) API를 제공.

- 선행. §2.1~§2.3 (AT-01 트리거 · AT-02 액션 · AT-03 조건) — 완료.
- D단계. D1 도메인(RuleExecution) → D2 명세(trace context + 재실행) → D3 마이그레이션
  (`rule_executions`) → D4 백엔드(이력 저장 + `POST /api/v1/automation/executions/{id}/replay`)
  → D5 테스트 → D6 UI → D7 E2E.
- 선례. automation BC는 백엔드(D1~D5) 먼저 PR → D6/D7 UI 후속 PR로 분할
  (AT-01 #251→#254, AT-04 #268→#269).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
