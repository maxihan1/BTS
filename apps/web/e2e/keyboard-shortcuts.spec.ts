// FR-UX-05 Task-5 E2E — 전역 키보드 단축키 5종 시나리오 (S1 c / S2 slash / S3a,b leader g / S4 ? 도움말 토글 / S5 입력가드 / E8 비로그인)
//
// 시나리오 개요.
//   S1. `c`      — 새 이슈 폼(`/issues/new`)으로 이동, 제목 입력창 렌더 확인
//   S2. `/`      — AQL 검색 페이지(`/search`)로 이동, heading 확인
//   S3a. `g` `i` — 내 이슈 목록(`/issues`)으로 이동
//   S3b. `g` `d` — 대시보드 목록(`/dashboards`)으로 이동
//   S4. `?`      — 단축키 도움말 모달 토글(열림 → Esc 닫힘 → 재오픈)
//   S5. 입력 포커스 가드 — 검색 입력창에 포커스 중엔 `c` 타이핑이 단축키로 발화하지 않음
//   E8. 비로그인 — `/login`에서 `c`를 눌러도 이동하지 않음(리스너 미등록)
//
// 설계 결정.
//   - 단축키는 전부 클라이언트 라우팅(mutation 0) → 신규 MSW 핸들러 불필요, 기존 핸들러 전부 재사용
//     (command-palette.spec.ts와 동일 근거 — issue/search/dashboard 기본 핸들러)
//   - S1의 "새 이슈 폼 렌더 확인"은 heading이 아니라 제목 입력창(getByLabel) 가시성으로 한다 —
//     issues.new.tsx(IssueCreateForm)에는 페이지 heading이 없다(실제 DOM 확인, 2026-07-05).
//   - S5는 `/issues/new`가 아니라 `/search`에서 검증한다 — `/issues/new` 자체가 `c` 단축키의
//     목적지라 그 페이지에서 타이핑해도(가드가 깨져도) URL이 그대로라 회귀를 못 잡는다.
//     `/search`는 `c`의 목적지와 다른 경로라 가드가 깨지면 URL 변화로 확실히 드러난다.
//   - 이동 검증은 `page.waitForURL(glob)` 대신 `page.waitForURL((url) => url.pathname === ...)`
//     predicate를 쓴다 — 라우트에 쿼리 파라미터가 붙어도(`/issues?page=1` 등) pathname만 정확히
//     비교해 glob 트레일링 와일드카드로 인한 오탐(예: `**/issues**`가 `/issues/new`도 매칭)을 원천 차단.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: dialog/heading을 role+name(exact 기본)으로 한정,
//     도움말 다이얼로그 본문 텍스트는 dialog 컨테이너로 스코프
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 공유 헬퍼 재사용(userId 정합)
//   - ui-pr-defer-e2e-regression-latent: 이 파일만 신규 추가, 기존 E2E 수정 없음
//   - msw-mutation-stateful-refetch / e2e-msw-scenario-toggle-localstorage-flag: 전부 네비게이션이라
//     stateful mutation·시나리오 토글이 필요 없음. `page.reload()` 는 쓰지 않는다.
//     ★단 S5 는 `page.goto('/search')` 로 진입한다(FR-UX-12 F13 이후 상단바가 버튼이 아니라
//      입력창이라 「클릭해서 검색으로 간다」가 없다). goto 는 문서를 새로 열지만 이 파일은
//      시나리오 플래그·stateful mutation 을 쓰지 않으므로 ServiceWorker 리셋이 무해하다.
//      대신 goto 가 폐기한 「상단바 렌더 완료 = keydown 리스너 등록」 전제를 S5 안에서
//      전역 검색 입력창 가시성으로 다시 세운다(아래 S5 주석).

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueCreateStrings } from '../src/i18n/ko'
import { dashboardLabels } from '../src/i18n/dashboard-labels'
import { navLabels } from '../src/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** ShortcutsHelpDialog.tsx DialogPrimitive.Title(비export) — 하드코딩 (command-palette.spec.ts PALETTE_DIALOG_LABEL 선례) */
const HELP_DIALOG_TITLE = '키보드 단축키'

