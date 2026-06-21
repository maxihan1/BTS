# FR-TT-02 D6/D7 — 워크로그 집계 프론트 UI + E2E

> slug: fr-tt-02-d6-d7-ui-recharts-e2e
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-21

## Brief

사용자 원문: "fr-tt-02 d6, d7 진행하자" + "다른 섹션 병행 작업 중이니 워크트리는 새로 만들어서 진행"

FR-TT-02 — 이슈/사용자/기간별 시간 집계 (agile-planning §5.2, BC=issue-tracking).
백엔드 D1~D5 완료(#167): `GET /api/v1/worklogs/aggregate?project=&by=&granularity=&from=&to=`.
이번 작업 = D6(프론트 UI 표 + recharts 차트) + D7(E2E).

classify: 원판정 type=qa(E2E 키워드 오판) → type=ui / agent=frontend-engineer 교정.

### Maxi 확정 결정 (2종, spec 선행)
1. **차트 = recharts 설치** (product 기획대로). 절대규칙 #17 — 버전 고정 필수.
2. **배치 = 프로젝트별 보고 페이지** `/projects/$projectKey/reports/worklog`.

### 백엔드 응답 계약 (실측, Zod 1:1 미러 대상)
`{ data: { by, granularity?, from?, to?, buckets:[{key,label,timeSpentSeconds,worklogCount}], totalTimeSpentSeconds } }`
- `@JsonInclude(NON_NULL)`: granularity(by≠period 시 키 제거), from/to(미전달 시 키 제거)
- 주의: spec 예시의 `project` 필드는 실제 DTO에 없음 (drift — Zod는 실 DTO 기준)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
