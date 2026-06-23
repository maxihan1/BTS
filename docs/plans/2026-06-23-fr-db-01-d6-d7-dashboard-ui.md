# FR-DB-01 D6/D7 — 사용자 정의 대시보드 프론트엔드 UI + E2E

> slug: fr-db-01-d6-d7-dashboard-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-23

## Brief

FR-DB-01 사용자 정의 대시보드의 D6(프론트엔드 UI, react-grid-layout) + D7(E2E) 단계.
백엔드 D1~D5는 #176으로 완료(notification 모듈 `com.bts.notification.dashboard` 패키지).
Dashboard Aggregate(visibility PRIVATE/TEAM/ORG · OCC · layout JSONB · 소프트삭제) +
dashboard_shares + 목록/단건/생성/수정/삭제 API 제공 중.

이번 작업은 프론트엔드 전용(apps/web). 백엔드 변경 없음(있다면 same-BC view layer patch만).

classify 정정 메모: classify가 "E2E" 키워드로 qa 오분류 → Maxi가 ui/frontend-engineer 확정.

## 도메인 정리

- **BC**: notification (dashboard 패키지 — notification-dashboard BC). 프론트는 apps/web.
- **영향 엔티티**: 신규 없음. 백엔드 #176에서 Dashboard Aggregate + DashboardShare 확립. 프론트는 REST 계약 소비만.
- **새 용어**: 없음. glossary에 이미 존재 — "대시보드(Dashboard)", "공유 범위(Visibility: PRIVATE/TEAM/ORG)", "대시보드 공유(Dashboard Share)". glossary/domain 갱신 불필요.
- **스코프 경계 (★ 결정적)**: glossary 명시 — "컨테이너=FR-DB-01, 가젯 10종=FR-DB-02, URL공유/임베드=FR-DB-03". **위젯(가젯) 콘텐츠 시스템(assigned_to_me·pie_chart 등 12종)은 FR-DB-02 범위로 이번 작업 밖.** FR-DB-01 D6/D7 = 대시보드 CRUD + react-grid-layout 레이아웃 편집 + 공유 설정 UI까지. 백엔드 layout JSONB는 구조 미검증(JSON 유효성만) → 프론트가 react-grid-layout `Layout[]`({i,x,y,w,h}) 형식 정의(ADR D4 명시).
- **기존 결정 충돌**: 없음. 백엔드 ADR [docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md] 존재(D4: layout=JSONB react-grid-layout {i,x,y,w,h} 명시). 프론트 ADR(그리드 라이브러리 선택)은 spec/plan에서 결정.

### 백엔드 계약 요약 (코드 실측, #176)

REST `/api/v1/dashboards` (5종):
- `POST` 201 — CreateDashboardRequest{name, description?, visibility, layout?(기본"[]"), sharedUserIds?}
- `GET ?limit&offset` 200 — DashboardPageResponse{items, total(별도COUNT), limit, offset}. 접근범위 owned∪shared(TEAM)∪ORG, updatedAt desc + id tiebreaker
- `GET /{id}` 200/404 — 권한별(PRIVATE owner만/TEAM owner+shares/ORG 전체), 미접근 404(존재숨김)
- `PATCH /{id}` 200/403/404/409 — PatchDashboardRequest{...?, version 필수(OCC)}. 3-state(null=유지, []=전체제거). owner만(403). version 불일치 409
- `DELETE /{id}` 204/403/404 — 소프트삭제, owner만, 멱등(이미삭제 404)

DashboardResponse{id, ownerId, name, description?(@JsonInclude NON_NULL), visibility(문자열), layout(JSON문자열), sharedUserIds[], createdAt, updatedAt, version}

불변식: name 1~200자, layout 유효JSON·64KB이하, sharedUserIds≤200·TEAM만유효·owner제외, visibility∈{PRIVATE,TEAM,ORG}. 에러코드 대문자 `NOTIF_DASHBOARD_*`.

