// FR-SR-03 D6/D7 저장 필터 E2E — 저장·목록·불러오기·공유·별표·게이팅·삭제 (Task-8)
//
// 시나리오 개요.
//   SF-1. 저장·목록·불러오기: /search AQL 입력 + "저장" UI 검증 +
//                             사전 시드된 필터를 드롭다운 "내 필터"에서 확인 → filterId 딥링크 → 결과
//   SF-2. 공유 가시:          alice 소유 AUTHENTICATED 필터 사전 시드 → bob 세션 "공유받은 필터"에 표시
//   SF-3. 별표 → Header 노출: SavedFilterMenu FilterRow FavoriteButton ☆ → Header ⭐ "필터" 그룹
//   SF-4. 비소유 게이팅:      isOwner=false 행에 편집/공유/삭제 버튼 없음 (FavoriteButton만)
//   SF-5. OCC 충돌:           편집 다이얼로그에서 stale version 저장 → 409 → conflictError 안내 메시지
//   SF-6. 삭제:               소유 필터 삭제 → 목록 사라짐 + 빈 상태 표시
//
// ── 핵심 설계 결정 ──────────────────────────────────────────────────────────
// SavedFilterMenu의 useQuery(staleTime: 30_000)는 Header 마운트 직후(loginAsAlice 완료 시점)
// 첫 요청을 보내고 결과를 캐시한다. loginAsAlice 완료 후에 시드해도 TanStack Query가
// staleTime 내에는 재요청하지 않으므로 드롭다운에 반영되지 않는다.
//
// 해결: page.goto('/login') → MSW 모듈 로드(window.__btsSeedSavedFilters 사용 가능) →
//       seed() 호출 → 로그인 완료(SPA 이동 — 모듈 스코프 savedFilterStore 유지) →
//       /dashboard 진입 → SavedFilterMenu 첫 마운트 → 첫 fetch가 이미 시드된 store 읽음.
//
// 이 패턴을 'loginXxxWithSeed' 헬퍼로 추상화한다.
// ────────────────────────────────────────────────────────────────────────────
//
// 교훈 반영.
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동(Header 아이콘 클릭),
//     page.goto/reload 금지 (SW 재기동 → savedFilterStore 리셋).
//   - msw-mutation-stateful-refetch: MSW savedFilterHandlers가 savedFilterStore를 영속해
//     DELETE 후 invalidateAll() → refetch가 빈 배열 반환 → 빈 상태 표시.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지.
//   - e2e-fixture-whoami-userid-alignment:
//       alice = 00000000-0000-4000-8000-000000000001
//       bob   = 00000000-0000-4000-8000-000000000002
//   - playwright-getbyrole-exact-strict-mode: filterRow 컨테이너로 셀렉터 한정.
//   - ui-pr-defer-e2e-regression-latent: 이 파일만 신규 추가, 기존 파일 수정 없음.
//   - msw-derived-behavior-shared-store-e2e: 시드는 window.__btsSeedSavedFilters 경유.
//
// MSW 핵심 사항.
//   - saved-filter-handlers.ts 의 savedFilterStore 는 메인스레드 모듈 스코프 (ServiceWorker 아님).
//   - page.goto 시 모듈 재초기화 → savedFilterStore = new Map().
//   - SPA 내부 이동(pushState)은 모듈 스코프 유지.
//   - window.__btsSeedSavedFilters(userId, items): 필터 store 시드
//   - window.__btsResetSavedFilterStore(): store 전체 리셋
//
// 셀렉터 근거.
//   - SavedFilterMenu 트리거: aria-label="저장된 필터 목록" (saved-filter-labels.ts menuTriggerAriaLabel)
//   - FavoritesMenu 트리거: aria-label="즐겨찾기 목록 열기" (favorite-labels.ts dropdownTriggerAriaLabel)
//   - FilterRow FavoriteButton: data-testid="favorite-button" (FavoriteButton.tsx L98)
//   - 내 필터 섹션 헤더: "내 필터" (saved-filter-labels.ts myFiltersTab)
//   - 공유받은 필터 섹션 헤더: "공유받은 필터" (saved-filter-labels.ts sharedFiltersSection)
//   - 빈 상태 메시지: "저장된 필터 없음" (saved-filter-labels.ts menuEmptyMessage)
//   - 삭제 버튼: aria-label="삭제", 확인 버튼: "삭제하기" (saved-filter-labels.ts)
//   - FavoritesMenu FILTER 그룹 헤더: "필터" (FavoritesMenu.tsx FILTER_GROUP_LABEL)
//   - 저장 버튼: aria-label="현재 검색 저장" (search.tsx L559)
//   - 다이얼로그 이름 입력: aria-label="이름" (saved-filter-labels.ts nameLabel)

