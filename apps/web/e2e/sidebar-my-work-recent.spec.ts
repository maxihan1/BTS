// 사이드바 "내 작업"·"최근 항목" E2E — FR-UX-08 PR-B Task 7 (S7·S8·S9)
//
// 관련 학습.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript 로 저장값 선주입
//   - frontend-nav-aria-label-e2e-contract: nav aria-label 은 계약이다 (신규 nav 도입 금지)
//   - e2e-playwright-filter-arg-drop: 이 spec 단독 실행은 바이너리 직접 호출로
//
// ★ `page.addInitScript` 는 이후 **모든 goto 에 재적용**된다. 시작 상태를 심는 용도로는
//   맞지만(여기 쓰임), 1회성 초기화에 쓰면 왕복마다 값을 지워 "영속 안 됨" 거짓 실패가 난다.
//
// ★ 판별자 선택 — "최근 항목이 무엇을 담고 있나"를 **사이드바 링크의 접근가능 이름**으로
//   판정한다. localStorage 내부 표현이 바뀌어도 사용자가 보는 것이 기준이라 의미가 유지된다.
import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { navLabels } from '../src/i18n/nav-labels'
import { ALICE_USER_ID } from '../src/mocks/auth-fixtures'

/** `hooks/use-recent-issues.ts` 의 저장 키 — 값은 JSON 인코딩된 문자열 배열이다 */
const RECENT_ISSUES_KEY = 'bts.recent-issues'

/** 로그인 전에 최근 이슈 목록을 심는다 */
async function seedRecentIssues(page: Page, keys: string[]): Promise<void> {
  await page.addInitScript(
    ([storageKey, value]: [string, string]) => {
      window.localStorage.setItem(storageKey, value)
    },
    [RECENT_ISSUES_KEY, JSON.stringify(keys)] as [string, string],
  )
}

/** 사이드바 "최근 항목" 목록 로케이터 — 새 nav 가 아니라 list 롤이다(FR13-b) */
function recentList(page: Page) {
  return page.getByRole('list', { name: navLabels.recent, exact: true })
}

test.describe('FR-UX-08 PR-B — 사이드바 "내 작업"·"최근 항목"', () => {
  test('S7: "내 작업" 을 누르면 내가 담당인 이슈 목록으로 간다', async ({ page }) => {
    await loginAsAlice(page)

    // exact: true — Playwright getByRole 의 name 은 기본이 substring 매칭이다
    const myWork = page.getByRole('link', { name: navLabels.myWork, exact: true })
    await expect(myWork).toBeVisible()

    await myWork.click()

    // `?assignee=me` 는 실재하지 않는다 — 센티널은 `unassigned` 뿐이고 그 외는 400.
    // 링크는 whoami 의 userId 를 싣는다.
    await expect(page).toHaveURL(new RegExp(`/issues\\?.*assignee=${ALICE_USER_ID}`))
    await expect(page.getByRole('table', { name: '이슈 목록' })).toBeVisible()

    // §1-A — 링크가 projectKey 를 싣지 않는다. 프로젝트 스코프는 라우트가 해소한다.
    expect(page.url()).not.toContain('projectKey=')
  })

  test('S7-b (E9): "내 작업" 링크는 이슈 nav 보다 위에 온다 (§8-A D-A)', async ({ page }) => {
    await loginAsAlice(page)

    const mainNav = page.getByRole('navigation', { name: navLabels.mainNav, exact: true })
    const linkTexts = await mainNav.getByRole('link').allInnerTexts()
    const myWorkIndex = linkTexts.findIndex((t) => t.trim() === navLabels.myWork)
    const issuesIndex = linkTexts.findIndex((t) => t.trim() === navLabels.issues)

    expect(myWorkIndex).toBeGreaterThanOrEqual(0)
    expect(myWorkIndex).toBeLessThan(issuesIndex)
  })

  test('S8: 이슈를 열어보면 "최근 항목" 에 MRU 순으로 쌓인다', async ({ page }) => {
    await loginAsAlice(page)

    // 최근 목록이 비어 있으면 섹션 자체가 없다(E3) — 그것이 시작 상태다
    await expect(recentList(page)).toHaveCount(0)

    await page.goto('/issues/ATLAS-1')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    await page.goto('/issues/ATLAS-2')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    const list = recentList(page)
    await expect(list).toBeVisible()

    const items = list.getByRole('listitem')
    await expect(items).toHaveCount(2)
    // MRU — 나중에 본 ATLAS-2 가 맨 앞
    await expect(items.nth(0)).toContainText('ATLAS-2')
    await expect(items.nth(1)).toContainText('ATLAS-1')
  })

  test('S9: 죽은 키는 조용히 빠지고 사이드바는 살아 있다', async ({ page }) => {
    // 삭제됐거나 권한이 회수된 이슈는 조회가 403/404 로 떨어져 자동 탈락한다(E2).
    await seedRecentIssues(page, ['ATLAS-1', 'NOSUCH-999'])
    await loginAsAlice(page)

    const list = recentList(page)
    await expect(list).toBeVisible()
    await expect(list.getByRole('listitem')).toHaveCount(1)
    await expect(list).toContainText('ATLAS-1')
    await expect(list).not.toContainText('NOSUCH-999')

    // 사이드바 전체가 깨지지 않는다 — 기존 nav 계약이 그대로 살아 있다
    await expect(page.getByRole('navigation', { name: navLabels.mainNav, exact: true })).toBeVisible()
    await expect(page.getByRole('navigation', { name: navLabels.projectNav, exact: true })).toBeVisible()
  })

  test('S9-b (§8-A D-B): 사이드바를 접으면 "최근 항목" 섹션이 사라진다', async ({ page }) => {
    await seedRecentIssues(page, ['ATLAS-1'])
    await loginAsAlice(page)

    await expect(recentList(page)).toBeVisible()

    // ⚠️ 선재 결함(이 PR 무관) — `aria-label="사이드바 접기"` 버튼이 **2개**다.
    // 하나는 상단바(`banner`), 하나는 사이드바(`complementary`) 안에 있다. 전역 조회는
    // strict mode 위반이 되므로 사이드바 안으로 스코프를 좁힌다.
    // 같은 접근성 이름이 한 화면에 둘이면 스크린리더 사용자에게도 구분이 안 된다 — 별건 보고.
    await page
      .getByRole('complementary')
      .getByRole('button', { name: navLabels.collapseSidebar, exact: true })
      .click()

    // 64px 레일에서 5개 항목은 구분 불가능한 표시 5개가 되므로 정보가 아니라 소음이다.
    await expect(recentList(page)).toHaveCount(0)
    // 반면 "내 작업" 은 단일 링크라 기존 관례대로 DOM 에 남는다(E10)
    await expect(page.getByRole('link', { name: navLabels.myWork, exact: true })).toBeVisible()
  })
})
