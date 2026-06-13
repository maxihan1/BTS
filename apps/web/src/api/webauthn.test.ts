// WebAuthn(보안 키) API 클라이언트 단위 테스트 — fetch 방식·헤더·credentials·직렬화·회귀 가드(NFR-2)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  webauthnRegisterStart,
  webauthnRegisterFinish,
  listWebauthnKeys,
  deleteWebauthnKey,
  webauthnAuthenticateStart,
  verifyWebauthn,
  registerSecurityKey,
  authenticateWithSecurityKey,
} from './webauthn'

// @simplewebauthn/browser 모듈 mock — vi.mock은 호이스팅되므로 최상위에 선언
vi.mock('@simplewebauthn/browser', () => ({
  startRegistration: vi.fn(),
  startAuthentication: vi.fn(),
  browserSupportsWebAuthn: vi.fn().mockReturnValue(true),
}))

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — CSRF 헤더 검증용
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-webauthn-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture
// ─────────────────────────────────────────────────────────────────────────────

/** 백엔드가 내려주는 WebAuthn registration options (PublicKeyCredentialCreationOptionsJSON 형태) */
const registrationOptionsFixture = {
  rp: { name: 'BTS', id: 'localhost' },
  user: { id: 'dXNlcmlk', name: 'alice', displayName: 'Alice' },
  challenge: 'Y2hhbGxlbmdl',
  pubKeyCredParams: [{ alg: -7, type: 'public-key' }],
  timeout: 60000,
  attestation: 'none',
  excludeCredentials: [],
  authenticatorSelection: { userVerification: 'required' },
  extensions: {},
}

/** 브라우저 WebAuthn API가 반환하는 registration credential */
const registrationCredentialFixture = {
  id: 'credential-id',
  rawId: 'cmF3SWQ',
  response: {
    attestationObject: 'YXR0ZXN0YXRpb24=',
    clientDataJSON: 'Y2xpZW50RGF0YQ==',
  },
  type: 'public-key',
  clientExtensionResults: {},
}

/** 백엔드가 내려주는 WebAuthn authentication options (PublicKeyCredentialRequestOptionsJSON 형태) */
const authenticationOptionsFixture = {
  challenge: 'Y2hhbGxlbmdlMg==',
  timeout: 60000,
  rpId: 'localhost',
  allowCredentials: [{ id: 'credential-id', type: 'public-key' }],
  userVerification: 'required',
  extensions: {},
}

/** 브라우저 WebAuthn API가 반환하는 authentication assertion */
const authenticationCredentialFixture = {
  id: 'credential-id',
  rawId: 'cmF3SWQ',
  response: {
    authenticatorData: 'YXV0aERhdGE=',
    clientDataJSON: 'Y2xpZW50RGF0YQ==',
    signature: 'c2lnbmF0dXJl',
    userHandle: null,
  },
  type: 'public-key',
  clientExtensionResults: {},
}

const tokenResponseFixture = {
  access_token: 'eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.webauthn',
  token_type: 'Bearer' as const,
  expires_in: 900,
}

