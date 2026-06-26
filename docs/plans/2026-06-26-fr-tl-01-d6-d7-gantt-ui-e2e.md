# FR-TL-01 D6/D7 타임라인/로드맵 뷰 (Gantt) — 프론트엔드 UI + E2E

> slug: fr-tl-01-d6-d7-gantt-ui-e2e
> type: ui
> agent: frontend-engineer
> BC: agile-planning (프론트), 백엔드 D1~D5 = #192 완료
> SDD: §4.1 / §13.3.1
> 생성: 2026-06-26

## Brief

사용자 원문: "fr-tl-01 d6, d7 진행해줘"

FR-TL-01 — 타임라인/로드맵 뷰 (Gantt). 필수 우선순위. agile-planning BC. SDD §4.1 / §13.3.1.
백엔드 D1~D5 는 PR #192 로 완료 — `GET /api/v1/timeline?project={key}` 존재 (타임라인 아이템 평면 목록 + epicKey + truncated 반환).
이번 작업 = D6(프론트 Gantt 렌더) + D7(E2E). 이슈의 Start/Due/Target Date 기반 타임라인을 Gantt 막대로 시각화.

classify: type=qa 오판 → ui/frontend-engineer 교정 (E2E 키워드 오판 함정).

범위 결정(Maxi 2026-06-26):
- 신규 worktree (FR-SR-03 PR2 와 격리)
- Gantt 라이브러리는 domain/spec 단계에서 후보 조사 → ADR trade-off 제시 → Maxi 승인

## 도메인 정리

- **BC**: agile-planning (프론트엔드). 백엔드 D1~D5 = #192 완료.
- **grill-with-docs 생략 사유**: 도메인 모델(타임라인 아이템)이 백엔드 #192 + glossary 로 이미 확정. 새 용어 0. 완료된 도메인엔 대화형 검증 부적합(memory: bts-spec-office-hours-mismatch). 핵심 미결정은 도메인 언어가 아니라 Gantt 라이브러리(기술 결정) → spec ADR 로 이연.

### API 계약 (백엔드 #192 실측 — invent 금지, memory: frontend-zod-backend-dto-contract-gap)

`GET /api/v1/timeline?project={key}` → `DataResponse<TimelineResponse>` 봉투.
- `TimelineResponse { items: TimelineItemResponse[], truncated: boolean }`
- `TimelineItemResponse { key, summary, issueType, currentStateKey, assigneeId: UUID?|null, startDate: LocalDate?|null, dueDate: LocalDate?|null, targetDate: LocalDate?|null, epicKey: String?|null }`
- **issueType 은 소문자**(`epic`/`story`/`task`/`bug`) — `TimelineItemResponse.kt:18` KDoc. 프론트 enum/분기 소문자 기준.
- **날짜 = ISO `LocalDate` 문자열**(`"2026-07-20"`). production 직렬화 ISO 확정(게이트2 재리뷰 충실화). 슬라이스 테스트는 `[y,m,d]` 배열일 수 있음(memory: enablewebmvc-slice-localdate-array-serialization) — 프론트 Zod 는 production ISO 기준.
- **정렬**: startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC (백엔드가 이미 정렬). 프론트 재정렬 불필요.
- **권한/에러**: BROWSE 필요. 미인증 401 / BROWSE 없음 403 / `project` 누락 400 / 500개 초과 시 `truncated=true`(부분 누락 배너).

### 트리 조립 = 프론트 책임

백엔드는 평면 목록 + `epicKey` 만 반환. Epic 부모/자식(Story·Task) 그룹화는 프론트가 `epicKey` 로 조립. Epic 자신은 `issueType=epic` + `epicKey=null`. epicKey 가 가리키는 Epic 이 목록에 없을 수 있음(날짜 없는 Epic 등) → "Epic 없음/미분류" 그룹 폴백 필요.

### 새 용어 / 기존 결정 충돌

- 새 용어: **0** (타임라인 아이템 이미 glossary 등록).
- 기존 결정 충돌: **없음**.
- 관련 ADR: **Gantt 라이브러리 선택** = fr-index §A.3 #2 보류 → 이번 spec 단계에서 확정 + Maxi 승인 후 ADR 생성(docs/adr/, memory: bts-adr-dual-folder-convention).

### Gantt 라이브러리 후보 (spec ADR 에서 확정 — 현재 후보 정리만)

