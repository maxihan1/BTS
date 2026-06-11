// identity-access BC MSW mock handlers (alice/bob 두 사용자 + 401 invalid + 200 happy + me 조회)
import { http, HttpResponse } from 'msw'
import {
  AUTH_USERS,
  LDAP_VALID_PASSWORDS,
  MFA_E2E_ENABLED_KEY,
  VALID_PASSWORDS,
  mockAccessToken,
} from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키 — FR-AU-05 Task 9
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면 whoami 응답에
 * mustChangePassword:true 를 오버라이드한다.
 *
 * password-handlers.ts 의 변경 성공 시 이 플래그를 removeItem 으로 제거해
 * 강제 변경 해제를 시뮬레이션한다 (msw-derived-behavior-shared-store-e2e).
 *
 * Playwright addInitScript 로 goto 전에 플래그를 설정하면 첫 whoami fetch 시점부터 적용된다.
 */
export const E2E_MUST_CHANGE_PASSWORD_KEY = '__bts_e2e_must_change_password'

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면 whoami 응답에
 * isSystemAdmin:true 를 오버라이드한다.
 *
 * Playwright addInitScript 로 goto 전에 플래그를 설정하면 첫 whoami fetch 시점부터 적용된다.
 */
export const E2E_IS_SYSTEM_ADMIN_KEY = '__bts_e2e_is_system_admin'

/**
 * POST /api/v1/auth/login — provider + username/password 검증 후 token 또는 401 반환.
 *
 * provider 분기 (provider 누락 또는 미지원 값 모두 unknown_provider — silent 'local' fallback 금지).
 * - `local`: VALID_PASSWORDS (alice/password, bob/password) 검증
 * - `ldap-corp`: LDAP_VALID_PASSWORDS (alice/Test1234!, bob/Test1234!) 검증
 * - 누락 / 그 외: 401 `{ error: "unknown_provider" }` (방어 layer, frontend Zod 가 1차 차단 + 본 분기 가 2차)
 *
 * 응답 schema: backend AuthController.TokenResponse (`access_token`, `token_type`, `expires_in`)
 * 에러 schema: `{ error: "invalid_credentials" | "unknown_provider" }` — useLoginMutation 의 resolveLoginErrorMessage 가 한국어 매핑
 */
const loginHandler = http.post('/api/v1/auth/login', async ({ request }) => {
  const body = await request.clone().json() as { provider?: string; username?: string; password?: string }
  const provider = body.provider
  const username = body.username ?? ''
  const password = body.password ?? ''

  let validPasswordMap: Readonly<Record<string, string>>
  if (provider === 'local') validPasswordMap = VALID_PASSWORDS
  // 'ldap' — providers API가 반환하는 현재 id. 'ldap-corp'는 레거시 하드코딩값(하위 호환 유지).
  else if (provider === 'ldap' || provider === 'ldap-corp') validPasswordMap = LDAP_VALID_PASSWORDS
  else return HttpResponse.json({ error: 'unknown_provider' }, { status: 401 })

  const validPassword = validPasswordMap[username]
  if (validPassword === undefined || password !== validPassword) {
    return HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 })
  }

  // E2E 시나리오 토글 — localStorage 플래그가 true이면 MFA 챌린지 응답 반환
  // (msw-derived-behavior-shared-store-e2e, fr-mf-01-totp-backend-done)
  const mfaEnabled =
    globalThis.localStorage?.getItem(MFA_E2E_ENABLED_KEY) === 'true'

  if (mfaEnabled) {
    return HttpResponse.json({
      mfa_required: true,
      mfa_challenge_token: `mock-mfa-challenge-token-${username}`,
      expires_in: 300,
    })
  }

  return HttpResponse.json({
    access_token: mockAccessToken(username),
    token_type: 'Bearer',
    expires_in: 900,
  })
})

/**
 * GET /api/v1/users/me/whoami — Authorization Bearer 헤더 검증 후 사용자 정보 반환.
 *
 * 응답 schema: backend WhoamiResponse (`username`, `email`, `authMethod`, `userId`, `mustChangePassword`, `isSystemAdmin`)
 * Authorization 헤더 없거나 토큰 미인식 시 401.
 */
const whoamiHandler = http.get('/api/v1/users/me/whoami', ({ request }) => {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) {
    return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  const token = authHeader.slice('Bearer '.length)
  const prefix = 'mock-access-token-'
  if (!token.startsWith(prefix)) {
    return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  const username = token.slice(prefix.length)
  const user = AUTH_USERS[username]
  if (user === undefined) {
    return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
  }

  // E2E 시나리오 토글 — localStorage 플래그로 mustChangePassword / isSystemAdmin 오버라이드
  // (e2e-msw-scenario-toggle-localstorage-flag, msw-derived-behavior-shared-store-e2e)
  const mustChangePassword =
    globalThis.localStorage?.getItem(E2E_MUST_CHANGE_PASSWORD_KEY) === 'true'
      ? true
      : user.mustChangePassword
  const isSystemAdmin =
    globalThis.localStorage?.getItem(E2E_IS_SYSTEM_ADMIN_KEY) === 'true'
      ? true
      : user.isSystemAdmin

  return HttpResponse.json({ ...user, mustChangePassword, isSystemAdmin })
})

/**
 * POST /api/v1/auth/logout — 세션 폐기 (204 No Content).
 * mock 환경에서는 항상 성공 처리.
 */
const logoutHandler = http.post('/api/v1/auth/logout', () => {
  return new HttpResponse(null, { status: 204 })
})

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면 providers 응답에서
 * LDAP을 제외하고 LOCAL 만 반환한다 (S3 — LDAP 비활성 시나리오).
 *
 * Playwright addInitScript 로 goto 전에 플래그를 설정하면 첫 fetch 시점부터 적용된다.
 */
export const E2E_PROVIDERS_LOCAL_ONLY_KEY = '__bts_e2e_providers_local_only'

/**
 * GET /api/v1/auth/providers — 활성 인증 공급자 목록 반환.
 *
 * 기본값. LDAP(priority 0, 먼저 표시) + Local(priority 1).
 * 응답 schema: backend ProvidersController.ProvidersResponse.
 * id는 login 요청 provider 필드에 그대로 전달된다.
 *
 * E2E 시나리오 토글 — localStorage '__bts_e2e_providers_local_only' = 'true' 이면
 * LDAP을 제외한 LOCAL 1개만 반환한다 (msw-derived-behavior-shared-store-e2e).
 */
const providersHandler = http.get('/api/v1/auth/providers', () => {
  if (globalThis.localStorage?.getItem(E2E_PROVIDERS_LOCAL_ONLY_KEY) === 'true') {
    return HttpResponse.json({
      providers: [
        { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 0, available: true },
      ],
    })
  }
  return HttpResponse.json({
    providers: [
      { id: 'ldap', type: 'LDAP', displayName: 'Ldap', priority: 0, available: true },
      { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 1, available: true },
    ],
  })
})

export const authHandlers = [loginHandler, whoamiHandler, logoutHandler, providersHandler]
