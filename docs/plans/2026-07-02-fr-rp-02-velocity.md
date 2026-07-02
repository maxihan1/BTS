# FR-RP-02 벨로시티 차트 (Velocity Chart)

> slug: fr-rp-02-velocity
> type: feature (classify 원출력 qa→오분류 정정, E2E/recharts 키워드 반응)
> agent: backend-engineer (프론트 D6/D7은 plan 메타 agent로 frontend-engineer 지정)
> primary_bc: notification-dashboard (엔드포인트는 agile-planning co-located 가능, FR-RP-01 선례)
> 생성: 2026-07-02

## Brief

여러 과거 스프린트에 걸쳐 "완료한 작업량"을 막대 차트로 보여주는 벨로시티 리포트.
팀이 스프린트마다 얼마나 처리하는지 추세를 파악. 선행 FR-RP-01(번다운) 완료.

- 백엔드: `GET /api/v1/projects/{id}/velocity` (product 문서 §4.2)
- 프론트: recharts 바 차트 (WorklogAggregateChart/BurndownChart 선례 재사용)
- E2E: Playwright

**핵심 결정 포인트 (domain/spec에서 확정)**.
- 벨로시티 지표 = 스토리포인트 vs 완료? FR-RP-01에서 "스토리포인트 미구현 → 추정 시간(초)"으로 확정한 이력.
  벨로시티도 동일하게 추정 시간(초) 기반으로 갈지, 이슈 수 기반으로 갈지 결정 필요.
- "완료" 판정 = 워크플로우 상태 카테고리 DONE 기준 (FR-EP-02 선례: WorkflowStateCatalog.listStates → category).
- 대상 스프린트 = 완료(CLOSED)된 최근 N개 스프린트.
- PR 분할 = FR-RP-01 선례(백엔드 D1~D5 / 프론트 D6/D7 2 PR) 따를지 단일 PR로 갈지.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