| 후보 | 의존성 | trade-off |
|---|---|---|
| **자체 SVG/CSS Gantt** | 0 (신규 0) | 날짜→x좌표 / 행→y 단순 기하. 완전 커스터마이징 · 환각 위험 0 · BTS 단순성 철학 부합(memory: learnings Kafka/OpenSearch 도입 금지 정신). 스크롤/줌은 직접 구현(FR-TL-03 줌은 범위 외). **유력 후보**. |
| **recharts (기존)** | 0 (이미 설치) | floating BarChart 로 Gantt 흉내. 날짜 축은 됨. 계층 그룹 행·에픽 묶음·행 레이블 커스터마이징 제약. Gantt 전용 아님. |
| **frappe-gantt 등 전용 OSS** | +1 신규 | Gantt 전용이나 React 통합 명령형(매끄럽지 않음)·새 의존성 환각 위험(memory: learnings, Maxi 확인 필수). 1K 규모 오버킬 가능. |

## 스펙

전체 스펙. [docs/specs/2026-06-26-fr-tl-01-d6-d7-gantt-ui-e2e.md](../specs/2026-06-26-fr-tl-01-d6-d7-gantt-ui-e2e.md)

핵심 결정(Maxi 확정 2026-06-26).
- Gantt = **자체 SVG/CSS**(의존성 0). 레이아웃 = **Epic 그룹 + targetDate 마일스톤**. 시간축 = 고정 일 단위 폭 + 가로 스크롤(줌은 FR-TL-03 범위 외).

핵심 시나리오 3줄.
- `/projects/{key}/timeline` 진입 → 날짜 있는 가시 이슈를 Epic 그룹별 Gantt 막대로 렌더(start~due 막대 + targetDate ◆).
- Epic 그룹 접기/펼치기, 막대/레이블 클릭 → 이슈 상세 이동. 빈/truncated/403 상태 안내.
- 백엔드 `GET /api/v1/timeline` 만 호출(이미 정렬). 트리 조립·좌표 계산은 프론트 순수 함수.

## Brainstorming Check

✅ 통과 (1회 직접 sanity — 완료 FR 후속). gap 4건(레이아웃 sticky 구조·Epic 토글/막대 영역 분리·issueType 색 출처·FavoriteButton 제거) 발견 후 spec 본문 반영. 날짜 0/1/2개·start>due·targetDate 범위밖·빈/truncated/403·epicKey 미매칭·range폭0·타임존(UTC)·jsdom width0 커버 확인.

## Plan

> TDD red→green→refactor 강제. 각 task 메타(agent/files/depends-on)로 bts-impl 이 wave 계산.
> agent 전부 `frontend-engineer`. 자체 SVG/CSS(의존성0). 백엔드 #192 계약 정확 미러(invent 금지).
> **ADR + fr-index §A.3 #2 해소 + product D6/D7 동기화는 코드 외 — 컨트롤러가 impl 전(ADR)·merge 단계(동기화)에서 직접 처리.**

### Task 1. api/timeline.ts — Zod 스키마 + fetchTimeline

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/timeline.ts`, `apps/web/src/api/timeline.test.ts`]
- depends-on: []

**RED**. `timeline.test.ts` — (1) 정상 `DataResponse<TimelineResponse>` 봉투 파싱·언랩, (2) nullable 날짜(null/ISO date 문자열)·`issueType` 소문자 string·`assigneeId`/`epicKey` null 허용, (3) `truncated` boolean, (4) 403 → `ApiError`(errorCode 추출). 실패: `fetchTimeline`/스키마 없음.

**GREEN**. `timelineItemSchema`(백엔드 `TimelineItemResponse.kt` **정확 미러** — key/summary/issueType/currentStateKey/assigneeId(nullable)/startDate(nullable ISO)/dueDate/targetDate/epicKey(nullable)) + `timelineResponseSchema { items, truncated }`. `fetchTimeline(projectKey): Promise<TimelineResponse>` — **`apiGet(path, dataResponseSchema(timelineResponseSchema)).data`** (same-BC 선례 `apps/web/src/api/boards.ts:250-256` 패턴 — apiGet 이 GET+401 auto-refresh+ApiError 캡슐화, raw apiFetch 아님 C1). `GET /api/v1/timeline?project={key}`.

**REFACTOR**. `TimelineItem`/`TimelineResponse` 타입 export(lib/hooks 공유), KDoc(계약 출처 #192 명시). 날짜는 `string`(ISO) 그대로 보관 — Date 변환은 lib 책임.

**검증**: `pnpm --filter web test timeline.test`

### Task 2. lib/timeline-layout.ts — 순수 함수 (range·bar 좌표·Epic 그룹 조립)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/timeline-layout.ts`, `apps/web/src/lib/timeline-layout.test.ts`]
- depends-on: [1]