### 프론트 선례/컨벤션 (조사 실측)

- **react-grid-layout 미도입** — 새 외부 의존성. @dnd-kit(칸반)·recharts(워크로그)는 있으나 그리드+리사이즈 부적합. SDD §14.1.1이 명시적으로 "그리드 레이아웃(react-grid-layout)" 지정. → **spec에서 외부 의존성 도입 Maxi 확인 필요**.
- api 컨벤션: notification BC는 apiGet(읽기, CSRF불요) / apiFetch+X-XSRF-TOKEN(readXsrfToken, 상태변경) + Zod 스키마(파일별 분리, NON_NULL은 .nullish()). 선례 `api/notification-policies.ts`, `api/user-notification-subscriptions.ts`.
- 라우트: TanStack Router code-based. router.ts(.ts, JSX불가) + routes/<route>.tsx(RouteAdapter+Page 분리). 기존 `/dashboard`(환영메시지만) 존재 → 확장 또는 `/dashboards` 신설.
- 차트 jsdom width0 우회: 순수함수 단위 + vi.mock smoke + 실렌더 E2E (recharts 선례).
- MSW: BC별 handlers+fixtures. dashboard 핸들러 미존재 → 신규 작성. 시드 stateful(모듈로드 자동시드).
- i18n: BC별 labels 파일 분리 + 콜론 종결 금지(ko.test.ts).
- E2E 드래그: PointerSensor(mouse.move/down/move>threshold/move/up). react-grid-layout도 유사.

### ★ spec 단계로 넘길 핵심 결정 (2건)

1. **react-grid-layout 외부 의존성 도입** — SDD/ADR가 명시한 정석이나 새 라이브러리이므로 Maxi 확인(DEVELOPMENT.md §외부 의존성). 대안: 순수 CSS Grid 직접 구현(노력 과다)·@dnd-kit 확장(리사이즈 없음).
2. **위젯 placeholder 처리** — FR-DB-01엔 위젯 콘텐츠가 없음(FR-DB-02). 그리드 셀에 무엇을 표시할지(빈 placeholder 타일+제목 / 레이아웃 편집 골격만 / 더미 위젯). 백엔드 layout 자유형식이므로 프론트 결정 필요.

- **관련 ADR**: [docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md](../decisions/2026-06-22-fr-db-01-custom-dashboard.md) (백엔드, 충돌 없음). 프론트 그리드 라이브러리 ADR은 미작성 → spec/plan에서.

## 스펙

전체 스펙. [docs/specs/2026-06-23-fr-db-01-d6-d7-dashboard-ui.md](../specs/2026-06-23-fr-db-01-d6-d7-dashboard-ui.md)

**Maxi 확정 결정 3건 (2026-06-23)**.
1. 그리드 라이브러리 = **react-grid-layout 도입** (새 의존성, SDD §14.1.1·ADR D4 명시)
2. 위젯 = **placeholder 타일** (제목만, 콘텐츠는 FR-DB-02 범위 제외)
3. 라우트 = **`/dashboards` 신설** (목록+상세, 기존 `/dashboard` 환영 유지)

핵심 시나리오 요약.
- `/dashboards` 목록(owned∪shared∪ORG) → 생성 폼(name/visibility/공유) → `/dashboards/$id` 상세
- 상세 = react-grid-layout 12컬럼 그리드. placeholder 타일 추가/드래그/리사이즈/삭제 → 수동 "저장"(PATCH layout+version)
- 권한 게이팅(소유자만 편집, 비소유자 읽기전용) + OCC 409 처리(토스트+로컬보존)
- E2E 드래그는 PointerSensor 방식(칸반 선례), 실렌더 검증은 E2E 위임(jsdom width0)

FR 14개(FR-1~14), 엣지 10개(EC1~10). FR 총수 123 불변(D6/D7 체크박스만 마킹).

