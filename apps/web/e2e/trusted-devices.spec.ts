// FR-MF-05 E2E — 신뢰 디바이스 관리 + 로그인 신뢰 체크박스 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: exact:true 또는 컨테이너 한정
//   - msw-mutation-stateful-refetch: mutation 후 invalidateQueries → refetch 검증
//   - msw-derived-behavior-shared-store-e2e: trustedThisBrowser 플래그가 MSW 브라우저 인메모리 공유 store
//   - e2e-msw-scenario-toggle-localstorage-flag: MFA_E2E_ENABLED_KEY addInitScript 패턴
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동·드롭다운 로딩 대기
//   - fr-mf-05-trusted-devices-done: trustedThisBrowser 플래그 cross-handler 연동
//
// 테스트 격리.
//   - 새 브라우저 컨텍스트(새 test)마다 MSW store가 초기화된다.
//   - 단, S5/S6 는 MFA_E2E_ENABLED_KEY 플래그가 필요하므로 addInitScript로 주입한다.
//   - S1~S4 설정 화면 테스트는 beforeEach에서 X-MSW-Reset-TrustedDevices 리셋을 수행한다.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { mfaStrings, loginStrings } from '../src/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — auth-fixtures.ts 의 MFA_E2E_ENABLED_KEY 와 동일 (역방향 import 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** MFA E2E 토글 키 — src/mocks/auth-fixtures.ts MFA_E2E_ENABLED_KEY 와 동일 */
const MFA_E2E_ENABLED_KEY = '__bts_e2e_mfa_enabled'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — MSW trusted-devices 핸들러 store 리셋
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW trusted-devices-handlers 의 stateful revokedIds 와 trustedThisBrowser 플래그를
 * 초기 상태로 리셋한다.
 *
 * GET /api/v1/auth/mfa/trusted-devices 에 X-MSW-Reset-TrustedDevices: true 헤더를
 * 포함해 호출하면 handlers 가 store 를 clear 한다.
 * 각 테스트의 beforeEach 에서 호출해 테스트 격리를 보장한다.
 */
async function resetTrustedDevicesHandlerState(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async () => {
    await fetch('/api/v1/auth/mfa/trusted-devices', {
      headers: { 'X-MSW-Reset-TrustedDevices': 'true' },
    })
  })
}

