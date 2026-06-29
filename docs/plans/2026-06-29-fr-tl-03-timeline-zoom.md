# FR-TL-03 — 타임라인 줌 (주/월/분기)

> slug: fr-tl-03-timeline-zoom
> type: ui
> agent: frontend-engineer
> BC: agile-planning
> Plan slug(product): agile/timeline-zoom
> 생성: 2026-06-29

## Brief

FR-TL-03 진행. FR-TL-01에서 만든 자체 SVG/CSS Gantt 타임라인에 줌 레벨(주/월/분기)을 추가한다.
당시 "시간축 = 고정 일 단위 폭 + 가로 스크롤(줌은 FR-TL-03 범위 외)"로 미뤄둔 기능.

- 성격: 프론트엔드 전용 (D4/D5 백엔드 "해당 없음")
- D6: 줌 컨트롤 + 키보드 단축키 (designer → frontend-engineer)
- D7: E2E (qa-engineer)
- SDD §13.3.3: "주 / 월 / 분기 단위" (한 줄 명세 — 셀 크기/단축키/상태 보존은 spec에서 확정)

classify 정정: classify-task가 backend로 오판 → 문서 명세 근거로 ui/frontend-engineer 정정.

## 도메인 정리

- **BC**: agile-planning (타임라인은 agile-planning 소유, FR-TL 시리즈)
- **도메인 모델 영향**: 0 — 새 엔티티 0, 백엔드 0, DB 마이그레이션 0. 순수 프론트 뷰 레이어(시간축 스케일 전환).
- **새 용어**: "줌 레벨"(주/월/분기). 단, 도메인 유비쿼터스 언어가 아니라 **뷰 인터랙션 개념** → glossary 추가 불필요(타임라인 아이템은 이미 등록됨). spec에서 셀 폭·축 단위 정의.
- **기존 결정 충돌**: 없음. ADR `2026-06-26-gantt-rendering-self-svg.md` 결과 §가 "향후 FR-TL-03(줌)도 같은 자체 SVG/CSS 기반에서 확장"을 명시적으로 예고 → **연장 관계**(충돌 아님).
- **핵심 기술 컨텍스트**: FR-TL-01이 줌 인프라를 선반영함.
  - `lib/timeline-layout.ts`: `computeBarGeometry(item, range, dayWidth)` / `computeDependencyLines(rows, range, dayWidth, rowHeight, deps)` 모두 `dayWidth`를 인자로 받음(`DAY_WIDTH_PX = 20`은 기본값일 뿐).
  - 줌 = `dayWidth` 프리셋 전환 + `TimelineAxis` 눈금 단위(주/월/분기) 전환. FR-TL-02 의존성 라인도 같은 dayWidth로 자동 재계산(좌표 단일 출처) → 줌 시 라인 정합 자동 보장.
- **관련 ADR**: 신규 ADR 후보 = "줌 레벨 ↔ dayWidth/축 단위 매핑"(spec에서 프리셋 값 확정 후 작성 여부 결정).
- **grill-with-docs**: 스킵(도메인 영향 0인 순수 뷰 작업, 대화형 도메인 검증 과함).

## 스펙

전체 스펙. [docs/specs/2026-06-29-fr-tl-03-timeline-zoom.md](../specs/2026-06-29-fr-tl-03-timeline-zoom.md)

핵심 요약.
- 줌 = `GanttChart`의 `DAY_WIDTH_PX=20` 하드코딩을 `zoomLevel('week'|'month'|'quarter') → dayWidth/축단위` 매핑으로 교체. 데이터 재요청 0.
- 줌별 프리셋: 주(~28px, 월/일 축) · 월(20px 현행, 월/주 축) · 분기(~6px, 분기/월 축). 기본=월(무회귀).
- 컨트롤(Maxi 확정): 세그먼트(주|월|분기) + −/+ 버튼 / 상태=localStorage `timeline-zoom`(전역, 잘못된 값→월 폴백) / 단축키 1·2·3 직접.
- 순수 함수 분리: `lib/timeline-zoom.ts`(매핑·zoom in/out·parse·축단위) 단위 테스트, 시각은 E2E(ADR D2 정신).
- 의존성 라인(FR-TL-02)은 새 dayWidth로 자동 재계산(좌표 단일 출처). 접힘 상태·기존 E2E 무회귀.

