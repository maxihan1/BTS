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
/**
 * MSW 가 이 문서에서 실제로 가로채기 시작할 때까지 기다린다.
 *
 * ★`goto` 가 돌아왔다고 mocking 이 켜진 것이 아니다. 서비스워커는 문서의 clientId 를
 *   등록한 뒤에만 요청을 가로채는데(`public/mockServiceWorker.js:249`), Playwright 의
 *   어떤 대기 원시도 그 핸드셰이크를 관측하지 않는다. 그 창에 걸린 API 요청은 vite
 *   프록시로 새고 백엔드가 없으니 ECONNREFUSED 가 된다 — 무작위 red 의 원인이다.
 *
 *   `main.tsx` 가 `worker.start()` 직후 세우는 플래그를 본다.
 */
export async function waitForMsw(page: Page): Promise<void> {
  await page.waitForFunction(
    () => (window as unknown as { __MSW_READY__?: boolean }).__MSW_READY__ === true,
    undefined,
    { timeout: 15_000 },
  )
}

export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await waitForMsw(page)
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
  // 로그인 후 이동도 네비게이션이다 — 그 경계에서 워커가 다시 붙을 때까지 기다린다.
  await waitForMsw(page)
}
