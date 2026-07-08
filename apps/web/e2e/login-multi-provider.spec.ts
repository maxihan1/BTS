// FR-AU-06 다중 Provider 명시 선택 E2E — S1(Local 선택+성공) / S2(LDAP 선택+성공) / S3(LDAP 비활성 시 드롭다운 미표시)
//
// S1 — Local provider 선택 후 정상 로그인
//   Given  /login 진입 후 1단계 미매칭 이메일 입력 → "계속" → 2단계 폼 진입
//   When   드롭다운에서 "Local" 선택 → alice / password 입력 → 로그인
//   Then   로그인 성공 (목적지는 FR-PF-02 startPage 매핑에 따르는 부수사항 — alice 기본값 /dashboards)
//
// S2 — LDAP provider 선택 후 정상 로그인
//   Given  /login 진입 후 1단계 미매칭 이메일 입력 → "계속" → 2단계 폼 진입
//   When   드롭다운에서 "LDAP-corp" 선택 → alice / Test1234! 입력 → 로그인
//   Then   로그인 성공 (목적지는 FR-PF-02 startPage 매핑에 따르는 부수사항 — alice 기본값 /dashboards)
//
// S3 — LDAP 비활성 시 드롭다운에 LDAP 항목 미표시
//   Given  /login 진입 + addInitScript 로 localStorage '__bts_e2e_providers_local_only' = 'true' 설정
//          (MSW 핸들러가 LOCAL만 반환)
//          + 1단계 미매칭 이메일 입력 → "계속" → 2단계 폼 진입
//   When   로그인 폼 로드 완료
//   Then   드롭다운에 "LDAP-corp" 항목 없고 "Local" 항목만 존재
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: option/button 텍스트 exact:true 로 한정
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그 패턴
//   - msw-derived-behavior-shared-store-e2e: MSW store는 브라우저 시드 가능
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 드롭다운 로딩 대기 필수
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { E2E_PROVIDERS_LOCAL_ONLY_KEY } from '../src/mocks/auth-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 1단계 이메일 입력 + "계속" → 2단계 폼 진입 + 드롭다운 로딩 완료 대기
// ─────────────────────────────────────────────────────────────────────────────

async function proceedToStep2(page: import('@playwright/test').Page) {
  // 1단계. 이메일 입력 + "계속" — example.com 은 routeStore 미등록 → matched:false → 2단계 진입
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()

  // 2단계. 진입 대기 — provider 드롭다운이 로딩 완료될 때까지
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  return providerSelect
}

test.describe('다중 Provider 명시 선택 (FR-AU-06)', () => {
  // ─────────────────────────────────────────────────────────────────────────────
  // S1 — Local provider 선택 후 정상 로그인
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1 Local provider 선택 → alice/password → 로그인 성공', async ({ page }) => {
    // Given. 로그인 페이지 진입 — MSW 기본 핸들러가 LDAP+Local 반환 (LDAP이 기본값)
    await page.goto('/login')

    // Given. 1단계 → 2단계 진입
    const providerSelect = await proceedToStep2(page)

    // When. 드롭다운에서 "Local" 선택
    await providerSelect.click()
    // Radix SelectContent는 Portal로 렌더 — role="option" 으로 탐색
    // (playwright-getbyrole-exact-strict-mode: LDAP-corp와 구분하기 위해 exact:true)
    await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

    // When. alice / password 입력
    await page.getByLabel(loginStrings.usernameLabel).fill('alice')
    await page.getByLabel(loginStrings.passwordLabel).fill('password')

    // When. 로그인 버튼 클릭 (exact:true — SSO 버튼과 구분)
    await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

    // Then. 로그인 성공 후 리다이렉트 대기 (목적지는 FR-PF-02 startPage 매핑 부수사항)
    await page.waitForURL('**/dashboard*')

    // Then. 로그인 성공 신호 — 목적지 경로 무관, alice 기본 startPage='dashboards'로 도착 확인 + Header 계정 메뉴 확인
    await expect(page).toHaveURL(/\/dashboards/)
    await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S2 — LDAP provider 선택 후 정상 로그인
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 LDAP provider 선택 → alice/Test1234! → 로그인 성공', async ({ page }) => {
    // Given. 로그인 페이지 진입 — MSW 기본 핸들러가 LDAP+Local 반환 (LDAP이 기본값)
    await page.goto('/login')

    // Given. 1단계 → 2단계 진입
    const providerSelect = await proceedToStep2(page)

    // When. 드롭다운에서 "LDAP-corp" 선택
    // 기본값이 이미 LDAP-corp 이지만, 명시적으로 선택하여 시나리오 의도를 드러낸다.
    await providerSelect.click()
    await page.getByRole('option', { name: loginStrings.providerLdapCorp, exact: true }).click()

    // When. alice / Test1234! 입력 (LDAP 유효 비밀번호)
    await page.getByLabel(loginStrings.usernameLabel).fill('alice')
    await page.getByLabel(loginStrings.passwordLabel).fill('Test1234!')

    // When. 로그인 버튼 클릭
    await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

    // Then. 로그인 성공 후 리다이렉트 대기 (목적지는 FR-PF-02 startPage 매핑 부수사항)
    await page.waitForURL('**/dashboard*')

    // Then. 로그인 성공 신호 — 목적지 경로 무관, alice 기본 startPage='dashboards'로 도착 확인 + Header 계정 메뉴 확인
    await expect(page).toHaveURL(/\/dashboards/)
    await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S3 — LDAP 비활성 시 드롭다운에 LDAP 항목 미표시
  // ─────────────────────────────────────────────────────────────────────────────
  test('S3 LDAP 비활성 — 드롭다운에 LDAP-corp 항목 없고 Local 항목만 존재', async ({ page }) => {
    // Given. 다음 페이지 로드 전에 localStorage 플래그를 심는다.
    // MSW auth-handlers 가 이 플래그를 읽어 providers:[Local 만] 를 반환한다.
    // addInitScript 는 goto 이전에 등록해야 첫 fetch 시점부터 플래그가 살아있다.
    // (e2e-msw-scenario-toggle-localstorage-flag)
    await page.addInitScript((key) => {
      window.localStorage.setItem(key, 'true')
    }, E2E_PROVIDERS_LOCAL_ONLY_KEY)

    // When. 로그인 페이지 진입 + 1단계 → 2단계 진입
    await page.goto('/login')
    const providerSelect = await proceedToStep2(page)

    // When. 드롭다운 열기
    await providerSelect.click()

    // Then. "Local" 항목이 존재한다
    await expect(
      page.getByRole('option', { name: loginStrings.providerLocal, exact: true }),
    ).toBeVisible()

    // Then. "LDAP-corp" 항목이 존재하지 않는다
    // (not.toBeVisible — Radix Portal에서 DOM에 존재하더라도 hidden이면 통과하지 않음)
    await expect(
      page.getByRole('option', { name: loginStrings.providerLdapCorp, exact: true }),
    ).not.toBeVisible({ timeout: 3_000 })
  })
})
