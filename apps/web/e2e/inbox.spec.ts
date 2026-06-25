// FR-UX-03 D7 E2E — 개인 알림 보관함(Inbox) 페이지 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: article 래퍼 및 컨테이너 한정 — 텍스트 중복 strict mode 회피
//   - msw-mutation-stateful-refetch: MSW inboxStore가 stateful — SPA 내부 이동(Link 클릭)으로 검증.
//     page.reload() 금지 (MSW store 리셋 = 가짜그린)
//   - e2e-fixture-whoami-userid-alignment: alice userId = 00000000-0000-4000-8000-000000000001
//     inbox-handlers.ts 자동 시드 기준과 일치
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동(pushState+popstate)으로
//     ServiceWorker 재시작 없이 /inbox 진입
//   - ui-pr-defer-e2e-regression-latent: 이 파일만 신규 추가, 기존 E2E 수정 없음
//
// MSW 핵심 사항.
//   - inbox-handlers.ts: 모듈 로드 시 alice(00000000-0000-4000-8000-000000000001)의 기본 시드 자동 주입
//   - 각 테스트는 Playwright 기본 새 컨텍스트(새 ServiceWorker) → inboxStore가 기본 시드 상태로 시작
//   - defaultInboxFixtures: 미읽음 2건(ATLAS-1, ATLAS-3), 읽음 1건(ATLAS-2), 보관됨 2건(ATLAS-4)
//   - 읽음/보관 PATCH → inboxStore 변경 → invalidate refetch 후 화면 반영 (stateful)
//
// i18n 정본.
//   - inboxLabels: apps/web/src/i18n/inbox-labels.ts 단일 진실 출처
//   - 하드코딩 금지 (PR #22 §F4 학습)

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { inboxLabels } from '../src/i18n/inbox-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — i18n 정본 참조
// ─────────────────────────────────────────────────────────────────────────────

/** 탭 라벨 */
const TAB_ALL = inboxLabels.tabs.all
const TAB_UNREAD = inboxLabels.tabs.unread
const TAB_ARCHIVED = inboxLabels.tabs.archived

/** 빈 상태 메시지 */
const EMPTY_ALL = inboxLabels.empty.all
const EMPTY_UNREAD = inboxLabels.empty.unread
// EMPTY_ARCHIVED(보관함 빈 상태)는 기본 시드에 보관 항목이 존재해 검증 불가 — 분별 시드 인프라 후속 트랙(E2E-9 SKIP)

/** 항목 버튼 라벨 */
const BTN_MARK_READ = inboxLabels.item.markRead
const BTN_ARCHIVE = inboxLabels.item.archive

/** 일괄 읽음 버튼 */
const BTN_READ_ALL = inboxLabels.bulk.readAll

/** 검색 placeholder */
const SEARCH_PLACEHOLDER = inboxLabels.search.placeholder

/** Header 종 aria-label */
const BELL_ARIA_LABEL = inboxLabels.bell.ariaLabel

/** data-testid */
const UNREAD_BADGE_TESTID = 'inbox-unread-badge'
const UNREAD_MARKER_TESTID = 'inbox-item-unread-marker'

// ─────────────────────────────────────────────────────────────────────────────
// defaultInboxFixtures 요약 (inbox-fixtures.ts 정본 기준)
//
//   inboxFixtureUnread    : id=f0...001, issueKey=ATLAS-1, readAt=null, archivedAt=null
//   inboxFixtureRead      : id=f0...002, issueKey=ATLAS-2, readAt=비null, archivedAt=null
//   inboxFixtureArchived  : id=f0...003, issueKey=null,    readAt=비null, archivedAt=비null
//   inboxFixtureUnreadSystem : id=f0...004, issueKey=ATLAS-3, readAt=null, archivedAt=null
//   inboxFixtureUnreadArchived : id=f0...005, issueKey=ATLAS-4, readAt=null, archivedAt=비null
//
// 탭별 기대 항목.
//   ALL    : archivedAt=null → f0...001(ATLAS-1), f0...002(ATLAS-2), f0...004(ATLAS-3) — 3건
//   UNREAD : readAt=null AND archivedAt=null → f0...001, f0...004 — 2건
//   ARCHIVED : archivedAt=비null → f0...003, f0...005 — 2건
//
// 미읽음 카운트(unread-count) = readAt=null AND archivedAt=null = 2건
// ─────────────────────────────────────────────────────────────────────────────