## Brainstorming Check

✅ 통과 (1회). 자체 sanity check로 gap 3건(스크롤 보정 범위 외·i18n timeline-labels·재마운트 key 불변/리스너 cleanup) 발견 후 스펙 보강.

## Plan

> 모든 경로는 repo 루트 기준. agent 기본값 = `frontend-engineer`(T8만 `qa-engineer`).
> 모든 검증은 worktree에서 실행: `cd apps/web` 후 `pnpm ...`.

### Task 1. `lib/timeline-zoom.ts` — 줌 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/timeline-zoom.ts`, `apps/web/src/lib/timeline-zoom.test.ts`]
- depends-on: []

**RED**. `timeline-zoom.test.ts`:
- `ZOOM_LEVELS` 순서 = `['week','month','quarter']`, `DEFAULT_ZOOM === 'month'`.
- `ZOOM_PRESETS[level].dayWidth` — week > month(=20) > quarter, quarter ≥ 4(MIN_BAR_DAYS 가시성).
- `nextZoomIn('month')==='week'`, `nextZoomIn('week')==='week'`(끝단 클램프), `nextZoomOut('month')==='quarter'`, `nextZoomOut('quarter')==='quarter'`.
- `parseZoomLevel('quarter')==='quarter'`, `parseZoomLevel('garbage')==='month'`, `parseZoomLevel(null)==='month'`(S6/EC4).
- `getAxisConfig('week')` → `{ top:'month', bottom:'day' }`, `'month'`→`{top:'month',bottom:'week'}`, `'quarter'`→`{top:'quarter',bottom:'month'}`.
- 실패 예상: `timeline-zoom` 모듈 없음.

**GREEN**. `timeline-zoom.ts`:
- `export type ZoomLevel = 'week'|'month'|'quarter'`, `ZOOM_LEVELS`(const 배열, 확대→축소 순), `DEFAULT_ZOOM`.
- `ZOOM_PRESETS: Record<ZoomLevel,{dayWidth:number}>`(week 28 / month 20 / quarter 6 — 후보, 구현서 튜닝).
- `nextZoomIn/nextZoomOut`(ZOOM_LEVELS 인덱스 ±1 클램프). `parseZoomLevel(raw: string|null)`(화이트리스트).
- `getAxisConfig(level): { top:'month'|'quarter', bottom:'day'|'week'|'month' }`.

**REFACTOR**. L1 한국어 헤더 주석, KDoc(각 export), 매핑 상수화.

**검증**: `cd apps/web && pnpm test src/lib/timeline-zoom.test.ts`

---

### Task 2. `i18n/timeline-labels.ts` — 줌 레이블 추가

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/timeline-labels.ts`, `apps/web/src/i18n/timeline-labels.test.ts`]
- depends-on: []

**RED**. `timeline-labels.test.ts`:
- `timelineLabels.zoom.week==='주'`, `.month==='월'`, `.quarter==='분기'`.
- `.zoom.groupAriaLabel`, `.zoom.zoomInAriaLabel`(='확대'), `.zoom.zoomOutAriaLabel`(='축소') 존재.
- 기존 콜론 종결 금지 테스트가 zoom 섹션 값도 커버(전 값 순회) — 콜론 종결 0.
- 실패 예상: `timelineLabels.zoom` undefined.

**GREEN**. `timeline-labels.ts`에 `zoom: { week, month, quarter, groupAriaLabel, zoomInAriaLabel, zoomOutAriaLabel }` 추가.

**REFACTOR**. KDoc 그룹 주석(`zoom`) 추가, 콜론 종결 점검.

**검증**: `cd apps/web && pnpm test src/i18n/timeline-labels.test.ts`

---

### Task 3. `TimelineAxis` — 줌별 눈금 단위 분기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/TimelineAxis.tsx`, `apps/web/src/components/timeline/TimelineAxis.test.tsx`]
- depends-on: [1]

