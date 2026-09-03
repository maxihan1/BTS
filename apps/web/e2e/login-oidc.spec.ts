// FR-AU-04 OIDC SSO 로그인 진입 E2E — IdP 버튼 노출/클릭 시나리오
//
// 실제 IdP 왕복(Google OAuth2 리다이렉트)은 백엔드 통합테스트(T7)에 위임한다.
// 프론트는 버튼 노출 확인 및 인증 URL 진입 시도까지만 검증한다.
//
// OIDC 버튼은 자격 증명 폼 하단에 상시 렌더된다.
// 단계 전환 없이 폼 렌더 직후 OIDC 버튼을 검증한다.
//
// S1 — IdP 버튼 노출 + 클릭 시 OIDC 인증 경로로 네비게이션 시도.
//   Given  /login 진입 후 단일 화면 폼 제출
//          (MSW 기본 핸들러가 Google provider 1개 반환)
//   When   "Google 로 로그인" 버튼 클릭
//   Then   /oauth2/authorization/google 로 네비게이션 시도 (page.route 인터셉트로 검증)
//
// S5 — IdP 0개이면 OIDC 버튼 영역 미노출.
//   Given  /login 진입 + addInitScript 로 localStorage '__bts_e2e_oidc_no_providers' = 'true' 설정
//          (MSW 핸들러가 빈 providers 배열 반환)
//          + 단일 화면 폼 제출
//   When   로그인 폼 로드 완료
//   Then   OIDC 버튼("Google 로 로그인") 미노출 — SAML 버튼 영향 없음
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: 버튼 텍스트 exact:true 로 한정
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 (이 파일에 없음)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그 패턴
//   - ui-pr-defer-e2e-regression-latent: OIDC 버튼 추가가 SAML/기존 셀렉터 strict mode 안 깨는지 확인
//   - e2e-fixture-whoami-userid-alignment: 로그인 불필요 (로그인 페이지 자체 검증)
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { E2E_OIDC_NO_PROVIDERS_KEY } from '../src/mocks/oidc-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 자격 증명 폼 렌더 대기
// ─────────────────────────────────────────────────────────────────────────────

async function proceedToStep2(page: import('@playwright/test').Page) {
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  // 로그인 버튼이 나타나면 폼 준비 완료
  await expect(page.getByRole('button', { name: loginStrings.submitButton, exact: true })).toBeVisible()
}

test.describe('OIDC SSO 로그인 진입 (FR-AU-04)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — IdP 버튼 노출 + 클릭 시 OIDC 인증 경로로 네비게이션 시도
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 IdP 버튼 노출 — "Google 로 로그인" 표시 + 클릭 시 /oauth2/authorization/google 네비게이션 시도', async ({ page }) => {
    // Given. /oauth2/authorization/** 로 실제 HTTP 요청이 나가기 전에 인터셉트한다.
    // OidcIdpButtons 는 window.location.assign 으로 풀 네비게이션을 일으키므로
    // page.route 로 경로를 잡아 응답 대신 navigated URL 을 검증한다.
    // Spring Security 표준 OIDC 엔드포인트: /oauth2/authorization/{registrationId}
    let capturedOidcUrl: string | null = null
    await page.route('**/oauth2/authorization/**', (route) => {
      capturedOidcUrl = route.request().url()
      // 실제 백엔드가 없으므로 빈 200 으로 이행 — 리다이렉트 루프 방지
      void route.fulfill({ status: 200, body: '' })
    })

    // Given. 로그인 페이지 진입 후 폼 준비 대기
    // MSW 기본 핸들러가 Google provider 1개 반환
    await page.goto('/login')
    await proceedToStep2(page)

    // Given. OIDC 버튼이 나타날 때까지 대기 (useQuery fetch 완료 후 렌더)
    const idpButton = page.getByRole('button', {
      name: loginStrings.oidcLoginButtonLabel('Google'),
      exact: true,
    })
    await expect(idpButton).toBeVisible()

    // When. IdP 버튼 클릭
    await idpButton.click()

    // Then. /oauth2/authorization/google 경로로 네비게이션 시도가 발생했는지 확인
    // page.route 가 요청을 잡았으므로 capturedOidcUrl 이 설정되어 있어야 한다.
    expect(capturedOidcUrl).not.toBeNull()
    expect(capturedOidcUrl).toMatch(/\/oauth2\/authorization\/google$/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 — IdP 0개이면 OIDC 버튼 영역 미노출 (SAML 버튼 영향 없음)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 IdP 0개 — OIDC 버튼 영역 미노출 (기존 SAML/로그인 폼 영향 없음)', async ({ page }) => {
    // Given. 다음 페이지 로드 전에 localStorage 플래그를 심는다.
    // MSW oidc-handlers 가 이 플래그를 읽어 providers:[] 를 반환한다.
    // addInitScript 는 goto 이전에 등록해야 첫 fetch 시점부터 플래그가 살아있다.
    await page.addInitScript((key) => {
      window.localStorage.setItem(key, 'true')
    }, E2E_OIDC_NO_PROVIDERS_KEY)

    // When. 로그인 페이지 진입 후 폼 준비 대기
    await page.goto('/login')
    await proceedToStep2(page)

    // Then. 로그인 버튼("로그인")은 존재하고 — 기존 폼 정상 렌더 확인
    await expect(
      page.getByRole('button', { name: loginStrings.submitButton, exact: true }),
    ).toBeVisible()

    // Then. OIDC IdP 버튼 미노출 — "Google 로 로그인" exact 매칭
    // (SAML과 동일한 "X 로 로그인" 패턴이므로 exact:true + provider 이름 명시로 한정)
    await expect(
      page.getByRole('button', {
        name: loginStrings.oidcLoginButtonLabel('Google'),
        exact: true,
      }),
    ).not.toBeVisible({ timeout: 5_000 })
  })
})
