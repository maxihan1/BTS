// FR-WT-01 감시자 watch/unwatch + 카운트/명단 E2E
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: watchers-section 컨테이너로 모든 셀렉터 한정
//   - msw-mutation-stateful-refetch: MSW addWatcherHandler/removeWatcherHandler가 watcherStore에
//     영속 → invalidateQueries refetch 후 카운트/명단 최신 값 반환
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동(pushState+popstate)으로
//     ServiceWorker 재시작 없이 이슈 상세 진입
//   - e2e-fixture-whoami-userid-alignment: alice userId = 00000000-...-001 (MSW AUTH_USERS 정본)
//   - ui-pr-defer-e2e-regression-latent: 기존 issue- E2E 회귀 방지 — 이 파일만 신규 추가
//
// MSW 핵심 사항.
//   - issue-watcher-handlers.ts: Authorization Bearer 토큰에서 현재 사용자(alice) 자동 도출
//   - watcherStore는 모듈-스코프 stateful; 각 test는 Playwright 기본 새 context(새 ServiceWorker)
//     → store가 빈 상태로 시작 → 진입 시 count=0, 빈 상태 메시지, 버튼 "지켜보기"
//   - 명단에서 본인은 displayName 뒤에 watcherSelfSuffix "(나)" 접미사로 식별
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** watchers-section data-testid */
const WATCHERS_SECTION_TESTID = 'watchers-section'

/** watch 토글 버튼 data-testid */
const WATCH_TOGGLE_TESTID = 'watch-toggle-button'

/** i18n 정본 — ko.ts issueDetailStrings (단일 진실 원천; 하드코딩 회피) */
const WATCH_BUTTON_TEXT = '지켜보기'
const UNWATCH_BUTTON_TEXT = '지켜보는 중'
const EMPTY_TEXT = '감시자가 없습니다.'
const SELF_SUFFIX = '(나)'

/** alice displayName — KNOWN_DISPLAY_NAMES 정본 (issue-watcher-handlers.ts) */
const ALICE_DISPLAY_NAME = 'User alice'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * watchers-section이 렌더될 때까지 대기.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl = ISSUE_URL,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  await expect(page.getByTestId(WATCHERS_SECTION_TESTID)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-WT-01 이슈 감시자 Watch/Unwatch (WatchersSection)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 → canEdit=true → 토글 버튼 활성
    // issue-fixtures.ts의 loginAsAlice 재사용 (정본 헬퍼)
    await loginAsAlice(page)
    // loginAsAlice 완료 시 /dashboard 진입 → ServiceWorker 기동 완료
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-1 초기 상태
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         watcherStore 빈 상태 (새 Playwright context → 새 ServiceWorker 모듈)
  // When    이슈 상세 페이지 렌더 완료
  // Then    watchers-section에 빈 상태 메시지 "감시자가 없습니다." 표시
  //         Watch 토글 버튼이 "지켜보기" 텍스트 + aria-pressed=false 상태
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-1 초기 상태 — watchers-section 빈 상태 메시지 + 지켜보기 버튼', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const section = page.getByTestId(WATCHERS_SECTION_TESTID)

    // Then. 빈 상태 메시지
    await expect(section.getByText(EMPTY_TEXT)).toBeVisible()

    // Then. 토글 버튼 "지켜보기" + aria-pressed=false
    const toggleBtn = section.getByTestId(WATCH_TOGGLE_TESTID)
    await expect(toggleBtn).toBeVisible()
    await expect(toggleBtn).toHaveText(WATCH_BUTTON_TEXT)
    await expect(toggleBtn).toHaveAttribute('aria-pressed', 'false')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-2 watch(self)
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         watcherStore 빈 상태 (카운트 0, 버튼 "지켜보기")
  // When    "지켜보기" 토글 버튼 클릭
  //         → MSW addWatcherHandler: alice userId를 watcherStore에 추가 → 201
  //         → useAddWatcher.onSuccess: invalidateQueries → refetch
  //         → MSW getWatchersHandler: count=1, isWatching=true, watchers=[alice]
  // Then    watchers-section 카운트 "1명" 표시
  //         토글 버튼 "지켜보는 중" + aria-pressed=true
  //         명단에 "User alice (나)" 표시
  //         빈 상태 메시지 사라짐
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-2 watch(self) — 지켜보기 클릭 시 카운트+1, 버튼 전환, 명단에 (나) 표시', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const section = page.getByTestId(WATCHERS_SECTION_TESTID)
    const toggleBtn = section.getByTestId(WATCH_TOGGLE_TESTID)

    // Given. 초기 상태 확인
    await expect(toggleBtn).toHaveText(WATCH_BUTTON_TEXT)

    // When. "지켜보기" 클릭
    await toggleBtn.click()

    // Then. 토글 버튼 "지켜보는 중" + aria-pressed=true (invalidate refetch 후 자동 재시도)
    await expect(toggleBtn).toHaveText(UNWATCH_BUTTON_TEXT)
    await expect(toggleBtn).toHaveAttribute('aria-pressed', 'true')

    // Then. 카운트 "1명" 표시
    await expect(section.getByText('1명')).toBeVisible()

    // Then. 명단에 alice displayName + "(나)" 접미사
    await expect(section.getByText(ALICE_DISPLAY_NAME)).toBeVisible()
    await expect(section.getByText(SELF_SUFFIX)).toBeVisible()

    // Then. 빈 상태 메시지 사라짐
    await expect(section.getByText(EMPTY_TEXT)).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-3 unwatch(self) — watch 후 unwatch 전체 플로우
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         "지켜보기" 클릭으로 alice를 watcherStore에 추가한 상태
  //         (카운트 1, 버튼 "지켜보는 중", 명단에 alice)
  // When    "지켜보는 중" 토글 버튼 클릭
  //         → MSW removeWatcherHandler: alice userId 제거 → 204
  //         → useRemoveWatcher.onSuccess: invalidateQueries → refetch
  //         → MSW getWatchersHandler: count=0, isWatching=false, watchers=[]
  // Then    카운트 0 (카운트 텍스트 사라지거나 "0명") / 빈 상태 메시지 복원
  //         토글 버튼 "지켜보기" + aria-pressed=false
  //         명단에서 alice "(나)" 항목 제거
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-3 unwatch(self) — 지켜보는 중 클릭 시 카운트-1, 버튼 복귀, 명단에서 제거', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const section = page.getByTestId(WATCHERS_SECTION_TESTID)
    const toggleBtn = section.getByTestId(WATCH_TOGGLE_TESTID)

    // 사전 상태. watch 먼저 수행
    await toggleBtn.click()
    await expect(toggleBtn).toHaveText(UNWATCH_BUTTON_TEXT)
    await expect(section.getByText(ALICE_DISPLAY_NAME)).toBeVisible()

    // When. "지켜보는 중" 클릭 → unwatch
    await toggleBtn.click()

    // Then. 토글 버튼 "지켜보기" + aria-pressed=false (invalidate refetch 후 자동 재시도)
    await expect(toggleBtn).toHaveText(WATCH_BUTTON_TEXT)
    await expect(toggleBtn).toHaveAttribute('aria-pressed', 'false')

    // Then. 빈 상태 메시지 복원
    await expect(section.getByText(EMPTY_TEXT)).toBeVisible()

    // Then. alice 명단 항목 제거
    await expect(section.getByText(ALICE_DISPLAY_NAME)).toHaveCount(0)
    await expect(section.getByText(SELF_SUFFIX)).toHaveCount(0)
  })
})