**RED**. `TimelineAxis.test.tsx`(신규):
- `zoomLevel='month'`: 월(YYYY.MM)·주(MM/DD) 눈금 렌더(현행 동작 보존).
- `zoomLevel='week'`: 상단 월·하단 일(DD) 눈금. 일 눈금 개수 = 기간 일수.
- `zoomLevel='quarter'`: 상단 분기(`YYYY Q{n}`)·하단 월(MM) 눈금. 주/일 눈금 미렌더.
- 모든 눈금 offsetDay·label UTC 기준.
- 실패 예상: `TimelineAxis`가 `zoomLevel` prop 미수용.

**GREEN**.
- `TimelineAxisProps`에 `zoomLevel: ZoomLevel` 추가. `getAxisConfig(zoomLevel)`로 (top/bottom) 단위 선택.
- 눈금 계산 헬퍼: 기존 `computeMonthTicks`/`computeWeekTicks` + 신규 `computeDayTicks`(매일 DD)·`computeQuarterTicks`(분기 시작월 1일, `YYYY Q{n}`, n=⌊month/3⌋+1).
- top/bottom 단위에 맞는 헬퍼 선택해 2행 렌더(기존 구조 유지).

**REFACTOR**. 눈금 헬퍼를 `tickComputers: Record<unit, fn>` 맵으로 정리, KDoc.

**검증**: `cd apps/web && pnpm test src/components/timeline/TimelineAxis.test.tsx`

---

### Task 4. `TimelineZoomControl` — 세그먼트 + −/+ 컨트롤

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/TimelineZoomControl.tsx`, `apps/web/src/components/timeline/TimelineZoomControl.test.tsx`]
- depends-on: [1, 2]

**RED**. `TimelineZoomControl.test.tsx`:
- 세그먼트 3버튼(주/월/분기), 현재 레벨 버튼 `aria-pressed=true` 나머지 false.
- 세그먼트 클릭 → `onZoomChange(level)` 호출.
- "+"(확대) 클릭 → `onZoomChange(nextZoomIn(current))`, "−"(축소) → `nextZoomOut`.
- current='week' → "+" `disabled`, current='quarter' → "−" `disabled`(S3).
- 그룹 `role="group"` + aria-label, −/+ aria-label(확대/축소).
- 실패 예상: 컴포넌트 없음.

**GREEN**.
- props `{ zoomLevel: ZoomLevel; onZoomChange: (l: ZoomLevel)=>void }`.
- `ui/button.tsx` 사용(`data-slot=button-group` 그룹 스타일), 세그먼트는 `variant`/`aria-pressed`로 활성 표현.
- 레이블은 `timelineLabels.zoom.*`(인라인 한국어 금지).

**REFACTOR**. 세그먼트 버튼 `.map(ZOOM_LEVELS)`로 정리, L1 헤더 주석.

**디자인 보강(D2/D3)**. 세그먼트 그룹과 −/+ 버튼 사이 시각적 간격(`gap`/구분). 세그먼트 버튼에
`title`(또는 `aria-keyshortcuts="1"/"2"/"3"`)로 단축키 discoverability 제공.

**검증**: `cd apps/web && pnpm test src/components/timeline/TimelineZoomControl.test.tsx`

---

### Task 5. `use-timeline-zoom` — localStorage 영속 + 1/2/3 단축키 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-timeline-zoom.ts`, `apps/web/src/hooks/use-timeline-zoom.test.tsx`]
- depends-on: [1]