## Brainstorming Check

✅ 통과 — 명세 명확 완료 FR이라 office-hours/brainstorming 대신 직접 기술 스펙 + self-review(스펙 §9). Maxi 결정 3건으로 핵심 모호성(의존성·위젯스코프·라우트) 선해소. 누락 gap 없음. 과설계 항목(더미위젯·자동저장·모드토글) self-review에서 배제.

## Plan

10 TDD task (frontend 9 + qa 1). 모든 경로는 repo 루트 기준. 선례 = 칸반보드(@dnd-kit)·워크로그(recharts).

### Task 1. react-grid-layout 도입 + layout 직렬화 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/package.json`, `apps/web/src/lib/dashboard-layout.ts`, `apps/web/src/lib/dashboard-layout.test.ts`]
- depends-on: []

**RED**: `dashboard-layout.test.ts` —
- `parseLayout(jsonString): DashboardTile[]` — 정상 배열 파싱, 빈 문자열→`[]`, 손상 JSON→`[]` 폴백(throw 금지), 비-배열→`[]`
- `serializeLayout(tiles): string` — round-trip 보존
- `createTile(existing): DashboardTile` — 고유 `i` 생성(crypto.randomUUID), 기본 위치/크기(빈 칸 탐색 or y=Infinity)

**GREEN**: `lib/dashboard-layout.ts` 구현 + **`react-grid-layout@^1.5` 고정 설치**(★Maxi 결정 — v2.2.3은 WidthProvider→/legacy 이동 + useContainerWidth가 jsdom mounted=false 가짜그린이라 회피) + `@types/react-grid-layout` 설치(v1은 별도 타입 필요). `DashboardTile{i,x,y,w,h,title}` 타입 export. 그리드 CSS(`react-grid-layout/css/styles.css`, `react-resizable/css/styles.css`) import 위치 결정.

**REFACTOR**: 그리드 상수 추출(`COLS=12`, `ROW_HEIGHT`, `MARGIN`, `CONTAINER_PADDING`, 기본 타일 `w/h`) + 파일 L1 한국어 주석.

**검증**: `pnpm test dashboard-layout` + `pnpm typecheck`.
- ★NFR-1 = **react-grid-layout@1.5 React 19 호환 첫 검증**: 전이 의존(`react-draggable`/`react-resizable`)의 peer가 React 19를 거부하지 않는지 설치 로그 + import 타입 확인. 충돌 시 controller 보고.
- ★worktree node_modules: 새 의존성 설치 후 `pnpm install` 부분설치 깨짐 시(worktree-node-modules-partial-install) main 트리에서 설치 후 해당 패키지 dist 복사로 복구.

