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
- files: [`apps/web/src/api/saved-filters.ts`, `apps/web/src/api/saved-filters.test.ts`, `apps/web/src/i18n/saved-filter-labels.ts`]
- depends-on: []

**RED**. `saved-filters.test.ts`(인라인 MSW http 핸들러) — (a) `fetchOwnedFilters` GET `/api/v1/filters`가 배열 반환(**`{data:}` 래퍼 없음** 단언), (b) `fetchSharedFilters(0,50)` GET `/shared?page&size`, (c) `fetchFilter(id)` GET `/{id}`, (d) `createFilter`/`updateFilter`/`deleteFilter`(204), (e) Zod 거부 — `targetId:null`/`createdAt:null` **명시 포함 통과**(`.nullable()`, `.default()` 아님), (f) shareType `'PROJECT'/'GROUP'/'AUTHENTICATED'` enum, 그 외 거부. 모듈 부재로 실패.

**GREEN**. `SavedFilterDtos.kt` 1:1 Zod(§4 스펙) + 함수. `apiGet`/`apiPost`(POST/PUT은 `apiFetch({method})` favorites 선례) 재사용. labels 파일 생성(콜론 종결 금지).

**REFACTOR**. queryKey 헬퍼(`savedFiltersKey`) + KDoc("backend 정본 SavedFilterDtos.kt, 변경 시 동반"). 메모리 [[frontend-zod-backend-dto-contract-gap]] — invent 금지, grep로 백엔드 필드 검증.

**검증**. `pnpm --filter web test saved-filters`

---

### Task 2. 별표 FILTER 타입 활성화 — favorites 상수 + FavoritesMenu FILTER 그룹

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/favorites.ts`, `apps/web/src/components/favorite/FavoritesMenu.tsx`, `apps/web/src/components/favorite/FavoritesMenu.test.tsx`, `apps/web/src/api/favorites.test.ts`]
- depends-on: [1]

**RED**. (a) `FavoritesMenu.test.tsx` — FILTER 즐겨찾기가 "필터" 그룹에 **이름과 함께**(GET `/{id}` 조회) 렌더 + 클릭 시 `/search?filterId=<id>` Link, (b) 삭제된 필터(404) 항목 **숨김**, (c) 기존 ISSUE/DASHBOARD/PROJECT 그룹 회귀 0. 실패.

**GREEN**. `FAVORITE_TARGET_TYPES`에 `FILTER:'FILTER'` 추가(Zod enum은 이미 `'FILTER'` 허용 — line 50). `FavoritesMenu` TYPE_META에 FILTER 항목(label/group/toPath). 이름은 `fetchFilter(id)`(React Query 캐시, 소수 N) 조회, 404는 `enabled`/에러로 숨김.

**REFACTOR**. ★ **기존 favorites 테스트의 enum 망라/카운트 단언 grep 갱신**(메모리 [[enum-add-breaks-crossmodule-count-guard]]·[[zod-schema-strengthen-inline-mock-fanout]] — FILTER 추가로 깨질 수 있음). 정적 메타 테이블 일관.

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

**RED**. `ShareFilterDialog.test.tsx` — (a) `filter.shares`에서 AUTHENTICATED·PROJECT(자기 projectKey) → 토글 초기상태 바인딩, (b) 토글 변경 후 저장 → `updateFilter(id,{...,version,shares})` 호출, (c) **★ EC4 핵심**: 로드된 shares에 GROUP(또는 타 projectKey PROJECT) 항목이 있으면 제출 배열에 **그대로 포함**(미보존 시 데이터 손실 회귀) — 명시 단언, (d) 둘 다 off → shares `[]`(공유 제거). 실패.

**GREEN**. 토글 2개 + 보존 share 분리 보관 후 제출 시 병합. PROJECT 토글 targetId=`filter.projectKey`. version 동반.

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

**RED**. `search.test.tsx` — (a) 검색바에 "저장" 버튼(쿼리 비면 비활성, EC1) + SavedFilterMenu 마운트, (b) **`/search?filterId=<id>` 진입 → `fetchFilter` 해석 → q·projectKey 세팅 후 자동 실행**(FR-4), (c) filterId 404 → 토스트 + 일반 검색 유지(EC6), (d) 기존 검색 흐름 회귀 0. 실패.

**GREEN**. SearchRouteAdapter `validateSearch`에 `filterId?` 추가(router.ts code-based adapter 패턴, 메모리 [[tanstack-router 등록]] — `.ts` JSX 제약은 adapter로 회피). SearchPage가 filterId 있으면 로드 effect. 저장 버튼은 `submittedQuery`/입력값 기준.

**REFACTOR**. 로드 effect cleanup(stale 방지). 기존 q/projectKey 우선순위 명확화(filterId 로드 후 URL 정규화).

**검증**. `pnpm --filter web test search` + `pnpm --filter web typecheck`

---

### Task 7. MSW 저장필터 핸들러 — stateful store(소유 + 가시성)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/saved-filter-handlers.ts`, `apps/web/src/mocks/handlers/index.ts`]
- depends-on: [1]

