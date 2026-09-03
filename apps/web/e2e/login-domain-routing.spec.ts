// FR-AU-07 도메인 라우팅 E2E — 단일 화면에서 식별자 blur 시 배경 조회 → SSO 버튼 노출
//
// 이 스펙은 이메일 선입력 1단계 폐기와 함께 재작성됐다. 기능(도메인 → SSO 라우팅)은 그대로이고
// 트리거와 결과만 바뀌었다. "계속" 클릭 → 자동 리다이렉트가 blur → 버튼 노출 → 사용자 클릭이 됐다.
//
// 자동 이동을 폐기한 이유. 단일 화면에서 조회 트리거는 blur/디바운스로 수동적이라,
// 그 상태로 풀 네비게이션을 걸면 타이핑 중이던 비밀번호와 함께 화면이 통째로 사라진다.
//
// S1 — SAML 도메인 매칭: alice@partner.com blur → SSO 버튼 노출 → 클릭 시 /saml2/authenticate/partner-saml
// S2 — OIDC 도메인 매칭: bob@acme.com blur → SSO 버튼 노출 → 클릭 시 /oauth2/authorization/acme-oidc
// S3 — 미매칭 도메인: x@gmail.com → SSO 버튼 미노출, 로컬 폼만
// S4 — @가 없는 입력: alice → route 조회 없이 로컬 폼만 (LDAP 사용자명 경로)
// S5 — 매칭돼도 로컬 제출은 살아 있다 (FR-07 S4 fail-safe)
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 시드 패턴
//   - msw-derived-behavior-shared-store-e2e: MSW routeStore 는 브라우저 localStorage 시드로 오버라이드
//   - playwright-getbyrole-exact-strict-mode: exact:true 한정
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { E2E_ROUTE_STORE_KEY } from '../src/mocks/route-handlers'

/** routeStore 를 브라우저 localStorage 에 시드한다 — 다른 테스트 오염 방지 */
async function seedRouteStore(page: Page, store: Record<string, unknown>) {
  await page.addInitScript(
    ({ key, value }) => {
      window.localStorage.setItem(key, value)
    },
    { key: E2E_ROUTE_STORE_KEY, value: JSON.stringify(store) },
  )
}

/**
 * 로그인 모달에서 식별자를 입력하고 blur 로 조회를 확정시킨다.
 * blur 는 Tab 이 아니라 비밀번호 필드 클릭으로 만든다 — 실제 사용자 동선과 같다.
 */
async function fillIdentifierAndBlur(page: Page, identifier: string) {
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  await page.getByLabel(loginStrings.usernameLabel).fill(identifier)
  await page.getByLabel(loginStrings.passwordLabel).click()
}

