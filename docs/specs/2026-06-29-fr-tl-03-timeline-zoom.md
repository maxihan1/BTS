# FR-TL-03 타임라인 줌 (주/월/분기) — 스펙

> slug: fr-tl-03-timeline-zoom · type: ui · BC: agile-planning · 프론트 전용
> 선행: FR-TL-01(자체 SVG Gantt, #194), FR-TL-02(의존성 라인, #201)
> SDD §13.3.3, product agile-planning §4.3

## 개요

FR-TL-01 자체 SVG/CSS Gantt 타임라인에 **줌 레벨(주/월/분기)**을 추가한다. 줌은 순수 클라이언트
뷰 스케일 전환이며 데이터 재요청·백엔드·DB 변경이 없다. FR-TL-01이 좌표 함수
(`computeBarGeometry`, `computeDependencyLines`)·축(`TimelineAxis`)에 `dayWidth`를 이미 인자화해
두었으므로(ADR `2026-06-26-gantt-rendering-self-svg.md` 결과 §가 줌 확장을 예고), 줌은
`GanttChart`의 `DAY_WIDTH_PX=20` 하드코딩을 `zoomLevel → dayWidth/축단위` 매핑으로 교체하는 작업이다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (세그먼트 선택)**. Given 타임라인 페이지, When 사용자가 "분기" 세그먼트 버튼을 클릭하면,
  Then 차트가 분기 스케일(셀 폭 축소 + 월/분기 축 눈금)로 즉시 다시 그려지고 "분기" 버튼이 활성 표시된다.
- **S2 (확대/축소 버튼)**. Given 현재 줌="월", When 사용자가 "+"(확대) 버튼을 누르면, Then 한 단계
  확대되어 "주"가 된다. When "−"(축소)를 누르면 "분기"로 한 단계 축소된다.
- **S3 (끝단 비활성)**. Given 현재 줌="주"(가장 확대), When 보면, Then "+" 버튼은 비활성(disabled)이다.
  Given 줌="분기"(가장 축소), Then "−" 버튼이 비활성이다.
- **S4 (키보드 단축키)**. Given 타임라인 페이지에 포커스, When 사용자가 `1`/`2`/`3`을 누르면,
  Then 각각 주/월/분기로 전환된다. 단 입력 필드(input/textarea/contenteditable)에 포커스가 있으면 무시한다.
- **S5 (상태 영속)**. Given 사용자가 줌="주"로 변경, When 페이지를 새로고침하거나 같은 브라우저로
  재방문하면, Then 줌="주"가 복원된다(localStorage).
- **S6 (잘못된 저장값)**. Given localStorage에 알 수 없는 값, When 페이지 로드, Then 기본 줌="월"로 폴백한다.
- **S7 (의존성 라인 정합)**. Given blocks 의존 라인(FR-TL-02)이 보이는 상태, When 줌을 변경하면,
  Then 라인이 새 셀 폭에 맞춰 자동 재계산되어 막대와 정합을 유지한다(좌표 단일 출처).
- **S8 (접힘 상태 보존)**. Given 에픽 그룹 일부를 접은 상태, When 줌을 변경하면, Then 접힘 상태가 유지된다
  (줌 변경이 GanttChart를 재마운트하지 않음).

## 기능 요구사항 (FR)

- **FR1**. 줌 레벨은 `'week' | 'month' | 'quarter'` 3종. 표시 순서·정도 = 주(가장 확대) > 월 > 분기(가장 축소).
- **FR2**. 기본 줌 = **월**. FR-TL-01 현행 동작(dayWidth=20 + 월/주 2행 축)과 일치 → 무회귀.
- **FR3**. 줌 레벨별 `dayWidth` 프리셋(구현 시 시각 튜닝, 후보값):
  | 줌 | dayWidth(px/일) | 상단 축 눈금 | 하단 축 눈금 |
  |---|---|---|---|
  | week | ~28 | 월(YYYY.MM) | 일(DD) |
  | month | 20 (현행) | 월(YYYY.MM) | 주(MM/DD, 월요일) |
  | quarter | ~6 | 분기(YYYY Q{n}) | 월(MM) |
  - 제약: quarter dayWidth가 너무 작으면 `MIN_BAR_DAYS=1` 막대가 안 보이므로 ≥4px 유지.
- **FR4**. 줌 컨트롤 UI = **세그먼트 버튼(주|월|분기) + −/+ 버튼**. `ui/button.tsx` 조합으로 직접 구현
  (ToggleGroup 컴포넌트 미설치). 위치 = TimelinePage 헤더 영역(현재 비어있음, GanttChart 위).
- **FR5**. 키보드 단축키 `1`=주, `2`=월, `3`=분기. 페이지 레벨 keydown 리스너. 입력 필드 포커스 시 무시(S4).
- **FR6**. 줌 상태는 localStorage 키 `timeline-zoom`에 영속(전역 단일 키 — 줌은 프로젝트 무관 보기 선호도).
  로드 시 파싱 실패/미존재/잘못된 값 → 기본 월(S6).
- **FR7**. 줌 변경 시 의존성 라인(FR-TL-02)이 새 dayWidth로 자동 재계산(S7). `overlayWidth`도 새 dayWidth 반영.
- **FR8**. 접근성: 세그먼트 버튼은 `aria-pressed`로 활성 표시, 그룹은 `role="group"` + aria-label.
  −/+ 버튼은 끝단에서 `disabled` + aria-label("확대"/"축소"). WCAG AA(DESIGN.md §3).

## 비기능 요구사항 (NFR)

- **NFR1**. 줌 전환은 데이터 재요청 0(순수 클라이언트 스케일). 기존 useTimeline/useTimelineDeps 쿼리 재실행 없음.
- **NFR2 (jsdom 안전)**. 줌 매핑·축 눈금·zoom in/out·localStorage 직렬화는 **순수 함수**(`lib/timeline-zoom.ts`)로
  분리해 단위 테스트. 픽셀 시각 검증은 E2E(Playwright)에 위임(ADR D2 정신, getBBox/width0 함정 회피).
- **NFR3**. UTC 날짜 기준 유지(NFR4 of FR-TL-01). 분기/일 눈금도 UTC.
- **NFR4 (성능)**. 500건 타임라인에서 줌 전환 후 재렌더 부드러움(기존 렌더 임계 2s 회귀 없음).

## 데이터 모델 변경

없음. 마이그레이션 0, 백엔드 0, API 0.

## 엣지 케이스

- **EC1 (빈 타임라인)**. items 0 → TimelineEmptyView. 줌 컨트롤은 차트가 있을 때만 표시(빈 상태엔 숨김).
  단 localStorage 줌 값은 보존.
- **EC2 (긴 기간)**. 분기 줌이 1년+ 기간을 한눈에 보기 위한 것. 가로 스크롤 유지.
- **EC3 (짧은 기간 1~2일)**. computeDateRange의 EC8(단일 날짜 폭 0 방지) 기존 처리로 안전. 주 줌도 정상.
- **EC4 (잘못된 localStorage)**. `parseZoomLevel`이 화이트리스트 검증 후 기본 월 폴백.
- **EC5 (의존성 라인 자동 재계산)**. computeDependencyLines/overlayWidth에 새 dayWidth 전달(FR7).
- **EC6 (단축키-입력 충돌)**. 1/2/3 keydown이 input/textarea/contenteditable 포커스 시 무시(FR5).
- **EC7 (분기 줌 일 눈금 겹침)**. quarter는 하단=월 눈금만(주/일 눈금 미표시) — 셀 폭이 작아 겹침 방지.
- **EC8 (주 줌 일 눈금 가독)**. week dayWidth(~28px)는 일(DD) 레이블 표시 가능. 너무 좁으면 격일 표기 검토.

## Brainstorming 보강 (Phase B 발견)

- **gap-1 (스크롤 보정 범위 외)**. 줌 전환 시 가로 스크롤 위치의 "줌 중심 날짜 유지" 보정은 **MVP 범위 외**.
  현재 스크롤 중심 날짜를 새 dayWidth로 재스크롤하는 보정은 복잡도 대비 효익 낮음 → 미구현(보정 없음).
  향후 개선 후보로 기록. (SDD 한 줄 명세 = 단순 MVP 적절.)
- **gap-2 (i18n)**. 줌 레이블("주"/"월"/"분기"), 줌 그룹 aria-label, −/+ aria-label("확대"/"축소")은
  `i18n/timeline-labels.ts`에 추가(인라인 한국어 금지 — 메모리 교훈 데드 i18n·콜론 종결 ko.test 검증).
- **gap-3 (재마운트/리스너)**. 줌 prop 추가 시 `GanttChart` `key` 불변 유지(collapsedGroups 보존 S8).
  키보드 단축키 리스너는 useEffect 등록 + cleanup으로 해제(페이지 이탈 시 누수 0).

## 제약 조건

- 신규 npm 의존성 0(ADR self-SVG 정신, 환각 위험 0).
- BC 격리: agile-planning 프론트 전용. 다른 BC import 0.
- 기존 FR-TL-01/02 컴포넌트(`GanttChart`/`TimelineAxis`/`TimelineRow`/`DependencyOverlay`) 회귀 0
  — board/backlog/timeline 기존 E2E 통과.
- `lib/timeline-layout.ts`의 좌표 함수 시그니처(이미 dayWidth 인자)는 변경 없음(호출부만 새 dayWidth 전달).

## 측정 가능한 완료 기준

- [ ] `lib/timeline-zoom.ts` 순수 함수 단위 테스트: ZOOM_PRESETS 매핑, nextZoomIn/Out(끝단 클램프), parseZoomLevel(폴백), 축 눈금 단위 결정.
- [ ] TimelineAxis가 zoomLevel별 (상단/하단) 눈금 단위를 올바르게 렌더(단위 테스트로 눈금 개수/레이블 검증).
- [ ] 줌 컨트롤 컴포넌트: 세그먼트 활성 표시(aria-pressed), −/+ 끝단 disabled, 클릭/단축키 동작.
- [ ] GanttChart가 zoomLevel/dayWidth prop을 받아 축·행·의존선·오버레이에 일관 전달(하드코딩 제거).
- [ ] localStorage 영속/복원/폴백.
- [ ] E2E: 세그먼트 클릭 → 셀 폭 변화 실렌더 확인, −/+ 버튼, 1/2/3 단축키, 새로고침 후 복원, 의존선 정합.
- [ ] 기존 timeline/board/backlog E2E 무회귀.

## Brainstorming Check

✅ 통과 (1회 iteration). 자체 sanity check로 gap 3건(스크롤 보정 범위 외·i18n·재마운트/리스너) 발견 후
스펙에 보강 반영. Maxi 결정 필요 항목은 사전 AskUserQuestion(세그먼트+−/+·localStorage·1/2/3)으로 확정.
