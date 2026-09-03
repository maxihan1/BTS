// FR-MF-02 E2E — 백업코드 생성·재생성·로그인 시나리오 (/settings/mfa + 로그인 2단계)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: exact:true 또는 컨테이너 한정
//   - msw-mutation-stateful-refetch: mfaStore가 stateful — mutation 후 refetch 검증
//   - msw-derived-behavior-shared-store-e2e: mfaStore는 MSW 브라우저 인메모리 공유 store
//   - e2e-msw-scenario-toggle-localstorage-flag: localStorage 플래그 + addInitScript 패턴
//
// S2(remaining≤3 경고)는 단위 테스트에 위임한다.
// mfaStore.backupCodesRemaining 값을 E2E에서 직접 낮게 시드할 수단이 없다
// (mfaStore는 MSW 브라우저 인메모리 모듈 스코프 — src/ 수정 금지 제약).
// 관련 단위 테스트: apps/web/src/components/auth/BackupCodesSection.test.tsx
//
// mfaStore는 Playwright 브라우저 컨텍스트 단위로 격리된다.
// 새 컨텍스트(새 test)마다 초기화됨 — 별도 리셋 불필요.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { mfaStrings, mfaErrorMessage, loginStrings } from '../src/i18n/ko'

/** MFA E2E 토글 키 — src/mocks/auth-fixtures.ts MFA_E2E_ENABLED_KEY와 동일 (역방향 import 금지) */
const MFA_E2E_ENABLED_KEY = '__bts_e2e_mfa_enabled'

/** MSW 백업코드 고정 유효 코드 — src/mocks/auth-fixtures.ts MFA_VALID_BACKUP_CODE와 동일 */
const MFA_VALID_BACKUP_CODE = 'aaaaa-bbbbb'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼 — MFA 활성화 완료 상태까지 이동
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로그인 → /settings/mfa → TOTP 활성화 완료 상태까지 진행한다.
 * 완료 후 "활성화됨" 배지와 비활성화 버튼이 표시된 상태가 된다.
 */
async function activateMfaForAlice(page: import('@playwright/test').Page): Promise<void> {
  await loginAsAlice(page)
  await page.goto('/settings/mfa')
  await expect(page.getByRole('button', { name: mfaStrings.enableButton, exact: true })).toBeVisible()
  await page.getByRole('button', { name: mfaStrings.enableButton, exact: true }).click()
  await expect(page.getByRole('img', { name: 'TOTP QR 코드' })).toBeVisible()
  await page.getByLabel(mfaStrings.codeLabel, { exact: true }).fill('123456')
  await page.getByRole('button', { name: mfaStrings.enableConfirmButton, exact: true }).click()
  await expect(page.getByText(mfaStrings.statusEnabled, { exact: true })).toBeVisible()
}

