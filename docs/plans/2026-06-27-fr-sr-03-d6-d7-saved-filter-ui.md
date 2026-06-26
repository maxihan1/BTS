# FR-SR-03 D6/D7 — 저장 필터 저장/공유 모달 + 별표(FILTER) UI

> slug: fr-sr-03-d6-d7-saved-filter-ui
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7 E2E)
> primary_bc: search-export-import
> 생성: 2026-06-27

## Brief

사용자 원문: "fr-sr-03 d6, d7 진행해줘 백엔드 구현이 잘되어있는지도 확인하고"

FR-SR-03 "필터 저장 및 공유"의 D6(프론트 UI) + D7(E2E). D1~D5(백엔드 PR1 #191 + PR2 #193)는 완료.
백엔드 검증 완료 — code-reviewer 적대적 검증에서 BLOCKER 3종(B1 단건가시성/B2 shares적재N+1/B3 단일술어) 전부
코드 반영 + 가짜그린 방지장치 확인. NIT 1건(POST 응답 dedupe前 echo, GET 정본이라 무해)만 잔존.

저장 필터 = 단일 AQL 문자열(aqlQuery) + projectKey + shares[] → /search AQL 검색 페이지에 부착.

### Maxi 결정 (게이트 전 사전 확정, AskUserQuestion 2026-06-27)
- Q1. UI 노출 범위 = **/search 페이지 통합만** (전용 /filters 관리 페이지 없음).
- Q2. 공유 대상 picker = **AUTHENTICATED + PROJECT(이 필터의 프로젝트)만**. GROUP 보류
       (그룹 목록 API admin전용 + 프로젝트 목록 API 부재 + group UUID 직접입력 UX 불량).
- Q3. 별표(FILTER 즐겨찾기) = **이번에 활성화** (FR-UX-02 백엔드 FILTER 이미 지원, 프론트만 배선).

### ★ 핵심 함의 (구현 시 필수)
- 공유는 replace-all(전체 교체). 편집 시 PROJECT/AUTHENTICATED만 보내면 백엔드에 남은 GROUP share가 지워짐
  → 공유 모달은 응답에 온 미편집(GROUP 등) share를 보존해 재전송해야 함.

## 도메인 정리

> 신규 도메인 용어/ADR **0건**. grill-with-docs 풀 세션 스킵(프론트 연속 작업, BC·ADR 기확립).

- **BC**: search-export-import (PR1 #191 부트스트랩 + PR2 #193 공유 확장). 프론트는 이 BC의 view layer 소비만.
- **유비쿼터스 언어** (기확립): "저장된 필터(SavedFilter)" = 이름붙은 AQL 쿼리 + 실행 projectKey. "공유(Share)" = 대상지정 PROJECT/GROUP/AUTHENTICATED. "가시성 4경로". 프론트 신규 용어 도입 없음.
- **관련 ADR**: `docs/decisions/2026-06-26-fr-sr-03-saved-filters.md` (PR1/PR2 설계 정본). 프론트 PR 신규 ADR 불필요 — view layer 결정만.
- **BC 격리**: 순수 프론트(apps/web). 백엔드 변경 0 (Maxi Q2 결정으로 GROUP picker 백엔드 미추가). cross-BC 없음.
- **재사용 자산** (frontend): FR-UX-02 favorites(FILTER 타입, 백엔드 기지원) · AQL 검색 클라이언트(`api/search.ts`) · Radix Dialog · TanStack Router code-based adapter 패턴.

## 스펙

전체 스펙. [docs/specs/2026-06-27-fr-sr-03-d6-d7-saved-filter-ui.md](../specs/2026-06-27-fr-sr-03-d6-d7-saved-filter-ui.md)

핵심 5줄.
- `/search` 검색바에 "저장"(현재 AQL+projectKey) + "필터" 드롭다운(내 필터 GET `/filters` · 공유받은 GET `/shared`).
- 불러오기 = `/search?filterId=<id>` 딥링크 → SearchPage가 GET `/{id}` 해석해 AQL·projectKey 세팅 후 실행(전용 실행 엔드포인트 미사용).
- 공유 모달 = AUTHENTICATED + PROJECT(자기 projectKey) 토글, **replace-all이라 미편집 GROUP share 보존** 필수(EC4).
- 별표 = `FAVORITE_TARGET_TYPES`에 FILTER 추가 + FavoriteButton 재사용 + Header ⭐ FILTER 그룹(이름은 GET `/{id}` 조회, 404 숨김).
- 백엔드 변경 0. Zod는 SavedFilterDtos.kt 1:1(`{data:}` 래퍼 없음, null 명시 → `.nullable()`).

## Brainstorming Check

✅ 통과 (1 iteration, 적대적 자체 검토). Maxi 결정 gap 0. 세부는 plan 흡수(저장쿼리 의미·빈상태·길이검증·MSW store·E2E 회귀0).

## Plan

> 8 task / 순수 프론트(apps/web). TDD red→green→refactor 강제(test 커밋 먼저).
> agent: frontend-engineer(T1~T7) + qa-engineer(T8 E2E). 백엔드 변경 0.
> 검증: `pnpm typecheck`(tsconfig.app, 메모리 [[ci-typecheck-tsconfig-app-vs-local]]) + `pnpm lint` + `pnpm test` + `pnpm test:e2e`.

### Task 1. `api/saved-filters.ts` — Zod 계약 + API 함수 + React Query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/saved-filters.ts`, `apps/web/src/api/saved-filters.test.ts`, `apps/web/src/i18n/saved-filter-labels.ts`, `apps/web/src/i18n/ko.test.ts`]
- depends-on: []

**RED**. `saved-filters.test.ts`(인라인 MSW http 핸들러) — (a) `fetchOwnedFilters` GET `/api/v1/filters`가 **bare 배열** 반환(**`{data:}`·Page 래퍼 둘 다 없음** 단언, C6), (b) `fetchSharedFilters(0,50)` GET `/shared?page&size`도 **bare 배열**(소유와 동일 `z.array(savedFilterSchema)`, totalElements 없음 C6), (c) `fetchFilter(id)` GET `/{id}`, (d) `createFilter`(POST)/`updateFilter`(PUT)/`deleteFilter`(204), (e) Zod 거부 — `targetId:null`/`createdAt:null` **명시 포함 통과**(`.nullable()`, `.default()` 아님), (f) shareType `'PROJECT'/'GROUP'/'AUTHENTICATED'` enum, 그 외 거부. + `ko.test.ts`에 `saved-filter-labels` 콜론종결 금지 describe 추가(C4, glob 아닌 파일별 명시 import). 모듈 부재로 실패.

**GREEN**. `SavedFilterDtos.kt` 1:1 Zod(§4 스펙) + 함수. `apiGet`(GET)·`apiPost`(POST=create). **PUT/DELETE는 헬퍼 없음** → favorites 선례처럼 `apiFetch({method})` + 수동 `res.ok`/`schema.parse`(PUT)·204(DELETE) 인라인(N1). spec FR-9 에러코드는 `SAVED_FILTER_ERROR_CODES` 상수로 명시(N2, [[frontend-api-convention-per-bc]] error-key drift 차단). labels 파일 생성(콜론 종결 금지).

**REFACTOR**. queryKey 헬퍼(`savedFiltersKey`) + KDoc("backend 정본 SavedFilterDtos.kt, 변경 시 동반"). 메모리 [[frontend-zod-backend-dto-contract-gap]] — invent 금지, grep로 백엔드 필드 검증.

**검증**. `pnpm --filter web test saved-filters` + `pnpm --filter web test ko`

---

### Task 2. 별표 FILTER 타입 활성화 — favorites 상수 + FavoritesMenu FILTER 그룹

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/favorites.ts`, `apps/web/src/components/favorite/FavoritesMenu.tsx`, `apps/web/src/components/favorite/FavoritesMenu.test.tsx`, `apps/web/src/api/favorites.test.ts`]
- depends-on: [1]

**RED**. (a) `FavoritesMenu.test.tsx` — FILTER 즐겨찾기가 "필터" 그룹에 **이름과 함께**(GET `/{id}` 조회) 렌더 + 클릭 시 `/search?filterId=<id>` Link, (b) 삭제된 필터(404) 항목 **숨김** + 해당 그룹 전항목 404면 **그룹 헤더도 비표시**(플리커/빈그룹 방지 C3), (c) 기존 ISSUE/DASHBOARD/PROJECT 그룹 회귀 0. 실패.

**GREEN**. `FAVORITE_TARGET_TYPES`에 `FILTER:'FILTER'` 추가(Zod enum은 이미 `'FILTER'` 허용 — line 50). **현 FavoritesMenu는 동기 reduce 렌더(라벨=정적 targetId)** 이므로(FavoritesMenu.tsx:139/33-49/73-75) FILTER는 TYPE_META 추가만으론 부족 → **항목별 `useQuery(fetchFilter)` 전용 하위 컴포넌트 `FilterFavoriteItem`**(이름 비동기 해소 + 404 hide)로 구현(C3). 404/로딩 중 그룹 헤더 깜빡임 차단.

**REFACTOR**. ★ C3 교정: 유일한 enum 단언 `favorites.test.ts:353-359`는 **존재만**(exhaustive/count 아님)이라 FILTER 추가로 안 깨짐 → T2는 "깨진 단언 수정"이 아니라 **FILTER 신규 단언 추가**. `FavoriteButton.tsx:19,22` stale 주석(ISSUE/DASHBOARD/PROJECT)도 FILTER 포함으로 동반 갱신(N4).

**검증**. `pnpm --filter web test FavoritesMenu favorites`

---

### Task 3. SaveFilterDialog — 생성/편집 모달 (이름·AQL, 409/OCC 처리)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/search/SaveFilterDialog.tsx`, `apps/web/src/components/search/SaveFilterDialog.test.tsx`]
- depends-on: [1]

**RED**. `SaveFilterDialog.test.tsx` — (a) 생성 모드: name 입력 → `createFilter({name,aqlQuery,projectKey})` 호출, (b) 편집 모드: 기존 값 프리필(props `filter`) + `updateFilter(id,{name,aqlQuery,version})`, (c) 빈 name → 제출 비활성(EC1), name>100 클라 차단, (d) 409 `SEARCH_FILTER_NAME_CONFLICT` → 인라인 "이미 사용 중인 이름"(EC2), (e) 409 `SEARCH_FILTER_CONFLICT` → "다른 곳에서 수정됨" + `onConflict` 콜백(EC3). 실패.

**GREEN**. Radix `DialogPrimitive`(ResolutionPickerModal 선례). 편집 프리필은 **key prop 재마운트**로 stale 방지(메모리 [[react-usestate-stale-key-prop]]). submitError는 **이 컴포넌트 소유**(메모리 [[dialog-submiterror-ownership-dead-path]] — 부모 미전달 dead-path 회피).

**REFACTOR**. errorCode→메시지 매핑 헬퍼 + labels 사용. Zod v4 required 패턴 주의(메모리 [[fr-tm-01-d6-d7-done]] — vitest통과 tsc실패 회피).

**검증**. `pnpm --filter web test SaveFilterDialog`

---

### Task 4. ShareFilterDialog — AUTHENTICATED+PROJECT 토글 + GROUP 보존

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/search/ShareFilterDialog.tsx`, `apps/web/src/components/search/ShareFilterDialog.test.tsx`]
- depends-on: [1]

**RED**. `ShareFilterDialog.test.tsx` — (a) `filter.shares`에서 AUTHENTICATED·PROJECT(자기 projectKey) → 토글 초기상태 바인딩, (b) 토글 변경 후 저장 → `updateFilter(id, payload)` 호출이되 **★ B2: payload = `{name: filter.name, aqlQuery: filter.aqlQuery, version: filter.version, shares}`** — name/aqlQuery 포함을 **명시 단언**(공유만 바꿔도 백엔드가 name/aqlQuery 필수 강제, SavedFilterController.kt:131-132 누락 시 400), (c) **★ EC4 핵심**: 로드된 shares에 GROUP(또는 타 projectKey PROJECT) 항목이 있으면 제출 배열에 **그대로 포함**(미보존 시 데이터 손실 회귀) — 명시 단언, (d) 둘 다 off → shares `[]`(공유 제거). 실패.

**GREEN**. 토글 2개 + 보존 share 분리 보관 후 제출 시 병합. PUT 바디에 name/aqlQuery/version 동반(B2). PROJECT 토글 targetId=`filter.projectKey`.

**REFACTOR**. 보존 로직 순수 함수(`mergeShares(toggles, preserved)`)로 추출 + 단위 커버. 메모리 [[frontend-zod-backend-dto-contract-gap]] shareType 대문자.

**검증**. `pnpm --filter web test ShareFilterDialog`

---

### Task 5. SavedFilterMenu — 드롭다운(내 필터/공유받은/별표/소유자 액션)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/search/SavedFilterMenu.tsx`, `apps/web/src/components/search/SavedFilterMenu.test.tsx`]
- depends-on: [1, 3, 4]

