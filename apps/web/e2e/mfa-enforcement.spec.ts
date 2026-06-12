// FR-MF-04 D7 E2E — MFA 등록 강제 게이트 시나리오 (/settings/mfa 리다이렉트 + 게이트 해제)
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: 시나리오 토글은 addInitScript + goto 전 시드
//   - msw-derived-behavior-shared-store-e2e: mfaEnrollmentRequired 는 enforcement 플래그 + mfaStore.enabled 파생
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 시 exact:true 또는 컨테이너 한정
//   - auth-pre-session-401-raw-fetch: refresh 는 raw fetch — MSW refreshHandler 가 처리
//   - e2e-loginasalice-fixture-fr-au-07-regression: enforcement 시나리오에서 waitForURL('/dashboard') 금지
//
// 시나리오 격리.
//   - 각 test 는 새 브라우저 컨텍스트 → mfaStore 인메모리 초기화(enabled=false) 보장
//   - addInitScript 는 go 전에 실행되므로 MSW 핸들러가 브라우저 load 시점부터 플래그를 읽는다

import { test, expect } from '@playwright/test'
import { loginStrings } from '../src/i18n/ko'
import { mfaStrings } from '../src/i18n/ko'
import { E2E_MFA_ENFORCEMENT_KEY, MFA_VALID_CODE } from '../src/mocks/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 — enforcement 플래그를 goto 전에 시드하는 addInitScript 래퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MFA 강제 게이트 시나리오용 로그인 헬퍼.
 *
 * enforcement 플래그가 ON 이면 로그인 후 /settings/mfa 로 리다이렉트된다.
 * 따라서 waitForURL('dashboard') 대신 /settings/mfa 로의 도달을 기다린다.
 *
 * @param page Playwright Page 객체
 */
async function loginAsAliceWithEnforcement(page: import('@playwright/test').Page): Promise<void> {
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

  // enforcement ON 이면 /settings/mfa 로 리다이렉트 — 이 URL을 기다린다
  await page.waitForURL('**/settings/mfa')
}

