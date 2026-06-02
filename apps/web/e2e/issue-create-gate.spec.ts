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
//   Given  alice 로그인 + addInitScript 로 localStorage 플래그(__bts_e2e_force_create_false='true') 설정
//   When   /issues 목록 진입 (첫 권한 fetch 시점부터 MSW 핸들러가 플래그를 읽어 CREATE:false 반환)
//   Then   new-issue-button 이 비활성(disabled button), 클릭해도 /issues 유지
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: data-testid 셀렉터로 텍스트 중복 회피
//   - e2e-fixture-whoami-userid-alignment: alice userId 00000000-...-001 (MSW whoami 정합)
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - ui-pr-defer-e2e-regression-latent: 기존 spec 회귀 방지
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

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
  // S2 — CREATE 권한 없음: addInitScript localStorage 플래그로 CREATE:false 주입 → disabled button
  //
  // addInitScript 는 모든 페이지 로드/리로드 직전에 실행된다.
  // goto('/issues') 전에 플래그를 설정하면 첫 권한 fetch 시점부터 MSW 핸들러가
  // CREATE:false 를 반환한다. worker.use / invalidateQueries / __msw 노출 불필요.
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 CREATE 권한 없음 — new-issue-button 이 disabled, 클릭해도 /issues 유지', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 다음 페이지 로드 전에 localStorage 플래그를 심는다.
    // MSW 핸들러(project-permission-handlers.ts E2E_FORCE_CREATE_FALSE_KEY)가
    // 이 플래그를 읽어 CREATE:false 를 반환한다.
    await page.addInitScript(() => {
      // 핸들러 상수 E2E_FORCE_CREATE_FALSE_KEY = '__bts_e2e_force_create_false' 와 동일
      window.localStorage.setItem('__bts_e2e_force_create_false', 'true')
    })

    // When. /issues 진입 — 첫 권한 fetch 부터 CREATE:false
    await page.goto('/issues')

    const newIssueBtn = page.getByTestId('new-issue-button')
    await expect(newIssueBtn).toBeVisible()

    // Then. disabled button (a 태그 아님)
    await expect(newIssueBtn).toBeDisabled()

    // Then. href 속성 없음 (button 태그이므로)
    await expect(newIssueBtn).not.toHaveAttribute('href')

    // Then. 클릭해도 /issues 유지 (disabled button 은 click 이벤트 차단).
    // force:true 로 클릭해도 URL 이 바뀌지 않는지 확인.
    await newIssueBtn.click({ force: true })
    await expect(page).toHaveURL(/\/issues$/)
    expect(new URL(page.url()).pathname).toBe('/issues')
  })
})
