// FR-MF-03 E2E — 로그인 2단계 보안 키 인증 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - playwright-getbyrole-exact-strict-mode: exact:true 또는 컨테이너 한정
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동 활용
//
// MFA_E2E_ENABLED_KEY('__bts_e2e_mfa_enabled') 를 addInitScript 로 주입하면
// loginHandler 가 정식 토큰 대신 mfa_required:true 응답을 반환한다.
// (mfa-login.spec.ts 와 동일 패턴 — 역방향 import 금지, 키 문자열 직접 정의)
//
// navigator.credentials.get 은 webauthn-stub.ts 가 addInitScript 로 교체한다.
// verifyHandler(MSW) 는 method:'webauthn' + credential 객체 존재 시 200 토큰 반환한다.

import { test, expect } from '@playwright/test'
import { loginStrings, mfaStrings } from '../src/i18n/ko'
import { injectWebauthnStub } from './fixtures/webauthn-stub'

/** MFA E2E 토글 키 — src/mocks/auth-fixtures.ts MFA_E2E_ENABLED_KEY 와 동일 (역방향 import 금지) */
const MFA_E2E_ENABLED_KEY = '__bts_e2e_mfa_enabled'

/** 취소 시뮬레이션 플래그 키 — webauthn-stub.ts 가 이 값을 읽어 NotAllowedError throw */
const WEBAUTHN_CANCEL_KEY = '__bts_e2e_webauthn_cancel'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — MFA 챌린지 화면까지 로그인 진행
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MFA 챌린지 응답을 받는 상태까지 로그인를 진행한다.
 * addInitScript 주입(MFA 플래그 + webauthn stub)은 호출자가 직접 처리한다.
 * 완료 후 LoginMfaStep 화면(loginStepGuide 문구)이 표시된 상태가 된다.
 */
async function loginToMfaStep(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()


  // provider 드롭다운 로딩 대기 + Local 선택
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

  // username/password 입력 → 로그인 (MFA 플래그 세팅 시 mfa_required 응답)
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

  // MFA 화면 전환 대기 — loginStepGuide 문구 표시 확인
  await expect(page.getByText(mfaStrings.loginStepGuide)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 보안 키 인증 성공
//
// Given   MFA_E2E_ENABLED_KEY = 'true' (addInitScript 주입) → loginHandler 가 mfa_required 응답
//         navigator.credentials.get 이 stub 으로 교체됨 (유효 credential 반환)
// When    로그인 완료 → MFA 화면 전환
// Then    "보안 키로 인증" 버튼 표시
// When    "보안 키로 인증" 버튼 클릭
//         → authenticate/start → stub get → verify(method:'webauthn') → 200 토큰
// Then    로그인 성공(목적지는 FR-PF-02 startPage 매핑 부수사항 — alice 기본값 /dashboards)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 보안 키 로그인 인증 성공 (FR-MF-03)', () => {
  test.beforeEach(async ({ page }) => {
    // goto 전에 MFA 플래그 + webauthn stub 주입
    await page.addInitScript((key: string) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)
    await injectWebauthnStub(page)
  })

  test('Given MFA 화면 When 보안 키로 인증 버튼 표시 확인', async ({ page }) => {
    // Given + When. 로그인 → MFA 화면
    await loginToMfaStep(page)

    // Then. "보안 키로 인증" 버튼 표시 (isWebauthnSupported=true — Chromium 은 지원)
    await expect(
      page.getByRole('button', { name: mfaStrings.webauthnVerifyButton, exact: true }),
    ).toBeVisible()
  })

  test('Given MFA 화면 When 보안 키로 인증 클릭 Then 로그인 성공', async ({ page }) => {
    // Given. MFA 코드 입력 화면까지 도달
    await loginToMfaStep(page)
    await expect(
      page.getByRole('button', { name: mfaStrings.webauthnVerifyButton, exact: true }),
    ).toBeVisible()

    // When. "보안 키로 인증" 버튼 클릭
    //   → authenticate/start → stub navigator.credentials.get → verifyWebauthn(method:'webauthn')
    //   → MSW verifyHandler: credential 객체 존재 → 200 mock-access-token-alice
    await page.getByRole('button', { name: mfaStrings.webauthnVerifyButton, exact: true }).click()

    // Then. 로그인 성공 — 목적지는 FR-PF-02 startPage 매핑 부수사항(alice 기본값 /dashboards)
    await page.waitForURL('**/dashboard*')
    await expect(page).toHaveURL(/\/dashboards/)
    await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 보안 키 인증 취소 → 인라인 에러 + 화면 유지
//
// Given   MFA 화면 진입 + navigator.credentials.get 이 NotAllowedError throw 하도록 세팅
//         (localStorage[WEBAUTHN_CANCEL_KEY] = 'true')
// When    "보안 키로 인증" 버튼 클릭
//         → authenticate/start 이후 stub 이 DOMException('...', 'NotAllowedError') throw
// Then    인라인 에러 메시지 표시 (mfaErrorMessage default 메시지)
//         + "보안 키로 인증" 버튼 유지 (화면 유지, /dashboard 미전환)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 보안 키 인증 취소 → 인라인 에러 (FR-MF-03)', () => {
  test.beforeEach(async ({ page }) => {
    // MFA 플래그 + webauthn stub 주입 (stub 은 cancel 키를 런타임에 확인한다)
    await page.addInitScript((key: string) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)
    await injectWebauthnStub(page)
  })

  test('Given 보안 키 취소 세팅 When 인증 버튼 클릭 Then 인라인 에러 + 화면 유지', async ({ page }) => {
    // Given. 취소 플래그 주입 — stub 이 NotAllowedError 를 throw 하도록
    await page.addInitScript((key: string) => {
      localStorage.setItem(key, 'true')
    }, WEBAUTHN_CANCEL_KEY)

    // Given. MFA 화면까지 도달
    await loginToMfaStep(page)
    await expect(
      page.getByRole('button', { name: mfaStrings.webauthnVerifyButton, exact: true }),
    ).toBeVisible()

    // When. "보안 키로 인증" 버튼 클릭 → stub 이 NotAllowedError throw
    await page.getByRole('button', { name: mfaStrings.webauthnVerifyButton, exact: true }).click()

    // Then. 인라인 에러 표시 (role="alert")
    // WebauthnSection 의 handleWebauthnVerify: NotAllowedError → setWebauthnError(mfaErrorMessage(''))
    // mfaErrorMessage('') → default → '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()

    // Then. "보안 키로 인증" 버튼 유지 (화면 유지)
    await expect(
      page.getByRole('button', { name: mfaStrings.webauthnVerifyButton, exact: true }),
    ).toBeVisible()

    // Then. /dashboard 로 전환되지 않음
    await expect(page).not.toHaveURL('**/dashboard')
  })
})