### Task 2. api/dashboards.ts — Zod 스키마 + CRUD

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/dashboards.ts`, `apps/web/src/api/dashboards.test.ts`]
- depends-on: []

**RED**: `dashboards.test.ts` —
- Zod: `dashboardSchema`(필드 전수 — `id`/`ownerId` uuid, `name`, `description` **`.nullish()`** ← @JsonInclude NON_NULL은 description만 클래스레벨 적용, `visibility` 문자열, `layout` `z.string()`, `sharedUserIds` `z.array(uuid)`(non-null 빈배열 가능), `createdAt`/`updatedAt`, `version`), `dashboardPageSchema{items, total, limit, offset}` + **`DataResponse` 래퍼(`{data: ...}`)** 명시(백엔드 DTO grep으로 확인 ← frontend-zod-backend-dto-contract-gap)
- `listDashboards(limit,offset)` GET, `getDashboard(id)` GET — apiGet(CSRF 불요)
- `createDashboard(body)` POST, `patchDashboard(id,body)` PATCH(version 포함), `deleteDashboard(id)` DELETE — apiFetch + **`X-XSRF-TOKEN`(readXsrfToken) 3종 모두 필수**
- 에러코드 대문자 `NOTIF_DASHBOARD_*` 매핑(ApiError 전파)

**GREEN**: ★선례 = **`api/notification-policies.ts` 단독**(같은 notification BC). `api/boards.ts`(agile-planning BC, PATCH에 CSRF 없음)는 **선례에서 제외** — 혼용 시 CSRF 헤더 누락 P0 보안 drift(frontend-api-convention-per-bc). dataWrapper 헬퍼 파일내 정의.
- ★`createdAt`/`updatedAt` Instant 직렬화 형식: notification BC 기존 API 응답이 ISO 문자열인지 실측(`z.string()` 가정). 숫자 직렬화면 가짜그린(fr-pl-02-overdue-notify-done) → 백엔드 Jackson 설정 확인.

**검증**: `pnpm test src/api/dashboards`.

### Task 3. MSW dashboard 핸들러 + fixtures (stateful, OCC 시뮬)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/dashboard-handlers.ts`, `apps/web/src/mocks/dashboard-fixtures.ts`, `apps/web/src/mocks/dashboard-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED**: `dashboard-handlers.test.ts` — list/get/create/patch/delete stateful store. PATCH version 불일치→409 `NOTIF_DASHBOARD_CONFLICT`. 비소유자 patch/delete→403. 미존재→404. PATCH 성공 시 **version+1 반환**.

**GREEN**: 선례 `board-handlers.ts`·`board-fixtures.ts`. **모듈 로드 시 자동 시드**(★msw-derived-behavior-shared-store-e2e: 브라우저 시드가능 공유 store).
- ★fixtures의 소유 대시보드 `ownerId` = **alice userId(`00000000-0000-4000-8000-000000000001`)와 동기화**(e2e-fixture-whoami-userid-alignment) + 비소유 대시보드(다른 ownerId)도 시드(S7 읽기전용 검증용).
- ★OCC 409 토글 플래그는 **고유 키**(`LS_KEY_DASHBOARD_CONFLICT`, board 플래그와 분리). 단 version 기반 자연 충돌이 우선, 플래그는 E2E 강제용.
- ★`handlers.ts` 수정 전 진행 중 다른 FR이 동시 수정하는지 확인(parallel-fr-overlapping-frontend-infra-collision). 자기 라인만 stage.

**검증**: `pnpm test dashboard-handlers`.

### Task 4. use-dashboards.ts — TanStack Query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-dashboards.ts`, `apps/web/src/hooks/use-dashboards.test.tsx`]
- depends-on: [2, 3]

**RED**: `use-dashboards.test.tsx` — `useDashboards`(목록 queryKey), `useDashboard(id)`(단건), `useCreateDashboard`/`useUpdateDashboard`/`useDeleteDashboard` mutation + onSuccess 캐시 무효화(목록+단건).

**GREEN**: 선례 `use-boards.ts`. **depends-on [2,3]** — MSW 핸들러(Task 3) 완료 후라 통합 테스트 인라인 mock 불요.
- ★OCC mutation 방식 = **invalidate-only**(`useUpdateDashboard` onSuccess에서 단건+목록 `invalidateQueries`, setQueryData 부분머지 금지 ← mutation-setquerydata-partial-response-flicker). 낙관적 업데이트(onMutate) **사용 안 함**(spec: 자동 덮어쓰기 금지). version은 invalidate 후 refetch로 갱신.

**검증**: `pnpm test use-dashboards`.

