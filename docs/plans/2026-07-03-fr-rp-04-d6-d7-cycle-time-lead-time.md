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

## 도메인 정리

- **BC**. issue-tracking(물리 모듈, 백엔드 데이터 소유), 논리 라벨 notification-dashboard 유지(총수 123 불변). 프론트는 BC 경계 없는 뷰 레이어.
- **영향 대상**. 신규 프론트 자산만 — `api/cycle-time.ts`(Zod+fetch), `components/cycle-time/*`(리포트·히스토그램·박스플롯), `i18n/cycle-time-labels.ts`, `mocks/cycle-time-handlers.ts`, `routes/projects.$projectKey.reports.cycle-time.tsx`, E2E. 백엔드/도메인 코드 변경 0.
- **신규 도메인 용어**. 없음. Cycle Time·Lead Time·백분위(nearest-rank)·samples는 #228 백엔드/ADR에서 확정된 유비쿼터스 언어. "히스토그램"·"박스플롯"은 차트 유형(시각화 어휘)이지 도메인 언어 아님 — FR-RP-01/02/03(번다운·벨로시티·CFD)도 차트 유형을 glossary에 추가하지 않은 선례 정합.
- **기존 결정 충돌**. 없음. 본 PR은 ADR `2026-07-03-fr-rp-04-cycle-lead-time`의 다운스트림 프론트 소비자(계약 준수).
- **관련 ADR**. [docs/decisions/2026-07-03-fr-rp-04-cycle-lead-time.md](../decisions/2026-07-03-fr-rp-04-cycle-lead-time.md) (백엔드 PR #228 생성, 본 PR은 참조만). 신규 ADR 불필요.
- **설계 결정(spec으로 인계)**. 박스플롯은 recharts 네이티브 미지원 → 커스텀 SVG(FR-TL-01 Gantt·FR-TL-02 elbow 오버레이 선례). 히스토그램은 recharts BarChart. 초 단위 → 사람 친화 표시(시간/일) 변환 규칙 필요.

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-rp-04-d6-d7-cycle-time-lead-time.md](../specs/2026-07-03-fr-rp-04-d6-d7-cycle-time-lead-time.md)

핵심 요약.
- 라우트 `/projects/$projectKey/reports/cycle-time`(requireAuth), 백로그 nav에 링크. 기간 피커 없음(기본 30일 창, 형제 리포트 동형).
- 페이지 = Cycle Time 섹션(위) → Lead Time 섹션(아래) 세로 스택. 각 섹션 = 요약 타일 + 히스토그램(recharts BarChart) + 박스플롯(커스텀 SVG).
- 박스플롯 표준 Tukey — 상자 p25~p75·중앙선 p50·수염 min~max, p90은 타일 수치.
- 순수 함수 4종(isCycleTimeEmpty·toHistogram·boxPlotScale·formatDuration)이 단위 테스트 핵심. Zod는 백엔드 DTO 1:1(count=0→통계 null).

Maxi 확정(2026-07-03). ① 기간 피커 없음(기본 30일) ② 세로 스택 배치 ③ 표준 Tukey 박스플롯.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건(박스플롯 독립 스케일 v1 단순화 명시·정확값 확인 수단) 반영, Maxi 결정 필요 사항 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