test.describe('도메인 라우팅 (FR-AU-07)', () => {
  test('S1 SAML 도메인 매칭 — blur 로 SSO 버튼이 뜨고 클릭하면 /saml2/authenticate/partner-saml 로 간다', async ({
    page,
  }) => {
    // Given. 풀 네비게이션을 인터셉트해 목적지 URL 을 잡는다
    let capturedUrl: string | null = null
    await page.route('**/saml2/authenticate/**', (route) => {
      capturedUrl = route.request().url()
      void route.fulfill({ status: 200, body: '' })
    })
    await seedRouteStore(page, {
      'partner.com': { type: 'SAML', registrationId: 'partner-saml', displayName: 'Partner SSO' },
    })

    await page.goto('/login')
    await fillIdentifierAndBlur(page, 'alice@partner.com')

    // Then. blur 만으로는 이동하지 않고 버튼이 나타난다
    const ssoButton = page.getByRole('button', {
      name: loginStrings.samlLoginButtonLabel('Partner SSO'),
      exact: true,
    })
    await expect(ssoButton).toBeVisible()
    expect(capturedUrl).toBeNull()

    // Then. 사용자가 명시적으로 클릭해야 이동한다
    await ssoButton.click()
    await expect(async () => {
      expect(capturedUrl).not.toBeNull()
      expect(capturedUrl).toMatch(/\/saml2\/authenticate\/partner-saml$/)
    }).toPass({ timeout: 8_000 })
  })

  test('S2 OIDC 도메인 매칭 — blur 로 SSO 버튼이 뜨고 클릭하면 /oauth2/authorization/acme-oidc 로 간다', async ({
    page,
  }) => {
    let capturedUrl: string | null = null
    await page.route('**/oauth2/authorization/**', (route) => {
      capturedUrl = route.request().url()
      void route.fulfill({ status: 200, body: '' })
    })
    await seedRouteStore(page, {
      'acme.com': { type: 'OIDC', registrationId: 'acme-oidc', displayName: 'Acme Google SSO' },
    })

    await page.goto('/login')
    await fillIdentifierAndBlur(page, 'bob@acme.com')

    const ssoButton = page.getByRole('button', {
      name: loginStrings.oidcLoginButtonLabel('Acme Google SSO'),
      exact: true,
    })
    await expect(ssoButton).toBeVisible()
    expect(capturedUrl).toBeNull()

    await ssoButton.click()
    await expect(async () => {
      expect(capturedUrl).not.toBeNull()
      expect(capturedUrl).toMatch(/\/oauth2\/authorization\/acme-oidc$/)
    }).toPass({ timeout: 8_000 })
  })

  test('S3 미매칭 도메인 — x@gmail.com 은 SSO 버튼 없이 로컬 폼만 남는다', async ({ page }) => {
    // Given. gmail.com 은 routeStore 에 없으므로 matched:false (route-handlers.ts 기본 동작)
    await page.goto('/login')
    await fillIdentifierAndBlur(page, 'x@gmail.com')

    // Then. 로컬 폼 3요소가 처음부터 함께 보인다 — 단계 전환이 없다
    await expect(page.getByRole('combobox', { name: loginStrings.providerLabel })).toBeVisible()
    await expect(page.getByLabel(loginStrings.usernameLabel)).toHaveValue('x@gmail.com')
    await expect(page.getByLabel(loginStrings.passwordLabel)).toBeVisible()

    // Then. SSO 힌트가 뜨지 않는다
    await expect(page.getByText(loginStrings.ssoRoutedHint)).toHaveCount(0)
  })

  test('S4 @ 없는 입력 — alice 는 SSO 안내 없이 로컬 폼만 (LDAP 사용자명)', async ({ page }) => {
    // 🛑 `page.route` 로 요청 수를 세지 마라. `/api/v1/auth/route` 는 MSW 핸들러라
    //    서비스워커가 처리하고 네트워크로 나가지 않는다 — 카운터는 앱이 조회를 하든 말든
    //    항상 0이라 단언을 지워도 통과하는 공허한 판정이 된다(커밋 75e010512 가 확립한 사실).
    //    「조회를 안 한다」의 실제 검증은 MSW node 서버에서 카운트하는
    //    `LoginForm.test.tsx` 의 "@ 가 없는 식별자는 route 조회를 하지 않는다" 가 맡는다.
    //    여기서는 **관측 가능한 결과**만 잰다.
    // 이 도메인이 매칭되도록 시드해 둔다 — 그래도 `@` 가 없으면 SSO 안내가 뜨지 않아야 한다.
    await seedRouteStore(page, {
      'partner.com': { type: 'SAML', registrationId: 'partner-saml', displayName: 'Partner SSO' },
    })

    await page.goto('/login')
    await fillIdentifierAndBlur(page, 'alice')

    await expect(
      page.getByRole('button', { name: loginStrings.submitButton, exact: true }),
    ).toBeVisible()
    await expect(page.getByLabel(loginStrings.usernameLabel)).toHaveValue('alice')
    await expect(page.getByText(loginStrings.ssoRoutedHint)).toHaveCount(0)
  })

  test('S5 SSO 로 라우팅된 도메인에서도 로컬 로그인 버튼이 살아 있다 (FR-07 S4 fail-safe)', async ({
    page,
  }) => {
    // 매칭 도메인에 LOCAL/LDAP 계정이 공존할 수 있으므로 로컬 경로를 막으면 안 된다.
    await seedRouteStore(page, {
      'partner.com': { type: 'SAML', registrationId: 'partner-saml', displayName: 'Partner SSO' },
    })

    await page.goto('/login')
    await fillIdentifierAndBlur(page, 'alice@partner.com')

    await expect(
      page.getByRole('button', {
        name: loginStrings.samlLoginButtonLabel('Partner SSO'),
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: loginStrings.submitButton, exact: true }),
    ).toBeEnabled()
  })
})