// ─────────────────────────────────────────────────────────────────────────────
// E2E-1 — 강제 진입: enforcement 플래그 ON + 미등록 → /settings/mfa 리다이렉트 + 배너 + 활성화 버튼
//
// Given   E2E_MFA_ENFORCEMENT_KEY='true', mfaStore.enabled=false (초기값)
// When    alice 로그인
// Then    /settings/mfa 로 강제 리다이렉트
//         + 강제 안내 배너(role="status") 노출
//         + "2단계 인증 활성화" 버튼 표시 (EC-1 페이지 정상 렌더)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2E-1 MFA 강제 게이트 — 리다이렉트 + 배너 + 활성화 버튼 (FR-MF-04)', () => {
  test.beforeEach(async ({ page }) => {
    // enforcement 플래그를 goto 전 브라우저 localStorage 에 시드한다
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, E2E_MFA_ENFORCEMENT_KEY)
  })

  test(
    'Given 강제대상 미등록 When 로그인 Then /settings/mfa 리다이렉트 + 배너 노출 + 활성화 버튼',
    async ({ page }) => {
      // Given. enforcement 플래그 ON (beforeEach), mfaStore.enabled=false (컨텍스트 초기값)
      // When.  alice 로그인 (enforcement → /settings/mfa 리다이렉트)
      await loginAsAliceWithEnforcement(page)

      // Then. /settings/mfa 도달 확인
      await expect(page).toHaveURL(/\/settings\/mfa/)

      // Then. 강제 안내 배너(role="status") 노출
      await expect(page.getByRole('status')).toBeVisible()
      await expect(page.getByRole('status')).toContainText(mfaStrings.enforcementBanner)

      // Then. 페이지 제목 표시 (EC-1 — 레이아웃 403 소음에도 기본 UI 정상)
      await expect(
        page.getByRole('heading', { name: mfaStrings.settingsTitle, exact: true })
      ).toBeVisible()

      // Then. "2단계 인증 활성화" 버튼 표시 (등록 흐름 진입 가능)
      await expect(
        page.getByRole('button', { name: mfaStrings.enableButton, exact: true })
      ).toBeVisible()
    }
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// E2E-2 — 게이트 고정: 강제 상태에서 /dashboard 직접 이동 시도 → /settings/mfa 되돌림
//
// Given   E2E_MFA_ENFORCEMENT_KEY='true', alice 로그인(/settings/mfa 에 있는 상태)
// When    /dashboard 로 SPA 내부 이동 시도
// Then    /settings/mfa 로 다시 리다이렉트됨 (등록 전 탈출 불가)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2E-2 MFA 강제 게이트 — /dashboard 이동 시도 시 /settings/mfa 되돌림 (FR-MF-04)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, E2E_MFA_ENFORCEMENT_KEY)
  })

  test(
    'Given 강제 미등록 상태 When /dashboard 이동 시도 Then /settings/mfa 로 되돌려짐',
    async ({ page }) => {
      // Given. 로그인 → /settings/mfa (enforcement 리다이렉트)
      await loginAsAliceWithEnforcement(page)
      await expect(page).toHaveURL(/\/settings\/mfa/)

      // When. /dashboard 로 SPA 내부 이동 시도
      await page.goto('/dashboard')

      // Then. requireMfaEnrolled 가드가 /settings/mfa 로 되돌림
      await expect(page).toHaveURL(/\/settings\/mfa/)

      // Then. 배너 여전히 노출 (게이트 해제 안 됨)
      await expect(page.getByRole('status')).toBeVisible()
    }
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// E2E-3 — 등록 후 게이트 해제: setup→enable → refreshSession → /dashboard 진입 + 배너 사라짐
//
// Given   E2E_MFA_ENFORCEMENT_KEY='true', 강제 미등록 상태 (/settings/mfa 에 있음)
// When    MFA 활성화 (setup → enable with MFA_VALID_CODE)
// Then    refreshSession 호출 + mfaStore.enabled=true → whoami 재조회 시 mfaEnrollmentRequired=false
//         → /dashboard 진입 성공 + 배너 사라짐
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2E-3 MFA 등록 후 강제 게이트 해제 (FR-MF-04)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, E2E_MFA_ENFORCEMENT_KEY)
  })

  test(
    'Given 강제 미등록 When MFA 활성화 완료 Then 게이트 해제 + /dashboard 진입 + 배너 사라짐',
    async ({ page }) => {
      // Given. 로그인 → /settings/mfa (enforcement 리다이렉트)
      await loginAsAliceWithEnforcement(page)
      await expect(page).toHaveURL(/\/settings\/mfa/)

      // Given. 배너 노출 확인 (강제 게이트 활성)
      await expect(page.getByRole('status')).toBeVisible()

      // Given. "2단계 인증 활성화" 버튼 대기
      await expect(
        page.getByRole('button', { name: mfaStrings.enableButton, exact: true })
      ).toBeVisible()

      // When. 활성화 버튼 클릭 → POST /api/v1/auth/mfa/totp/setup 호출 → QR 표시
      await page.getByRole('button', { name: mfaStrings.enableButton, exact: true }).click()
      await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).toBeVisible()

      // When. 코드 입력 + 활성화 확인 → POST /api/v1/auth/mfa/totp/enable 호출
      //       mfaStore.enabled=true 전환 → 이후 whoami 파생에서 mfaEnrollmentRequired=false
      const codeInput = page.getByLabel(mfaStrings.codeLabel, { exact: true })
      await expect(codeInput).toBeVisible()
      await codeInput.fill(MFA_VALID_CODE)
      await page.getByRole('button', { name: mfaStrings.enableConfirmButton, exact: true }).click()

      // Then. FR-D6-4 — 강제 모드 enable 성공 시 refreshSession → /dashboard 이동
      //       POST /api/v1/auth/refresh (refreshHandler) + GET whoami(mfaEnrollmentRequired=false)
      await page.waitForURL('**/dashboard')

      // Then. /dashboard 진입 성공
      await expect(page).toHaveURL(/\/dashboard/)

      // Then. 배너 없음 (게이트 해제됨)
      await expect(page.getByRole('status')).not.toBeVisible()
    }
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// E2E-4 — 회귀: enforcement 플래그 OFF 사용자 → 리다이렉트·배너 없음, /dashboard 정상
//
// Given   E2E_MFA_ENFORCEMENT_KEY 미설정 (기본 비강제), mfaStore.enabled=false
// When    alice 로그인
// Then    /dashboard 진입 정상 — 리다이렉트·배너 없음
//         기존 mfa/login E2E 동작 불변 검증
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2E-4 비강제 사용자 회귀 — 리다이렉트·배너 없음 (FR-MF-04)', () => {
  test(
    'Given 비강제(플래그 OFF) 사용자 When 로그인 Then /dashboard 정상 진입 + 배너 없음',
    async ({ page }) => {
      // Given. enforcement 플래그 미설정 (addInitScript 없음 — 기본값 false)
      await page.goto('/login')
      await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

      // When. 정상 로그인 흐름 (issue-fixtures.loginAsAlice 동형)
      await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
      await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()

      const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
      await expect(providerSelect).toBeVisible()
      await expect(providerSelect).not.toBeDisabled()
      await providerSelect.click()
      await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
      await page.getByLabel(loginStrings.usernameLabel).fill('alice')
      await page.getByLabel(loginStrings.passwordLabel).fill('password')
      await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

      // Then. /dashboard 진입 정상 — 리다이렉트 없음
      await page.waitForURL('**/dashboard')
      await expect(page).toHaveURL(/\/dashboard/)

      // Then. /settings/mfa 이동 시 배너 없음 (비강제)
      await page.goto('/settings/mfa')
      await expect(
        page.getByRole('heading', { name: mfaStrings.settingsTitle, exact: true })
      ).toBeVisible()
      await expect(page.getByRole('status')).not.toBeVisible()
    }
  )
})
