// S8 — 이미 인증된 사용자가 /login 진입 시 /dashboard 즉시 리다이렉트 검증
import { test, expect } from '@playwright/test'

import { loginAsAlice } from './fixtures/auth-fixtures'

test('S8 이미 인증된 사용자가 /login 진입 → /dashboard 즉시 리다이렉트', async ({ page }) => {
  // 1단계. 정상 로그인 — 2단계 identifier-first 흐름 (FR-AU-07)
  await loginAsAlice(page)

  // 2단계. 인증 상태에서 /login 진입 시도
  await page.goto('/login')

  // 3단계. /dashboard 로 즉시 리다이렉트 (redirectIfAuth 가드 동작)
  await page.waitForURL('**/dashboard')
  await expect(page.getByRole('heading', { name: '환영합니다, alice' })).toBeVisible()
})
