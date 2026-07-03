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

## Plan

> 경로는 repo 루트(worktree) 기준. 모든 프론트 자산은 `apps/web/`. 백엔드 변경 0.
> TDD: 각 task RED(실패 테스트) → GREEN(최소 구현) → REFACTOR(정리). vitest는 타입 무시 → `tsc --noEmit` 동반(메모리 zod-schema-strengthen).

### Task 1. API 클라이언트 + Zod + isCycleTimeEmpty

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/cycle-time.ts`, `apps/web/src/api/cycle-time.test.ts`]
- depends-on: []

**RED**. `cycle-time.test.ts` — (a) `cycleTimeResponseSchema.parse`가 백엔드 형식(count=0→통계 null 포함) 통과·필드 누락 시 throw, (b) `fetchProjectCycleTime`가 `{data:...}` 언랩·비-2xx→`ApiError(status)`, (c) `isCycleTimeEmpty`가 두 지표 count=0이면 true·하나라도>0이면 false.
**GREEN**. `api/cfd.ts` 미러. `sampleResponseSchema`/`metricResponseSchema`(min~p90 `.nullable()`)/`cycleTimeResponseSchema`. `fetchProjectCycleTime(projectKey)`는 `apiFetch(/api/v1/projects/{projectKey}/cycle-time)`(from/to 미전달)·`dataResponseSchema(...).parse`. `isCycleTimeEmpty(res)` 순수 함수.
**REFACTOR**. 백엔드 DTO(`CycleTimeResponse.kt`) 필드명 grep 대조 주석 + KDoc.
**검증**. `pnpm --filter web test cycle-time` (api) + `pnpm --filter web typecheck`.

### Task 2. i18n 라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/cycle-time-labels.ts`, `apps/web/src/i18n/cycle-time-labels.test.ts`]
- depends-on: []

**RED**. 라벨 존재·콜론 종결 금지(정규식) 회귀 테스트(`cfd-labels.test.ts` 미러).
**GREEN**. `cycleTimeLabels` — page(title "Cycle / Lead Time 분포", description)·metric(cycleTitle "Cycle Time", leadTitle "Lead Time", cycleDesc, leadDesc)·stats(count/min/max/avg/p25/p50/p75/p90 라벨)·status(loading/forbidden/empty/loadFailed)·metricEmpty(cycle 표본 없음 안내)·chart(histogramAriaLabel/boxPlotAriaLabel/xAxisTitle/yAxisTitle)·window(from~to 표기 접두).
**REFACTOR**. 그룹 KDoc.
**검증**. `pnpm --filter web test cycle-time-labels`.

### Task 3. formatDuration 순수 포맷터

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/format-duration.ts`, `apps/web/src/lib/format-duration.test.ts`]
- depends-on: []

**RED**. 경계값 테스트 — 0/59→"N초", 60/3599→"N분", 3600/86399→"N시간 M분"(0분 생략), 86400+→"N일 M시간". 반올림 규칙·음수 없음 가정.
**GREEN**. `formatDuration(seconds: number): string` 순수 함수. 범위 표기용 `formatDurationRange(start, end)`도 함께(히스토그램 라벨).
**REFACTOR**. 임계 상수(SECONDS_PER_MINUTE 등) 추출 + KDoc.
**검증**. `pnpm --filter web test format-duration`.

### Task 4. CycleTimeHistogram + toHistogram(순수)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/cycle-time/CycleTimeHistogram.tsx`, `apps/web/src/components/cycle-time/CycleTimeHistogram.test.tsx`]
- depends-on: [1, 2, 3]

**RED**. `toHistogram(samples, binCount)` 순수 함수 — (a) 등간격 구간·개수 합=표본수, (b) `min===max`(전부 동일)→단일 구간, (c) 빈 배열→빈 결과, (d) 마지막 구간 우측 경계 포함. 라벨은 `formatDurationRange`.
**GREEN**. `toHistogram` export(테스트용, `toCfdSeries` 선례) + `CycleTimeHistogram({ samples })` recharts BarChart(x=구간 라벨, y=개수, Tooltip). `role="img"`+aria-label. 실 렌더는 E2E 위임(jsdom width 0 — 메모리 recharts jsdom).
**REFACTOR**. BIN_COUNT 상수·색상 상수·마진 CFD 톤 통일.
**검증**. `pnpm --filter web test CycleTimeHistogram` + typecheck.

### Task 5. CycleTimeBoxPlot + boxPlotScale(순수)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/cycle-time/CycleTimeBoxPlot.tsx`, `apps/web/src/components/cycle-time/CycleTimeBoxPlot.test.tsx`]
- depends-on: [1, 2, 3]

**RED**. `boxPlotScale({min,max}, width)` 순수 함수 — 값→[0,width] 선형 매핑, `min===max` 0-division 방어(폭 0). 통계값 텍스트 병기 여부 단위 검증(min/p25/p50/p75/max 텍스트 렌더).
**GREEN**. 커스텀 SVG(recharts 미지원) — 수염 min~max 라인, 상자 p25~p75 rect, 중앙선 p50, 값 텍스트 병기. `boxPlotScale` export. `role="img"`+aria-label. count=0(통계 null)이면 렌더 안 함(상위 섹션이 가드).
**REFACTOR**. SVG 좌표 상수·높이 상수 + KDoc(FR-TL-01 커스텀 SVG 선례 인용).
**검증**. `pnpm --filter web test CycleTimeBoxPlot` + typecheck.

### Task 6. CycleTimeMetricSection(요약 타일 + 조립)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/cycle-time/CycleTimeMetricSection.tsx`, `apps/web/src/components/cycle-time/CycleTimeMetricSection.test.tsx`]
- depends-on: [1, 2, 3, 4, 5]