**RED**. `timeline-layout.test.ts` —
- `computeDateRange(items)` — min(startDate)~max(dueDate, **targetDate 포함** EC3). 빈→null. 단일 날짜(rangeStart==rangeEnd) 폭 0 방지 최소 1일(EC8).
- `computeBarGeometry(item, range, dayWidth)` — start+due(정상 폭), start만/due만(개방 최소폭 EC1), start>due 음수폭 클램프(EC2), targetDate→마일스톤 x좌표(FR4). **UTC 일수 계산**(`Date.UTC`, 타임존 시프트 회피 NFR4).
- `assembleEpicGroups(items)` — Epic(issueType==='epic') 그룹 헤더+자식(epicKey 매칭), 미분류(매칭 실패·epicKey null 비-Epic EC6/EC7), 그룹 내 백엔드 정렬 유지, 미분류 맨끝.

**GREEN**. 위 3 순수 함수 + UTC 날짜 헬퍼(`parseIsoDateUtc`/`daysBetweenUtc`). `lib/date-format.ts` 재사용 가능 시 재사용.

**REFACTOR**. 상수(`DAY_WIDTH`, `MIN_BAR_DAYS`), KDoc(EC 매핑), 타입(`TimelineGroup`/`BarGeometry`).

**검증**: `pnpm --filter web test timeline-layout.test`

### Task 3. hooks/use-timeline.ts — TanStack Query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-timeline.ts`, `apps/web/src/hooks/use-timeline.test.tsx`]
- depends-on: [1]

**RED**. `use-timeline.test.tsx` — queryKey `['timeline', projectKey]`, `fetchTimeline` 호출, 로딩→성공 데이터, 에러 전파. `use-boards.test.tsx` 패턴.

**GREEN**. `useTimeline(projectKey)` — `useQuery({ queryKey, queryFn: () => fetchTimeline(projectKey) })`.

**REFACTOR**. queryKey 상수/factory, KDoc.

**검증**: `pnpm --filter web test use-timeline.test`

### Task 4. mocks/timeline — fixtures + MSW handler (신규 모듈 자동 시드)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/timeline-fixtures.ts`, `apps/web/src/mocks/timeline-handlers.ts`, `apps/web/src/mocks/timeline-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**. `timeline-handlers.test.ts`(board-handlers.test.ts 패턴) — `GET /api/v1/timeline?project=BTS` → 봉투+items(Epic+자식+미분류+targetDate)·truncated 시나리오·미인증/403 시나리오. fixture 가 백엔드 정렬 순서(startDate ASC...) 준수.

**GREEN**. `timeline-fixtures.ts`(Epic 1+ 자식 2+ 미분류 1+ start만/due만 + targetDate + truncated 시드) + `timeline-handlers.ts`(GET handler, project 별 분기) + `handlers.ts` 등록(`...timelineHandlers` spread, handlers.ts:57-107 배열). **읽기 전용 정적 반환 핸들러**(선례 = board-handlers 의 stateful boardStore 가 아니라 `search-handlers`/`worklog-aggregate-handlers` 정적 핸들러 C3). E2E auto-seed 는 **`MODE!=='test'` 게이팅**(backlog-fixtures 패턴 — vitest 의 api/hook mock 과 충돌 0).

**REFACTOR**. 시나리오 헬퍼, 403/truncated 토글(memory: e2e-msw-scenario-toggle localStorage flag + addInitScript — E2E용).

**검증**: `pnpm --filter web test timeline-handlers.test`

