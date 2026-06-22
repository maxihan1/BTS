# FR-EP-02 — Epic 진행률 자동 집계

> slug: fr-ep-02-epic-progress
> type: feature
> agent: backend-engineer
> 생성: 2026-06-23

## Brief

사용자 원문: "fr-ep-02 진행하자"

FR-EP-02 — Epic 진행률 자동 집계 (agile-planning BC §7.2).
- 선행: FR-EP-01 (Epic 이슈 타입 + 자식 연결, PR #175 완료) — `issues.epic_id` 자기참조 FK 기반
- 핵심: 에픽에 연결된 자식 이슈들의 상태(WorkflowState) 비율을 집계해 진행률 자동 계산
- 엔드포인트: GET /api/v1/epics/{key}/progress
- 데이터 모델: 신규 테이블 없이 활용 (epic_id 기반 집계 쿼리)
- 프론트: 진행률 막대 UI

classify 보정: project-workflow/api → agile-planning/feature (수동)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