/** 픽스처 issueKey 상수 */
const FIXTURE_UNREAD_ISSUE_KEY = 'ATLAS-1'
const FIXTURE_READ_ISSUE_KEY = 'ATLAS-2'
const FIXTURE_UNREAD_ISSUE_KEY_2 = 'ATLAS-3'

/** 픽스처 제목 상수 (검색 필터 시나리오용) */
const FIXTURE_UNREAD_TITLE = '이슈 담당자로 지정됨'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * /inbox 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * 페이지 제목이 렌더될 때까지 대기.
 */
async function navigateToInbox(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(() => {
    window.history.pushState({}, '', '/inbox')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })
  await expect(page.getByRole('heading', { name: inboxLabels.page.title })).toBeVisible()
}

/**
 * 탭 버튼을 클릭하고 탭이 선택 상태(aria-selected=true)가 될 때까지 대기한다.
 */
async function clickTab(page: import('@playwright/test').Page, tabName: string): Promise<void> {
  const tab = page.getByRole('tab', { name: tabName, exact: true })
  await tab.click()
  await expect(tab).toHaveAttribute('aria-selected', 'true')
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-03 개인 알림 보관함 (InboxPage)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice로 로그인 → /dashboard 진입 → ServiceWorker 기동 완료
    // alice userId=00000000-0000-4000-8000-000000000001 (inbox-handlers.ts 자동 시드 기준)
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-1 진입: Header 종 뱃지 → 클릭 → /inbox 페이지 도달
  //
  // Given   alice로 로그인, /dashboard 진입
  //         defaultInboxFixtures 자동 시드 (미읽음 2건)
  //         unread-count = 2
  // When    Header 🔔 종 아이콘(aria-label=BELL_ARIA_LABEL) 클릭
  // Then    /inbox 페이지로 이동
  //         페이지 제목 "알림 보관함" 표시
  //         미읽음 뱃지 "2" 표시 (data-testid=inbox-unread-badge)
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-1 진입 — Header 종 뱃지 표시 + 클릭 시 /inbox 페이지 도달', async ({ page }) => {
    // Given. /dashboard에 있는 상태 (loginAsAlice 완료)

    // Then. Header 종 아이콘에 미읽음 뱃지 표시
    const badge = page.getByTestId(UNREAD_BADGE_TESTID)
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('2')

    // When. 종 아이콘(Link to="/inbox") 클릭
    const bellLink = page.getByRole('link', { name: BELL_ARIA_LABEL })
    await expect(bellLink).toBeVisible()
    await bellLink.click()

    // Then. /inbox 페이지 도달
    await page.waitForURL('**/inbox')
    await expect(page.getByRole('heading', { name: inboxLabels.page.title })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-2 탭 전환: 전체/안읽음/보관함 — 탭별 목록 변화
  //
  // Given   alice로 로그인, /inbox 진입
  //         defaultInboxFixtures: ALL=3건, UNREAD=2건, ARCHIVED=2건
  // When    각 탭 클릭
  // Then    탭별 항목 표시 변화 확인
  //         - 전체 탭: ATLAS-1, ATLAS-2, ATLAS-3 issueKey 링크 표시
  //         - 안읽음 탭: ATLAS-1, ATLAS-3 issueKey 링크 표시
  //         - 보관함 탭: issueKey=null(f0...003) + ATLAS-4(f0...005) 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-2 탭 전환 — 전체/안읽음/보관함 탭별 목록 변화', async ({ page }) => {
    // Given. /inbox SPA 내부 이동
    await navigateToInbox(page)

    // --- 전체 탭 (기본) ---
    // 전체 탭 활성 확인
    await expect(page.getByRole('tab', { name: TAB_ALL })).toHaveAttribute('aria-selected', 'true')

    // ATLAS-1, ATLAS-2, ATLAS-3 issueKey 링크 표시 (보관 안 된 항목)
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY })).toBeVisible()
    await expect(page.getByRole('link', { name: FIXTURE_READ_ISSUE_KEY })).toBeVisible()
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY_2 })).toBeVisible()

    // --- 안읽음 탭 ---
    await clickTab(page, TAB_UNREAD)

    // UNREAD: readAt=null AND archivedAt=null → ATLAS-1, ATLAS-3만 표시
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY })).toBeVisible()
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY_2 })).toBeVisible()
    // ATLAS-2는 readAt 비null → 안읽음 탭에 미표시
    await expect(page.getByRole('link', { name: FIXTURE_READ_ISSUE_KEY })).toHaveCount(0)

    // --- 보관함 탭 ---
    await clickTab(page, TAB_ARCHIVED)

    // ARCHIVED: archivedAt 비null → f0...003(issueKey=null) + f0...005(ATLAS-4)
    // ATLAS-4 issueKey 링크 표시
    await expect(page.getByRole('link', { name: 'ATLAS-4' })).toBeVisible()
    // 미보관 항목(ATLAS-1, ATLAS-2, ATLAS-3)은 미표시
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY })).toHaveCount(0)
    await expect(page.getByRole('link', { name: FIXTURE_READ_ISSUE_KEY })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-3 읽음 토글 라운드트립 — MSW stateful + 뱃지 감소
  //
  // Given   alice로 로그인, /inbox 진입, 안읽음 탭
  //         ATLAS-1(미읽음) — 미읽음 마커 표시, "읽음으로 표시" 버튼 노출
  //         미읽음 뱃지 = 2
  // When    ATLAS-1 항목의 "읽음으로 표시" 버튼 클릭
  //         → MSW patchReadHandler: store 변경(readAt 설정)
  //         → invalidateQueries → refetch → 화면 반영
  // Then    안읽음 탭에서 ATLAS-1 항목 사라짐 (readAt 비null → UNREAD 탭 필터 제외)
  //         Header 미읽음 뱃지 "1"로 감소
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-3 읽음 토글 — 안읽음 항목 읽음 처리 시 목록 제거 + 뱃지 감소', async ({ page }) => {
    // Given. /inbox SPA 내부 이동
    await navigateToInbox(page)

    // Given. 안읽음 탭으로 전환
    await clickTab(page, TAB_UNREAD)

    // Given. ATLAS-1 항목의 article 컨테이너 (strict mode 회피)
    const atlas1Article = page.getByRole('article', { name: FIXTURE_UNREAD_TITLE })
    await expect(atlas1Article).toBeVisible()

    // Given. 미읽음 마커 표시 (data-testid=inbox-item-unread-marker)
    await expect(atlas1Article.getByTestId(UNREAD_MARKER_TESTID)).toBeVisible()

    // Given. "읽음으로 표시" 버튼 노출 (컨테이너 한정 — strict mode 회피)
    const markReadBtn = atlas1Article.getByRole('button', { name: BTN_MARK_READ, exact: true })
    await expect(markReadBtn).toBeVisible()

    // When. 읽음 처리 (SPA 내부 상태 변화 — reload 금지)
    await markReadBtn.click()

    // Then. 안읽음 탭에서 ATLAS-1 사라짐 (readAt 비null → UNREAD 필터 제외)
    await expect(atlas1Article).toHaveCount(0)

    // Then. Header 미읽음 뱃지 "1"로 감소 (unread-count invalidate 반영)
    const badge = page.getByTestId(UNREAD_BADGE_TESTID)
    await expect(badge).toContainText('1')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-4 보관 토글 — 항목이 현재 탭에서 사라지고 보관함에 표시
  //
  // Given   alice로 로그인, /inbox 진입, 전체 탭
  //         ATLAS-2(읽음+미보관) — "보관" 버튼 노출
  // When    ATLAS-2 항목의 "보관" 버튼 클릭
  //         → MSW patchArchiveHandler: store 변경(archivedAt 설정)
  //         → invalidate refetch → ALL 탭에서 제거 (archivedAt 비null)
  // Then    전체 탭에서 ATLAS-2 사라짐
  // When2   보관함 탭으로 전환
  // Then2   보관함 탭에 ATLAS-2 issueKey 링크 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-4 보관 토글 — 전체 탭에서 사라지고 보관함 탭에 표시', async ({ page }) => {
    // Given. /inbox SPA 내부 이동 (전체 탭 기본)
    await navigateToInbox(page)

    // Given. ATLAS-2(읽음+미보관) 항목 확인
    const atlas2Article = page.getByRole('article', { name: '코멘트가 추가됨' })
    await expect(atlas2Article).toBeVisible()

    // Given. 보관 버튼 노출
    const archiveBtn = atlas2Article.getByRole('button', { name: BTN_ARCHIVE, exact: true })
    await expect(archiveBtn).toBeVisible()

    // When. 보관 처리
    await archiveBtn.click()

    // Then. 전체 탭에서 ATLAS-2 사라짐 (archivedAt 비null → ALL 탭 필터 제외)
    await expect(atlas2Article).toHaveCount(0)

    // When2. 보관함 탭으로 전환
    await clickTab(page, TAB_ARCHIVED)

    // Then2. 보관함 탭에 ATLAS-2 issueKey 링크 표시
    await expect(page.getByRole('link', { name: FIXTURE_READ_ISSUE_KEY })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-5 일괄 읽음("전체 읽음") — 안읽음 0건
  //
  // Given   alice로 로그인, /inbox 진입
  //         미읽음 뱃지 = 2
  //         안읽음 탭: ATLAS-1, ATLAS-3 (2건)
  // When    "전체 읽음" 버튼 클릭
  //         → MSW postReadAllHandler: 미읽음+미보관 전체 readAt 설정
  //         → invalidate refetch
  // Then    안읽음 탭 빈 상태 메시지 "읽지 않은 알림이 없습니다." 표시
  //         미읽음 뱃지 사라짐 (count=0 → 뱃지 숨김)
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-5 일괄 읽음 — "전체 읽음" 클릭 시 안읽음 0건, 뱃지 사라짐', async ({ page }) => {
    // Given. /inbox SPA 내부 이동
    await navigateToInbox(page)

    // Given. 미읽음 뱃지 = 2 확인
    const badge = page.getByTestId(UNREAD_BADGE_TESTID)
    await expect(badge).toContainText('2')

    // When. "전체 읽음" 버튼 클릭
    const readAllBtn = page.getByRole('button', { name: BTN_READ_ALL, exact: true })
    await expect(readAllBtn).toBeVisible()
    await readAllBtn.click()

    // Given. 안읽음 탭으로 전환
    await clickTab(page, TAB_UNREAD)

    // Then. 안읽음 탭 빈 상태 메시지
    await expect(page.getByText(EMPTY_UNREAD)).toBeVisible()

    // Then. 미읽음 뱃지 사라짐 (count=0 → InboxBell이 뱃지 숨김)
    await expect(page.getByTestId(UNREAD_BADGE_TESTID)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-6 검색(텍스트) 필터 결과
  //
  // Given   alice로 로그인, /inbox 진입, 전체 탭
  //         ALL 탭: 3건 (ATLAS-1/이슈 담당자로 지정됨, ATLAS-2/코멘트가 추가됨, ATLAS-3/상태 변경)
  // When    검색 필터에 "담당자" 입력
  //         → MSW getInboxHandler: q="담당자" → title 포함 필터 → 1건
  // Then    ATLAS-1 항목(제목 "이슈 담당자로 지정됨")만 표시
  //         ATLAS-2, ATLAS-3 항목 미표시
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-6 검색 필터 — 텍스트 검색으로 제목 필터링', async ({ page }) => {
    // Given. /inbox SPA 내부 이동 (전체 탭)
    await navigateToInbox(page)

    // Given. 초기 3건 모두 표시 확인
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY })).toBeVisible()
    await expect(page.getByRole('link', { name: FIXTURE_READ_ISSUE_KEY })).toBeVisible()
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY_2 })).toBeVisible()

    // When. 검색 필터 텍스트 입력 (InboxFilters — placeholder 기반)
    const searchInput = page.getByPlaceholder(SEARCH_PLACEHOLDER)
    await expect(searchInput).toBeVisible()
    await searchInput.fill('담당자')

    // Then. 1건만 표시 (title에 "담당자" 포함: "이슈 담당자로 지정됨")
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY })).toBeVisible()
    // ATLAS-2("코멘트가 추가됨"), ATLAS-3("ATLAS-3 상태가 변경됨") 미포함
    await expect(page.getByRole('link', { name: FIXTURE_READ_ISSUE_KEY })).toHaveCount(0)
    await expect(page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY_2 })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-7 빈 상태 메시지 — 빈 탭별 안내 메시지
  //
  // Given   alice로 로그인, /inbox 진입
  //         검색 필터에 존재하지 않는 텍스트 입력 → ALL 탭 빈 결과
  //         안읽음 탭: 데이터 있음(2건)
  //         보관함 탭: 데이터 있음(2건)
  // When    검색 필터에 "존재하지않는알림제목xyz" 입력 (ALL 탭 빈 상태 유발)
  // Then    전체 탭 빈 상태 메시지 "받은 알림이 없습니다." 표시
  //
  // Note: 안읽음 탭 + 보관함 탭 빈 상태는 E2E-5(일괄 읽음) 시나리오로 커버됨
  //       EMPTY_ARCHIVED는 보관함이 초기에 이미 2건 있어 별도 분별 시드 없이 검증 불가
  //       → 검색 필터로 ALL 탭 빈 상태만 검증
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-7 빈 상태 메시지 — 검색 결과 없음 시 전체 탭 빈 상태 표시', async ({ page }) => {
    // Given. /inbox SPA 내부 이동 (전체 탭)
    await navigateToInbox(page)

    // When. 존재하지 않는 검색어 입력
    const searchInput = page.getByPlaceholder(SEARCH_PLACEHOLDER)
    await searchInput.fill('존재하지않는알림제목xyz')

    // Then. 전체 탭 빈 상태 메시지
    await expect(page.getByText(EMPTY_ALL)).toBeVisible()

    // Then. 항목 없음
    await expect(page.getByRole('article')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-8 issueKey → 이슈 상세 SPA 이동
  //
  // Given   alice로 로그인, /inbox 진입, 전체 탭
  //         ATLAS-1 항목에 issueKey 링크 표시
  // When    ATLAS-1 issueKey 링크 클릭
  //         → SPA 내부 이동 (/issues/ATLAS-1)
  // Then    URL이 /issues/ATLAS-1로 변경됨 (SPA Link — page.reload 없이)
  //
  // Note: page.reload() 금지 (MSW store 리셋). URL 변경만 검증 (이슈 상세 렌더는 타 E2E 커버)
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-8 issueKey 링크 — 클릭 시 이슈 상세 SPA 이동', async ({ page }) => {
    // Given. /inbox SPA 내부 이동
    await navigateToInbox(page)

    // Given. ATLAS-1 issueKey 링크 확인
    const issueLink = page.getByRole('link', { name: FIXTURE_UNREAD_ISSUE_KEY })
    await expect(issueLink).toBeVisible()

    // When. issueKey 링크 클릭
    await issueLink.click()

    // Then. URL이 /issues/ATLAS-1로 변경됨 (TanStack Router SPA 이동)
    await page.waitForURL(`**/issues/${FIXTURE_UNREAD_ISSUE_KEY}`)
    expect(page.url()).toContain(`/issues/${FIXTURE_UNREAD_ISSUE_KEY}`)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-9 보관함 빈 상태 — ARCHIVED 탭 빈 상태 메시지
  //
  // Note: 초기 보관함에 2건이 있으므로 E2E-4(보관 후 보관함 이동) 시나리오와 겹치지 않도록
  //       이미 보관된 항목을 보관 해제해서 보관함을 비우는 방식은 추가 구현이 필요.
  //       대신 안읽음 탭 초기 빈 상태 검증: 일괄 읽음 후 안읽음 탭에서 EMPTY_UNREAD 표시는
  //       E2E-5에서 커버됨.
  //       보관함 빈 상태(EMPTY_ARCHIVED)는 별도 분별 시드 없이 MSW 기본 시드로는 검증 불가.
  //       → SKIPPED (별도 분별 시드 인프라 필요 시 후속 트랙)
  // ───────────────────────────────────────────────────────────────────────────
  // SKIPPED: 보관함 탭 EMPTY_ARCHIVED는 별도 분별 시드 필요 — 후속 트랙
  // (현재 MSW 기본 시드에서 보관함에 2건 존재, 비우려면 보관해제 2회 필요 — E2E-4 중복 검증)
})