/** issues.index.tsx L508 h1(i18n 미경유 하드코딩 리터럴) — 이슈 목록 페이지 heading */
const ISSUES_LIST_HEADING = '이슈 목록'

/** AqlHighlighter.tsx textarea placeholder 부분 문자열 — search.spec.ts 선례와 동일 셀렉터 */
const AQL_TEXTAREA_SELECTOR = 'textarea[placeholder*="AQL 쿼리를 입력하세요"]'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice로 로그인하고 RootLayout(TopBar + useKeyboardShortcuts 훅) 마운트 완료까지 대기한다.
 *
 * 상단바 전역 검색 **입력창**(FR-UX-12 F13) 렌더를 신호로 사용 — isAuthenticated 분기 렌더 완료 및
 * useKeyboardShortcuts의 document keydown 리스너 등록(useEffect)이 끝났음을 보장한다.
 * (command-palette.spec.ts loginAndWaitForRootReady 미러 — 같은 RootLayout이 두 훅을 함께 마운트)
 *
 * 이름은 `navLabels` 정본에서 읽는다(하드코딩 금지). `exact: true` 필수 —
 * `검색`(AQL 페이지 제출 버튼 전용 이름)이 `전역 검색` 의 substring 이다.
 *
 * @param page Playwright Page 객체
 */
