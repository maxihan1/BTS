// S2 잘못된 비밀번호 — 401 응답 시 한국어 에러 메시지 표시 + URL /login 유지 검증
import { test, expect } from '@playwright/test'

test('S2 잘못된 비밀번호 — alice/wrong (Local) → 401 한국어 에러 + URL /login 유지', async ({ page }) => {
  await page.goto('/login')

  // username 입력
  await page.getByLabel('사용자명').fill('alice')

  // password 입력
  await page.getByLabel('비밀번호').fill('wrong')

  // provider는 기본값 local 유지 — 별도 조작 없음

  // 로그인 버튼 클릭
  await page.getByRole('button', { name: '로그인', exact: true }).click()

  // 에러 메시지가 나타날 때까지 대기 (API 응답 소요 시간 감안)
  const errorAlert = page.getByRole('alert')
  await expect(errorAlert).toBeVisible({ timeout: 10_000 })
  await expect(errorAlert).toHaveText('사용자명 또는 비밀번호가 올바르지 않습니다.')

  // URL이 /login (또는 /login?returnTo=...) 에 머물러 있어야 한다
  await expect(page).toHaveURL(/\/login/)
})