**RED**. `SavedFilterMenu.test.tsx` — (a) "내 필터"(useOwned) + "공유받은 필터"(useShared) 두 섹션 렌더, (b) 항목 클릭 → `/search?filterId=<id>` 네비게이션, (c) 소유 항목만 편집/공유/삭제 액션 노출, **공유받은(isOwner=false) 항목은 별표만**(EC5), (d) ★ FavoriteButton(targetType FILTER) 재사용, (e) 빈 목록 "저장된 필터 없음", (f) 삭제 확인 → `deleteFilter`. 실패.

**GREEN**. Radix DropdownMenu + 두 useQuery + SaveFilterDialog(편집)·ShareFilterDialog 오픈 상태 관리. 삭제 confirm.

**REFACTOR**. mutation 후 `['saved-filters']` invalidate. 액션 게이팅 `isOwner` 단일 출처.

**검증**. `pnpm --filter web test SavedFilterMenu`

---

### Task 6. SearchPage 통합 — "저장" 버튼 + 드롭다운 + `filterId` 딥링크 로드

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/search.tsx`, `apps/web/src/router.ts`, `apps/web/src/routes/search.test.tsx`]
- depends-on: [1, 5]
- ★ `search.test.tsx`는 **기존 파일(수정)** — 기존 케이스 회귀 0 유지(C2, 신규 RED처럼 다루지 말 것).

**RED**. `search.test.tsx` — (a) 검색바에 "저장" 버튼(쿼리 비면 비활성, EC1) + SavedFilterMenu 마운트, (b) **`/search?filterId=<id>` 진입 → `fetchFilter` 해석 → q·projectKey 세팅 후 자동 실행 → 실제 결과 목록이 렌더된다까지 단언**(C1 — "q 세팅"만 단언하면 vacuous green; SearchPage `submittedQuery`는 useState(q) 1회 초기화라 stale), (c) filterId 로드 실패(404 **및 비-UUID 400 등 모든 에러**, N3) → 토스트 + 일반 검색 유지(EC6), (d) 기존 검색 흐름 회귀 0. 실패.

**GREEN**. `validateSearch`에 `filterId?` 추가(router.ts code-based adapter 패턴 — `.ts` JSX 제약은 adapter export로 회피). **저장 버튼·SavedFilterMenu는 SearchRouteAdapter에 마운트**(라우터 의존 — SearchPage props-only 보존, C2). filterId 자동실행 메커니즘(C1): adapter가 `fetchFilter` 성공 시 `navigate`로 **filterId를 드롭하고 q+projectKey만 남김** + SearchPage에 `key`(예 `${projectKey}:${submittedQuery}`) 부여해 **재마운트**(submittedQuery stale 차단). 무한루프 차단 — filterId는 1회 해소 후 URL에서 제거.

**REFACTOR**. 로드 effect cleanup(stale 방지). 에러 처리 일반화(404 가정 금지, 모든 에러→토스트, N3).

**검증**. `pnpm --filter web test search` + `pnpm --filter web typecheck`

---

### Task 7. MSW 저장필터 핸들러 — stateful store(소유 + 가시성)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/saved-filter-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]
- ★ B1: 등록 정본은 **평탄 파일 `mocks/handlers.ts`**(favoriteHandlers spread 선례 handlers.ts:3/58-106). `handlers/index.ts`는 **존재하지 않음** — 만들면 orphan. `handlers.ts` 배열에 `...savedFilterHandlers` spread 추가 필수(미추가 시 T8 브라우저 워커 핸들러 누락 = 가짜그린).

**RED/GREEN**(인프라 — 기존 favorite-handlers.ts 패턴). userId별 소유 store + 공유 가시성 시뮬레이션(소유 아님 + AUTHENTICATED/PROJECT 매칭). `resetSavedFilterStore()`·`seedSavedFilters(userId, items)`·토큰→userId 파싱. POST/PUT replace-all 반영. **★ B2: PUT 핸들러가 name/aqlQuery blank면 400**(백엔드 SavedFilterController.kt:131-132 동형 — 미강제 시 계약 가짜그린). 핸들러 배열 export + **handlers.ts에 spread 추가**.

**REFACTOR**. 파생동작은 **브라우저 시드 가능 공유 store**에서 읽기(메모리 [[msw-derived-behavior-shared-store-e2e]] — 가짜그린 회피). ★ C5: T8 alice→bob 사용자 전환 시드용 **브라우저 노출 메커니즘**(window 글로벌 또는 addInitScript, [[e2e-msw-scenario-toggle-localstorage-flag]]) — node export `seedSavedFilters`는 브라우저 워커에 직접 안 닿음. 가시성 판정 = 소유자 응답엔 전체 shares, 비소유엔 매칭 share만(C2 충실).

**검증**. `pnpm --filter web test saved-filters`(T1 테스트가 공유 store로도 통과)

---

### Task 8. E2E — 저장/불러오기/공유/별표/비소유 게이팅/OCC

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/saved-filters.spec.ts`, `apps/web/src/mocks/saved-filter-handlers.ts`]
- depends-on: [6, 7]