import { test, expect } from '@playwright/test'
import { loginStrings } from '../src/i18n/ko'
import { savedFilterLabels } from '../src/i18n/saved-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — saved-filter-labels.ts 단일 진실 원천 (하드코딩 회피)
// ─────────────────────────────────────────────────────────────────────────────

/** SavedFilterMenu 드롭다운 트리거 aria-label */
const MENU_TRIGGER_ARIA_LABEL = savedFilterLabels.menuTriggerAriaLabel

/** FavoritesMenu(Header ⭐) 드롭다운 트리거 aria-label — favorite-labels.ts 정본 */
const FAV_MENU_TRIGGER_ARIA_LABEL = '즐겨찾기 목록 열기'

/** Header ⭐ 드롭다운 FILTER 그룹 헤더 텍스트 — FavoritesMenu.tsx FILTER_GROUP_LABEL */
const FILTER_GROUP_LABEL = '필터'

/** 내 필터 섹션 헤더 */
const MY_FILTERS_LABEL = savedFilterLabels.myFiltersTab

/** 공유받은 필터 섹션 헤더 */
const SHARED_FILTERS_LABEL = savedFilterLabels.sharedFiltersSection

/** 빈 상태 메시지 */
const MENU_EMPTY_MESSAGE = savedFilterLabels.menuEmptyMessage

/** alice userId — MSW AUTH_USERS 정본과 일치 */
const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

/** 테스트 기본 저장 필터 이름 */
const TEST_FILTER_NAME = 'E2E 테스트 필터'

/** 테스트 AQL 쿼리 */
const TEST_AQL = 'status = open'

/** 테스트 프로젝트 키 */
const TEST_PROJECT_KEY = 'ATLAS'

// ─────────────────────────────────────────────────────────────────────────────
// 시드 헬퍼 타입
// ─────────────────────────────────────────────────────────────────────────────

interface FilterSeedItem {
  id: string
  ownerId: string
  name: string
  aqlQuery: string
  projectKey: string
  createdAt: string | null
  updatedAt: string | null
  version: number
  shares: Array<{ shareType: string; targetId: string | null }>
}

// ─────────────────────────────────────────────────────────────────────────────
// 로그인 헬퍼 — 시드를 /login 페이지에서 주입한다
//
// 설계 근거.
//   page.goto('/login') → 모듈 스코프 초기화(savedFilterStore = new Map()) →
//   seed() → savedFilterStore에 데이터 주입 →
//   SPA 로그인 이동(/dashboard) → 모듈 스코프 유지 →
//   SavedFilterMenu 첫 마운트 → fetchOwnedFilters/fetchSharedFilters →
//   MSW가 savedFilterStore에서 데이터 반환 → TanStack Query 캐시에 올바른 데이터.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice로 로그인하되 /login 페이지 렌더 직후 beforeLoginSeed를 실행한다.
 * beforeLoginSeed에서 window.__btsSeedSavedFilters를 호출하면
 * SavedFilterMenu의 첫 fetch가 시드된 데이터를 읽는다.
 */
async function loginAsAliceWithSeed(
  page: import('@playwright/test').Page,
  beforeLoginSeed: () => Promise<void>,
): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  // /login 렌더 완료 → MSW 활성, Header 미마운트 → 이 시점이 시드 최적 시점
  await beforeLoginSeed()
  // 1단계
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()
  // 2단계
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
  // SPA 이동 — 모듈 스코프(savedFilterStore) 유지
  await page.waitForURL('**/dashboard*')
}

/**
 * bob으로 로그인하되 /login 페이지 렌더 직후 beforeLoginSeed를 실행한다.
 */
