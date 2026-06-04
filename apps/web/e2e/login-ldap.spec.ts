// LDAP 로그인 E2E 시나리오 — S1-ldap 정상 로그인 (LDAP-corp provider) + S2-ldap 잘못된 비번 (401 한국어 에러)
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'

/**
 * S1-ldap — LDAP-corp provider 정상 로그인
 *
 * Given  LoginForm 의 provider 드롭다운 (기본값 Local)
 * When   LDAP-corp 선택 → alice / Test1234! 입력 → 로그인 버튼 클릭
 * Then   /dashboard 리다이렉트 + 환영 메시지 'alice' + Header 'alice 계정 메뉴'
 */
test('S1-ldap LDAP-corp 정상 로그인 — alice/Test1234! → /dashboard 환영 메시지', async ({ page }) => {
  await page.goto('/login')

  // 페이지 진입 확인 — "BTS 로그인" 헤딩 (LoginPage)
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()

  // provider 드롭다운 클릭 — aria-label=loginStrings.providerLabel 기준 (i18n drift 차단)
  await page.getByRole('combobox', { name: loginStrings.providerLabel }).click()

  // LDAP-corp 옵션 선택 — Radix Portal 렌더이므로 role="option" 으로 자동 탐색
  await page.getByRole('option', { name: loginStrings.providerLdapCorp }).click()

  // username, password 입력
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('Test1234!')

  // 로그인 버튼 클릭 → 제출 (exact:true — "Okta SSO 로 로그인" 버튼과 구분)
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

  // /dashboard 리다이렉트 대기
  await page.waitForURL('**/dashboard')

  // dashboard 본문에 환영 메시지 존재 확인
  await expect(page.getByRole('heading', { name: '환영합니다, alice' })).toBeVisible()

  // Header 트리거 버튼에 alice 표시 확인 (DropdownMenu trigger)
  await expect(page.getByRole('button', { name: 'alice 계정 메뉴' })).toBeVisible()
})

/**
 * S2-ldap — LDAP-corp provider 잘못된 비밀번호
 *
 * Given  LoginForm 의 provider 드롭다운 (기본값 Local)
 * When   LDAP-corp 선택 → alice / wrong 입력 → 로그인 버튼 클릭
 * Then   401 한국어 에러 메시지 표시 + URL /login 유지
 */
test('S2-ldap LDAP-corp 잘못된 비밀번호 — alice/wrong → 401 한국어 에러 + URL /login 유지', async ({ page }) => {
  await page.goto('/login')

  // provider 드롭다운 클릭 후 LDAP-corp 선택 (i18n drift 차단)
  await page.getByRole('combobox', { name: loginStrings.providerLabel }).click()
  await page.getByRole('option', { name: loginStrings.providerLdapCorp }).click()

  // username 입력
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')

  // 잘못된 비밀번호 입력
  await page.getByLabel(loginStrings.passwordLabel).fill('wrong')

  // 로그인 버튼 클릭 (exact:true — "Okta SSO 로 로그인" 버튼과 구분)
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

  // 에러 메시지가 나타날 때까지 대기 (API 응답 소요 시간 감안)
  const errorAlert = page.getByRole('alert')
  await expect(errorAlert).toBeVisible({ timeout: 10_000 })
  await expect(errorAlert).toHaveText(loginStrings.errorInvalidCredentials)

  // URL이 /login (또는 /login?returnTo=...) 에 머물러 있어야 한다
  await expect(page).toHaveURL(/\/login/)
})