**RED/GREEN**. Playwright(MSW) — (1) 저장 → "내 필터" 목록 표시 → 클릭 → 검색 실행(결과), (2) 공유(PROJECT/AUTHENTICATED) → **공유받은 사용자 세션에서 "공유받은 필터"에 가시**(SPA 내 이동, reload 금지 — 메모리 [[worktree-stale-base-rebase-and-e2e-msw-traps]]). ★ C5: alice→bob 가시성 검증은 T7의 **브라우저 노출 시드**로 store를 미리 세팅 + reload 없는 사용자 전환(토큰 스왑/직접 시드, 기존 favorites.spec.ts는 단일사용자라 신패턴 — pushState SPA 이동 선례 참고). (3) 별표 토글 → Header ⭐ "필터" 그룹 노출, (4) 비소유 항목 편집버튼 부재, (5) OCC 충돌 안내, (6) 삭제. getByRole `exact`/컨테이너 한정(메모리 [[playwright-getbyrole-exact-strict-mode]]·[[ui-pr-defer-e2e-regression-latent]]).

**REFACTOR**. 시드 헬퍼 + ★ **기존 search/favorites E2E 함께 실행해 회귀 0 확인**. orphan vite 정리(메모리 [[e2e-orphan-vite-after-worktree-remove]]).

