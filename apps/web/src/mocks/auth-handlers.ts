// identity-access BC MSW mock handlers (alice/bob 두 사용자 + 401 invalid + 200 happy + me 조회)
import { http, HttpResponse } from 'msw'

/** alice fixture — backend DB seed 사용자와 일치 */
const aliceUser = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'jwt',
  userId: '00000000-0000-0000-0000-000000000001',
}

/** bob fixture — 추가 fixture 사용자 */
const bobUser = {
  username: 'bob',
  email: 'bob@bts.local',
  authMethod: 'jwt',
  userId: '00000000-0000-0000-0000-000000000002',
}

/** username → fixture 사용자 맵 */
const USERS: Readonly<Record<string, typeof aliceUser>> = {
  alice: aliceUser,
  bob: bobUser,
}

/** username → 유효 비밀번호 맵 (mock 전용) */
const VALID_PASSWORDS: Readonly<Record<string, string>> = {
  alice: 'password',
  bob: 'password',
}

/** mock access token 생성 — username 기반으로 E2E에서 추적 가능 */
function mockAccessToken(username: string): string {
  return `mock-access-token-${username}`
}

/**
 * POST /api/v1/auth/login — username/password 검증 후 token 또는 401 반환.
 *
 * 응답 schema: backend AuthController.TokenResponse (`access_token`, `token_type`, `expires_in`)
 * 에러 schema: `{ error: "invalid_credentials" }` — useLoginMutation의 resolveLoginErrorMessage 가 사용
 */
const loginHandler = http.post('/api/v1/auth/login', async ({ request }) => {
  const body = await request.json() as { username?: string; password?: string }
  const username = body.username ?? ''
  const password = body.password ?? ''

  const validPassword = VALID_PASSWORDS[username]
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
 * 응답 schema: backend WhoamiResponse (`username`, `email`, `authMethod`, `userId`)
 * Authorization 헤더 없거나 토큰이 `mock-access-token-` prefix가 아니면 401.
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
  const user = USERS[username]
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