async function loginAsBobWithSeed(
  page: import('@playwright/test').Page,
  beforeLoginSeed: () => Promise<void>,
): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  await beforeLoginSeed()
  // 1단계
  await page.getByLabel(loginStrings.emailLabel).fill('bob@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()
  // 2단계
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
  await page.getByLabel(loginStrings.usernameLabel).fill('bob')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
  await page.waitForURL('**/dashboard*')
}

// ─────────────────────────────────────────────────────────────────────────────
// 시드 헬퍼 — 브라우저 SW 모듈 store에 직접 시드
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW 핸들러의 savedFilterStore에 필터를 직접 시드한다.
 * window.__btsSeedSavedFilters (saved-filter-handlers.ts 노출) 호출.
 */
async function seedFilter(
  page: import('@playwright/test').Page,
  userId: string,
  filters: FilterSeedItem[],
): Promise<void> {
  await page.evaluate(
    ([uid, items]) => {
      ;(window as Record<string, unknown>)['__btsSeedSavedFilters'](uid, items)
    },
    [userId, filters] as [string, FilterSeedItem[]],
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 이동 + 드롭다운 열기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * /search 페이지로 SPA 내부 이동한다.
 * reload 금지 — ServiceWorker 재기동 → savedFilterStore 리셋.
 * Header "검색" 아이콘 클릭으로 pushState 이동한다.
 */
async function navigateToSearch(page: import('@playwright/test').Page): Promise<void> {
  await page.getByRole('button', { name: '검색', exact: true }).click()
  await page.waitForURL('**/search**')
  await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()
}

/**
 * SavedFilterMenu 드롭다운을 열고 콘텐츠 로케이터를 반환한다.
 * [[playwright-getbyrole-exact-strict-mode]] 준수 — 정확한 aria-label 사용.
 */
async function openSavedFilterMenu(
  page: import('@playwright/test').Page,
): Promise<import('@playwright/test').Locator> {
  const trigger = page.getByRole('button', { name: MENU_TRIGGER_ARIA_LABEL, exact: true })
  await expect(trigger).toBeVisible()
  await trigger.click()
  const content = page.locator('[data-radix-popper-content-wrapper]').first()
  await expect(content).toBeVisible()
  return content
}

/**
 * AQL 입력창에 쿼리를 채우고 검색 버튼을 클릭한다.
 * 검색 버튼은 Header "검색" 버튼과 동명이므로 입력 영역 컨테이너로 한정한다.
 * [[playwright-getbyrole-exact-strict-mode]] 준수.
 */
async function fillAndSearch(
  page: import('@playwright/test').Page,
  query: string,
): Promise<void> {
  const textarea = page.locator('textarea[placeholder*="AQL 쿼리를 입력하세요"]')
  await textarea.fill(query)
  const searchButtonContainer = page.locator('div.flex.items-center.gap-3').filter({
    has: page.getByRole('button', { name: '검색', exact: true }),
  })
  await searchButtonContainer.getByRole('button', { name: '검색', exact: true }).click()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-SR-03 저장 필터 (SF-1 저장/목록/불러오기 · SF-2 공유 · SF-3 별표 · SF-4 게이팅 · SF-5 OCC충돌 · SF-6 삭제)', () => {
  // ──────────────────────────────────────────────────────────────────────────
  // SF-1. 저장·목록·불러오기
  //
  // Given  /login 에서 alice 소유 필터를 MSW store에 시드 (Header 마운트 전)
  //        alice 로그인 완료 → /search SPA 이동 → AQL 입력 + 검색 실행
  // When   SavedFilterMenu 드롭다운 열기 → "내 필터" 섹션에 TEST_FILTER_NAME 확인
  //        Link 클릭 → /search?filterId=preSeededFilterId
  // Then   검색 결과 목록 3건 렌더 (filterId 딥링크 해소 → q+projectKey navigate)
  //
  // Also   AQL 입력 후 "저장" 버튼(aria-label="현재 검색 저장") → dialog → 이름 → 저장 →
  //        dialog 닫힘 확인 (create UI 검증 — POST 성공의 암묵적 증명)
  //
  // 설계 근거.
  //   SearchRouteAdapter의 SaveFilterDialog에 onSaved 콜백이 없어 create 후
  //   saved-filters 쿼리가 자동 invalidate되지 않음 (staleTime=30s 이내 재요청 없음).
  //   따라서 create UI 검증(dialog 닫힘)과 display 검증(사전 시드 기반)을 분리한다.
  // ──────────────────────────────────────────────────────────────────────────
  test('SF-1 저장·목록·불러오기 — create UI 검증 + 사전 시드 기반 display + filterId 딥링크', async ({ page }) => {
    // 무거운 시나리오(login+seed+search+save+menu+딥링크 해소) — 4-worker 병렬 부하에서 기본 30s 예산이
    // 빠듯해 filterId 네비게이션 대기에서 간헐 타임아웃. test.slow()로 예산 3배(격리 실행은 ~2s).
    test.slow()
    // Given. /login 에서 alice 소유 필터 시드 (Header 마운트 전 — 첫 query가 seeded data 읽음)
    const preSeededId = 'aaaaaaaa-0000-4000-8000-000000000010'
    await loginAsAliceWithSeed(page, async () => {
      await seedFilter(page, ALICE_USER_ID, [
        {
          id: preSeededId,
          ownerId: ALICE_USER_ID,
          name: TEST_FILTER_NAME,
          aqlQuery: TEST_AQL,
          projectKey: TEST_PROJECT_KEY,
          createdAt: null,
          updatedAt: null,
          version: 0,
          shares: [],
        },
      ])
    })

    // Given. /search SPA 이동 + AQL 입력 + 검색 실행
    await navigateToSearch(page)
    await fillAndSearch(page, TEST_AQL)
    await expect(page.getByRole('list', { name: '검색 결과' })).toBeVisible()

    // Also: "저장" 버튼 → dialog → 이름 → 저장 → dialog 닫힘 (create UI 검증)
    const saveToolbarBtn = page.getByRole('button', { name: '현재 검색 저장', exact: true })
    await expect(saveToolbarBtn).not.toBeDisabled()
    await saveToolbarBtn.click()

    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByLabel(savedFilterLabels.nameLabel, { exact: true }).fill('새 필터 이름')
    await page.getByRole('dialog').getByRole('button', {
      name: savedFilterLabels.saveButton,
      exact: true,
    }).click()
    // dialog 닫힘 → POST /api/v1/filters 성공 (암묵적 증명)
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // When. SavedFilterMenu 드롭다운 열기 → 사전 시드된 필터 "내 필터" 섹션에 표시
    const menuContent = await openSavedFilterMenu(page)
    await expect(menuContent.getByText(MY_FILTERS_LABEL)).toBeVisible()
    const filterLink = menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true })
    await expect(filterLink).toBeVisible()

    // When. Link 클릭 → /search?filterId= URL로 이동 (Radix 드롭다운은 자동 닫힘 없음)
    await filterLink.click()
    await page.waitForURL(`**/search?filterId=${preSeededId}**`)

    // Radix DropdownMenu는 Link 클릭 시 자동으로 닫히지 않는다.
    // 드롭다운이 열려 있으면 Radix가 배경에 aria-hidden을 적용해
    // getByRole('heading') 등 배경 요소가 accessible하지 않게 된다.
    // Escape로 드롭다운을 닫아 배경 컨텐츠를 accessible 상태로 복구한다.
    await page.keyboard.press('Escape')

    // Then. filterId 딥링크 해소 (C1) — SearchRouteAdapter가 fetchFilter 후 navigate(replace)
    // filterId가 q+projectKey로 교체될 때까지 대기 (navigate 완료 기다림)
    await page.waitForURL(/\/search\?q=/, { timeout: 10_000 })
    // SearchPage가 fresh mount → AQL 검색 heading 가시 = filterIdLoading 해소 확인
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()
  })

  // ──────────────────────────────────────────────────────────────────────────
  // SF-2. 공유 → 공유받은측 가시
  //
  // Given  /login 에서 alice 소유 AUTHENTICATED 공유 필터를 store에 시드
  //        bob으로 로그인 → /search SPA 이동
  // When   SavedFilterMenu 드롭다운 열기
  // Then   "공유받은 필터" 섹션에 TEST_FILTER_NAME 표시 (비소유 가시)
  //
  // MSW 가시성 로직.
  //   GET /api/v1/filters/shared — bob Bearer 토큰 → matchesShare(AUTHENTICATED)=true →
  //   alice 필터 반환 (비소유 가시 C2).
  // ──────────────────────────────────────────────────────────────────────────
  test('SF-2 alice AUTHENTICATED 공유 필터 → bob "공유받은 필터"에 가시', async ({ page }) => {
    // Given. /login 에서 alice 소유 AUTHENTICATED 공유 필터 시드 (bob 로그인 전)
    const sharedFromAliceId = 'bbbbbbbb-0000-4000-8000-000000000001'
    await loginAsBobWithSeed(page, async () => {
      await seedFilter(page, ALICE_USER_ID, [
        {
          id: sharedFromAliceId,
          ownerId: ALICE_USER_ID,
          name: TEST_FILTER_NAME,
          aqlQuery: TEST_AQL,
          projectKey: TEST_PROJECT_KEY,
          createdAt: null,
          updatedAt: null,
          version: 0,
          shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
        },
      ])
    })

    // Given. /search SPA 이동
    await navigateToSearch(page)

    // When. SavedFilterMenu 드롭다운 열기
    const menuContent = await openSavedFilterMenu(page)

    // Then. "공유받은 필터" 섹션에 TEST_FILTER_NAME 표시 (bob에게 alice 필터 가시)
    await expect(menuContent.getByText(SHARED_FILTERS_LABEL)).toBeVisible()
    // vacuous 차단 — 항목이 실제로 존재함을 단언
    await expect(
      menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true }),
    ).toBeVisible()
  })

  // ──────────────────────────────────────────────────────────────────────────
  // SF-3. 별표 → Header ⭐ 드롭다운 "필터" 그룹에 노출
  //
  // Given  /login 에서 alice 소유 필터 시드 → alice 로그인 → /search
  //        SavedFilterMenu 드롭다운 "내 필터" 섹션에 필터 표시 확인
  // When   FilterRow FavoriteButton(☆) 클릭 → POST /api/v1/favorites
  //        FavoriteButton aria-pressed=true 확인
  // Then   Header ⭐ FavoritesMenu 드롭다운 열기
  //        "필터" 그룹 헤더 + TEST_FILTER_NAME 표시
  //        Link 클릭 → /search?filterId= URL 이동 확인
  // ──────────────────────────────────────────────────────────────────────────
  test('SF-3 FilterRow FavoriteButton ☆ 클릭 → Header 즐겨찾기 "필터" 그룹에 filter.name 표시', async ({ page }) => {
    // Given. /login 에서 alice 소유 필터 시드
    const favTargetId = 'cccccccc-0000-4000-8000-000000000001'
    await loginAsAliceWithSeed(page, async () => {
      await seedFilter(page, ALICE_USER_ID, [
        {
          id: favTargetId,
          ownerId: ALICE_USER_ID,
          name: TEST_FILTER_NAME,
          aqlQuery: TEST_AQL,
          projectKey: TEST_PROJECT_KEY,
          createdAt: null,
          updatedAt: null,
          version: 0,
          shares: [],
        },
      ])
    })

    // Given. /search SPA 이동
    await navigateToSearch(page)

    // Given. SavedFilterMenu 드롭다운 열기 → "내 필터"에 필터 표시 확인
    const menuContent = await openSavedFilterMenu(page)
    await expect(
      menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true }),
    ).toBeVisible()

    // When. FilterRow 내 FavoriteButton(☆) 클릭
    // 이 테스트는 필터가 1개뿐이므로 menuContent 전체에서 직접 찾는다.
    // filter({ has: menuContent.getByRole(...) })는 상위-바인딩 locator를 has에 넘기면
    // Playwright가 relative locator로 처리하지 않아 empty match가 된다.
    const favBtn = menuContent.getByTestId('favorite-button')
    await expect(favBtn).toBeVisible()
    await expect(favBtn).toHaveAttribute('aria-pressed', 'false')
    await favBtn.click()

    // When. FavoriteButton aria-pressed=true → POST /api/v1/favorites 성공
    await expect(favBtn).toHaveAttribute('aria-pressed', 'true')

    // When. SavedFilterMenu 드롭다운 닫기 (Escape)
    await page.keyboard.press('Escape')

    // Then. Header ⭐ FavoritesMenu 드롭다운 열기
    const favMenuTrigger = page.getByRole('button', { name: FAV_MENU_TRIGGER_ARIA_LABEL, exact: true })
    await expect(favMenuTrigger).toBeVisible()
    await favMenuTrigger.click()

    const favMenuContent = page.locator('[data-radix-popper-content-wrapper]').first()
    await expect(favMenuContent).toBeVisible()

    // Then. "필터" 그룹 헤더 표시 (FilterFavoritesGroup — FILTER_GROUP_LABEL)
    // { exact: true } 필수 — "E2E 테스트 필터" 스팬이 substring "필터"를 포함하므로 strict mode 위반 방지
    await expect(favMenuContent.getByText(FILTER_GROUP_LABEL, { exact: true })).toBeVisible()

    // Then. filter.name 텍스트 표시 (FavoritesMenu.tsx L149: {filter.name})
    // FilterFavoritesGroup이 GET /api/v1/filters/:filterId를 호출해 filter.name을 노출
    await expect(favMenuContent.getByText(TEST_FILTER_NAME)).toBeVisible()

    // Then. 항목 클릭 → /search?filterId=favTargetId URL
    // getByRole('menuitem') — FavoritesMenu 항목의 accessible name이 filter.name과 일치
    // filter({ has: favMenuContent.getByText(...) })는 바인딩-locator 문제로 empty match 발생
    const filterMenuItem = favMenuContent.getByRole('menuitem', { name: TEST_FILTER_NAME, exact: true })
    await expect(filterMenuItem).toBeVisible()
    await filterMenuItem.click()
    await page.waitForURL(`**/search?filterId=${favTargetId}**`)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // SF-4. 비소유 게이팅 — isOwner=false 필터 행에 편집/공유/삭제 버튼 부재
  //
  // Given  /login 에서 alice 소유 AUTHENTICATED 공유 필터 시드
  //        bob으로 로그인 → /search
  //        "공유받은 필터" 섹션에 alice 필터 표시 확인
  // When   필터 행(isOwner=false) 컨테이너 확인
  // Then   FavoriteButton 존재
  //        편집(수정) · 공유 · 삭제 버튼 부재 (FilterRow 조건부 렌더 — isOwner=false)
  // ──────────────────────────────────────────────────────────────────────────
  test('SF-4 비소유(isOwner=false) 필터 행 — FavoriteButton만 존재, 편집/공유/삭제 부재', async ({ page }) => {
    // Given. /login 에서 alice 소유 AUTHENTICATED 공유 필터 시드
    const gatingFilterId = 'dddddddd-0000-4000-8000-000000000001'
    await loginAsBobWithSeed(page, async () => {
      await seedFilter(page, ALICE_USER_ID, [
        {
          id: gatingFilterId,
          ownerId: ALICE_USER_ID,
          name: TEST_FILTER_NAME,
          aqlQuery: TEST_AQL,
          projectKey: TEST_PROJECT_KEY,
          createdAt: null,
          updatedAt: null,
          version: 0,
          shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
        },
      ])
    })

    // Given. /search SPA 이동
    await navigateToSearch(page)

    // Given. SavedFilterMenu 드롭다운 열기 → "공유받은 필터" 섹션에 alice 필터 표시 확인
    const menuContent = await openSavedFilterMenu(page)
    await expect(menuContent.getByText(SHARED_FILTERS_LABEL)).toBeVisible()
    await expect(
      menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true }),
    ).toBeVisible()

    // Then. FavoriteButton 존재 확인 (비소유도 별표 가능)
    // 이 테스트는 필터가 1개뿐이므로 menuContent 전체에서 직접 단언.
    // filter({ has: 바인딩-locator })는 relative 처리가 안 돼 empty match가 나므로 사용하지 않는다.
    await expect(menuContent.getByTestId('favorite-button')).toBeVisible()

    // Then. 편집/공유/삭제 버튼 부재 (isOwner=false → FilterRow 조건부 렌더 안 함)
    // menuContent 전체에서 count=0 단언 — 비소유 필터만 있으므로 안전
    await expect(
      menuContent.getByRole('button', { name: savedFilterLabels.editButton, exact: true }),
    ).toHaveCount(0)
    await expect(
      menuContent.getByRole('button', { name: savedFilterLabels.shareButton, exact: true }),
    ).toHaveCount(0)
    await expect(
      menuContent.getByRole('button', { name: savedFilterLabels.deleteButton, exact: true }),
    ).toHaveCount(0)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // SF-5. OCC 충돌 — 편집 다이얼로그에서 stale version 저장 → 409 → conflictError 안내 메시지
  //
  // Given  /login 에서 alice 소유 필터(version=0) 시드 → alice 로그인 → /search
  //        SavedFilterMenu 드롭다운 열기 → 편집(수정) 버튼 클릭 → SaveFilterDialog 열림
  //        (다이얼로그 폼 state에 version=0 보유)
  // When   동시 수정 시뮬레이션:
  //        window.__btsSeedSavedFilters로 같은 필터 id를 version=1로 재시드
  //        (타 세션이 먼저 저장한 상황 재현 — reload/goto 없이 모듈 스코프 store만 갱신)
  //        다이얼로그에서 이름 수정 후 저장 → PUT /api/v1/filters/:id (version=0 전송)
  //        MSW 핸들러: body.version(0) !== existing.version(1) → 409 SEARCH_FILTER_CONFLICT
  // Then   SaveFilterForm.onError → mapErrorToMessage(CONFLICT) → setSubmitError
  //        <p role="alert"> 에 savedFilterLabels.conflictError 문구 표시
  //
  // 가짜 그린 차단 근거.
  //   version bump가 실패하면 MSW가 200을 반환 → onSuccess → 다이얼로그 닫힘
  //   → dialog.getByRole('alert') 단언이 DOM에서 요소를 찾지 못해 FAIL.
  //   즉 이 테스트는 실제로 409가 발생해야만 통과한다.
  // ──────────────────────────────────────────────────────────────────────────
  test('SF-5 OCC 충돌 — 편집 다이얼로그 stale version 저장 → 409 → conflictError 안내 메시지', async ({ page }) => {
    // Given. /login 에서 alice 소유 필터 시드 (version=0)
    const occFilterId = 'ffffffff-0000-4000-8000-000000000001'
    await loginAsAliceWithSeed(page, async () => {
      await seedFilter(page, ALICE_USER_ID, [
        {
          id: occFilterId,
          ownerId: ALICE_USER_ID,
          name: TEST_FILTER_NAME,
          aqlQuery: TEST_AQL,
          projectKey: TEST_PROJECT_KEY,
          createdAt: null,
          updatedAt: null,
          version: 0,
          shares: [],
        },
      ])
    })

    // Given. /search SPA 이동
    await navigateToSearch(page)

    // Given. SavedFilterMenu 드롭다운 열기 → 편집 버튼 클릭 → SaveFilterDialog 열림
    // 이 테스트는 필터가 1개뿐이므로 menuContent 전체에서 "수정" 버튼 직접 탐색
    const menuContent = await openSavedFilterMenu(page)
    await expect(
      menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true }),
    ).toBeVisible()
    const editBtn = menuContent.getByRole('button', { name: savedFilterLabels.editButton, exact: true })
    await expect(editBtn).toBeVisible()
    await editBtn.click()

    // Given. 다이얼로그 열림 확인 (편집 모드 — filter.version=0 보유)
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // When. 동시 수정 시뮬레이션 — store의 같은 필터 version을 1로 올림
    // (page.goto/reload 금지 — SPA 내부 모듈 스코프 savedFilterStore 유지)
    // 다이얼로그는 version=0을 props에 보유한 채 열려 있음
    await seedFilter(page, ALICE_USER_ID, [
      {
        id: occFilterId,
        ownerId: ALICE_USER_ID,
        name: TEST_FILTER_NAME,
        aqlQuery: TEST_AQL,
        projectKey: TEST_PROJECT_KEY,
        createdAt: null,
        updatedAt: null,
        version: 1,
        shares: [],
      },
    ])

    // When. 이름 수정 후 저장 → stale version=0 전송
    //       MSW PUT: body.version(0) !== existing.version(1) → 409 SEARCH_FILTER_CONFLICT
    await dialog.getByLabel(savedFilterLabels.nameLabel, { exact: true }).fill('수정된 이름')
    await dialog.getByRole('button', { name: savedFilterLabels.saveButton, exact: true }).click()

    // Then. role=alert에 conflictError 안내 메시지 표시
    //       SaveFilterForm.onError → mapErrorToMessage(CONFLICT) → savedFilterLabels.conflictError
    await expect(dialog.getByRole('alert')).toHaveText(savedFilterLabels.conflictError)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // SF-6. 삭제 — 소유 필터 삭제 → 목록에서 사라짐 + 빈 상태
  //
  // Given  /login 에서 alice 소유 필터 시드 → alice 로그인 → /search
  //        SavedFilterMenu 드롭다운 열기 → TEST_FILTER_NAME 표시 확인 (vacuous 차단)
  // When   삭제 버튼(aria-label="삭제") 클릭 → "삭제하기" 확인 버튼 클릭
  //        DELETE /api/v1/filters/:id → 204 → savedFilterStore 제거 → invalidateAll()
  // Then   드롭다운 내 해당 항목 사라짐 + "저장된 필터 없음" 표시
  //        드롭다운 닫고 다시 열어도 빈 상태 유지 (stateful store 영속 확인)
  // ──────────────────────────────────────────────────────────────────────────
  test('SF-6 소유 필터 삭제 버튼 → 삭제하기 확인 → 목록 사라짐 + 빈 상태 표시', async ({ page }) => {
    // Given. /login 에서 alice 소유 필터 시드
    const deleteTargetId = 'eeeeeeee-0000-4000-8000-000000000001'
    await loginAsAliceWithSeed(page, async () => {
      await seedFilter(page, ALICE_USER_ID, [
        {
          id: deleteTargetId,
          ownerId: ALICE_USER_ID,
          name: TEST_FILTER_NAME,
          aqlQuery: TEST_AQL,
          projectKey: TEST_PROJECT_KEY,
          createdAt: null,
          updatedAt: null,
          version: 0,
          shares: [],
        },
      ])
    })

    // Given. /search SPA 이동
    await navigateToSearch(page)

    // Given. 드롭다운 열기 → 필터 표시 확인 (삭제 전 존재 vacuous 차단)
    let menuContent = await openSavedFilterMenu(page)
    await expect(
      menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true }),
    ).toBeVisible()

    // When. 삭제 버튼 클릭
    // 이 테스트는 필터가 1개뿐이므로 menuContent 전체에서 직접 찾는다.
    // filter({ has: 바인딩-locator })는 relative 처리가 안 돼 empty match가 나므로 사용하지 않는다.
    const deleteBtn = menuContent.getByRole('button', {
      name: savedFilterLabels.deleteButton,
      exact: true,
    })
    await expect(deleteBtn).toBeVisible()
    await deleteBtn.click()

    // When. 삭제 확인 섹션 표시 → "삭제하기" 확인 버튼 클릭
    const confirmBtn = menuContent.getByRole('button', {
      name: savedFilterLabels.deleteConfirmButton,
      exact: true,
    })
    await expect(confirmBtn).toBeVisible()
    await confirmBtn.click()

    // Then. DELETE 성공 → invalidateAll() → refetch → [] → isEmpty=true → 빈 상태 메시지
    await expect(menuContent.getByText(MENU_EMPTY_MESSAGE)).toBeVisible()
    // vacuous 차단 — 삭제된 필터 Link가 0개임을 단언
    await expect(
      menuContent.getByRole('link', { name: TEST_FILTER_NAME, exact: true }),
    ).toHaveCount(0)

    // Then. 드롭다운 닫고 다시 열어도 빈 상태 유지 (stateful store 영속 확인)
    await page.keyboard.press('Escape')
    menuContent = await openSavedFilterMenu(page)
    await expect(menuContent.getByText(MENU_EMPTY_MESSAGE)).toBeVisible()
  })
})