**검증**. `pnpm --filter web test:e2e saved-filters` + 기존 search/favorites E2E 재실행.

## Plan 메타

- task 수: 8 (각 TDD 사이클)
- 모듈: apps/web 단일(프론트). 백엔드 변경 0.
- 예상 wave: 5 (W1: T1 / W2: T2·T3·T4·T7 병렬 / W3: T5 / W4: T6 / W5: T8). 프론트는 Gradle 모듈 직렬화 무관이라 W2 4-병렬 가능.
- TDD 강제: yes (test: 커밋 먼저)
- 추가 검증: typecheck(tsconfig.app) + lint + vitest + playwright(qa). `pnpm verify` 최종.
- 핵심 리스크: EC4 GROUP 보존(T4) · 별표 enum 추가 회귀(T2) · 기존 E2E 회귀(T8) · filterId 딥링크 라우터 등록(T6).

## 리뷰 결과

### eng 적대적 독립 리뷰 (code-reviewer, 2026-06-27)

UI plan이나 디자인은 Maxi 목업 승인으로 확정 → 실질 리스크인 eng/계약/회귀에 집중(메모리 [[bts-review-plan-autoplan-overkill]]). plan을 실제 코드·백엔드 계약·스펙과 파일:라인 대조.

**🔴 BLOCKER 2건 — 전부 plan 수정으로 해소**.
- **B1** T7 MSW 등록 경로 오류(`handlers/index.ts` 부재, 정본은 평탄 `handlers.ts`) → 핸들러 미배선 E2E 가짜그린. → **해소**: T7 files `handlers.ts`로 교정 + `...savedFilterHandlers` spread 명시.
- **B2** 공유 PUT이 `name`/`aqlQuery` 필수(SavedFilterController.kt:131-132) — shares만 보내면 400 + MSW 미강제 시 계약 가짜그린. → **해소**: T4 payload `{name,aqlQuery,version,shares}` 명시 + RED 단언 + T7 MSW PUT blank 400.

