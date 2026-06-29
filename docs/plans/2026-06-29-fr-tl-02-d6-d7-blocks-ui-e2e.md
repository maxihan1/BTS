# FR-TL-02 D6/D7 — 타임라인 의존 라인(blocks 오버레이) 프론트엔드 UI + E2E

> slug: fr-tl-02-d6-d7-blocks-ui-e2e
> type: ui
> agent: frontend-engineer
> primary BC: agile-planning (프론트)
> 생성: 2026-06-29

## Brief

FR-TL-02 백엔드 D1~D5(#200)는 머지 완료. 남은 프론트 D6~D7을 구현한다.
FR-TL-01에서 만든 자체 SVG/CSS Gantt 타임라인 위에, `blocks` 관계로 연결된 이슈들을
화살표 라인으로 오버레이하고 클릭 시 강조한다.

**백엔드 계약(머지 완료, #200)**.
- `GET /api/v1/timeline/deps?project=KEY` → `{data:{deps:[{blockerKey,blockedKey}],truncated}}`
- 엣지 = 양끝 이슈가 모두 (동일 프로젝트 + 미삭제 + 타임라인 아이템 + viewer 가시)인 BLOCKS 링크만.
  비가시/cross-project/날짜0 이슈는 백엔드에서 이미 제외 → 프론트는 누출 걱정 없이 그대로 렌더.
- blockerKey=차단측(source), blockedKey=피차단측(target).
- DTO nullable 0 (blockerKey/blockedKey: String, truncated: Boolean) → @JsonInclude(NON_NULL)↔Zod drift 무관.

**기존 plan 참조**: docs/plans/2026-06-28-fr-tl-02-timeline-deps.md §"(후속 PR) 프론트 D6~D7 — 개요" (T6~T11).
**게이트 1 PR 분할 결정**: 백엔드/프론트 2 PR (백엔드 #200 종료, 이 PR이 프론트).

classify: type=ui, agent=frontend-engineer, primary_bc=agile-planning (classifier qa 오판 교정).

## 도메인 정리

- **BC**: agile-planning(프론트 소비 측). 백엔드 엔드포인트도 agile-planning(`TimelineController.getDeps`).
- **영향 엔티티(전부 기존)**: 신규 0.
  - 소비 대상 — `TimelineDepEdge{blockerKey, blockedKey}`(백엔드 DTO, #200) + `TimelineItem`(FR-TL-01 Gantt 막대).
  - 시각화 — 자체 SVG Gantt(FR-TL-01) 위 의존 라인 오버레이.
- **새 용어**: 0. glossary에 "타임라인 아이템(TimelineItem)"·"링크(Link — blocks 포함)" 이미 정의됨.
  "의존성 라인(dependency line)"은 신규 도메인 엔티티가 아니라 기존 BLOCKS 링크의 **시각화 개념**(UI) → glossary 추가 불필요.
- **새 도메인 모델 변경**: 0 (순수 프론트 작업, 백엔드 계약 무변경).
- **기존 결정 충돌**: 없음. ADR 결정(별도 project-scoped BLOCKS 엣지 엔드포인트 + 양끝 가시성 백엔드 필터)을 프론트가 그대로 소비.
  비가시/cross-project/날짜0 이슈는 백엔드에서 이미 제외되므로 프론트는 누출 판정 책임 0.
- **관련 ADR**: [docs/decisions/2026-06-28-timeline-deps-blocks-overlay.md](../decisions/2026-06-28-timeline-deps-blocks-overlay.md) (기존, 프론트 무변경) ·
  [docs/adr/2026-06-26-gantt-rendering-self-svg.md](../adr/2026-06-26-gantt-rendering-self-svg.md) (자체 SVG 렌더, FR-TL-01).
- **grill-with-docs 스킵 사유**: 신규 유비쿼터스 용어 0 + 완료된 FR-TL-01/FR-TL-02 백엔드 패턴의 파생 프론트 작업.
  대화형 도메인 검증보다 직접 정리가 적합(learnings `bts-spec-office-hours-mismatch` 정신).

## 스펙

전체 스펙. [docs/specs/2026-06-29-fr-tl-02-d6-d7-blocks-ui-e2e.md](../specs/2026-06-29-fr-tl-02-d6-d7-blocks-ui-e2e.md)

핵심 시나리오 요약.
- 자체 div Gantt(FR-TL-01) 위 **SVG 오버레이**로 blocks 의존 라인(blocker 막대 우→blocked 막대 좌 화살표) 렌더.
- 라인 클릭 강조 + 재클릭/빈영역 클릭 해제(S2/S3).
- 접힌 에픽 그룹·미존재 막대로의 라인은 미렌더(S4/EC1) — `flattenVisibleRows`로 현재 보이는 행만 대상.
- deps truncated 누락 경고(S6), deps 실패는 간트 안돌릴 best-effort(EC6).

핵심 설계.
- **세로 좌표 = GanttChart 행 배치와 동일 출처**. `flattenVisibleRows(groups, collapsedGroups)`를 추출하고 GanttChart가 실제로 사용 → drift 차단.
- 좌표 순수함수 `computeDependencyLines`(jsdom 안전, getBBox 미사용).
- 백엔드 계약(#200) 무변경, 기존 timeline 무회귀.

## Brainstorming Check

✅ 통과 (자체 sanity, office-hours 부적합 learning `bts-spec-office-hours-mismatch` 적용).
최대 리스크 = 세로 좌표가 GanttChart 행 배치와 어긋남(접기/미분류 헤더) → `flattenVisibleRows` 단일 출처화 + positive/negative control 단위 테스트(vacuous 차단).

## Plan

> 의존 최소화로 wave 살림. lib 순수함수(T2)는 도메인 무관 자체 인터페이스(`DependencyEdge{blockerKey,blockedKey}`)로 받아 api(T1)와 독립.
> deps 관련 i18n 라벨은 T4에 모아 T5는 읽기만(파일 겹침 회피).

### Task 1. api deps 스키마/함수 + 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/timeline.ts`, `apps/web/src/api/timeline.test.ts`, `apps/web/src/hooks/use-timeline.ts`, `apps/web/src/hooks/use-timeline.test.tsx`]
- depends-on: []

**RED**.
- `api/timeline.test.ts`에 추가.
  - `timelineDepsResponseSchema`가 `{deps:[{blockerKey,blockedKey}],truncated}` 파싱 성공 + 누락 필드 reject.
  - `fetchTimelineDeps('BTS')`가 `GET /api/v1/timeline/deps?project=BTS` 호출 후 `{ data }` 언랩 반환(MSW 정상/truncated).
- `hooks/use-timeline.test.tsx`에 추가.
  - `timelineKeys.deps('BTS')` === `['timeline', 'BTS', 'deps']`.
  - `useTimelineDeps('BTS')`가 deps 배열 반환, 빈 키면 `enabled:false`.
- 실패: `timelineDepsResponseSchema`/`fetchTimelineDeps`/`useTimelineDeps`/`timelineKeys.deps` 미존재.

**GREEN**.
- `api/timeline.ts` — `timelineDepEdgeSchema = z.object({blockerKey:z.string(), blockedKey:z.string()})`, `timelineDepsResponseSchema = z.object({deps:z.array(...), truncated:z.boolean()})`, 타입 export, `fetchTimelineDeps(projectKey)` (기존 `apiGet`+`dataResponseSchema` 재사용).
- `hooks/use-timeline.ts` — `timelineKeys.deps`, `useTimelineDeps(projectKey)` (staleTime 30s, enabled 길이>0).

**REFACTOR**. KDoc — 백엔드 #200 계약 참조, blockerKey=source/blockedKey=target 명시.

**검증**: `cd apps/web && pnpm test src/api/timeline.test.ts src/hooks/use-timeline.test.tsx`

### Task 2. lib — flattenVisibleRows + computeDependencyLines 순수함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/timeline-layout.ts`, `apps/web/src/lib/timeline-layout.test.ts`]
- depends-on: []

**RED** (vacuous 차단 = positive control + negative 공존, FR-MV-01 EC8/EC9 교훈).
- `flattenVisibleRows(groups, collapsedGroups)` 테스트.
  - 펼친 에픽 그룹: epic 행 + 자식 행 모두 `{key, rowIndex}` 포함, rowIndex 0..n 연속.
  - 접은 그룹: 자식 행 제외(epic 행만), 이후 rowIndex 연속 유지.
  - 미분류 그룹: 헤더 행이 rowIndex 1칸 점유(key 없음 → 결과 제외) + 아이템 행 포함.
  - **GanttChart 행 배치와 동일 순서** 단언(에픽 그룹 입력순, 미분류 맨 끝).
- `computeDependencyLines(visibleRows, range, dayWidth, deps)` 테스트.
  - **positive**: (A blocks B) 두 행 모두 보임 → 엣지 1개 반환, `x1=barX_A+barWidth_A`, `x2=barX_B`, `y1/y2`= 각 행 중심.
  - **negative(같은 입력 공존)**: (A blocks C) C가 visibleRows에 없음(접힘/미존재) → 그 엣지 제외(EC1/S4).
  - self-block(A blocks A) → 제외(EC4).
  - 상호 blocks(A↔B) → 두 엣지 반환(EC3).
- 실패: `flattenVisibleRows`/`computeDependencyLines` 미존재.

**GREEN**.
- `DependencyEdge { blockerKey: string; blockedKey: string }` 인터페이스(자체 정의, api 무의존).
- `VisibleRow { key: string; rowIndex: number }`, `DependencyLine { blockerKey; blockedKey; x1; y1; x2; y2 }`.
- `flattenVisibleRows(groups: TimelineGroup[], collapsed: ReadonlySet<string>): VisibleRow[]` — GanttChart 순회 로직과 동일(에픽 행→자식, 미분류 헤더 1칸→아이템). 헤더 행은 rowIndex만 차지, 결과 미포함.
- `computeDependencyLines(rows, range, dayWidth, deps)` — rows로 key→{rowIndex, barGeometry} 맵 구성. 각 deps 엣지에서 양끝 모두 맵에 있고 key≠ 일 때만 좌표 산출. y중심 = `rowIndex*ROW_HEIGHT + ROW_HEIGHT/2`(축 오프셋은 컴포넌트가 더함). x = barGeometry 기반.
- 상수 재사용(`ROW_HEIGHT_PX`는 TimelineRow에서 import 또는 인자화 — 순수성 위해 인자/상수 결정은 구현 시).

**REFACTOR**. KDoc — 좌표는 우측 막대영역 로컬(축 오프셋 제외), jsdom 안전(getBBox 미사용).

**검증**: `cd apps/web && pnpm test src/lib/timeline-layout.test.ts`

### Task 3. MSW — /timeline/deps 핸들러 + 픽스처

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/timeline-handlers.ts`, `apps/web/src/mocks/timeline-fixtures.ts`, `apps/web/src/mocks/timeline-handlers.test.ts`]
- depends-on: [1]

**RED**.
- `timeline-handlers.test.ts`에 추가.
  - `GET /api/v1/timeline/deps?project=BTS` → `{data:{deps:[...],truncated:false}}` (BTS 픽스처와 정합: BTS 타임라인 막대 키 쌍).
  - `project=TRUNCATED` 또는 localStorage `deps-truncated` → `truncated:true`.
  - `project=EMPTY`/알 수 없음 → `deps:[]`.
- 실패: deps 핸들러 미등록(404/passthrough).

**GREEN**.
- `timeline-fixtures.ts` — `BTS_TIMELINE_DEPS: TimelineDepEdge[]` (예: (BTS-2 blocks BTS-3), (BTS-1 blocks BTS-4)). 기존 BTS_TIMELINE_ITEMS 키와 정합.
- `timeline-handlers.ts` — `getTimelineDepsHandler = http.get('/api/v1/timeline/deps', ...)` 정적 반환 + localStorage 시나리오 토글(기존 패턴), `timelineHandlers` 배열에 추가. unit override 핸들러(`timelineDepsTruncatedHandler` 등) export.

**REFACTOR**. KDoc — 기존 정적 반환/자동시드 패턴 일관(`msw-derived-behavior-shared-store-e2e`).

**검증**: `cd apps/web && pnpm test src/mocks/timeline-handlers.test.ts`

### Task 4. DependencyOverlay.tsx (신규 SVG 레이어 + 클릭 강조) + deps i18n

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/DependencyOverlay.tsx`, `apps/web/src/components/timeline/DependencyOverlay.test.tsx`, `apps/web/src/i18n/timeline-labels.ts`, `apps/web/src/i18n/timeline-labels.test.ts`]
- depends-on: [2]

**RED**.
- `DependencyOverlay.test.tsx`.
  - `lines`(computeDependencyLines 결과) N개 → SVG `<path>`/`<line>` N개 렌더.
  - 라인 클릭 → 해당 엣지 강조(선택 class/속성) + 나머지 흐림. 재클릭/배경 클릭 → 해제(S2/S3).
  - 각 라인 `aria-label`(예 "BTS-2가 BTS-3을 차단") 존재(NFR3).
  - lines 0개 → 라인 0개(빈 SVG, S5).
- `timeline-labels.test.ts` — deps 라벨 콜론 미종결 + 함수 라벨 동작.
- 실패: `DependencyOverlay` 미존재.

**GREEN**.
- `DependencyOverlay({ lines, axisOffset, width, height })` — absolute SVG 레이어. `<defs><marker>` 화살촉 + 각 line `<path>`(또는 line+화살촉). y에 `axisOffset` 더함. 선택 state(`selectedKey = blocker+blocked`) — 클릭 토글, 배경 rect 클릭 시 해제. 선택 시 강조/비선택 흐림 class.
- `timeline-labels.ts` — `deps.lineAriaLabel(blocker, blocked)`, `deps.truncatedMessage`(라인 누락 경고, 콜론 미종결).

**REFACTOR**. KDoc — pointer-events 처리(빈영역 클릭 해제), 좌표는 부모가 주입.

**검증**: `cd apps/web && pnpm test src/components/timeline/DependencyOverlay.test.tsx src/i18n/timeline-labels.test.ts`

### Task 5. GanttChart + TimelinePage 통합 (오버레이 결선 + deps truncated 배너)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/GanttChart.tsx`, `apps/web/src/components/timeline/GanttChart.test.tsx`, `apps/web/src/routes/projects.$projectKey.timeline.tsx`, `apps/web/src/routes/projects.$projectKey.timeline.test.tsx`]
- depends-on: [1, 2, 3, 4]

**RED**.
- `GanttChart.test.tsx`.
  - `deps` prop 주입 시 우측 영역에 `DependencyOverlay` 렌더(라인 존재 단언, positive control).
  - 그룹 접기 → 접힌 자식으로의 라인 제외(flattenVisibleRows 단일 출처 검증, negative 공존).
  - deps 미주입/빈 배열 → 라인 0(무회귀).
- `projects.$projectKey.timeline.test.tsx`.
  - TimelinePage가 `useTimelineDeps`로 deps 가져와 GanttChart에 주입.
  - deps `truncated:true` → deps 누락 경고 배너(기존 timeline truncated 배너와 구분).
  - 403/빈 타임라인 → deps 오버레이/배너 미표시(S7), 간트 무회귀.
- 실패: GanttChart에 deps prop/overlay 미연결.

**GREEN**.
- `GanttChart.tsx` — `assembleEpicGroups` 결과 + `collapsedGroups`로 `flattenVisibleRows` 호출 → `computeDependencyLines(rows, range, DAY_WIDTH_PX, deps)` → `DependencyOverlay` 우측 영역에 렌더(axisOffset=AXIS_HEIGHT_PX). `deps?: DependencyEdge[]` prop 추가(기본 빈 → 무회귀).
- `timeline.tsx` — `useTimelineDeps(projectKey)` 호출, deps를 GanttChart에 주입, deps.truncated 시 누락 경고 배너(best-effort: deps 에러는 간트 안 깸, EC6).

**REFACTOR**. KDoc — flattenVisibleRows가 행 배치 단일 출처임을 명시.

**검증**: `cd apps/web && pnpm test src/components/timeline/GanttChart.test.tsx "src/routes/projects.\$projectKey.timeline.test.tsx" && pnpm typecheck`

### Task 6. E2E — 의존 라인 실렌더 + 클릭 강조 + 무회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/timeline.spec.ts`]
- depends-on: [3, 5]

**RED→GREEN**.
- 기존 `e2e/timeline.spec.ts`에 추가.
  - BTS 타임라인 진입 → 의존 라인(SVG path/line) 실렌더 확인(≥1).
  - 라인 클릭 → 강조 상태 토글(class/속성), 배경 클릭 → 해제(S2/S3).
  - deps truncated 시나리오(localStorage 플래그) → 누락 경고 노출(S6).
  - 기존 timeline 시나리오(정상/403/empty/truncated) 무회귀.
- 실렌더 검증(좌표 픽셀 단언 대신 라인 개수/강조 토글), `e2e-msw-scenario-toggle-localstorage-flag` 패턴.

**검증**: `cd apps/web && pnpm test:e2e timeline.spec.ts`

## Plan 메타

- task 수: **6** (T1~T6). E2E(T6)만 qa-engineer, 나머지 frontend-engineer.
- wave (depends-on + files): Wave1=[T1,T2], Wave2=[T3(1),T4(2)], Wave3=[T5(1,2,3,4)], Wave4=[T6(3,5)] — 4 wave.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저).
- 마이그레이션: 0 (순수 프론트).
- 백엔드 변경: 0 (#200 계약 소비만).
- 리뷰 포커스(codereview): (1) flattenVisibleRows가 GanttChart 행배치와 동일 출처인지(좌표 drift), (2) vacuous 차단(접힘/미존재 negative + positive control 공존), (3) deps best-effort(간트 무중단), (4) 기존 timeline 무회귀, (5) i18n 콜론 미종결.
- 추가 검증: lint, typecheck(tsconfig.app.json), vitest, playwright.

## 리뷰 결과

### 집중 plan 리뷰 (eng + design + devex, 2026-06-29)

> type=ui이나 신규 디자인 결정이 거의 없는 기존 Gantt 확장(mockup 불요)이라, 대화형 plan-design-review 대신
> 집중 독립 리뷰로 진행(learning `bts-review-plan-autoplan-overkill` 정신). 게이트 1에서 Maxi가 원하면 대화형 추가 가능.

**eng 관점**.
- ✅ 좌표 단일출처. `flattenVisibleRows`를 GanttChart 행배치와 동일 출처로 추출 → drift 차단. T5 RED에 접기 negative 포함.
- ✅ vacuous 차단. T2가 positive control + negative(접힘/미존재)를 같은 입력에 공존(FR-MV-01 EC8/EC9 교훈).
- ✅ wave 정확. lib 자체 인터페이스로 T1/T2 독립(Wave1 병렬). i18n을 T4에 모아 T5와 파일 겹침 회피.
- ⚠️ **CONCERN-1 (impl 필수 준수) — lib→component 역의존 차단**. `ROW_HEIGHT_PX`는 `TimelineRow.tsx`(컴포넌트)에 정의됨. `timeline-layout.ts`(lib)이 이를 import하면 lib→component 역의존(아키텍처 위배). **`computeDependencyLines(rows, range, dayWidth, rowHeight, deps)`로 `rowHeight`를 인자로 받고** 호출자(GanttChart)가 `ROW_HEIGHT_PX`를 전달한다. T2 GREEN의 "인자/상수 결정"을 **인자화로 확정**. bts-impl 인계.
- ⚠️ **CONCERN-2 (마이너) — SVG 좌표계 계약**. 우측 영역은 `overflow-x-auto` + `flex-1`. `DependencyOverlay`의 width/height는 전체 타임라인 폭(range·dayWidth)+행 높이 합으로 부모(GanttChart)가 계산해 주입하고, 막대 좌표계(barX 원점)와 정확히 일치해야 라인이 막대에 붙는다. T4/T5에서 width/height/axisOffset 주입 계약을 명확히.
- ✅ best-effort. deps 실패가 간트를 깨지 않음(EC6, T5 GREEN 명시).

**design 관점**.
- ✅ 토큰 우선(DESIGN.md §4). 기본 라인 = `muted-foreground`, 강조 = `primary`(또는 `accent`), 비선택 흐림 = opacity. AQL(다중 타입)과 달리 단일 관계(blocks)라 신규 유채색 토큰 도입 불필요.
- ✅ 강조 명확성. 클릭 강조는 stroke 굵기 + 색 대비 병행(색 단독 의존 회피).
- ℹ️ deps truncated 경고는 기존 timeline amber 배너 스타일 재사용 + **문구로 구분**(S6). 의존 라인 SVG path는 lucide 아이콘이 아닌 기능적 경로라 DESIGN.md §일러스트 가이드 예외 정당.

**devex 관점**.
- ✅ 계약 일관성. `/timeline/deps` 별도 엔드포인트(ADR) `{data:{...}}` 봉투 동형. api/hook 관례(`apiGet`+`dataResponseSchema`+`timelineKeys`) 기존 timeline과 동일.
- ✅ DTO nullable 0 → Zod drift 무관.
- ✅ 무회귀. `deps` prop 기본 빈 배열 → GanttChart 기존 사용처 무영향. 기존 timeline E2E도 T6에 포함.

**BLOCKER: 없음.** CONCERN 2건(C1 역의존=impl 필수 준수, C2 좌표계 계약=마이너) → bts-impl 인계.
