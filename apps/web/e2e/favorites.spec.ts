// FR-UX-02 즐겨찾기 추가/제거/드롭다운 E2E — IssueMetaPanel FavoriteButton + FavoritesMenu
//
// 교훈 반영.
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동(pushState+popstate)으로
//     ServiceWorker 재시작 없이 이슈 상세 진입 — page.goto/reload 금지(SW 재기동→favoriteStore 리셋)
//   - msw-mutation-stateful-refetch: MSW addFavoriteHandler/deleteFavoriteHandler가 favoriteStore에
//     영속 → invalidateQueries refetch 후 버튼/드롭다운 최신 값 반환
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - e2e-fixture-whoami-userid-alignment: alice userId = 00000000-...-001 (MSW AUTH_USERS 정본)
//   - playwright-getbyrole-exact-strict-mode: favorite-section 컨테이너로 셀렉터 한정
//   - ui-pr-defer-e2e-regression-latent: 기존 issue- E2E 회귀 방지 — 이 파일만 신규 추가
//
// MSW 핵심 사항.
//   - favorite-handlers.ts: Authorization Bearer 토큰에서 alice userId 자동 도출
//   - favoriteStore는 모듈-스코프 stateful; 각 test는 Playwright 기본 새 context(새 ServiceWorker)
//     → store가 빈 상태로 시작 → E2E-1이 "빈 상태" 자연 성립
//   - E2E-3/E2E-4는 E2E-1/E2E-2와 독립된 context에서 실행 → store 상태 공유 없음
//   - FavoritesMenu 드롭다운은 Header에 삽입, 이슈 상세 페이지 SPA 이동 후 접근 가능
//
// 셀렉터 근거.
//   - data-testid="favorite-button": FavoriteButton.tsx L98
//   - data-testid="favorite-section": IssueMetaPanel.tsx L311 (FavoriteButton wrapper div)
//   - aria-label="즐겨찾기에 추가" / "즐겨찾기 해제": favorite-labels.ts addAriaLabel/removeAriaLabel
//   - aria-label="즐겨찾기 목록 열기": FavoritesMenu.tsx dropdownTriggerAriaLabel
//   - 빈 상태 메시지 "즐겨찾기한 항목이 없습니다": favorite-labels.ts emptyMessage
//   - 드롭다운 항목 텍스트: fav.targetId (이슈 키) — FavoritesMenu.tsx L139 `{fav.targetId}`
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** favorite-section data-testid */
const FAVORITE_SECTION_TESTID = 'favorite-section'

/** FavoriteButton data-testid */
const FAVORITE_BUTTON_TESTID = 'favorite-button'