async function loginAndWaitForRootReady(page: Page): Promise<void> {
  await loginAsAlice(page)
  await expect(
    page.getByRole('searchbox', { name: navLabels.globalSearch, exact: true }),
  ).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-05 전역 키보드 단축키', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. `c` — 새 이슈 폼으로 이동
  //
  // Given  alice로 로그인, 착지 페이지(/dashboard)에 입력 가능 요소 없음
  // When   `c` 입력
  // Then   `/issues/new`로 이동, 제목 입력창 렌더 확인
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 c → 새 이슈 폼으로 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)

    // When. `c` 입력
    await page.keyboard.press('c')

    // Then. /issues/new로 이동 + 제목 입력창 렌더 확인
    await page.waitForURL((url) => url.pathname === '/issues/new')
    await expect(page.getByLabel(issueCreateStrings.summaryLabel)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. `/` — 검색 페이지로 이동
  //
  // Given  alice로 로그인
  // When   `/`(물리 키 Slash) 입력
  // Then   `/search`로 이동, AQL 검색 페이지 heading 확인
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 / → AQL 검색 페이지로 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)

    // When. `/` 입력
    await page.keyboard.press('Slash')

    // Then. /search로 이동 + heading 확인
    await page.waitForURL((url) => url.pathname === '/search')
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3a. `g` `i` — 내 이슈 목록으로 이동
  //
  // Given  alice로 로그인
  // When   leader `g` 직후 `i` 입력(1초 이내)
  // Then   `/issues`로 이동, 이슈 목록 heading 확인
  // ───────────────────────────────────────────────────────────────────────────
  test('S3a g i → 내 이슈 목록으로 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)

    // When. leader `g` 직후 `i`
    await page.keyboard.press('g')
    await page.keyboard.press('i')

    // Then. /issues로 이동 + heading 확인
    await page.waitForURL((url) => url.pathname === '/issues')
    await expect(page.getByRole('heading', { name: ISSUES_LIST_HEADING, level: 1 })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3b. `g` `d` — 대시보드 목록으로 이동
  //
  // Given  alice로 로그인, 착지 페이지는 `/dashboard`(단수, 환영 문구) — 목적지 `/dashboards`(복수)와
  //        다른 페이지라 실제 이동 여부를 확실히 검증할 수 있다
  // When   leader `g` 직후 `d` 입력(1초 이내)
  // Then   `/dashboards`로 이동, 대시보드 목록 heading 확인
  // ───────────────────────────────────────────────────────────────────────────
  test('S3b g d → 대시보드 목록으로 이동', async ({ page }) => {
    await loginAndWaitForRootReady(page)

    // When. leader `g` 직후 `d`
    await page.keyboard.press('g')
    await page.keyboard.press('d')

    // Then. /dashboards로 이동 + heading 확인
    await page.waitForURL((url) => url.pathname === '/dashboards')
    await expect(
      page.getByRole('heading', { name: dashboardLabels.list.title, level: 1 }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. `?` — 단축키 도움말 토글
  //
  // Given  alice로 로그인
  // When   `?`(Shift+Slash) 입력
  // Then   도움말 dialog(role=dialog, name="키보드 단축키")가 열리고 단축키 설명이 표시된다
  // When2  `Esc` 입력
  // Then2  dialog가 닫힌다
  // When3  `?` 재입력
  // Then3  dialog가 다시 열린다(토글 동작)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 ? → 단축키 도움말 토글(열림/Esc 닫힘/재오픈)', async ({ page }) => {
    await loginAndWaitForRootReady(page)

    // When. `?` 입력
    await page.keyboard.press('Shift+Slash')

    // Then. 도움말 dialog 열림 + 단축키 설명 표시
    const dialog = page.getByRole('dialog', { name: HELP_DIALOG_TITLE })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('새 이슈 생성', { exact: true })).toBeVisible()

    // When2. Esc
    await page.keyboard.press('Escape')

    // Then2. 닫힘
    await expect(dialog).not.toBeVisible()

    // When3. `?` 재입력(토글 재오픈)
    await page.keyboard.press('Shift+Slash')

    // Then3. 다시 열림
    await expect(dialog).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. 입력 포커스 가드
  //
  // Given  alice로 로그인, 검색 페이지(`/search`) 진입 + AQL 입력창 포커스
  // When   `c` 타이핑
  // Then   URL 불변(`/issues/new`로 이동하지 않음) + 입력값에 'c' 반영(일반 타이핑으로 처리됨)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 입력 포커스 가드 — 검색 입력창 포커스 중엔 c 타이핑이 단축키로 발화하지 않음', async ({
    page,
  }) => {
    await loginAndWaitForRootReady(page)

    // Given. 검색 페이지 진입 — 직접 이동한다.
    //   ★상단바는 FR-UX-12 F13 이후 버튼이 아니라 입력창이라 「클릭해서 검색으로 간다」가 없다.
    //    이 테스트가 재려는 것은 **입력 포커스 가드**지 검색 진입 경로가 아니고,
    //    진입 경로는 S2(`/` → `/search`)가 이미 덮는다. 여기서 또 재면 한 시나리오가
    //    두 가정을 지게 된다.
    await page.goto('/search')
    await page.waitForURL((url) => url.pathname === '/search')
    // ★goto 는 문서를 새로 열어 위 loginAndWaitForRootReady 의 「상단바 렌더 완료」 전제를
    //  폐기한다. 이 시나리오는 **부정 단언**(c 를 쳐도 이동하지 않는다)이라, 리스너가 아예
    //  등록되지 않아도 초록이 된다 — 그래서 상단바 렌더를 여기서 다시 기다려
    //  useKeyboardShortcuts 의 document keydown 등록을 보장한다(가짜 초록 차단).
    await expect(
      page.getByRole('searchbox', { name: navLabels.globalSearch, exact: true }),
    ).toBeVisible()
    await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()

    // When. AQL 입력창 포커스 후 'c' 타이핑
    const queryInput = page.locator(AQL_TEXTAREA_SELECTOR)
    await expect(queryInput).toBeVisible()
    await queryInput.click()
    await page.keyboard.type('c')

    // Then. URL 불변(단축키 목적지 /issues/new로 이동하지 않음) + 입력값에 'c' 반영
    expect(page.url()).not.toContain('/issues/new')
    await expect(page).toHaveURL(/\/search/)
    await expect(queryInput).toHaveValue('c')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E8. 비로그인 — c 눌러도 이동하지 않음
  //
  // Given  비로그인 상태로 `/login` 페이지에 있음(RootLayout이 useKeyboardShortcuts 훅을
  //        enabled=false로 호출 → keydown 리스너 미등록, FR7)
  // When   `c` 입력
  // Then   이동하지 않는다(로그인 heading 그대로 유지)
  // ───────────────────────────────────────────────────────────────────────────
  test('E8 비로그인 — c 눌러도 이동하지 않음', async ({ page }) => {
    // Given. 로그인 없이 /login 페이지
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    // When. `c` 입력
    await page.keyboard.press('c')

    // Then. 이동하지 않음 — 로그인 heading 유지 + URL 불변
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
  })
})