const webauthnKeysFixture = {
  keys: [
    {
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      name: 'YubiKey 5',
      createdAt: '2026-06-01T00:00:00Z',
      lastUsedAt: '2026-06-10T00:00:00Z',
    },
    {
      id: 'b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22',
      name: null,
      createdAt: '2026-06-02T00:00:00Z',
      lastUsedAt: null,
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-1. webauthnRegisterStart — POST /api/v1/auth/mfa/webauthn/register/start
// ─────────────────────────────────────────────────────────────────────────────
describe('webauthnRegisterStart', () => {
  it('T-WA-1a: 200 응답 → res.json()으로 옵션 객체를 반환한다 (B-2)', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/start', () =>
        HttpResponse.json(registrationOptionsFixture, { status: 200 }),
      ),
    )
    const result = await webauthnRegisterStart()
    expect(result).toEqual(registrationOptionsFixture)
    expect(typeof result).toBe('object')
  })

  it('T-WA-1b: POST 메서드를 사용한다', async () => {
    let capturedMethod: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/start', ({ request }) => {
        capturedMethod = request.method
        return HttpResponse.json(registrationOptionsFixture, { status: 200 })
      }),
    )
    await webauthnRegisterStart()
    expect(capturedMethod).toBe('POST')
  })

  it('T-WA-1c: X-XSRF-TOKEN 헤더가 요청에 포함된다 (apiFetch 경로)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/start', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(registrationOptionsFixture, { status: 200 })
      }),
    )
    await webauthnRegisterStart()
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-WA-1d: 400 응답 → ApiError(400) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/start', () =>
        HttpResponse.json({ error: 'invalid_registration' }, { status: 400 }),
      ),
    )
    let thrown: unknown
    try {
      await webauthnRegisterStart()
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-2. webauthnRegisterFinish — POST /api/v1/auth/mfa/webauthn/register/finish
// ─────────────────────────────────────────────────────────────────────────────
describe('webauthnRegisterFinish', () => {
  it('T-WA-2a: 201 No Content → void 반환 (Zod parse 없음, B-1)', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/finish', () =>
        new HttpResponse(null, { status: 201 }),
      ),
    )
    await expect(
      webauthnRegisterFinish(registrationCredentialFixture, 'YubiKey 5'),
    ).resolves.toBeUndefined()
  })

  it('T-WA-2b: X-XSRF-TOKEN 헤더가 요청에 포함된다 (apiFetch 경로)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/finish', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 201 })
      }),
    )
    await webauthnRegisterFinish(registrationCredentialFixture, 'YubiKey 5')
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-WA-2c: 요청 바디에 credential 객체와 name이 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/finish', async ({ request }) => {
        capturedBody = await request.json()
        return new HttpResponse(null, { status: 201 })
      }),
    )
    await webauthnRegisterFinish(registrationCredentialFixture, 'YubiKey 5')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.credential).toEqual(registrationCredentialFixture)
    expect(body?.name).toBe('YubiKey 5')
  })

  it('T-WA-2d: 409 already_registered → ApiError(409) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/finish', () =>
        HttpResponse.json({ error: 'already_registered' }, { status: 409 }),
      ),
    )
    let thrown: unknown
    try {
      await webauthnRegisterFinish(registrationCredentialFixture, 'YubiKey 5')
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-3. listWebauthnKeys — GET /api/v1/auth/mfa/webauthn/keys
// ─────────────────────────────────────────────────────────────────────────────
describe('listWebauthnKeys', () => {
  it('T-WA-3a: 200 응답 → WebauthnKeysResponse 반환 (Zod 파싱)', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/webauthn/keys', () =>
        HttpResponse.json(webauthnKeysFixture),
      ),
    )
    const result = await listWebauthnKeys()
    expect(result.keys).toHaveLength(2)
    expect(result.keys[0]?.name).toBe('YubiKey 5')
    expect(result.keys[1]?.name).toBeNull()
  })

  it('T-WA-3b: GET 메서드를 사용한다', async () => {
    let capturedMethod: string | null = null
    server.use(
      http.get('/api/v1/auth/mfa/webauthn/keys', ({ request }) => {
        capturedMethod = request.method
        return HttpResponse.json(webauthnKeysFixture)
      }),
    )
    await listWebauthnKeys()
    expect(capturedMethod).toBe('GET')
  })

  it('T-WA-3c: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (GET 읽기 요청)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.get('/api/v1/auth/mfa/webauthn/keys', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(webauthnKeysFixture)
      }),
    )
    await listWebauthnKeys()
    expect(capturedXsrf).toBeNull()
  })

  it('T-WA-3d: 401 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/webauthn/keys', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )
    let thrown: unknown
    try {
      await listWebauthnKeys()
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-4. deleteWebauthnKey — DELETE /api/v1/auth/mfa/webauthn/keys/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('deleteWebauthnKey', () => {
  const KEY_ID = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'

  it('T-WA-4a: 204 No Content → void 반환', async () => {
    server.use(
      http.delete(`/api/v1/auth/mfa/webauthn/keys/${KEY_ID}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(deleteWebauthnKey(KEY_ID)).resolves.toBeUndefined()
  })

  it('T-WA-4b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete(`/api/v1/auth/mfa/webauthn/keys/${KEY_ID}`, ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deleteWebauthnKey(KEY_ID)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-WA-4c: 올바른 경로로 DELETE 요청을 보낸다', async () => {
    let capturedPath: string | null = null
    server.use(
      http.delete(`/api/v1/auth/mfa/webauthn/keys/${KEY_ID}`, ({ request }) => {
        capturedPath = new URL(request.url).pathname
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deleteWebauthnKey(KEY_ID)
    expect(capturedPath).toBe(`/api/v1/auth/mfa/webauthn/keys/${KEY_ID}`)
  })

  it('T-WA-4d: 404 not_found → ApiError(404) throw', async () => {
    server.use(
      http.delete(`/api/v1/auth/mfa/webauthn/keys/${KEY_ID}`, () =>
        HttpResponse.json({ error: 'not_found' }, { status: 404 }),
      ),
    )
    let thrown: unknown
    try {
      await deleteWebauthnKey(KEY_ID)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-5. webauthnAuthenticateStart — POST /api/v1/auth/mfa/webauthn/authenticate/start
// ─────────────────────────────────────────────────────────────────────────────
describe('webauthnAuthenticateStart', () => {
  it('T-WA-5a: 200 응답 → res.json()으로 옵션 객체를 반환한다 (B-2)', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/authenticate/start', () =>
        HttpResponse.json(authenticationOptionsFixture, { status: 200 }),
      ),
    )
    const result = await webauthnAuthenticateStart('challenge.jwt.token')
    expect(result).toEqual(authenticationOptionsFixture)
    expect(typeof result).toBe('object')
  })

  it('T-WA-5b: POST 메서드를 사용한다', async () => {
    let capturedMethod: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/authenticate/start', ({ request }) => {
        capturedMethod = request.method
        return HttpResponse.json(authenticationOptionsFixture, { status: 200 })
      }),
    )
    await webauthnAuthenticateStart('challenge.jwt.token')
    expect(capturedMethod).toBe('POST')
  })

  it('T-WA-5c: 요청 바디에 mfa_challenge_token이 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/authenticate/start', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(authenticationOptionsFixture, { status: 200 })
      }),
    )
    await webauthnAuthenticateStart('challenge.jwt.token')
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.mfa_challenge_token).toBe('challenge.jwt.token')
  })

  it('T-WA-5d: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (raw fetch, CSRF 불요)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/authenticate/start', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(authenticationOptionsFixture, { status: 200 })
      }),
    )
    await webauthnAuthenticateStart('challenge.jwt.token')
    expect(capturedXsrf).toBeNull()
  })

  it('T-WA-5e: 401 받아도 /refresh를 호출하지 않는다 (raw fetch — NFR-2 회귀 가드)', async () => {
    let refreshCallCount = 0
    server.use(
      http.post('/api/v1/auth/mfa/webauthn/authenticate/start', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount++
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )
    let thrown: unknown
    try {
      await webauthnAuthenticateStart('challenge.jwt.token')
    } catch (e) {
      thrown = e
    }
    expect(refreshCallCount).toBe(0)
    expect(thrown).toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-6. verifyWebauthn — POST /api/v1/auth/mfa/verify
// ─────────────────────────────────────────────────────────────────────────────
describe('verifyWebauthn', () => {
  it('T-WA-6a: 200 응답 → TokenResponse 반환', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json(tokenResponseFixture, { status: 200 }),
      ),
    )
    const result = await verifyWebauthn('challenge.jwt.token', authenticationCredentialFixture)
    expect(result.access_token).toBe(tokenResponseFixture.access_token)
    expect(result.token_type).toBe('Bearer')
  })

  it('T-WA-6b: credentials:"include"가 설정된다 (B-3 — 세션 쿠키 수신 필수)', async () => {
    // raw fetch credentials:'include' 검증 — MSW는 credentials를 직접 캡처하지 않으므로
    // fetch를 spy해서 options를 확인한다
    const originalFetch = globalThis.fetch
    let capturedCredentials: string | undefined
    globalThis.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      capturedCredentials = init?.credentials as string | undefined
      return originalFetch(input, init)
    })
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json(tokenResponseFixture, { status: 200 }),
      ),
    )
    await verifyWebauthn('challenge.jwt.token', authenticationCredentialFixture)
    expect(capturedCredentials).toBe('include')
    globalThis.fetch = originalFetch
  })

  it('T-WA-6c: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (raw fetch, CSRF 불요)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyWebauthn('challenge.jwt.token', authenticationCredentialFixture)
    expect(capturedXsrf).toBeNull()
  })

  it('T-WA-6d: 요청 바디에 credential 객체가 포함된다 (B-4 — 이중 직렬화 금지)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/auth/mfa/verify', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(tokenResponseFixture, { status: 200 })
      }),
    )
    await verifyWebauthn('challenge.jwt.token', authenticationCredentialFixture)
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.mfa_challenge_token).toBe('challenge.jwt.token')
    expect(body?.code).toBe('')
    expect(body?.method).toBe('webauthn')
    // credential은 객체 그대로 (문자열이 아니어야 함 — 이중 직렬화 금지)
    expect(typeof body?.credential).toBe('object')
    expect(body?.credential).toEqual(authenticationCredentialFixture)
  })

  it('T-WA-6e: 401 invalid_code → ApiError(401) throw', async () => {
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
    )
    let thrown: unknown
    try {
      await verifyWebauthn('challenge.jwt.token', authenticationCredentialFixture)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
  })

  it('T-WA-6f: 401 받아도 /refresh를 호출하지 않는다 (raw fetch — NFR-2 회귀 가드, B-3)', async () => {
    let refreshCallCount = 0
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount++
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )
    let thrown: unknown
    try {
      await verifyWebauthn('challenge.jwt.token', authenticationCredentialFixture)
    } catch (e) {
      thrown = e
    }
    // refresh가 단 한 번도 호출되지 않아야 한다
    expect(refreshCallCount).toBe(0)
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-7. registerSecurityKey — 오케스트레이션 헬퍼
// ─────────────────────────────────────────────────────────────────────────────
describe('registerSecurityKey', () => {
  it('T-WA-7a: start → startRegistration → finish 순서로 오케스트레이션된다', async () => {
    const simplewebauthn = await import('@simplewebauthn/browser')
    vi.mocked(simplewebauthn.startRegistration).mockResolvedValue(
      registrationCredentialFixture as Awaited<ReturnType<typeof simplewebauthn.startRegistration>>,
    )

    server.use(
      http.post('/api/v1/auth/mfa/webauthn/register/start', () =>
        HttpResponse.json(registrationOptionsFixture, { status: 200 }),
      ),
      http.post('/api/v1/auth/mfa/webauthn/register/finish', () =>
        new HttpResponse(null, { status: 201 }),
      ),
    )

    await expect(registerSecurityKey('YubiKey 5')).resolves.toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WA-8. authenticateWithSecurityKey — 오케스트레이션 헬퍼
// ─────────────────────────────────────────────────────────────────────────────
describe('authenticateWithSecurityKey', () => {
  it('T-WA-8a: start → startAuthentication → verifyWebauthn 순서로 오케스트레이션되고 TokenResponse를 반환한다', async () => {
    const simplewebauthn = await import('@simplewebauthn/browser')
    vi.mocked(simplewebauthn.startAuthentication).mockResolvedValue(
      authenticationCredentialFixture as Awaited<ReturnType<typeof simplewebauthn.startAuthentication>>,
    )

    server.use(
      http.post('/api/v1/auth/mfa/webauthn/authenticate/start', () =>
        HttpResponse.json(authenticationOptionsFixture, { status: 200 }),
      ),
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json(tokenResponseFixture, { status: 200 }),
      ),
    )

    const result = await authenticateWithSecurityKey('challenge.jwt.token')
    expect(result.access_token).toBe(tokenResponseFixture.access_token)
    expect(result.token_type).toBe('Bearer')
  })
})
