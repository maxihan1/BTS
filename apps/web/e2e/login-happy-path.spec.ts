// 정상 로그인 E2E 시나리오 — alice/password(Local) → /dashboard 환영 메시지
import { test, expect } from '@playwright/test'

test('S1 정상 로그인 — alice/password (Local) → /dashboard 환영 메시지', async ({ page }) => {
  await page.goto('/login')

  // 페이지 진입 확인 — "BTS 로그인" 헤딩 (LoginPage)
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

  // username, password 입력 (provider 기본값 Local — 변경 불필요)
  await page.getByLabel('사용자명').fill('alice')
  await page.getByLabel('비밀번호').fill('password')

  // 로그인 버튼 클릭 → 제출
  await page.getByRole('button', { name: '로그인' }).click()

  // /dashboard 리다이렉트 대기
  await page.waitForURL('**/dashboard')

  // dashboard 본문에 환영 메시지 존재 확인
  await expect(page.getByRole('heading', { name: '환영합니다, alice' })).toBeVisible()

  // Header 트리거 버튼에 alice 표시 확인 (DropdownMenu trigger)
  await expect(page.getByRole('button', { name: 'alice 계정 메뉴' })).toBeVisible()
})