/** i18n 정본 — favorite-labels.ts (단일 진실 원천; 하드코딩 회피) */
const ADD_ARIA_LABEL = '즐겨찾기에 추가'
const REMOVE_ARIA_LABEL = '즐겨찾기 해제'
const DROPDOWN_TRIGGER_ARIA_LABEL = '즐겨찾기 목록 열기'
const EMPTY_MESSAGE = '즐겨찾기한 항목이 없습니다'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * favorite-section이 렌더될 때까지 대기.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl = ISSUE_URL,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  await expect(page.getByTestId(FAVORITE_SECTION_TESTID)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-02 즐겨찾기 토글 + 드롭다운 (FavoriteButton + FavoritesMenu)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 → FavoriteButton 접근 가능
    // issue-fixtures.ts의 loginAsAlice 재사용 (정본 헬퍼)
    await loginAsAlice(page)
    // loginAsAlice 완료 시 /dashboard 진입 → ServiceWorker 기동 완료
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-1 빈 상태 + 즐겨찾기 추가 → 드롭다운에서 건수 확인
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         favoriteStore 빈 상태 (새 Playwright context → 새 ServiceWorker 모듈)
  // When    FavoriteButton 클릭 (☆ → ★ POST 반영)
  // Then    버튼 aria-label = "즐겨찾기 해제" + aria-pressed=true
  //         Header 드롭다운 열면 ATLAS-1 항목이 목록에 존재 (건수 0→1, vacuous 차단)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-1 즐겨찾기 추가 → 버튼 전환 + 드롭다운 건수 0→1 단언', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const favSection = page.getByTestId(FAVORITE_SECTION_TESTID)
    const favBtn = favSection.getByTestId(FAVORITE_BUTTON_TESTID)

    // Given. 초기 상태 확인 — 빈 상태 (aria-label = 추가, aria-pressed=false)
    await expect(favBtn).toHaveAttribute('aria-label', ADD_ARIA_LABEL)
    await expect(favBtn).toHaveAttribute('aria-pressed', 'false')

    // When. ☆ 클릭 → POST /api/v1/favorites → favoriteStore 추가 → invalidate refetch
    await favBtn.click()

    // Then. 버튼 "즐겨찾기 해제" + aria-pressed=true
    await expect(favBtn).toHaveAttribute('aria-label', REMOVE_ARIA_LABEL)
    await expect(favBtn).toHaveAttribute('aria-pressed', 'true')

    // Then. 드롭다운 열어 ATLAS-1 항목 존재 확인 (건수 0→1, vacuous 차단)
    const triggerBtn = page.getByRole('button', { name: DROPDOWN_TRIGGER_ARIA_LABEL, exact: true })
    await triggerBtn.click()

    // 드롭다운 내 ATLAS-1 항목 — FavoritesMenu.tsx L139: `{fav.targetId}`
    const dropdownContent = page.locator('[role="menu"], [data-radix-popper-content-wrapper]').first()
    await expect(dropdownContent.getByText(ISSUE_KEY)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-2 드롭다운 Link 클릭 → 이슈 라우트 SPA 이동 (URL 확인)
  //
  // Given   alice로 로그인, ATLAS-1 이슈 즐겨찾기 추가 상태
  //         (E2E-2는 독립 context — 새 SW → store 빈 상태에서 시작 → 클릭으로 추가 후 드롭다운 진입)
  // When    Header 드롭다운에서 ATLAS-1 Link 클릭
  // Then    URL이 /issues/ATLAS-1 포함 → SPA 이동 확인
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-2 드롭다운 Link 클릭 → 이슈 URL로 SPA 이동', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const favSection = page.getByTestId(FAVORITE_SECTION_TESTID)
    const favBtn = favSection.getByTestId(FAVORITE_BUTTON_TESTID)

    // 사전 상태. ATLAS-1 즐겨찾기 추가 (store에 영속)
    await favBtn.click()
    await expect(favBtn).toHaveAttribute('aria-pressed', 'true')

    // /dashboard로 이동해 이슈 상세를 떠난다 — 드롭다운 Link 이동 대상을 명확히 하기 위해
    await page.evaluate(() => {
      window.history.pushState({}, '', '/dashboard')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })

    // When. 드롭다운 트리거 클릭 → ATLAS-1 Link 클릭
    const triggerBtn = page.getByRole('button', { name: DROPDOWN_TRIGGER_ARIA_LABEL, exact: true })
    await triggerBtn.click()

    const dropdownContent = page.locator('[role="menu"], [data-radix-popper-content-wrapper]').first()
    const issueLink = dropdownContent.getByText(ISSUE_KEY)
    await expect(issueLink).toBeVisible()
    await issueLink.click()

    // Then. URL이 /issues/ATLAS-1 포함 — SPA Link 이동 확인
    await expect(page).toHaveURL(new RegExp(`/issues/${ISSUE_KEY}`))
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-3 즐겨찾기 해제 → 버튼 복귀 + 드롭다운에서 사라짐 (건수 1→0)
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         FavoriteButton 클릭으로 즐겨찾기 추가 상태 (카운트 1)
  // When    ★ 클릭 → DELETE 반영 → store에서 제거 → invalidate refetch
  // Then    버튼 aria-label = "즐겨찾기에 추가" + aria-pressed=false
  //         드롭다운 열면 ATLAS-1 항목이 사라짐 (건수 1→0, vacuous 차단)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-3 즐겨찾기 해제 → 버튼 복귀 + 드롭다운 건수 1→0', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const favSection = page.getByTestId(FAVORITE_SECTION_TESTID)
    const favBtn = favSection.getByTestId(FAVORITE_BUTTON_TESTID)

    // 사전 상태. 즐겨찾기 추가 먼저 수행 (store에 ATLAS-1 영속)
    await favBtn.click()
    await expect(favBtn).toHaveAttribute('aria-pressed', 'true')

    // When. ★ 클릭 → DELETE → store 제거 → invalidate refetch
    await favBtn.click()

    // Then. 버튼 "즐겨찾기에 추가" + aria-pressed=false
    await expect(favBtn).toHaveAttribute('aria-label', ADD_ARIA_LABEL)
    await expect(favBtn).toHaveAttribute('aria-pressed', 'false')

    // Then. 드롭다운 열어 ATLAS-1 항목이 사라짐 확인 (건수 1→0)
    const triggerBtn = page.getByRole('button', { name: DROPDOWN_TRIGGER_ARIA_LABEL, exact: true })
    await triggerBtn.click()

    const dropdownContent = page.locator('[role="menu"], [data-radix-popper-content-wrapper]').first()
    // 드롭다운이 열릴 때까지 대기 — 빈 상태 메시지로 확인
    await expect(dropdownContent.getByText(EMPTY_MESSAGE)).toBeVisible()
    // ATLAS-1 항목이 없음을 단언 (vacuous 차단 — 빈 상태 메시지 존재로 0개 증명)
    await expect(dropdownContent.getByText(ISSUE_KEY)).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-4 즐겨찾기 0개 상태에서 드롭다운 빈 상태 메시지 표시
  //
  // Given   alice로 로그인
  //         favoriteStore 빈 상태 (새 Playwright context → 새 ServiceWorker)
  // When    Header ⭐ 드롭다운 트리거 클릭
  // Then    "즐겨찾기한 항목이 없습니다" 빈 상태 메시지 표시
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-4 즐겨찾기 0개 → 드롭다운 빈 상태 메시지 표시', async ({ page }) => {
    // Given. favoriteStore 빈 상태 — 이슈 상세 이동 없이 바로 드롭다운 클릭
    // loginAsAlice 후 /dashboard에 있음 (FavoritesMenu가 Header에 항상 노출)

    // When. 드롭다운 트리거 클릭
    const triggerBtn = page.getByRole('button', { name: DROPDOWN_TRIGGER_ARIA_LABEL, exact: true })
    await expect(triggerBtn).toBeVisible()
    await triggerBtn.click()

    // Then. 빈 상태 메시지 표시
    const dropdownContent = page.locator('[role="menu"], [data-radix-popper-content-wrapper]').first()
    await expect(dropdownContent.getByText(EMPTY_MESSAGE)).toBeVisible()
  })
})