### Task 5. i18n dashboard-labels + 권한 판정 유틸

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/dashboard-labels.ts`, `apps/web/src/i18n/dashboard-labels.test.ts`, `apps/web/src/lib/dashboard-permission.ts`, `apps/web/src/lib/dashboard-permission.test.ts`]
- depends-on: []

**RED**:
- `dashboard-labels.test.ts` — 라벨 키 존재 + **콜론 종결 금지**(board-labels.test.ts 패턴). ★placeholder 본문 문구는 **사용자 노출 중립 한국어**("위젯 자리" 등), "FR-DB-02"·내부 FR 용어 노출 금지(DESIGN §10).
- `dashboard-permission.test.ts` — `canEditDashboard(dashboard, currentUserId): boolean` = `ownerId === currentUserId`(비소유자 false, currentUserId null→false)

**GREEN**: `i18n/dashboard-labels.ts`(BC별 분리) + `lib/dashboard-permission.ts`.
- ★`currentUserId` 출처 = `useAuthUser()`/auth store의 `whoami userId`(상세 페이지에서 주입). 순수 함수는 id만 받음(테스트 용이).

**검증**: `pnpm test dashboard-labels dashboard-permission`.

### Task 6. DashboardForm — 생성/편집 공용 폼 (메타 + visibility + 공유)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/DashboardForm.tsx`, `apps/web/src/components/dashboard/DashboardForm.test.tsx`]
- depends-on: [5]

**RED**: `DashboardForm.test.tsx` — name 검증(빈/201자 차단+인라인 에러 EC8), description, visibility select(PRIVATE/TEAM/ORG), **TEAM 선택 시에만 공유 사용자 입력 활성**(EC7 허용), 제출 페이로드 형태(생성 vs patch 3-state), 제출중 버튼 disabled(isPending).

**GREEN**: 기존 폼 패턴(★`CreateBoardForm` 차용 — 제출중 "저장 중..." disabled). 공유 사용자 선택은 기존 `use-users.ts`/`use-user-directory.ts` 재사용(없으면 최소 입력, 과설계 금지). labels(Task 5) import.

**검증**: `pnpm test DashboardForm`.

### Task 7. routes/dashboards.tsx — 목록 + 생성 페이지

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/dashboards.tsx`, `apps/web/src/routes/__tests__/dashboards.test.tsx`]
- depends-on: [4, 6]

**RED**: `dashboards.test.tsx` — 목록 카드(이름·설명·visibility 배지·소유여부), 빈 상태+생성 CTA(EC1), 로딩(`isLoading`)·에러(`isError`) 분기, "대시보드 만들기"→DashboardForm 제출→`/dashboards/$id` 네비.

**GREEN**: `DashboardsRouteAdapter`(useDashboards) + `DashboardsListPage`(props, 라우터 비의존). 선례 `routes/projects.$projectKey.board.tsx` RouteAdapter+Page 분리.
- ★디자인(리뷰 반영): 빈 상태 = **`CreateBoardForm` 구조 차용**(제목 "아직 대시보드가 없습니다" + 맥락문 + "대시보드 만들기" 1차 버튼). 로딩 = **board식 인라인 `Skeleton`**(공유 컴포넌트 없음, 동일 헬퍼). 에러 = "불러오지 못했습니다 + 다시 시도".
- ★정보 계층: 이름 `text-lg font-semibold`, 설명 `text-sm text-muted-foreground` truncate, visibility 배지는 우상단. **shadcn `Badge` 컴포넌트 부재** → DESIGN 토큰만으로 인라인(`muted`+텍스트 라벨, 새 색 토큰 도입 금지). 소유 여부 표시.

**검증**: `pnpm test routes/__tests__/dashboards`.

### Task 8. routes/dashboards.$dashboardId.tsx — 상세 그리드 (핵심)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/dashboards.$dashboardId.tsx`, `apps/web/src/components/dashboard/DashboardGrid.tsx`, `apps/web/src/components/dashboard/DashboardTile.tsx`, `apps/web/src/routes/__tests__/dashboards.$dashboardId.test.tsx`, `apps/web/src/components/dashboard/DashboardGrid.test.tsx`]
- depends-on: [1, 4, 5, 6]