**RED/GREEN**(인프라 — 기존 favorite-handlers.ts 패턴). userId별 소유 store + 공유 가시성 시뮬레이션(소유 아님 + AUTHENTICATED/PROJECT 매칭). `resetSavedFilterStore()`·`seedSavedFilters(userId, items)`·토큰→userId 파싱. POST/PUT/DELETE replace-all 반영. 핸들러 배열 export + 등록.

**REFACTOR**. 파생동작은 **브라우저 시드 가능 공유 store**에서 읽기(메모리 [[msw-derived-behavior-shared-store-e2e]] — 가짜그린 회피). 가시성 판정 = 소유자 응답엔 전체 shares, 비소유엔 매칭 share만(C2 충실).

**검증**. `pnpm --filter web test saved-filters`(T1 테스트가 공유 store로도 통과)

---

### Task 8. E2E — 저장/불러오기/공유/별표/비소유 게이팅/OCC

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/saved-filters.spec.ts`, `apps/web/src/mocks/saved-filter-handlers.ts`]
- depends-on: [6, 7]

**RED/GREEN**. Playwright(MSW) — (1) 저장 → "내 필터" 목록 표시 → 클릭 → 검색 실행(결과), (2) 공유(PROJECT/AUTHENTICATED) → **공유받은 사용자 세션에서 "공유받은 필터"에 가시**(SPA 내 이동, reload 금지 — 메모리 [[worktree-stale-base-rebase-and-e2e-msw-traps]]), (3) 별표 토글 → Header ⭐ "필터" 그룹 노출, (4) 비소유 항목 편집버튼 부재, (5) OCC 충돌 안내, (6) 삭제. getByRole `exact`/컨테이너 한정(메모리 [[playwright-getbyrole-exact-strict-mode]]·[[ui-pr-defer-e2e-regression-latent]]).

**REFACTOR**. 시드 헬퍼 + ★ **기존 search/favorites E2E 함께 실행해 회귀 0 확인**. orphan vite 정리(메모리 [[e2e-orphan-vite-after-worktree-remove]]).

**검증**. `pnpm --filter web test:e2e saved-filters` + 기존 search/favorites E2E 재실행.

## Plan 메타

- task 수: 8 (각 TDD 사이클)
- 모듈: apps/web 단일(프론트). 백엔드 변경 0.
- 예상 wave: 5 (W1: T1 / W2: T2·T3·T4·T7 병렬 / W3: T5 / W4: T6 / W5: T8). 프론트는 Gradle 모듈 직렬화 무관이라 W2 4-병렬 가능.
- TDD 강제: yes (test: 커밋 먼저)
- 추가 검증: typecheck(tsconfig.app) + lint + vitest + playwright(qa). `pnpm verify` 최종.
- 핵심 리스크: EC4 GROUP 보존(T4) · 별표 enum 추가 회귀(T2) · 기존 E2E 회귀(T8) · filterId 딥링크 라우터 등록(T6).

## 리뷰 결과 (← /bts-review-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