/**
 * MFA 챌린지 응답을 받는 상태까지 로그인를 진행한다.
 * addInitScript 주입은 호출자가 직접 처리한다.
 * 완료 후 TOTP 코드 입력 화면(LoginMfaStep)이 표시된 상태가 된다.
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

  // MFA 코드 입력 화면 전환 대기 — loginStepGuide 문구 표시 확인
  await expect(page.getByText(mfaStrings.loginStepGuide)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 백업코드 최초 생성 → 평문 10개 + 복사/다운로드/저장 완료 버튼 + 저장 경고
//
// Given   alice 로그인 → MFA 활성화 완료 → /settings/mfa 진입
// When    백업코드 섹션에서 "백업 코드 생성" 버튼 클릭 → POST /api/v1/auth/mfa/backup-codes 호출
// Then    평문 코드 10개 표시 + 저장 경고 문구 + 복사/다운로드/저장 완료 버튼 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 백업코드 최초 생성 (FR-MF-02)', () => {
  test('Given MFA 활성 When 백업코드 생성 Then 평문 10개 + 저장 경고 + 복사/다운로드/저장완료 버튼 표시', async ({ page }) => {
    // Given. alice 로그인 → MFA 활성화 완료
    await activateMfaForAlice(page)

    // Given. 백업코드 섹션 로딩 대기 — "백업 코드 생성" 버튼 표시 확인
    const generateButton = page.getByRole('button', { name: mfaStrings.backupGenerateButton, exact: true })
    await expect(generateButton).toBeVisible()

    // When. "백업 코드 생성" 클릭 → POST /api/v1/auth/mfa/backup-codes 호출
    await generateButton.click()

    // Then. 저장 경고 문구 표시 (CodesRevealBox)
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).toBeVisible()

    // Then. 평문 코드 목록(aria-label="백업 코드 목록") 표시 — 10개 li 확인
    const codesList = page.getByRole('list', { name: '백업 코드 목록' })
    await expect(codesList).toBeVisible()
    const codeItems = codesList.getByRole('listitem')
    await expect(codeItems).toHaveCount(10)

    // Then. 복사 버튼 노출
    await expect(page.getByRole('button', { name: mfaStrings.backupCopyButton, exact: true })).toBeVisible()

    // Then. 다운로드 버튼 노출
    await expect(page.getByRole('button', { name: mfaStrings.backupDownloadButton, exact: true })).toBeVisible()

    // Then. 저장 완료 버튼 노출
    await expect(page.getByRole('button', { name: mfaStrings.backupCloseButton, exact: true })).toBeVisible()
  })

  test('Given 평문 코드 표시 중 When 저장 완료 클릭 Then 코드 목록 사라짐 + 재생성 버튼 표시', async ({ page }) => {
    // Given. alice 로그인 → MFA 활성화 → 백업코드 생성 완료
    await activateMfaForAlice(page)
    await page.getByRole('button', { name: mfaStrings.backupGenerateButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).toBeVisible()

    // When. "저장 완료" 클릭 → CodesRevealBox 닫힘
    await page.getByRole('button', { name: mfaStrings.backupCloseButton, exact: true }).click()

    // Then. 코드 목록 사라짐
    await expect(page.getByRole('list', { name: '백업 코드 목록' })).not.toBeVisible()

    // Then. 저장 경고 문구 사라짐
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).not.toBeVisible()

    // Then. 재생성 버튼 표시 (generated=true 상태로 refetch)
    await expect(page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 재생성 인라인 확인 박스 노출 + 취소/재생성 동작
//
// Given   백업코드 생성 완료 → "저장 완료" 닫기 → 재생성 버튼 표시
// When    "백업 코드 재생성" 버튼 클릭
// Then    인라인 확인 박스 노출 (경고 문구 + 재생성/취소 버튼)
// When    "취소" 클릭
// Then    인라인 확인 박스 사라짐 + 재생성 버튼 복귀
// When    다시 "백업 코드 재생성" → "재생성" 확인 클릭
// Then    평문 코드 10개 다시 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 백업코드 재생성 인라인 확인 (FR-MF-02)', () => {
  test('Given 재생성 버튼 When 클릭 Then 인라인 확인 박스(경고문+재생성/취소) 노출', async ({ page }) => {
    // Given. alice 로그인 → MFA 활성화 → 백업코드 생성 → 저장 완료로 닫기
    await activateMfaForAlice(page)
    await page.getByRole('button', { name: mfaStrings.backupGenerateButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.backupCloseButton, exact: true }).click()

    // Given. 재생성 버튼 표시 대기
    const regenerateButton = page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true })
    await expect(regenerateButton).toBeVisible()

    // When. "백업 코드 재생성" 클릭 → 인라인 확인 박스 노출
    await regenerateButton.click()

    // Then. 인라인 확인 박스 제목 표시
    await expect(page.getByText(mfaStrings.backupRegenerateConfirmTitle, { exact: true })).toBeVisible()

    // Then. 경고 문구 표시
    await expect(page.getByText(mfaStrings.backupRegenerateConfirmBody, { exact: true })).toBeVisible()

    // Then. 재생성/취소 버튼 표시 (확인 박스 컨테이너로 한정 — 버튼 중복 회피)
    const confirmBox = page.locator('.space-y-3.rounded-lg.border.border-destructive\\/20')
    await expect(confirmBox.getByRole('button', { name: mfaStrings.backupRegenerateConfirmButton, exact: true })).toBeVisible()
    await expect(confirmBox.getByRole('button', { name: mfaStrings.backupRegenerateCancelButton, exact: true })).toBeVisible()
  })

  test('Given 인라인 확인 박스 When 취소 클릭 Then 확인 박스 사라짐 + 재생성 버튼 복귀', async ({ page }) => {
    // Given. alice 로그인 → MFA 활성화 → 백업코드 생성 → 닫기 → 재생성 확인 박스 진입
    await activateMfaForAlice(page)
    await page.getByRole('button', { name: mfaStrings.backupGenerateButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.backupCloseButton, exact: true }).click()
    await expect(page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.backupRegenerateConfirmTitle, { exact: true })).toBeVisible()

    // When. "취소" 클릭 (확인 박스 컨테이너 한정)
    const confirmBox = page.locator('.space-y-3.rounded-lg.border.border-destructive\\/20')
    await confirmBox.getByRole('button', { name: mfaStrings.backupRegenerateCancelButton, exact: true }).click()

    // Then. 인라인 확인 박스 본문(경고문) 사라짐
    // backupRegenerateConfirmTitle과 동일 텍스트가 재생성 버튼에도 있으므로
    // 고유한 본문(backupRegenerateConfirmBody)으로 확인 박스 닫힘을 검증한다.
    await expect(page.getByText(mfaStrings.backupRegenerateConfirmBody, { exact: true })).not.toBeVisible()

    // Then. 재생성 버튼 복귀
    await expect(page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true })).toBeVisible()
  })

  test('Given 인라인 확인 박스 When 재생성 확인 클릭 Then 평문 코드 10개 다시 표시', async ({ page }) => {
    // Given. alice 로그인 → MFA 활성화 → 백업코드 생성 → 닫기 → 재생성 확인 박스 진입
    await activateMfaForAlice(page)
    await page.getByRole('button', { name: mfaStrings.backupGenerateButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.backupCloseButton, exact: true }).click()
    await expect(page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.backupRegenerateButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.backupRegenerateConfirmTitle, { exact: true })).toBeVisible()

    // When. "재생성" 확인 클릭 (확인 박스 컨테이너 한정)
    const confirmBox = page.locator('.space-y-3.rounded-lg.border.border-destructive\\/20')
    await confirmBox.getByRole('button', { name: mfaStrings.backupRegenerateConfirmButton, exact: true }).click()

    // Then. 평문 코드 10개 다시 표시
    const codesList = page.getByRole('list', { name: '백업 코드 목록' })
    await expect(codesList).toBeVisible()
    await expect(codesList.getByRole('listitem')).toHaveCount(10)

    // Then. 저장 경고 문구 재표시
    await expect(page.getByText(mfaStrings.backupSaveWarning, { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 로그인 2단계에서 "백업 코드로 로그인" 토글 → 유효 백업코드 입력 → 대시보드
//
// Given   MFA_E2E_ENABLED_KEY='true' (addInitScript) → loginHandler가 mfa_required 응답
// When    로그인 완료 → TOTP 코드 입력 화면
// When    "백업 코드로 로그인" 버튼 클릭 → 백업코드 입력 화면 전환
// Then    loginBackupStepGuide 안내 문구 + 백업코드 입력 필드 + 확인 버튼 표시
// When    MFA_VALID_BACKUP_CODE('aaaaa-bbbbb') 입력 → "확인" 클릭
// Then    로그인 성공(목적지는 FR-PF-02 startPage 매핑 부수사항 — alice 기본값 /dashboards)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 백업코드 로그인 2단계 성공 (FR-MF-02)', () => {
  test.beforeEach(async ({ page }) => {
    // goto 전에 MFA 플래그 주입 — loginHandler가 mfa_required:true 반환하도록 세팅
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)
  })

  test('Given MFA 코드 화면 When 백업코드로 전환 Then 안내 문구 + 백업코드 입력 필드 표시', async ({ page }) => {
    // Given. TOTP 코드 입력 화면까지 도달
    await loginToMfaStep(page)

    // When. "백업 코드로 로그인" 버튼 클릭 → 백업코드 입력 화면으로 전환
    await page.getByRole('button', { name: mfaStrings.loginUseBackupCode, exact: true }).click()

    // Then. 백업코드 안내 문구 표시
    await expect(page.getByText(mfaStrings.loginBackupStepGuide, { exact: true })).toBeVisible()

    // Then. 백업코드 입력 필드 표시
    await expect(page.getByLabel(mfaStrings.loginBackupCodeLabel, { exact: true })).toBeVisible()

    // Then. 확인 버튼 표시
    await expect(page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true })).toBeVisible()

    // Then. "Authenticator 코드로 돌아가기" 토글 버튼 표시 (백업코드 → TOTP 전환용)
    await expect(page.getByRole('button', { name: mfaStrings.loginUseTotp, exact: true })).toBeVisible()
  })

  test('Given 백업코드 화면 When aaaaa-bbbbb 입력 → 확인 Then 로그인 성공', async ({ page }) => {
    // Given. TOTP 코드 입력 화면까지 도달 → 백업코드 화면 전환
    await loginToMfaStep(page)
    await page.getByRole('button', { name: mfaStrings.loginUseBackupCode, exact: true }).click()
    await expect(page.getByLabel(mfaStrings.loginBackupCodeLabel, { exact: true })).toBeVisible()

    // When. 유효 백업코드 입력 → 확인 클릭 → POST /api/v1/auth/mfa/verify (method:backup_code) 호출
    await page.getByLabel(mfaStrings.loginBackupCodeLabel, { exact: true }).fill(MFA_VALID_BACKUP_CODE)
    await page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true }).click()

    // Then. 로그인 성공 — 목적지는 FR-PF-02 startPage 매핑 부수사항(alice 기본값 /dashboards)
    await page.waitForURL('**/dashboard*')
    await expect(page).toHaveURL(/\/dashboards/)
    await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 잘못된 백업코드 입력 → 인라인 에러 + 화면 유지
