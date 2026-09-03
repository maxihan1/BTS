// S2 잘못된 비밀번호 — 401 응답 시 한국어 에러 메시지 표시 + URL /login 유지 검증
//
// FR-AU-07 재조정: 단일 화면 폼 적용.
// alice/wrong 입력 → 401 에러 확인
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: exact:true 로 버튼 한정
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 드롭다운 로딩 대기
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'

test('S2 잘못된 비밀번호 — alice/wrong (Local) → 401 한국어 에러 + URL /login 유지', async ({ page }) => {
  await page.goto('/login')

  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()

  // 로그인 버튼이 나타날 때까지
  await expect(page.getByRole('button', { name: loginStrings.submitButton, exact: true })).toBeVisible()

  // 식별자 입력
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')

  // 잘못된 비밀번호 입력
  await page.getByLabel(loginStrings.passwordLabel).fill('wrong')

  // 로그인 버튼 클릭
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

  // 에러 메시지가 나타날 때까지 대기 (API 응답 소요 시간 감안)
  const errorAlert = page.getByRole('alert')
  await expect(errorAlert).toBeVisible({ timeout: 10_000 })
  await expect(errorAlert).toHaveText(loginStrings.errorInvalidCredentials)

  // URL이 /login (또는 /login?returnTo=...) 에 머물러 있어야 한다
  await expect(page).toHaveURL(/\/login/)
})
