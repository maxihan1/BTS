# FR-RP-04 D6/D7 — Cycle Time / Lead Time 분포 리포트 프론트엔드

> slug: fr-rp-04-d6-d7-cycle-time-lead-time
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-03

## Brief

FR-RP-04 D6/D7 프론트엔드. 백엔드 D1~D5는 #228에서 완료(GET /api/v1/projects/{projectKey}/cycle-time).
product 문서(§4.4): "프론트 D6/D7 후속 PR(히스토그램+박스플롯)".

- classify: 최초 backend 오분류 → ui/frontend-engineer 정정(D6/D7=프론트, FR-RP-01/02/03 동형)
- 백엔드 계약: DataResponse{ data: { projectKey, from(YYYY-MM-DD), to, cycleTime: Metric, leadTime: Metric } }
  - Metric: { count, min/max/avg/p25/p50/p75/p90 (초, count=0→전부 null), samples: [{issueKey, seconds}] 오름차순 }
- 미러 템플릿: CFD 프론트 세트(api/cfd.ts · components/cfd/{CfdReport,CfdChart} · i18n/cfd-labels · mocks/cfd-handlers · routes/*.reports.cfd)
- 설계 결정(surface): 박스플롯은 recharts 네이티브 미지원 → 커스텀 SVG(FR-TL-01 Gantt 선례). 히스토그램은 recharts BarChart.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