//
// Given   백업코드 입력 화면
// When    잘못된 코드 "wrong-code" 입력 → "확인" 클릭 → verify 401 invalid_code
// Then    인라인 에러 메시지 표시 + 백업코드 입력 필드 유지(화면 유지) + /dashboard 미전환
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 잘못된 백업코드 → 인라인 에러 (FR-MF-02)', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript((key) => {
      localStorage.setItem(key, 'true')
    }, MFA_E2E_ENABLED_KEY)
  })

  test('Given 백업코드 화면 When 잘못된 코드 입력 Then 인라인 에러 + 화면 유지', async ({ page }) => {
    // Given. TOTP 코드 입력 화면까지 도달 → 백업코드 화면 전환
    await loginToMfaStep(page)
    await page.getByRole('button', { name: mfaStrings.loginUseBackupCode, exact: true }).click()
    await expect(page.getByLabel(mfaStrings.loginBackupCodeLabel, { exact: true })).toBeVisible()

    // When. 잘못된 백업코드 입력 → 확인 클릭
    await page.getByLabel(mfaStrings.loginBackupCodeLabel, { exact: true }).fill('wrong-code')
    await page.getByRole('button', { name: mfaStrings.loginVerifyButton, exact: true }).click()

    // Then. 인라인 에러 메시지 표시 (role="alert") + 정확한 텍스트 검증
    // invalid_code 에러가 generic 메시지("요청을 처리하지 못했습니다.")로 변질되면 이 검증이 잡는다.
    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText(mfaErrorMessage('invalid_code'))

    // Then. 백업코드 입력 필드 유지 (화면 유지)
    await expect(page.getByLabel(mfaStrings.loginBackupCodeLabel, { exact: true })).toBeVisible()

    // Then. /dashboard 로 전환되지 않음
    await expect(page).not.toHaveURL('**/dashboard')
  })
})