### Task 5. components/timeline/ — GanttChart + 하위 (자체 SVG/CSS) + i18n

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/timeline/GanttChart.tsx`, `apps/web/src/components/timeline/TimelineAxis.tsx`, `apps/web/src/components/timeline/TimelineRow.tsx`, `apps/web/src/components/timeline/GanttChart.test.tsx`, `apps/web/src/i18n/timeline-labels.ts`, `apps/web/src/i18n/timeline-labels.test.ts`]
- depends-on: [2]

**RED**. `GanttChart.test.tsx`(순수 함수 결과 기반 — jsdom width0 무관, memory: NFR2 fr-tt-02) — (1) Epic 그룹/막대 행 수, (2) 그룹 접기/펼치기 토글, (3) 마일스톤 ◆ 렌더, (4) 막대/레이블 클릭→`onSelectIssue(key)` 콜백, (5) 토글 button stopPropagation(막대 클릭과 분리 G2), (6) 행 레이블에 **담당자 displayName**(props 로 받은 매핑 결과) 표시·미배정 폴백(EC11). `timeline-labels.test.ts` — **콜론 종결 0**(memory: fr-mf-05 ko.test).

**GREEN**. `GanttChart`(좌측 sticky 레이블 열 + 우측 시간축 단일 스크롤 컨테이너 G1) + `TimelineAxis`(주/월 눈금) + `TimelineRow`(막대 div/SVG + ◆ 마일스톤 + 개방 표식). `assembleEpicGroups`/`computeBarGeometry` 결과 소비. **담당자 이름은 `assigneeNames: Map<id,name>` props 로 받아 표시**(조회/매핑은 T6 route 책임 C2). **issueType 색**: 기존 색맵 없음 실측 → timeline 전용 색맵 신규 정의(epic/story/task/bug/폴백). aria-label(키+기간)·토글 aria-expanded(NFR3). `timeline-labels.ts`.

**REFACTOR**. 색맵 상수, 하위 컴포넌트 분리, KDoc(자체 SVG 좌표 모델).

**검증**: `pnpm --filter web test GanttChart.test timeline-labels.test`

### Task 6. routes/projects.$projectKey.timeline.tsx — Adapter + Page + **router.ts 등록**

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.timeline.tsx`, `apps/web/src/routes/projects.$projectKey.timeline.test.tsx`, **`apps/web/src/router.ts`**]
- depends-on: [3, 5]

**RED**. `timeline.test.tsx`(**hook mock 패턴 — `vi.mock('@/hooks/use-timeline')` + `vi.mock('@tanstack/react-router')` + `GanttChart` mock 후 `TimelinePage` 직접 render**. 프로젝트 어떤 route test 도 MSW 안 씀, board test `__tests__/projects.board.test.tsx:16,85` 패턴 B2) — 로딩 Skeleton, 정상 GanttChart 렌더, 빈 상태(S4), truncated 배너(S5), 403 접근거부(AGILE_ACCESS_DENIED S6), 막대 클릭→`/issues/{key}` navigate, 담당자 이름 매핑 전달(C2).

**GREEN**.
- `TimelineRouteAdapter`(useParams projectKey) + `TimelinePage` — `useTimeline` + **`useQuery(['users'], fetchUsers)` + `buildUserMap`/`buildAssigneeNames` 3-state(board route `projects.$projectKey.board.tsx:303-317` 미러 C2)** → GanttChart 에 `assigneeNames` 전달 + onSelectIssue navigate.
- **code-based 라우팅 등록 B1**: `BoardRouteAdapter`(projects.$projectKey.board.tsx) 패턴으로 Adapter export 후 **`router.ts` 에 import + `createRoute({ path:'/projects/$projectKey/timeline', component: TimelineRouteAdapter, ... beforeLoad: requireAuthAndPasswordChanged })` 등록 + `routeTree.addChildren` 추가**. `createFileRoute` 아님(grep 0건). router.ts 라우트 카운트 주석(router.ts:1) 33→34 동기화.
- board route 에러 처리 패턴(extractErrorCode/AGILE_ACCESS_DENIED) 재사용.

**REFACTOR**. Skeleton/빈/배너/에러 컴포넌트 분리, KDoc. key prop 재마운트 주의(memory: react-usestate-stale-key-prop — projectKey 변경 시).

**검증**: `pnpm --filter web test projects.\$projectKey.timeline.test`

### Task 7. 네비 링크 — board/backlog → 타임라인 진입점

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/projects.$projectKey.backlog.tsx`, `apps/web/src/routes/__tests__/projects.board.test.tsx`, `apps/web/src/routes/projects.$projectKey.backlog.test.tsx`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/i18n/backlog-labels.ts`]
- depends-on: [6]   # `Link to="/projects/$projectKey/timeline"` 는 router.ts(T6) 등록 후에만 타입 통과 (Register 모듈 증강 + strict). vitest 는 mock 으로 통과하나 typecheck 가 깨짐.

