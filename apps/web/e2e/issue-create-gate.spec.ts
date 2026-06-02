// FR-PM-02 이슈 CREATE 권한 게이트 E2E — "새 이슈" 버튼 활성/비활성 시나리오
//
// project-permissions 응답에 따라 "새 이슈" 버튼이 활성(link)인지 비활성(disabled button)인지 검증한다.
//
// S1 — CREATE 권한 있음 (alice, ADMIN).
//   Given  alice 로그인 + project-permissions CREATE:true (기본 MSW 핸들러)
//   When   /issues 목록 진입
//   Then   new-issue-button 이 활성(enabled, role=link, href=/issues/new), 클릭 시 /issues/new 이동
//
// S2 — CREATE 권한 없음 (비멤버 시뮬레이션).
//   Given  alice 로그인 + project-permissions CREATE:false (page.route 오버라이드)
//   When   /issues 목록 진입
//   Then   new-issue-button 이 비활성(disabled button), 클릭해도 /issues 유지
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: data-testid 셀렉터로 텍스트 중복 회피
//   - e2e-fixture-whoami-userid-alignment: alice userId 00000000-...-001 (MSW whoami 정합)
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - ui-pr-defer-e2e-regression-latent: 기존 spec 회귀 방지
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { nonMemberProjectPermissions } from '../src/mocks/project-permission-fixtures'

test.describe('이슈 CREATE 권한 게이트 (FR-PM-02 Task-6)', () => {
  // ─────────────────────────────────────────────────────────────────────────────
  // S1 — CREATE 권한 있음: alice(ADMIN) → "새 이슈" 링크 활성
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1 CREATE 권한 있음 — new-issue-button 이 link(활성), 클릭 시 /issues/new 이동', async ({ page }) => {
    // Given. alice 로그인 — MSW 기본 핸들러가 CREATE:true 응답
    await loginAsAlice(page)

    // When. 이슈 목록 진입 + 권한 로딩 대기
    await page.goto('/issues')
    const newIssueBtn = page.getByTestId('new-issue-button')
    await expect(newIssueBtn).toBeVisible()

    // Then. role=link (a 태그), href 속성 확인
    await expect(newIssueBtn).toHaveAttribute('href', '/issues/new')

    // Then. 클릭 시 /issues/new 로 이동
    await newIssueBtn.click()
    await page.waitForURL('**/issues/new')
    expect(new URL(page.url()).pathname).toBe('/issues/new')
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S2 — CREATE 권한 없음: page.route 로 CREATE:false 주입 → disabled button
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 CREATE 권한 없음 — new-issue-button 이 disabled, 클릭해도 /issues 유지', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. project-permissions 를 CREATE:false 로 오버라이드 (비멤버 시뮬레이션).
    //
    // MSW 는 Service Worker 레벨에서 fetch 를 가로채므로 Playwright page.route 보다 먼저 처리된다.
    // 따라서 page.addInitScript 로 native fetch 를 monkeypatch 하여 project-permissions
    // 엔드포인트에 대해서만 CREATE:false 응답을 반환하도록 한다. 이는 테스트 전용 수단이며
    // 구현 코드를 변경하지 않는다.
    //
    // 단, addInitScript 는 페이지 리로드 시 적용된다. 따라서 goto('/issues') 전에 먼저 설정한다.
    const nonMemberPerms = nonMemberProjectPermissions
    await page.addInitScript((perms: typeof nonMemberProjectPermissions) => {
      const originalFetch = window.fetch.bind(window)
      window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : (input as Request).url
        if (url.includes('/api/v1/users/me/project-permissions')) {
          const urlObj = new URL(url, window.location.href)
          const projectKey = urlObj.searchParams.get('projectKey') ?? 'ATLAS'
          return new Response(
            JSON.stringify({ projectKey, permissions: perms }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          )
        }
        return originalFetch(input, init)
      }
    }, nonMemberPerms)

    // When. 이슈 목록 진입
    await page.goto('/issues')
    const newIssueBtn = page.getByTestId('new-issue-button')
    await expect(newIssueBtn).toBeVisible()

    // Then. disabled button (a 태그 아님)
    await expect(newIssueBtn).toBeDisabled()

    // Then. href 속성 없음 (button 태그이므로)
    await expect(newIssueBtn).not.toHaveAttribute('href')

    // Then. 클릭해도 /issues 유지 (disabled button 은 click 이벤트 차단).
    // force:true 로 클릭해도 URL 이 바뀌지 않는지 확인.
    // waitForURL 에 타임아웃을 짧게 주어 이동이 발생하지 않음을 검증한다.
    await newIssueBtn.click({ force: true })
    await expect(page).toHaveURL(/\/issues$/)
    expect(new URL(page.url()).pathname).toBe('/issues')
  })
})