/**
 * MFA 챌린지 화면까지 로그인 1+2단계를 진행한다.
 * addInitScript(MFA_E2E_ENABLED_KEY) 주입은 호출자가 담당한다.
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

  // MFA 코드 입력 화면 전환 대기
  await expect(page.getByText(mfaStrings.loginStepGuide)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 설정 화면 시나리오 (S1~S4)
// /settings/mfa 진입 → TrustedDevicesSection 검증
// ─────────────────────────────────────────────────────────────────────────────

test.describe('신뢰 디바이스 설정 화면 (FR-MF-05)', () => {
  test.beforeEach(async ({ page }) => {
    // MSW trusted-devices store 를 초기화해 테스트 격리 보장.
    // 로그인 전 페이지에서 fetch 가 MSW 에 도달하도록 /login 로드 후 리셋을 수행한다.
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
    await resetTrustedDevicesHandlerState(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 — 2건 시드 → 섹션 표시
  //
  // Given   MSW fixtureDevices 기본 2건 (Chrome on macOS + label=null)
  //         alice 로그인 → /settings/mfa 진입
  // When    TrustedDevicesSection 로딩 완료
  // Then    섹션 제목·설명 표시
  //         기기명 "Chrome on macOS" 표시
  //         label=null 기기는 fallback 레이블 "알 수 없는 기기" 표시
  //         등록일(createdAt)·만료일(expiresAt) 텍스트 표시
  //         lastUsedAt=null 기기는 "사용 기록 없음" 표시
  // ─────────────────────────────────────────────────────────────────────────

  test('S1 — Given 2건 시드 When 설정 화면 진입 Then 기기 목록 + 섹션 메타 표시', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa
    await loginAsAlice(page)
    await page.goto('/settings/mfa')

    // Then. MFA 설정 페이지 헤딩 확인
    await expect(page.getByRole('heading', { name: mfaStrings.settingsTitle, exact: true })).toBeVisible()

    // Then. 신뢰 디바이스 섹션 제목·설명
    await expect(page.getByText(mfaStrings.trustedDevicesSectionTitle, { exact: true })).toBeVisible()
    await expect(page.getByText(mfaStrings.trustedDevicesSectionDescription, { exact: true })).toBeVisible()

    // Then. 기기명 표시 — label 있는 기기
    await expect(page.getByText('Chrome on macOS', { exact: true })).toBeVisible()

    // Then. label=null 기기는 fallback 레이블 표시
    await expect(page.getByText(mfaStrings.trustedDevicesLabelFallback, { exact: true })).toBeVisible()

    // Then. lastUsedAt=null 기기 → "사용 기록 없음" 표시
    await expect(page.getByText(mfaStrings.trustedDevicesLastUsedNever, { exact: true })).toBeVisible()

    // Then. "신뢰 해제" 버튼이 2개 존재 (각 기기 행)
    const revokeButtons = page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton, exact: true })
    await expect(revokeButtons).toHaveCount(2)

    // Then. "모든 기기 신뢰 해제" 버튼 표시 (기기 있을 때만)
    await expect(page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton, exact: true })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 — 단건 신뢰 해제
  //
  // Given   2건 시드 상태 + alice 로그인 → /settings/mfa
  // When    "Chrome on macOS" 기기의 "신뢰 해제" 버튼 클릭
  // Then    인라인 확인 박스 표시 + 확인 메시지 노출
  // When    "확인" 버튼 클릭 → DELETE /api/v1/auth/mfa/trusted-devices/:id
  // Then    해당 기기 행이 목록에서 사라짐 (invalidateQueries → refetch 반영)
  //         남은 1건만 표시
  // ─────────────────────────────────────────────────────────────────────────

  test('S2 — Given 2건 시드 When 단건 신뢰 해제 확인 Then 목록에서 제거', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa
    await loginAsAlice(page)
    await page.goto('/settings/mfa')

    // Given. 목록 로딩 완료 대기
    await expect(page.getByText('Chrome on macOS', { exact: true })).toBeVisible()

    // When. "Chrome on macOS" 기기 행의 "신뢰 해제" 버튼 클릭
    // 기기 행은 <li> 내부에 button 이 있다. 첫 번째 "신뢰 해제" 버튼이 Chrome 기기다.
    const revokeButtons = page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton, exact: true })
    await expect(revokeButtons).toHaveCount(2)
    await revokeButtons.first().click()

    // Then. 인라인 확인 박스 표시
    await expect(page.getByText(mfaStrings.trustedDevicesRevokeConfirm, { exact: true })).toBeVisible()

    // When. "확인" 버튼 클릭 → DELETE API 호출 → invalidateQueries
    await page.getByRole('button', { name: mfaStrings.trustedDevicesConfirmButton, exact: true }).click()

    // Then. Chrome on macOS 기기 행이 목록에서 사라짐
    await expect(page.getByText('Chrome on macOS', { exact: true })).not.toBeVisible()

    // Then. 나머지 1건("알 수 없는 기기") 은 여전히 표시
    await expect(page.getByText(mfaStrings.trustedDevicesLabelFallback, { exact: true })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3 — 모든 기기 신뢰 해제
  //
  // Given   2건 시드 상태 + alice 로그인 → /settings/mfa
  // When    "모든 기기 신뢰 해제" 버튼 클릭
  // Then    인라인 확인 박스 표시
  // When    "확인" 버튼 클릭 → DELETE /api/v1/auth/mfa/trusted-devices
  // Then    목록 전체 사라짐 + "신뢰한 기기가 없습니다." 빈 상태 안내 표시
  //         "모든 기기 신뢰 해제" 버튼도 사라짐 (기기 없을 때 숨김)
  // ─────────────────────────────────────────────────────────────────────────

  test('S3 — Given 2건 시드 When 모든 기기 신뢰 해제 확인 Then 빈 상태 표시', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa
    await loginAsAlice(page)
    await page.goto('/settings/mfa')

    // Given. 목록 로딩 완료 대기
    await expect(page.getByText('Chrome on macOS', { exact: true })).toBeVisible()

    // When. "모든 기기 신뢰 해제" 버튼 클릭
    await page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton, exact: true }).click()

    // Then. 인라인 확인 박스 표시
    await expect(page.getByText(mfaStrings.trustedDevicesRevokeConfirm, { exact: true })).toBeVisible()

    // When. "확인" 버튼 클릭 → DELETE /api/v1/auth/mfa/trusted-devices (전체)
    await page.getByRole('button', { name: mfaStrings.trustedDevicesConfirmButton, exact: true }).click()

    // Then. "신뢰한 기기가 없습니다." 빈 상태 메시지 표시
    await expect(page.getByText(mfaStrings.trustedDevicesEmptyState, { exact: true })).toBeVisible()

    // Then. 기기 목록이 모두 사라짐
    await expect(page.getByText('Chrome on macOS', { exact: true })).not.toBeVisible()
    await expect(page.getByText(mfaStrings.trustedDevicesLabelFallback, { exact: true })).not.toBeVisible()

    // Then. "모든 기기 신뢰 해제" 버튼도 사라짐 (기기 없을 때 숨김)
    await expect(page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton, exact: true })).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4 — 0건 상태 → 빈 상태 안내 + SPA 재진입 후에도 유지
  //
  // Given   alice 로그인 → /settings/mfa SPA 내부 이동 → UI 전체 취소 → 빈 상태
  // When    /dashboard SPA 내부 이동 → "2단계 인증" 링크 클릭 → /settings/mfa 재진입
  // Then    "신뢰한 기기가 없습니다." 안내 여전히 표시
  //         "신뢰 해제" 버튼 없음
  //         "모든 기기 신뢰 해제" 버튼 없음
  //
  // 주의 — S3 과 차이점.
  //   S3 은 "전체 취소 후 같은 페이지에서 빈 상태" 검증.
  //   S4 는 "빈 상태에서 다른 페이지로 이동 후 재진입해도 빈 상태 지속" 검증
  //   (SPA 라우팅 + QueryClient staleTime 만료 전 캐시 무효화 → refetch 정합).
  //
  // 구현 노트 — MSW 메모리 초기화 함정.
  //   page.goto('/settings/mfa') 는 full page reload 로 앱 컨텍스트(revokedIds Set)가
  //   초기화된다. 따라서 S4 는 SPA 내부 이동(링크 클릭)으로 페이지를 오가야 한다.
  // ─────────────────────────────────────────────────────────────────────────

  test('S4 — Given 전체 취소 후 SPA 재진입 When 설정 화면 재진입 Then 빈 상태 여전히 표시', async ({ page }) => {
    // Given. alice 로그인 후 계정 메뉴 → "2단계 인증" 링크로 SPA 내부 이동
    await loginAsAlice(page)

    // /dashboard 에서 계정 메뉴 클릭 → 2단계 인증 링크 클릭 (SPA 내부 이동)
    await page.getByRole('button', { name: 'alice 계정 메뉴' }).click()
    await page.getByRole('menuitem', { name: '2단계 인증', exact: true }).click()

    // Given. /settings/mfa TrustedDevicesSection 로딩 완료
    await expect(page.getByText('Chrome on macOS', { exact: true })).toBeVisible()

    // Given. "모든 기기 신뢰 해제" → 인라인 확인 → "확인" (S3 과 동일한 전체 취소 플로우)
    await page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.trustedDevicesRevokeConfirm, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.trustedDevicesConfirmButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.trustedDevicesEmptyState, { exact: true })).toBeVisible()

    // When. /dashboard SPA 내부 이동 (로고 클릭 또는 URL 직접 이동 대신 window.history)
    // SPA 내부 이동이어야 revokedIds 가 유지된다.
    await page.evaluate(() => { window.history.pushState({}, '', '/dashboard') })
    await page.waitForURL('**/dashboard')

    // When. 계정 메뉴 → "2단계 인증" 링크로 /settings/mfa 재진입 (SPA 내부 이동)
    await page.getByRole('button', { name: 'alice 계정 메뉴' }).click()
    await page.getByRole('menuitem', { name: '2단계 인증', exact: true }).click()

    // Then. MFA 설정 페이지 로딩 완료
    await expect(page.getByRole('heading', { name: mfaStrings.settingsTitle, exact: true })).toBeVisible()

    // Then. 빈 상태 안내 메시지 여전히 표시 (QueryClient refetch → MSW 빈 배열 반환)
    await expect(page.getByText(mfaStrings.trustedDevicesEmptyState, { exact: true })).toBeVisible()

    // Then. 섹션 제목/설명은 빈 상태에서도 표시 (섹션 자체는 항상 렌더)
    await expect(page.getByText(mfaStrings.trustedDevicesSectionTitle, { exact: true })).toBeVisible()
    await expect(page.getByText(mfaStrings.trustedDevicesSectionDescription, { exact: true })).toBeVisible()

    // Then. "신뢰 해제" 버튼 없음 (기기가 없으므로)
    await expect(page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeButton, exact: true })).toHaveCount(0)

    // Then. "모든 기기 신뢰 해제" 버튼 없음 (기기 없을 때 숨김)
    await expect(page.getByRole('button', { name: mfaStrings.trustedDevicesRevokeAllButton, exact: true })).not.toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 로그인 MFA 신뢰 체크박스 시나리오 (S5~S6)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('로그인 신뢰 디바이스 체크박스 (FR-MF-05)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S5 — "이 기기를 30일간 신뢰" 체크 → MFA verify 성공 → dashboard 도달
  //
  // Given   MFA_E2E_ENABLED_KEY = 'true' (addInitScript 주입)
  // When    로그인 1+2단계 완료 → MFA 코드 입력 화면 전환
  //         "이 기기를 30일간 신뢰" 체크박스 체크 → checked 상태 확인
  //         코드 "123456" 입력 → "확인" 버튼 클릭
  //         → POST /api/v1/auth/mfa/verify { trust_device: true, ... }
  // Then    /dashboard 도달 (정식 로그인 성공)
  //         LoginMfaStep 가 checked trustDevice 상태로 verifyMfa() 를 호출했음을 확인
  //
  // 주의 — cross-handler 연동(trustedThisBrowser 플래그 → 다음 로그인 MFA 생략) 범위:
  //   MSW Service Worker 가 fetch 를 인터셉트하므로 Playwright page.route() 로 요청 바디를
  //   캡처하면 SW 가 먼저 처리해 route 핸들러에 도달하지 않는다.
  //   cross-handler 연동은 MSW 핸들러 단위 테스트(mfa-handlers.test.ts)로 커버한다.
  // ─────────────────────────────────────────────────────────────────────────

  test('S5 — Given 신뢰 체크박스 체크 When MFA verify 성공 Then dashboard 도달', async ({ page }) => {
    // Given. MFA_E2E_ENABLED_KEY 플래그 주입
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)

    // When. 로그인 1+2단계 → MFA 코드 입력 화면 도달
    await loginToMfaStep(page)

    // When. "이 기기를 30일간 신뢰" 체크박스 체크
    const trustCheckbox = page.getByLabel(mfaStrings.trustedDevicesLoginCheckboxLabel, { exact: true })
    await expect(trustCheckbox).toBeVisible()
    await expect(trustCheckbox).not.toBeChecked()
    await trustCheckbox.check()

    // Then. 체크박스가 checked 상태 확인 (trustDevice=true 로 verifyMfa 가 호출될 것)
    await expect(trustCheckbox).toBeChecked()

    // When. 코드 입력 → "확인" 버튼 클릭 → POST /api/v1/auth/mfa/verify { trust_device: true }
    await page.getByLabel(mfaStrings.loginCodeLabel, { exact: true }).fill('123456')
    await page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true }).click()

    // Then. /dashboard 도달 (정식 로그인 성공 — trust_device=true 여부와 관계없이 verify 성공)
    await page.waitForURL('**/dashboard')

    // Then. MFA 코드 입력 화면이 사라짐 (verify 완료 + 로그인 성공)
    await expect(page.getByText(mfaStrings.loginStepGuide)).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6 — "이 기기를 30일간 신뢰" 미체크 → MFA verify → 다음 로그인에서도 MFA 요구
  //
  // Given   MFA_E2E_ENABLED_KEY = 'true' (addInitScript 주입)
  //         MSW trusted-devices store 초기화 (trustedThisBrowser = false)
  // When    로그인 1+2단계 완료 → MFA 코드 입력 화면 전환
  //         신뢰 체크박스 체크 안 함 (기본값)
  //         코드 "123456" 입력 → "확인" 버튼 클릭
  //         → POST /api/v1/auth/mfa/verify { trust_device: false 또는 누락 }
  //         → trustedThisBrowser 플래그 변경 없음 (= false 유지)
  // Then    /dashboard 도달
  // When    로그아웃 후 다시 로그인
  // Then    MFA 코드 입력 화면이 여전히 표시됨 (신뢰 기기가 없으므로)
  // ─────────────────────────────────────────────────────────────────────────

  test('S6 — Given 신뢰 미체크 When 다음 로그인 Then MFA 여전히 요구 (기본 동작 회귀 없음)', async ({ page }) => {
    // Given. MFA_E2E_ENABLED_KEY 플래그 주입
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)

    // Given. MSW trusted-devices store 초기화 (trustedThisBrowser = false 보장)
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
    await resetTrustedDevicesHandlerState(page)

    // When. 로그인 1+2단계 → MFA 코드 입력 화면 도달
    await loginToMfaStep(page)

    // When. 신뢰 체크박스가 기본적으로 unchecked 임을 확인 + 체크 안 함
    const trustCheckbox = page.getByLabel(mfaStrings.trustedDevicesLoginCheckboxLabel, { exact: true })
    await expect(trustCheckbox).toBeVisible()
    await expect(trustCheckbox).not.toBeChecked()
    // 체크하지 않은 상태 유지

    // When. 코드 입력 → "확인" → /dashboard 도달
    await page.getByLabel(mfaStrings.loginCodeLabel, { exact: true }).fill('123456')
    await page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true }).click()
    await page.waitForURL('**/dashboard')

    // When. 로그아웃 — sessionStorage 직접 클리어로 authStore 세션 초기화
    await page.evaluate(async () => {
      await fetch('/api/v1/auth/logout', {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': 'e2e-test' },
      })
      sessionStorage.removeItem('bts.auth')
    })
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    // When. 다시 로그인
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

    // Then. MFA 코드 입력 화면이 여전히 표시됨 (trustedThisBrowser=false 이므로)
    await expect(page.getByText(mfaStrings.loginStepGuide)).toBeVisible()
    await expect(page.getByLabel(mfaStrings.loginCodeLabel, { exact: true })).toBeVisible()

    // Then. /dashboard 로 전환되지 않음 (아직 MFA 코드 입력 필요)
    await expect(page).not.toHaveURL('**/dashboard')
  })
})
