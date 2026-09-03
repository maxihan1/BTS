// E2E 로그인 헬퍼 정본 — 단일 화면 폼 (provider + 식별자 + 비밀번호)
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

import { loginStrings } from '../../src/i18n/ko'

/**
 * Alice (dev seed LOCAL provider) 로 로그인하고 로그인 후 목적지 진입까지 완료한다.
 *
 * 로그인은 모달 안의 **한 화면**에서 끝난다. 이메일 선입력 1단계는 폐기됐다.
 * `alice` 는 `@` 가 없어 도메인 route 조회 자체가 일어나지 않는다(LDAP 사용자명과 같은 경로).
 *
 * MSW dev mock 환경 가정 (backend dev 서버 불필요).
 *
 * @param page Playwright Page 객체
 */
export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  // provider 드롭다운 로딩 대기 + Local 선택
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
  await page.waitForURL('**/dashboard*')
}