**RED**. (a) count>0이면 요약 타일(count/min/max/avg/p25~p90 formatDuration)+히스토그램+박스플롯 렌더, (b) count=0이면 `metricEmpty` 안내만·차트 미렌더(S2). props로 `metric: MetricResponse` + `title/description/emptyMessage`.
**GREEN**. 타일 그리드 + count 가드 분기 + Histogram/BoxPlot 조립.
**REFACTOR**. 타일 서브컴포넌트 추출·DESIGN.md 토큰(muted-foreground 등) 정합.
**검증**. `pnpm --filter web test CycleTimeMetricSection` + typecheck.

### Task 7. CycleTimeReport(useQuery 상태분기 + 세로 스택)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/cycle-time/CycleTimeReport.tsx`, `apps/web/src/components/cycle-time/CycleTimeReport.test.tsx`]
- depends-on: [1, 2, 6]

**RED**. 상태 분기 4종 — 로딩(role=status)·403(forbidden, 데이터 미노출)·기타 에러(loadFailed)·전체 빈(isCycleTimeEmpty→empty). 성공 시 Cycle 섹션→Lead 섹션 순서 스택 렌더(`CfdReport` 미러).
**GREEN**. useQuery(`['cycle-time', projectKey]`, fetchProjectCycleTime) + 분기. resolveErrorMessage(403→forbidden). 두 `CycleTimeMetricSection`(cycle 위, lead 아래).
**REFACTOR**. 상태 안내 서브컴포넌트 공용화(CfdStatusMessage 톤).
**검증**. `pnpm --filter web test CycleTimeReport` + typecheck.

### Task 8. MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/cycle-time-handlers.ts`, `apps/web/src/mocks/cycle-time-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**. 핸들러가 정상 응답(cycle/lead 표본+통계)·빈 응답(둘 다 count=0)·403 시나리오를 시드 가능하게 반환. Date 비교 불필요(기간 파라미터 미사용). `cfd-handlers` 미러.
**GREEN**. `cycleTimeHandlers` — `GET /api/v1/projects/:projectKey/cycle-time` 정상 fixture. handlers.ts 집계에 등록(공유 파일 — pre-commit race 주의, 자기 파일만 stage).
**REFACTOR**. fixture를 계약(Task1 타입) 대조 주석.
**검증**. `pnpm --filter web test cycle-time-handlers` + typecheck.

### Task 9. 라우트 페이지 + router 등록 + 백로그 nav 링크

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.reports.cycle-time.tsx`, `apps/web/src/routes/projects.$projectKey.reports.cycle-time.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/routes/projects.$projectKey.backlog.tsx`, `apps/web/src/i18n/backlog-labels.ts`]
- depends-on: [7]

**RED**. (a) route adapter가 useParams→`CycleTimeReportPage` props 전달·Page가 헤더(h1+desc)+Report 렌더(라우터 비의존, `reports.cfd.tsx` 미러). (b) backlog nav에 cycleTime 링크 렌더(`backlogLabels.page.cycleTimeLink`).
**GREEN**. Page+adapter export. router.ts에 `projectCycleTimeRoute`(path `/projects/$projectKey/reports/cycle-time`, requireAuth, beforeLoad) 등록+라우트 트리 추가. backlog.tsx nav에 Link 추가(velocity/cfd 동형). backlog-labels에 `cycleTimeLink` 추가.
**REFACTOR**. router 주석 라우트 목록 갱신.
**검증**. `pnpm --filter web test reports.cycle-time backlog` + typecheck + `pnpm --filter web build`.

### Task 10. E2E (D7)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/cycle-time-report.spec.ts`]
- depends-on: [8, 9]

**RED/시나리오**. (1) 백로그→링크 클릭→SPA 이동(reload 금지, 메모리 msw SPA 내부이동), (2) 정상 렌더(요약 타일 수치·히스토그램 SVG·박스플롯 SVG 존재), (3) 전체 빈 상태(둘 다 count=0), (4) 가능 시 Cycle 표본 없음(MSW 시나리오 토글=localStorage+addInitScript). SVG 시각 검증은 존재/텍스트 단언 위주(bbox 폭 0 함정 회피 — 메모리 FR-TL-02).
**GREEN**. spec 작성 + MSW 시나리오 시드.
**검증**. `pnpm --filter web test:e2e cycle-time-report`. UI 변경이 기존 reports E2E 셀렉터를 깨지 않는지 함께 실행(메모리 ui-pr-defer-e2e).

## Plan 메타

- task 수: 10 (각 TDD 사이클, T10 qa-engineer 나머지 frontend-engineer)
- 예상 wave: 약 4 — W1[T1·T2·T3·T8(부분)] → W2[T4·T5] → W3[T6] → W4[T7]→[T9]→[T10]. (bts-impl이 depends-on+files 교집합으로 최종 계산)
- TDD 강제: yes. 순수 함수 4종(isCycleTimeEmpty·toHistogram·boxPlotScale·formatDuration)이 단위 테스트 핵심
- 파일 겹침 주의: T8·T9가 공유 파일(handlers.ts·router.ts·backlog.tsx·backlog-labels.ts) 수정 — pre-commit 자기 파일만 stage(메모리 parallel-dispatch-precommit-race)
- 추가 검증: typecheck(tsconfig.app), vitest, build, playwright(qa)
- 박스플롯=커스텀 SVG(recharts 미지원, FR-TL-01/02 선례). 히스토그램=recharts BarChart

## 리뷰 결과 (← /bts-review-plan 채움)