**RED**:
- `DashboardGrid.test.tsx` — react-grid-layout **v1 WidthProvider** smoke(★jsdom = `WidthProvider`가 width 측정 → 컨테이너 width 0이라 타일 미렌더 위험. recharts식 `vi.mock('react-grid-layout')`로 WidthProvider/GridLayout stub 또는 width 명시 주입. mock 없이 가짜그린 금지). onLayoutChange→tile 변환(순수 로직 단위).
- `dashboards.$dashboardId.test.tsx` — 타일 추가/삭제 로컬 state, 제목 인라인 편집(S4), 저장(PATCH layout+version, dirty 표시 S3, 저장중 버튼 disabled), **권한 게이팅**(비소유자 읽기전용=편집UI 숨김 EC3/FR-9), OCC 409→토스트+로컬보존(EC4/S8, **onError에서 invalidate 금지**=로컬 tiles state 덮임 방지), 손상 layout→빈 폴백(EC6), 404(EC5), 로딩/에러 분기.

**GREEN**: `DashboardDetailRouteAdapter`(useParams + whoami userId 주입)+`DashboardDetailPage`(props). `DashboardGrid`(**react-grid-layout@1.5 `WidthProvider(GridLayout)`, 단일 12컬럼**)+`DashboardTile`.
- ★디자인(리뷰 반영):
  - placeholder 타일 = 제목 + lucide 아이콘 + **중립 한국어 문구**(labels Task 5, "FR-DB-02" 노출 금지) + `rounded-lg border shadow-sm`(board 카드 시각 일관). "미완성 회색 박스" 회피.
  - 빈 그리드(EC2) = 점선 테두리 영역 + 중앙 "위젯 추가" 1차 버튼(클릭가능 빈 캔버스).
  - 편집 affordance: 소유자 = `isDraggable/isResizable=true` + `cursor-grab`(BoardCard 선례) + 리사이즈 핸들 노출. 비소유자 = `isDraggable/isResizable=false` + `cursor-default` + 핸들 숨김.
  - 반응형 = **단일 12컬럼 + 모바일 `overflow-x-auto` 가로스크롤**(★Maxi 결정, board 선례). ResponsiveGridLayout 미사용.
  - 접근성: 아이콘 버튼 `aria-label` + 44px 터치타깃. **키보드로 타일 추가/삭제/제목편집/저장 완전 동작 의무**, 드래그/리사이즈만 마우스 우선(키보드 대안 없음을 한계로 명시).
- 권한=`canEditDashboard`(Task 5). 설정 편집=DashboardForm(Task 6). 실 드래그/리사이즈 시각검증은 E2E 위임(NFR-2).

**검증**: `pnpm test dashboards.\$dashboardId DashboardGrid`.

### Task 9. router.ts 등록 + Header 네비

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/components/Header.tsx`, `apps/web/src/components/Header.test.tsx`]
- depends-on: [7, 8]

**RED**: `Header.test.tsx` — "대시보드" 링크(`/dashboards`) 노출. (router는 router.test.tsx 있으면 라우트 등록 확인)

**GREEN**: `createRoute` 2개(`/dashboards`, `/dashboards/$dashboardId`, requireAuth+requireAuthAndPasswordChanged 가드) + `addChildren` 추가. Header 메인 네비 링크(ADMIN_LINKS 옆). 기존 `/dashboard`(환영) 보존.

**검증**: `pnpm test Header router` + `pnpm typecheck`.

### Task 10. E2E — dashboard.spec.ts

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/dashboard.spec.ts`]
- depends-on: [9]

**RED→GREEN**: 선례 `e2e/board-kanban.spec.ts`(PointerSensor). 시나리오 —
- S1 목록 표시, S2 생성→상세 이동, S3 타일 추가+드래그+리사이즈+저장(mouse.move/down/move>임계/move/up — RGL 드래그 핸들 기준), S6 삭제, S7 비소유자 읽기전용(fixture userId 정합 ★e2e-fixture-whoami-userid-alignment), S8 OCC 409 토스트.
- ★접근성 경로 1개: 키보드로 타일 추가→제목편집→저장(드래그 없이 완전 동작 검증, 디자인 리뷰 반영).
- MSW 공유 store 시드(reload 금지=SPA 내 이동 ★worktree-stale-base-rebase-and-e2e-msw-traps). 회귀: `/dashboard` 환영 경로 + 네비.