**🟡 CONCERN 6건 — 전부 반영**.
- **C1** filterId 딥링크 자동실행 ↔ `submittedQuery` stale(useState 1회 초기화) 충돌 → S2/S4 silent fail. → T6: navigate로 filterId 드롭 + SearchPage `key` 재마운트, RED는 **결과 렌더까지** 단언.
- **C2** SearchPage는 props-only(라우터 비의존) — 메뉴를 adapter에 마운트, `search.test.tsx`는 기존 파일 회귀 0.
- **C3** FavoritesMenu는 동기 reduce 렌더 → FILTER는 전용 비동기 항목 컴포넌트(useQuery+404 hide) 필요. enum 단언은 존재만이라 회귀 낮음(신규 단언 추가).
- **C4** 신규 labels 콜론종결 가드 미배정 → T1에 `ko.test.ts` 추가.
- **C5** T8 alice→bob reload 없는 전환 신패턴 → T7 브라우저 노출 시드 메커니즘 명시.
- **C6** `/shared`·소유 목록 모두 **bare 배열**(Page 래퍼 아님) → T1 `z.array` 재사용 못박기.

**🔵 NIT 4건 반영**. N1(PUT/DELETE 헬퍼 없음→apiFetch 인라인) · N2(SAVED_FILTER 에러코드 상수) · N3(비-UUID filterId 400→에러 일반화) · N4(FavoriteButton stale 주석).

**🟢 PASS**. T1 Zod 1:1 정합(`@JsonInclude` 부재 nullable 판단 정확)·`{data:}` 래퍼 구분·shareType enum·EC4 GROUP 보존 성립(소유자=전체 shares)·에러코드 전수 일치·딥링크 설계 근거·wave 병렬 무충돌·favorites 인프라 사전충족('FILTER' 이미 VALID/Zod 허용).

**총평**. BLOCKER 2 + CONCERN 6 + NIT 4 전부 plan 반영 완료 → 구현 진입 가능. 가장 위험한 가짜그린 벡터는 B2(MSW name/aqlQuery 미강제)·C1(RED가 결과렌더 대신 q세팅만) → 두 RED 단언 강도 집중 점검.
