// E2E 로그인 헬퍼 정본 — loginAsAlice/loginAsBob 2단계 identifier-first 흐름 (FR-AU-07)
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

import { loginStrings } from '../../src/i18n/ko'

/**
 * Alice (dev seed LOCAL provider) 로 로그인하고 /dashboard 진입까지 완료한다.
 *
 * identifier-first 2단계 흐름 (FR-AU-07 적용 이후).
 * 1단계: 미매칭 이메일(example.com) 입력 → "계속" → 2단계 폼 진입
 * 2단계: Local provider 선택 + alice / password 입력 → 로그인
 *
 * MSW dev mock 환경 가정 (backend dev 서버 불필요).
 *
 * @param page Playwright Page 객체
 */
export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  // 1단계: 미매칭 도메인 이메일 입력 → "계속"
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()
  // 2단계: provider 드롭다운 로딩 대기 + Local 선택
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
  // 2단계: username / password 입력
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
  await page.waitForURL('**/dashboard*')
}