**검증**: `pnpm test:e2e dashboard` + 기존 E2E 회귀 0.

## Plan 메타

- task 수: 10 (frontend 9 + qa 1)
- depends 그래프(리뷰 반영 후) → 예상 wave 5개.
  - Wave 1 (`[]`): Task 1·2·3·5 (4 병렬, 파일 무충돌)
  - Wave 2: Task 4(dep 2,3)·6(dep 5)
  - Wave 3: Task 7(dep 4,6)·8(dep 1,4,5,6) (routes 파일 분리=무충돌)
  - Wave 4: Task 9(dep 7,8) — router.ts+Header 통합(공유파일이라 직렬)
  - Wave 5: Task 10(dep 9) — E2E (qa-engineer)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: pnpm typecheck(tsconfig.app) + lint + vitest + playwright
- ★주의: (1) react-grid-layout React 19 호환 Task 1 첫 검증, (2) jsdom width0 → 그리드 smoke+E2E, (3) worktree node_modules 새 의존성 설치(부분설치 깨짐 주의 worktree-node-modules-partial-install), (4) MSW stateful 공유 store, (5) E2E fixture userId 정합.

## 리뷰 결과

타입=ui이나 핵심 리스크가 엔지니어링이라, 디자인 관점 + 엔지니어링 관점 **두 집중 리뷰 병렬**(plan-design-review 풀 목업 플로우는 placeholder 타일에 과함, bts-review-plan-autoplan-overkill 정신).

### 디자인 리뷰 (2026-06-23) — BLOCKER 없음, CONCERN 7건 (전부 plan 반영 완료)
- ✅ 스코프(placeholder·기존패턴 재사용) 건전, self-review 과설계 배제(더미위젯·자동저장·모드토글) 타당
- 반영: 빈 상태=CreateBoardForm 차용(Task7), 로딩=board Skeleton·에러 분기(Task7/8), 정보계층+Badge 부재 인라인(Task7), 편집 affordance(cursor-grab/핸들 소유자만, Task8), placeholder "FR-DB-02" 내부용어 노출 제거→중립 한국어(Task5/8 ★카피결함), 반응형 단일12컬럼+overflow-x-auto(Task8 Maxi결정), 접근성 키보드 완전동작 의무+44px+aria-label(Task8/10)

### 엔지니어링 리뷰 (2026-06-23) — BLOCKER 2건 (해소) + CONCERN 6건 (반영 완료)
- 🔴 BLOCKER-1/2: react-grid-layout v2.2.3 API 불일치(WidthProvider→/legacy, useContainerWidth jsdom mounted=false 가짜그린) → **Maxi 결정 v1.5 고정 + WidthProvider**(Task1) + jsdom vi.mock 명시(Task8). **해소**.
- ✅ CONCERN 반영: Task2 선례 notification-policies.ts 단독화(boards.ts 제외=CSRF drift 차단 P0) + DataResponse 래퍼/Page 내부구조/Instant 직렬화 실측. Task4 depends-on[2,3]+invalidate-only(플리커 회피). Task8 depends-on[1,4,5,6]. Task3 fixtures ownerId↔alice userId 동기화 + handlers.ts 충돌주의 + OCC 플래그 고유키. Task8 OCC onError invalidate 금지.
- NITPICK 3건(OCC onError·MSW 플래그키·Page 구조) 모두 해당 Task에 인라인 반영.

**BLOCKER 잔여: 없음.** 두 리뷰의 모든 BLOCKER 해소 + CONCERN 13건 plan 반영 완료. 게이트1 진행 가능.
