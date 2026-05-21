// S8 — 이미 인증된 사용자가 /login 진입 시 /dashboard 즉시 리다이렉트 검증
import { test, expect } from '@playwright/test'

test('S8 이미 인증된 사용자가 /login 진입 → /dashboard 즉시 리다이렉트', async ({ page }) => {
  // 1단계. 정상 로그인 (alice/password) — T17 happy path 패턴 재사용
  await page.goto('/login')
  await page.getByLabel('사용자명').fill('alice')
  await page.getByLabel('비밀번호').fill('password')
  await page.getByRole('button', { name: '로그인' }).click()
  await page.waitForURL('**/dashboard')

  // 2단계. 인증 상태에서 /login 진입 시도
  await page.goto('/login')

  // 3단계. /dashboard 로 즉시 리다이렉트 (redirectIfAuth 가드 동작)
  await page.waitForURL('**/dashboard')
  await expect(page.getByRole('heading', { name: '환영합니다, alice' })).toBeVisible()
})