**현황 실측(B3)**. 네비는 **단방향** — backlog route 만 `<nav aria-label="프로젝트 뷰 전환">`+board 링크 보유(`projects.$projectKey.backlog.tsx:59-67`). **board route 엔 nav/Link 자체가 없음**. board test(`__tests__/projects.board.test.tsx:16`)의 react-router mock 엔 `Link` stub 부재(backlog test:12-34 엔 있음).

**RED**.
- backlog test — nav 에 "타임라인" 링크(`to="/projects/$projectKey/timeline"`) 단언 추가.
- board test — **react-router mock 에 `Link` stub 추가**(backlog test 미러 — 없으면 board 에 Link 넣는 순간 "Element type is invalid" 로 기존 board test 전부 깨짐) + nav "백로그"/"타임라인" 링크 단언.
- 텍스트 중복 시 nav 컨테이너 한정/exact(memory: playwright-getbyrole-exact, ui-pr-defer-e2e-regression).

**GREEN**.
- backlog nav 에 타임라인 `<Link>` 추가(기존 nav 확장).
- **board route 에 `<nav aria-label="프로젝트 뷰 전환">` 신규 추가**(0→1, backlog nav 미러) — 백로그 + 타임라인 링크. 상호 네비 완성.
- i18n `board-labels`/`backlog-labels` 에 `timelineLink`/`backlogLink` 라벨 추가(콜론 종결 0).

**REFACTOR**. 링크 라벨 i18n 통일, nav 마크업 일관.

**검증**: `pnpm --filter web test projects.board projects.\$projectKey.backlog`

### Task 8. E2E — e2e/timeline.spec.ts (happy path 실렌더)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/e2e/timeline.spec.ts`]
- depends-on: [4, 6, 7]

**RED→GREEN**. Playwright happy path — board/backlog 에서 타임라인 진입(SPA 내부 이동, **reload 금지** memory: fr-nt-03 MSW 영속) → Epic 그룹 + 막대 실렌더 확인(시각화는 E2E 실렌더, memory: fr-lk-02) → 그룹 토글 → 막대 클릭 → 이슈 상세 이동. MSW fixture 시드(T4). 드래그 없음(PointerSensor 불요).

**REFACTOR**. 셀렉터 컨테이너 한정(strict mode), 시나리오 분리.

**검증**: `pnpm --filter web test:e2e timeline`

## Plan 메타

- task 수: 8 (전부 frontend-engineer)
- 예상 wave: 6 (W1: T1 / W2: T2·T3·T4 / W3: T5 / W4: T6 / W5: T7 / W6: T8)
- depends-on: T1[] T2[1] T3[1] T4[1] T5[2] T6[3,5] T7[6] T8[4,6,7]. 순환 없음. (T7→6 = Link 타입 의존, 리뷰 후 추가 발견)
- TDD 강제: yes
- 파일 겹침: 없음 (T6 router.ts 단독, T7만 기존 board/backlog route+test — 단독 wave)
- 추가 검증: pnpm lint + typecheck(tsconfig.app, memory: ci-typecheck) + test + e2e. ko i18n 콜론 종결 0.
- 코드 외(컨트롤러 직접): ADR `docs/adr/2026-06-26-gantt-rendering-self-svg.md`(impl 전) · fr-index §A.3 #2 해소 + product/agile-planning D6/D7 체크 + 카운트 동기화(merge, verify-master-plan exit0)
- 함정 주의 — ① Zod 백엔드 DTO 정확 미러 ② UTC 날짜 계산 ③ 신규 MSW 모듈 자동 시드 ④ jsdom width0→순수함수 검증 ⑤ MSW E2E SPA 내부 이동(reload 금지) ⑥ 콜론 종결 0 ⑦ apiFetch BC 관례 grep

## 리뷰 결과

### 독립 적대 리뷰 (2026-06-26, general-purpose agent — 코드 실측 26 tool use)