**RED**. `use-timeline-zoom.test.tsx`:
- 초기값 = localStorage `timeline-zoom` 파싱(없음→month). `setZoom('quarter')` 후 localStorage에 'quarter' 저장.
- 잘못된 저장값('xxx') → month(S6).
- `keydown` `1`/`2`/`3` → week/month/quarter 전환(S4). input/textarea 포커스 시 무시(EC6).
- 언마운트 시 keydown 리스너 해제(gap-3).
- 반환 `{ zoomLevel, setZoom, dayWidth }`(dayWidth=ZOOM_PRESETS[level].dayWidth).
- 실패 예상: 훅 없음.

**GREEN**.
- `useState`(lazy init = `parseZoomLevel(localStorage.getItem('timeline-zoom'))`).
- `setZoom` = state + `localStorage.setItem`. `useEffect`로 window keydown 등록 + cleanup, `event.target` 입력요소 가드.

**REFACTOR**. 상수 `STORAGE_KEY`, 키→레벨 맵, KDoc.

**검증**: `cd apps/web && pnpm test src/hooks/use-timeline-zoom.test.tsx`

---

### Task 6. `GanttChart` — 줌 prop 배선 (DAY_WIDTH 하드코딩 제거)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/GanttChart.tsx`, `apps/web/src/components/timeline/GanttChart.test.tsx`]
- depends-on: [1, 3]

**RED**. `GanttChart.test.tsx`(기존 보강):
- `GanttChart`가 `zoomLevel` prop을 받아 `TimelineAxis`에 전달(렌더 단언).
- `dayWidth`가 `ZOOM_PRESETS[zoomLevel]`에서 파생되어 `computeDependencyLines`·`overlayWidth`에 일관 전달(zoom 변경 시 의존선 좌표 변화, S7).
- `zoomLevel` 기본값 `'month'` 시 기존 동작 보존(무회귀).
- 실패 예상: prop 미수용 / 하드코딩 DAY_WIDTH_PX 사용.

**GREEN**.
- `GanttChartProps`에 `zoomLevel?: ZoomLevel`(기본 `'month'`) 추가. 내부 `const dayWidth = ZOOM_PRESETS[zoomLevel].dayWidth`.
- `DAY_WIDTH_PX` 하드코딩 사용처(TimelineAxis·TimelineRow·computeDependencyLines·overlayWidth) 전부 `dayWidth`로 교체. `TimelineAxis`에 `zoomLevel` 전달.

**REFACTOR**. dayWidth 파생 1곳 단일화, KDoc 갱신(zoom prop).

**검증**: `cd apps/web && pnpm test src/components/timeline/GanttChart.test.tsx`

---

### Task 7. `TimelinePage` 통합 — 훅 + 컨트롤 + GanttChart 연결

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.timeline.tsx`, `apps/web/src/routes/projects.$projectKey.timeline.test.tsx`]
- depends-on: [4, 5, 6]

**RED**. `projects.$projectKey.timeline.test.tsx`(기존 보강):
- 정상 상태에서 `TimelineZoomControl` 렌더(헤더 영역), `GanttChart`에 현재 `zoomLevel` 전달.
- 세그먼트 클릭 → GanttChart zoomLevel 갱신(통합).
- 빈 상태(items 0)에선 줌 컨트롤 미렌더(EC1).
- `GanttChart` key 불변(줌 변경이 재마운트 유발 안 함, S8) — projectKey만 key.
- 실패 예상: 컨트롤 미존재 / GanttChart에 zoom 미전달.

**GREEN**.
- `useTimelineZoom()` 사용, `{zoomLevel,setZoom}`을 `TimelineZoomControl`에, `zoomLevel`을 `GanttChart`에 전달.
- 컨트롤은 정상(GanttChart 렌더) 분기에서만, 헤더 영역(`p-4 space-y-3` 상단)에 배치.

**디자인 보강(D1)**. 헤더 순서 = 줌 컨트롤(최상단, 항상 보이는 도구) → 경고 배너(truncated/deps) → GanttChart.

**REFACTOR**. 헤더 영역 서브컴포넌트 분리(가독성), KDoc 갱신.

**검증**: `cd apps/web && pnpm test src/routes/projects.\$projectKey.timeline.test.tsx`

---

### Task 8. E2E — 줌 시나리오 (qa-engineer)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/timeline-zoom.spec.ts`]
- depends-on: [7]

