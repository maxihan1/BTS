// S8 — 이미 인증된 사용자가 /login 진입 시 /dashboards 즉시 리다이렉트 검증
//
// 회귀 흡수(FR-PF-02 Task 7, 게이트1 확정): redirectIfAuth 폴백이 `/dashboard`(단수)에서
// `/dashboards`(복수)로 바뀌었다. 공용 헬퍼 `./fixtures/auth-fixtures.ts`의 loginAsAlice()는
// 여전히 `**/dashboard`(단수) 도착을 기다리도록 구현돼 있어 이 변경 이후로는 타임아웃난다
// (허용 파일 범위 밖이라 이 PR에서 직접 고치지 않음 — qa-engineer 보고 참고). 이 스펙은 로컬
// 로그인 스텝을 인라인해 그 회귀를 우회한다.
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { dashboardLabels } from '../src/i18n/dashboard-labels'

test('S8 이미 인증된 사용자가 /login 진입 → /dashboards 즉시 리다이렉트', async ({ page }) => {
  // 1단계. 정상 로그인 — 2단계 identifier-first 흐름 (FR-AU-07)
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()

  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
  await page.waitForURL('**/dashboards')

  // 2단계. 인증 상태에서 /login 진입 시도
  await page.goto('/login')

  // 3단계. /dashboards 로 즉시 리다이렉트 (redirectIfAuth 가드 동작, FR-PF-02 게이트1 확정 폴백)
  await page.waitForURL('**/dashboards')
  await expect(page.getByRole('heading', { name: dashboardLabels.list.title, level: 1 })).toBeVisible()
})