**BLOCKER (반영 완료)**.
- **B1** — 라우팅은 **code-based(`router.ts` createRoute)**인데 plan 이 file-based `createFileRoute` 가정 + router.ts 등록 task 누락(`grep createFileRoute`=0, router.ts 가 33 라우트 등록). → T6 files 에 `router.ts` 추가, GREEN 을 `TimelineRouteAdapter`+createRoute 등록+카운트 33→34 동기화로 교체. ✅
- **B2** — route test 는 MSW 아닌 **hook mock**(board test `__tests__/projects.board.test.tsx:16,85` = `vi.mock` use-boards/react-router/KanbanBoard). → T6 RED 를 hook mock 패턴으로 교정, MSW 는 T8 E2E 전용. ✅
- **B3** — board route 엔 nav/Link **자체가 없음**(backlog 만 단방향 board 링크), board test mock 에 `Link` stub 부재. board 에 Link 추가 시 기존 board test 전부 회귀. → T7 files 에 test 2개 + i18n 추가, board test mock 에 Link stub, board nav 0→1 신규 추가 명시. ✅

**CONCERN (반영 완료)**.
- **C1** — `apiFetch` 아닌 same-BC 관례 **`apiGet`**(boards.ts:250-256, GET+401refresh+ApiError 캡슐화). → T1 GREEN 교정. ✅
- **C2** — 담당자 **이름** 조회 경로 미배정(응답은 assigneeId UUID 만, board 는 `fetchUsers`+`buildUserMap`+`buildAssigneeNames` 3-state). → T6 GREEN 에 user 조회+매핑, T5 는 `assigneeNames` props 수신. ✅
- **C3** — read-only 핸들러 선례를 board(stateful store) 아닌 `search`/`worklog-aggregate` 정적 핸들러로, E2E auto-seed `MODE!=='test'` 게이팅. → T4 교정. ✅

**OK (실측 확인)**. API 계약 미러 정확(TimelineItemResponse.kt:26-36)·403 errorCode `AGILE_ACCESS_DENIED`(TimelineExceptionHandler.kt:80)·DataResponse 봉투(boards.ts:10)·handlers.ts spread 등록·hook test 패턴(use-boards.test.tsx:9)·i18n 단일 ko·E2E reload금지(backlog.spec.ts:13)·의존성 그래프 순환0·navigate `/issues/$key`(router.ts:128)·issueType 색맵 부재→신규 정의·메모리 교훈(UTC날짜/jsdom width0/콜론종결/key prop) 반영.

**결론**: FIX-FIRST → BLOCKER 3 + CONCERN 3 전부 plan 반영 완료 → **GO**.

### PR 단위 코드 리뷰 (2026-06-26, 게이트 2)

**superpowers:code-reviewer** — **PASS** (BLOCKER 0, 절대 규칙 19개 위반 0). 9개 중점 전수 통과(Zod 백엔드 DTO 미러·UTC 날짜 EC1/2/3/8·자체SVG div기반 jsdom안전·code-based router 인증가드·board nav 회귀0·apiGet/담당자 3-state·콜론종결0·MSW MODE게이팅·403 누출0). 단위 140 + 전체 4651 통과, typecheck/lint 클린. CONCERN 3(비차단).
- **C1** '미분류' 라벨 이중정의 + 데드 i18n 엔트리(`timeline-labels.ts:14` unclassifiedHeader 테스트서만 참조).
- **C2** 라우트 인라인 한국어(접근거부/배너) vs i18n 중앙화 불일치(backlog는 중앙화 선례).
- **C3** LocalDate→ISO 직렬화 end-to-end 미검증 → **#192에서 해소 확인**(백엔드 게이트2 재리뷰 "컨트롤러 테스트 ISO 직렬화 충실화, [y,m,d] 배열 가짜그린 교정").

**/review (BTS 체크리스트, 구조/안전성)** — **PASS**. 직렬화↔Zod 정합 실증(TimelineItemResponse @JsonInclude 미부착 → null 필드 JSON 포함 → Zod nullable 정합). init_codegen/Flyway/도메인예외핸들러/cartesian = N/A(프론트 전용). race/LLM신뢰경계/shell injection 없음(읽기전용 SVG). CONCERN 1(저심각도).
- **C4** board 선례는 `epicKey: nullable().default(null)`로 키 부재 방어, timeline은 `.default(null)` 미적용. 현재 정합(NON_NULL 미부착)하나 백엔드 NON_NULL 도입 시 깨질 여지 — 방어적 일관성 권장.

**종합**: 두 리뷰 PASS, BLOCKER 0. CONCERN 4건 전부 머지 비차단(C3는 #192 해소).
