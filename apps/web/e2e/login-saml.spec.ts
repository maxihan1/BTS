// FR-AU-03 SAML SSO 로그인 진입 E2E — IdP 버튼 노출/클릭 시나리오
//
// 실제 IdP 왕복은 백엔드 통합테스트(Task 7)에 위임한다.
// 프론트는 버튼 노출 확인 및 SP-initiated 인증 URL 진입 시도까지만 검증한다.
//
// S1 — IdP 버튼 노출 + 클릭 시 SP-initiated 인증 경로로 네비게이션 시도.
//   Given  /login 진입 (MSW 기본 핸들러가 Okta SSO 1개 반환)
//   When   "Okta SSO 로 로그인" 버튼 클릭
//   Then   /sso/saml2/authenticate/okta 로 네비게이션 시도 (page.route 인터셉트로 검증)
//
// S5 — IdP 0개이면 SAML 버튼 영역 미노출.
//   Given  /login 진입 + addInitScript 로 localStorage '__bts_e2e_saml_no_idps' = 'true' 설정
//          (MSW 핸들러가 빈 idps 배열 반환)
//   When   로그인 폼 로드 완료
//   Then   SAML 버튼 및 divider("또는") 미노출
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: 버튼 텍스트 exact:true 로 한정
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 (이 파일에 없음)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그 패턴
//   - e2e-fixture-whoami-userid-alignment: 로그인 불필요 (로그인 페이지 자체 검증)
import { test, expect } from '@playwright/test'
import { loginStrings } from '../src/i18n/ko'
import { E2E_SAML_NO_IDPS_KEY } from '../src/mocks/saml-handlers'

test.describe('SAML SSO 로그인 진입 (FR-AU-03)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — IdP 버튼 노출 + 클릭 시 SP-initiated 인증 경로로 네비게이션 시도
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 IdP 버튼 노출 — "Okta SSO 로 로그인" 표시 + 클릭 시 /sso/saml2/authenticate/okta 네비게이션 시도', async ({ page }) => {
    // Given. /sso/saml2/** 로 실제 HTTP 요청이 나가기 전에 인터셉트한다.
    // SamlIdpButtons 는 window.location.assign 으로 풀 네비게이션을 일으키므로
    // page.route 로 경로를 잡아 응답 대신 navigated URL 을 검증한다.
    let capturedSamlUrl: string | null = null
    await page.route('**/sso/saml2/**', (route) => {
      capturedSamlUrl = route.request().url()
      // 실제 백엔드가 없으므로 빈 200 으로 이행 — 리다이렉트 루프 방지
      void route.fulfill({ status: 200, body: '' })
    })

    // Given. 로그인 페이지 진입 — MSW 기본 핸들러가 Okta SSO 1개 반환
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    // Given. SAML 버튼이 나타날 때까지 대기 (useQuery fetch 완료 후 렌더)
    const idpButton = page.getByRole('button', {
      name: loginStrings.samlLoginButtonLabel('Okta SSO'),
      exact: true,
    })
    await expect(idpButton).toBeVisible()

    // When. IdP 버튼 클릭
    await idpButton.click()

    // Then. /sso/saml2/authenticate/okta 경로로 네비게이션 시도가 발생했는지 확인
    // page.route 가 요청을 잡았으므로 capturedSamlUrl 이 설정되어 있어야 한다.
    expect(capturedSamlUrl).not.toBeNull()
    expect(capturedSamlUrl).toMatch(/\/sso\/saml2\/authenticate\/okta$/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 — IdP 0개이면 SAML 버튼 영역(버튼 + divider) 미노출
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 IdP 0개 — SAML 버튼 영역 미노출 (divider "또는" 포함)', async ({ page }) => {
    // Given. 다음 페이지 로드 전에 localStorage 플래그를 심는다.
    // MSW saml-handlers 가 이 플래그를 읽어 idps:[] 를 반환한다.
    // addInitScript 는 goto 이전에 등록해야 첫 fetch 시점부터 플래그가 살아있다.
    await page.addInitScript((key) => {
      window.localStorage.setItem(key, 'true')
    }, E2E_SAML_NO_IDPS_KEY)

    // When. 로그인 페이지 진입
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    // Then. 로그인 버튼("로그인")은 존재하고 — 기존 폼 정상 렌더 확인
    await expect(
      page.getByRole('button', { name: loginStrings.submitButton, exact: true }),
    ).toBeVisible()

    // Then. SAML IdP 버튼 미노출 — IdP 목록이 비어있으므로 SamlIdpButtons 가 null 반환
    // useQuery 가 완료된 후에도 버튼이 없어야 하므로 충분한 시간 대기
    await expect(
      page.getByRole('button', { name: /로 로그인/ }),
    ).not.toBeVisible({ timeout: 5_000 })

    // Then. divider 텍스트("또는")도 미노출
    await expect(
      page.getByText(loginStrings.samlDividerText, { exact: true }),
    ).not.toBeVisible({ timeout: 5_000 })
  })
})
