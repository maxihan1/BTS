// FR-MF-01 E2E — MFA 설정 화면 활성화/비활성화 시나리오 (/settings/mfa)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: exact:true 또는 컨테이너 한정
//   - msw-mutation-stateful-refetch: mfaStore가 stateful — mutation 후 refetch 검증
//   - msw-derived-behavior-shared-store-e2e: mfaStore는 MSW 브라우저 인메모리 공유 store
//   - e2e-msw-scenario-toggle-localstorage-flag: localStorage 플래그 + addInitScript 패턴
//
// mfaStore는 Playwright 브라우저 컨텍스트 단위로 격리된다.
// 새 컨텍스트(새 test)마다 초기화됨 — 별도 리셋 불필요.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { mfaStrings } from '../src/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// S1 — MFA 미활성 상태 확인 + 활성화 (QR 확인 → 코드 입력 → 활성 상태 전환)
//
// Given   alice 로그인 → /settings/mfa 진입
// When    mfaStore 초기값(비활성)
// Then    "비활성화됨" 배지 + "2단계 인증 활성화" 버튼 표시
// When    "2단계 인증 활성화" 버튼 클릭 → setup API 호출
// Then    QR <img> 표시
// When    코드 입력 필드에 "123456" 입력 → "활성화 확인" 버튼 클릭 → enable API 호출
// Then    "활성화됨" 배지로 전환 + QR/코드 입력 영역 사라짐
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 MFA 활성화 플로우 (FR-MF-01)', () => {
  test('Given 미활성 When 설정 페이지 진입 Then 비활성화됨 배지 + 활성화 버튼 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /settings/mfa 직접 이동 (SPA 내부 이동)
    await page.goto('/settings/mfa')

    // Then. 페이지 제목 표시
    await expect(page.getByRole('heading', { name: mfaStrings.settingsTitle, exact: true })).toBeVisible()

    // Then. 비활성화됨 배지 표시
    await expect(page.getByText(mfaStrings.statusDisabled, { exact: true })).toBeVisible()

    // Then. 활성화 버튼 표시
    await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()
  })

  test('Given 미활성 When 활성화 클릭 Then QR 이미지 + 코드 입력 폼 표시', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()

    // When. "2단계 인증 활성화" 버튼 클릭 → POST /api/v1/auth/mfa/totp/setup 호출
    await page.getByRole('button', { name: mfaStrings.enableButton, exact: true }).click()

    // Then. QR <img> (alt="TOTP QR 코드") 표시
    await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).toBeVisible()

    // Then. 코드 입력 필드(Label: mfaStrings.codeLabel) 표시
    await expect(page.getByLabel(mfaStrings.codeLabel, { exact: true })).toBeVisible()
  })

  test('Given QR 표시 중 When 코드 123456 입력 → 활성화 확인 Then 활성화됨 배지로 전환', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa → setup 트리거
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.enableButton, exact: true }).click()

    // Given. QR + 코드 입력 폼 대기
    await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).toBeVisible()
    const codeInput = page.getByLabel(mfaStrings.codeLabel, { exact: true })
    await expect(codeInput).toBeVisible()

    // When. 코드 입력 + "활성화 확인" 버튼 클릭 → POST /api/v1/auth/mfa/totp/enable 호출
    await codeInput.fill('123456')
    await page.getByRole('button', { name: mfaStrings.enableConfirmButton, exact: true }).click()

    // Then. "활성화됨" 배지로 전환 (invalidateQueries → GET status refetch)
    await expect(page.getByText(mfaStrings.statusEnabled, { exact: true })).toBeVisible()

    // Then. QR 이미지 사라짐 (CONCERN-state null 리셋 확인)
    await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).not.toBeVisible()

    // Then. 비활성화 버튼 표시 (활성 상태 플로우 진입)
    await expect(page.getByRole('button', { name: mfaStrings.disableButton, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — MFA 비활성화 플로우 (활성화 완료 상태에서 step-up → 비활성화)
//
// Given   S1 활성화 완료 후 (mfaStore.enabled=true)
// When    "2단계 인증 비활성화" 버튼 클릭
// Then    step-up 코드 입력 폼 표시
// When    코드 "123456" 입력 → "비활성화 확인" 버튼 클릭 → DELETE API 호출
// Then    "비활성화됨" 배지로 전환 + 활성화 버튼 복귀
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 MFA 비활성화 플로우 (FR-MF-01)', () => {
  test('Given 활성화 완료 When 비활성화 버튼 클릭 Then step-up 코드 입력 폼 표시', async ({ page }) => {
    // Given. alice 로그인 → 활성화 완료 상태까지
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.enableButton, exact: true }).click()
    await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).toBeVisible()
    await page.getByLabel(mfaStrings.codeLabel, { exact: true }).fill('123456')
    await page.getByRole('button', { name: mfaStrings.enableConfirmButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.statusEnabled, { exact: true })).toBeVisible()

    // When. 비활성화 버튼 클릭
    await page.getByRole('button', { name: mfaStrings.disableButton, exact: true }).click()

    // Then. step-up 코드 입력 폼 표시
    await expect(page.getByLabel(mfaStrings.disableCodeLabel, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: mfaStrings.disableConfirmButton, exact: true })).toBeVisible()
  })

  test('Given step-up 폼 When 코드 123456 입력 → 비활성화 확인 Then 비활성화됨 배지로 전환', async ({ page }) => {
    // Given. alice 로그인 → 활성화 → 비활성화 폼 진입
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.enableButton, exact: true }).click()
    await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).toBeVisible()
    await page.getByLabel(mfaStrings.codeLabel, { exact: true }).fill('123456')
    await page.getByRole('button', { name: mfaStrings.enableConfirmButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.statusEnabled, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.disableButton, exact: true }).click()
    await expect(page.getByLabel(mfaStrings.disableCodeLabel, { exact: true })).toBeVisible()

    // When. step-up 코드 입력 → 비활성화 확인
    await page.getByLabel(mfaStrings.disableCodeLabel, { exact: true }).fill('123456')
    await page.getByRole('button', { name: mfaStrings.disableConfirmButton, exact: true }).click()

    // Then. "비활성화됨" 배지로 전환
    await expect(page.getByText(mfaStrings.statusDisabled, { exact: true })).toBeVisible()

    // Then. 활성화 버튼 복귀
    await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()
  })
})
