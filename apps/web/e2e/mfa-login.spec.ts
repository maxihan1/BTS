// FR-MF-01 E2E — 로그인 2단계(TOTP 코드 입력) 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - playwright-getbyrole-exact-strict-mode: exact:true 또는 컨테이너 한정
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동 활용, CSRF 쿠키는 login 절차가 세팅
//
// MFA_E2E_ENABLED_KEY('__bts_e2e_mfa_enabled')를 addInitScript로 goto 전에 주입하면
// loginHandler가 정식 토큰 대신 mfa_required:true 응답을 반환한다.

import { test, expect } from '@playwright/test'
import { loginStrings, mfaStrings, mfaErrorMessage } from '../src/i18n/ko'

/** MFA E2E 토글 키 — src/mocks/auth-fixtures.ts MFA_E2E_ENABLED_KEY와 동일 (역방향 import 금지) */
const MFA_E2E_ENABLED_KEY = '__bts_e2e_mfa_enabled'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — MFA 활성 상태 로그인 1+2단계 (이메일 → provider/pw → MFA 코드 입력 화면 도달)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MFA 챌린지 응답을 받는 상태까지 로그인 1+2단계를 진행한다.
 * addInitScript 주입은 호출자가 직접 처리한다.
 * 완료 후 MFA 코드 입력 화면(LoginMfaStep)이 표시된 상태가 된다.
 */
async function loginToMfaStep(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

  // 1단계: 이메일 입력 → "계속"
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()

  // 2단계: provider 드롭다운 로딩 대기 + Local 선택
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

  // 2단계: username/password 입력 → 로그인 (MFA 플래그 세팅 시 mfa_required 응답)
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

  // MFA 코드 입력 화면 전환 대기 — loginStepGuide 문구 표시 확인
  await expect(page.getByText(mfaStrings.loginStepGuide)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S3 — MFA 로그인 2단계 성공
//
// Given   MFA_E2E_ENABLED_KEY = 'true' (addInitScript 주입) → loginHandler가 mfa_required 응답
// When    로그인 1+2단계 완료 → MFA 코드 입력 화면 전환
// Then    loginStepGuide 안내 문구 + 코드 입력 필드 + 확인 버튼 표시
// When    코드 "123456" 입력 → "확인" 버튼 클릭 → POST /api/v1/auth/mfa/verify 호출
// Then    /dashboard 도달 + 환영 메시지 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 MFA 로그인 2단계 성공 (FR-MF-01)', () => {
  test.beforeEach(async ({ page }) => {
    // goto 전에 MFA 플래그 주입 — loginHandler가 mfa_required:true 반환하도록 세팅
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)
  })

  test('Given MFA 활성 When 로그인 완료 Then MFA 코드 입력 화면 표시', async ({ page }) => {
    // Given + When. 로그인 1+2단계 진행 → MFA 코드 입력 화면 도달
    await loginToMfaStep(page)

    // Then. 안내 문구, 코드 입력 필드, 확인 버튼 표시
    await expect(page.getByLabel(mfaStrings.loginCodeLabel, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: mfaStrings.loginBackToLogin, exact: true })).toBeVisible()
  })

  test('Given MFA 코드 입력 화면 When 123456 입력 → 확인 Then /dashboard 도달', async ({ page }) => {
    // Given. MFA 코드 입력 화면까지 도달
    await loginToMfaStep(page)

    // When. 코드 입력 → 확인
    await page.getByLabel(mfaStrings.loginCodeLabel, { exact: true }).fill('123456')
    await page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true }).click()

    // Then. /dashboard 도달
    await page.waitForURL('**/dashboard')
    await expect(page.getByRole('heading', { name: '환영합니다, alice' })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — MFA 코드 오류 시 인라인 에러 표시 (필드 유지)
//
// Given   MFA 코드 입력 화면
// When    잘못된 코드 "000000" 입력 → "확인" 버튼 클릭 → verify 401 invalid_code
// Then    인라인 에러 메시지 표시 + 코드 입력 필드 유지(화면 유지)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 MFA 잘못된 코드 → 인라인 에러 (FR-MF-01)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)
  })

  test('Given MFA 코드 화면 When 000000 입력 Then 인라인 에러 + 화면 유지', async ({ page }) => {
    // Given. MFA 코드 입력 화면까지 도달
    await loginToMfaStep(page)

    // When. 잘못된 코드 입력 → 확인
    await page.getByLabel(mfaStrings.loginCodeLabel, { exact: true }).fill('000000')
    await page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true }).click()

    // Then. 인라인 에러 메시지 표시 (role="alert") + 정확한 텍스트 검증
    // 텍스트 검증이 없으면 generic fallback 메시지("요청을 처리하지 못했습니다.")가 표시돼도 통과한다.
    // verifyMfa가 apiFetch를 사용해 /refresh를 트리거하면 에러가 generic으로 변질되므로
    // 이 검증이 회귀를 잡아낸다.
    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText(mfaErrorMessage('invalid_code'))

    // Then. 코드 입력 필드가 유지됨 (화면이 유지, /dashboard 미전환)
    await expect(page.getByLabel(mfaStrings.loginCodeLabel, { exact: true })).toBeVisible()

    // Then. /dashboard 로 전환되지 않음
    await expect(page).not.toHaveURL('**/dashboard')
  })
})
