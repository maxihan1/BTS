// FR-AU-07 도메인 라우팅 E2E — identifier-first 1단계 이메일 입력 → 도메인별 SSO 분기 또는 2단계 폼 진입
//
// S1 — SAML 도메인 매칭: alice@partner.com → /saml2/authenticate/partner-saml 네비게이션 시도
//   Given  E2E_ROUTE_STORE_KEY localStorage 시드 (partner.com → SAML:partner-saml)
//   When   이메일 입력 후 "계속" 클릭
//   Then   page.route 인터셉트로 /saml2/authenticate/partner-saml 진입 URL 확인
//
// S2 — OIDC 도메인 매칭: bob@acme.com → /oauth2/authorization/acme-oidc 네비게이션 시도
//   Given  E2E_ROUTE_STORE_KEY localStorage 시드 (acme.com → OIDC:acme-oidc)
//   When   이메일 입력 후 "계속" 클릭
//   Then   page.route 인터셉트로 /oauth2/authorization/acme-oidc 진입 URL 확인
//
// S3 — 미매칭 도메인: x@gmail.com → 2단계 폼(드롭다운+username+password) 노출 + username에 이메일 프리필
//   Given  gmail.com 은 routeStore 미등록 → matched:false 반환
//   When   이메일 입력 후 "계속" 클릭
//   Then   2단계 폼 노출 + username 필드에 'x@gmail.com' 프리필
//
// S4 — @가 없는 입력: alice → route 조회 없이 즉시 2단계 진입
//   Given  '@' 없는 plain 문자열
//   When   "계속" 클릭
//   Then   즉시 2단계 폼 노출 + username 필드에 'alice' 프리필
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 시드 패턴
//   - msw-derived-behavior-shared-store-e2e: MSW routeStore 는 브라우저 localStorage 시드로 오버라이드
//   - worktree-stale-base-rebase-and-e2e-msw-traps: CSRF 쿠키/드롭다운 로딩 대기
//   - playwright-getbyrole-exact-strict-mode: exact:true 한정
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { E2E_ROUTE_STORE_KEY } from '../src/mocks/route-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 1단계 이메일 입력 + "계속" 클릭
// ─────────────────────────────────────────────────────────────────────────────

async function fillEmailAndContinue(page: import('@playwright/test').Page, email: string) {
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  const emailInput = page.getByLabel(loginStrings.emailLabel)
  await expect(emailInput).toBeVisible()
  await emailInput.fill(email)
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()
}

test.describe('도메인 라우팅 (FR-AU-07)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — SAML 도메인 매칭
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 SAML 도메인 매칭 — alice@partner.com → /saml2/authenticate/partner-saml 네비게이션 시도', async ({ page }) => {
    // Given. /saml2/authenticate/** 요청을 인터셉트한다.
    // LoginForm 은 window.location.assign 으로 풀 네비게이션을 일으키므로
    // page.route 로 경로를 잡아 navigated URL을 검증한다.
    let capturedUrl: string | null = null
    await page.route('**/saml2/authenticate/**', (route) => {
      capturedUrl = route.request().url()
      void route.fulfill({ status: 200, body: '' })
    })

    // Given. routeStore 시드 — 기본 partner.com→SAML:partner-saml 이 이미 설정되어 있지만
    // addInitScript 로 명시적으로 확인한다 (다른 테스트 오염 방지).
    await page.addInitScript(
      ({ key, value }) => {
        window.localStorage.setItem(key, value)
      },
      {
        key: E2E_ROUTE_STORE_KEY,
        value: JSON.stringify({
          'partner.com': { type: 'SAML', registrationId: 'partner-saml', displayName: 'Partner SSO' },
        }),
      },
    )

    // When. 로그인 페이지 진입 + 이메일 입력 + 계속
    await page.goto('/login')
    await fillEmailAndContinue(page, 'alice@partner.com')

    // Then. /saml2/authenticate/partner-saml 로 네비게이션 시도가 발생해야 한다
    await expect(async () => {
      expect(capturedUrl).not.toBeNull()
      expect(capturedUrl).toMatch(/\/saml2\/authenticate\/partner-saml$/)
    }).toPass({ timeout: 8_000 })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — OIDC 도메인 매칭
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 OIDC 도메인 매칭 — bob@acme.com → /oauth2/authorization/acme-oidc 네비게이션 시도', async ({ page }) => {
    // Given. /oauth2/authorization/** 요청을 인터셉트한다.
    let capturedUrl: string | null = null
    await page.route('**/oauth2/authorization/**', (route) => {
      capturedUrl = route.request().url()
      void route.fulfill({ status: 200, body: '' })
    })

    // Given. routeStore 시드 — acme.com→OIDC:acme-oidc
    await page.addInitScript(
      ({ key, value }) => {
        window.localStorage.setItem(key, value)
      },
      {
        key: E2E_ROUTE_STORE_KEY,
        value: JSON.stringify({
          'acme.com': { type: 'OIDC', registrationId: 'acme-oidc', displayName: 'Acme Google SSO' },
        }),
      },
    )

    // When. 로그인 페이지 진입 + 이메일 입력 + 계속
    await page.goto('/login')
    await fillEmailAndContinue(page, 'bob@acme.com')

    // Then. /oauth2/authorization/acme-oidc 로 네비게이션 시도가 발생해야 한다
    await expect(async () => {
      expect(capturedUrl).not.toBeNull()
      expect(capturedUrl).toMatch(/\/oauth2\/authorization\/acme-oidc$/)
    }).toPass({ timeout: 8_000 })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 — 미매칭 도메인 → 2단계 폼 + 이메일 프리필
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 미매칭 도메인 — x@gmail.com → 2단계 폼 노출 + username에 이메일 프리필', async ({ page }) => {
    // Given. gmail.com 은 routeStore 에 없으므로 matched:false 반환
    // (route-handlers.ts 기본 동작 — 별도 시드 불필요)

    await page.goto('/login')

    // When. 미매칭 도메인으로 계속 클릭
    await fillEmailAndContinue(page, 'x@gmail.com')

    // Then. 2단계 폼(provider 드롭다운 + username + password)이 노출된다
    // (worktree-stale-base-rebase-and-e2e-msw-traps: 드롭다운 로딩 대기)
    const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
    await expect(providerSelect).toBeVisible()
    await expect(page.getByLabel(loginStrings.usernameLabel)).toBeVisible()
    await expect(page.getByLabel(loginStrings.passwordLabel)).toBeVisible()

    // Then. username 필드에 'x@gmail.com' 이 프리필되어 있다
    await expect(page.getByLabel(loginStrings.usernameLabel)).toHaveValue('x@gmail.com')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 — @없는 입력 → route 조회 없이 즉시 2단계 진입
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 @ 없는 입력 — alice → route 조회 없이 즉시 2단계 폼 노출 + username에 alice 프리필', async ({ page }) => {
    // Given. '@' 가 없으면 LoginForm 은 route 조회를 건너뛴다
    await page.goto('/login')

    // When. '@' 없는 입력으로 계속 클릭
    await fillEmailAndContinue(page, 'alice')

    // Then. 2단계 폼이 즉시(네트워크 왕복 없이) 노출된다
    await expect(
      page.getByRole('button', { name: loginStrings.submitButton, exact: true }),
    ).toBeVisible()
    await expect(page.getByLabel(loginStrings.usernameLabel)).toBeVisible()

    // Then. username 필드에 'alice' 가 프리필되어 있다
    await expect(page.getByLabel(loginStrings.usernameLabel)).toHaveValue('alice')
  })
})