**RED→GREEN(E2E는 실동작 기준)**. `timeline-zoom.spec.ts`:
- 세그먼트 "분기" 클릭 → 셀 폭 축소 실렌더(축 눈금 텍스트/막대 width 변화 관찰).
- "+"/"−" 버튼 동작 + 끝단 disabled.
- `1`/`2`/`3` 키 → 주/월/분기 전환.
- 새로고침 후 마지막 줌 복원(localStorage, S5).
- 의존선 정합(FR-TL-02 시나리오와 함께 — 줌 후 라인이 막대에 붙음, S7).
- 기존 `timeline.spec.ts`(FR-TL-01/02) 무회귀 동반 실행.

**검증**: `cd apps/web && pnpm test:e2e timeline-zoom.spec.ts timeline.spec.ts`

---

## Plan 메타

- task 수: 8
- 의존성 그래프: T1[] T2[] · T3[1] T4[1,2] T5[1] · T6[1,3] · T7[4,5,6] · T8[7]
- 예상 wave: wave1(T1,T2) → wave2(T3,T4,T5) → wave3(T6) → wave4(T7) → wave5(T8). 약 5 wave.
- 파일 겹침: 없음(각 task 고유 파일). T6→T7, T3→T6은 코드 의존(import/prop)으로 직렬.
- TDD 강제: yes (T8 E2E는 실동작 기준 red→green).
- 추가 검증: `pnpm typecheck`, `pnpm lint`(eslint), `pnpm test`(vitest 전체), `pnpm test:e2e`.
- 백엔드 변경 0 — Gradle/ktlint/detekt 무관(프론트 전용).

## 리뷰 결과

### plan-design-review (2026-06-29) — 디자인 관점 직접 적용

> gstack 무거운 인터랙티브 리뷰(mockup/comparison board/7-pass/telemetry)는 소규모 컨트롤 추가 +
> 디자인 결정 사전 확정(세그먼트+−/+·localStorage·1/2/3·헤더 위치·WCAG AA)에 과해 미실행.
> 디자인 리뷰어 관점(접근성·상태·계층·일관성)만 직접 적용.

- ✅ **상태 커버리지**. 세그먼트 활성(aria-pressed)·끝단 disabled·focus-visible(button.tsx ring)·빈 상태 컨트롤 숨김(EC1) 명시. 줌 전환 즉시(로딩 상태 없음).
- ✅ **접근성**. role=group + aria-label, aria-pressed, disabled 끝단, 키보드 1/2/3 + 입력 포커스 가드(EC6). WCAG AA(DESIGN.md §3) 충족.
- ✅ **일관성**. 세그먼트는 ui/button.tsx button-group 슬롯 = DESIGN.md 컨벤션. 신규 의존성 0.
- ⚠️ **D1 (계층, 보강)**. 헤더 내 줌 컨트롤/배너 순서 미정 → 줌 컨트롤 최상단, 경고 배너 그 아래로 명시(T7 반영).
- ⚠️ **D2 (간격, 보강)**. 세그먼트↔−/+ 시각적 간격 → T4 반영.
- ⚠️ **D3 (discoverability, 보강)**. 단축키 1/2/3 힌트(title/aria-keyshortcuts) → T4 반영.
- **BLOCKER: 없음**. 3 gap 모두 plan task에 보강 완료(minor, taste).

반응형 주의(비차단). 타임라인은 데스크톱 가로 스크롤 도구(FR-TL-01 기준). 모바일 줌 컨트롤 레이아웃은 기존 타임라인과 동일하게 범위 외.
