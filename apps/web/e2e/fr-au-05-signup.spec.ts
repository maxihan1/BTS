// FR-AU-05 회원가입 E2E — 관리자 사용자 생성 + 권한 차단 + 비밀번호 강제 변경 흐름
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - msw-derived-behavior-shared-store-e2e: password 변경 성공 → mustChangePassword 파생 해제
//   - playwright-getbyrole-exact-strict-mode: exact:true / data-testid 우선
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 회귀 확인 필수
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// localStorage 플래그 키 상수 — auth-handlers.ts 와 동일
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

/** auth-handlers.ts E2E_MUST_CHANGE_PASSWORD_KEY */
const LS_MUST_CHANGE_PASSWORD = '__bts_e2e_must_change_password'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SYSTEM_ADMIN alice 로 로그인
//
// addInitScript 로 LS_IS_SYSTEM_ADMIN='true' 를 먼저 심은 뒤 로그인한다.
// 로그인 중 whoami 응답이 isSystemAdmin:true 로 반환되어 requireSystemAdmin 가드 통과.
// ─────────────────────────────────────────────────────────────────────────────

async function loginAsSystemAdmin(page: import('@playwright/test').Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_IS_SYSTEM_ADMIN)
  await loginAsAlice(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — mustChangePassword=true alice 로 로그인
//
// addInitScript 로 LS_MUST_CHANGE_PASSWORD='true' 를 먼저 심은 뒤 로그인한다.
// whoami 응답이 mustChangePassword:true → 로그인 직후 /settings/password redirect 됨.
// loginAsAlice 는 waitForURL('**/dashboard') 를 포함하므로 사용 불가.
// ─────────────────────────────────────────────────────────────────────────────

async function loginWithMustChangePwd(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  // 1단계: identifier-first 이메일 입력 → "계속"
  await page.getByLabel('이메일').fill('alice@example.com')
  await page.getByRole('button', { name: '계속', exact: true }).click()
  // 2단계: Local 선택 + 자격증명
  const providerSelect = page.getByRole('combobox', { name: '로그인 방식' })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: 'Local', exact: true }).click()
  await page.getByLabel('사용자명').fill('alice')
  await page.getByLabel('비밀번호').fill('password')
  // exact:true — strict mode violation 회피 (playwright-getbyrole-exact-strict-mode)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  // mustChangePassword:true → 로그인 후 /settings/password 로 redirect
  await page.waitForURL('**/settings/password')
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 관리자 사용자 생성
//
// Given   SYSTEM_ADMIN alice 로 로그인 → /admin/users/new 진입
// When    username="newuser01", displayName="새 사용자", email="new@bts.local" 입력 → 생성 클릭
// Then    임시 비밀번호가 화면에 표시됨 (data-testid="temporary-password")
//         "다시 표시되지 않습니다" 안내 텍스트 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 관리자 사용자 생성 (FR-AU-05)', () => {
  test('Given SYSTEM_ADMIN 로그인 When 사용자 생성 제출 Then 임시 비밀번호 1회 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // Given. /admin/users/new 진입
    await page.goto('/admin/users/new')
    await expect(page.getByRole('heading', { name: '사용자 생성', exact: true })).toBeVisible()

    // When. 폼 입력
    await page.getByLabel('사용자 이름').fill('newuser01')
    await page.getByLabel('표시 이름').fill('새 사용자')
    await page.getByLabel('이메일 (선택)').fill('new@bts.local')

    // When. 생성 버튼 클릭 (exact:true — strict mode violation 회피)
    await page.getByRole('button', { name: '사용자 생성', exact: true }).click()

    // Then. 임시 비밀번호 노출 (data-testid="temporary-password")
    const tmpPwd = page.getByTestId('temporary-password')
    await expect(tmpPwd).toBeVisible()
    const tmpPwdText = await tmpPwd.textContent()
    expect(tmpPwdText).toBeTruthy()
    expect((tmpPwdText ?? '').length).toBeGreaterThan(0)

    // Then. "다시 표시되지 않습니다" 안내 노출
    await expect(page.getByText('다시 표시되지 않습니다')).toBeVisible()

    // Then. 성공 상태 메시지 노출
    await expect(page.getByText('사용자가 생성되었습니다.')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 비 SYSTEM_ADMIN 권한 차단
//
// Given   일반 사용자 alice 로 로그인 (isSystemAdmin:false, 기본 fixture)
// When    /admin/users/new 직접 접근
// Then    requireSystemAdmin 가드가 /dashboard 로 redirect
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 비 SYSTEM_ADMIN 권한 차단 (FR-AU-05)', () => {
  test('Given 일반 사용자 로그인 When /admin/users/new 접근 Then /dashboard redirect', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false (기본 fixture, 플래그 없음)
    await loginAsAlice(page)

    // When. /admin/users/new 직접 접근
    await page.goto('/admin/users/new')

    // Then. requireSystemAdmin 가드 → /dashboard redirect
    await page.waitForURL('**/dashboard*')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4/S5 — 비밀번호 강제 변경 흐름
//
// S4 Given   mustChangePassword=true 사용자로 로그인
//    When    보호 페이지(/dashboard) 접근
//    Then    /settings/password 로 강제 이동
//
// S5 Given   /settings/password 강제 진입 상태
//    When    비밀번호 변경 성공 (CurrentPass123! → NewPass4567!@)
//    Then    성공 메시지 노출
//            강제 해제 — 이후 /dashboard 정상 접근 가능
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4/S5 비밀번호 강제 변경 흐름 (FR-AU-05)', () => {
  test('S4 mustChangePassword=true 사용자 — 로그인 후 /settings/password 강제 이동', async ({ page }) => {
    // Given. 로그인 전에 mustChangePassword 플래그 심기
    // addInitScript는 등록 이후 탐색(goto)부터 적용 → goto('/login') 이전에 등록 필수
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_MUST_CHANGE_PASSWORD)

    // When. 로그인 (mustChangePassword:true → 직후 /settings/password redirect)
    await loginWithMustChangePwd(page)

    // Then. /settings/password 에 도달 (loginWithMustChangePwd 내 waitForURL 확인)
    expect(new URL(page.url()).pathname).toBe('/settings/password')

    // Then. 비밀번호 변경 페이지 헤딩 노출
    await expect(page.getByRole('heading', { name: '비밀번호 변경', exact: true })).toBeVisible()

    // Then. 다른 보호 페이지(/dashboard) 직접 접근도 /settings/password 로 redirect
    await page.goto('/dashboard')
    await page.waitForURL('**/settings/password')
    expect(new URL(page.url()).pathname).toBe('/settings/password')
  })

  test('S5 비밀번호 변경 성공 — 강제 해제 후 /dashboard 정상 접근', async ({ page }) => {
    // Given. mustChangePassword 플래그 심기
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_MUST_CHANGE_PASSWORD)

    // Given. 로그인 → /settings/password 강제 진입
    await loginWithMustChangePwd(page)

    // When. 비밀번호 변경 성공
    // seed currentPassword = "CurrentPass123!" (password-handlers.ts 상수)
    await page.getByLabel('현재 비밀번호').fill('CurrentPass123!')
    await page.getByLabel('새 비밀번호', { exact: true }).fill('NewPass4567!@')
    await page.getByLabel('새 비밀번호 확인').fill('NewPass4567!@')
    await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click()

    // Then. 성공 메시지 노출
    await expect(page.getByText('비밀번호가 변경되었습니다')).toBeVisible()

    // Then. 강제 해제 — /dashboard 접근 가능
    // password 변경 성공 시 password-handlers.ts 가 LS_MUST_CHANGE_PASSWORD 플래그를 removeItem
    // 이후 whoami fetch에서 mustChangePassword:false → requirePasswordChanged 가드 통과
    await page.goto('/dashboard')
    await page.waitForURL('**/dashboard*')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })
})
