// identity-access BC MSW mock handlers (alice/bob 두 사용자 + 401 invalid + 200 happy + me 조회)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS, LDAP_VALID_PASSWORDS, VALID_PASSWORDS, mockAccessToken } from './auth-fixtures'

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
  else if (provider === 'ldap-corp') validPasswordMap = LDAP_VALID_PASSWORDS
  else return HttpResponse.json({ error: 'unknown_provider' }, { status: 401 })

  const validPassword = validPasswordMap[username]
  if (validPassword === undefined || password !== validPassword) {
    return HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 })
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

  return HttpResponse.json(user)
})

/**
 * POST /api/v1/auth/logout — 세션 폐기 (204 No Content).
 * mock 환경에서는 항상 성공 처리.
 */
const logoutHandler = http.post('/api/v1/auth/logout', () => {
  return new HttpResponse(null, { status: 204 })
})

export const authHandlers = [loginHandler, whoamiHandler, logoutHandler]
