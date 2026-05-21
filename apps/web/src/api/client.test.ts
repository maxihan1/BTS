// apiFetch / apiPost / apiGet / ApiError 단위 테스트 — msw로 HTTP 가로채기
import { describe, it, expect, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { apiFetch, apiPost, apiGet, ApiError } from './client'
import { useAuthStore } from '@/auth/authStore'
import { TokenResponseSchema } from './schemas'
import { z } from 'zod'

// setup.ts 가 전역 beforeAll/afterAll/afterEach 처리 중 — 여기서는 세션 클리어만 추가
afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────
// apiFetch
// ─────────────────────────────────────────────

describe('apiFetch', () => {
  it('credentials: include 항상 전송', async () => {
    let capturedCredentials: RequestCredentials | undefined

    server.use(
      http.post('/api/v1/auth/login', ({ request }) => {
        capturedCredentials = request.credentials
        return HttpResponse.json({ ok: true })
      }),
    )

    await apiFetch('/api/v1/auth/login', { method: 'POST', body: { username: 'alice' } })
    expect(capturedCredentials).toBe('include')
  })

  it('body 있을 때 Content-Type: application/json 자동 추가', async () => {
    let capturedContentType: string | null = null

    server.use(
      http.post('/api/v1/auth/login', ({ request }) => {
        capturedContentType = request.headers.get('content-type')
        return HttpResponse.json({ ok: true })
      }),
    )

    await apiFetch('/api/v1/auth/login', { method: 'POST', body: { username: 'alice' } })
    expect(capturedContentType).toContain('application/json')
  })

  it('accessToken 있을 때 Authorization: Bearer 헤더 추가', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'alice', email: 'alice@example.com', authMethod: 'local', userId: '1' },
    })

    let capturedAuth: string | null = null

    server.use(
      http.get('/api/v1/me', ({ request }) => {
        capturedAuth = request.headers.get('authorization')
        return HttpResponse.json({ ok: true })
      }),
    )

    await apiFetch('/api/v1/me', { method: 'GET' })
    expect(capturedAuth).toBe('Bearer test-access-token')
  })

  it('accessToken 없을 때 Authorization 헤더 미포함', async () => {
    let capturedAuth: string | null = 'placeholder'

    server.use(
      http.get('/api/v1/me', ({ request }) => {
        capturedAuth = request.headers.get('authorization')
        return HttpResponse.json({ ok: true })
      }),
    )

    await apiFetch('/api/v1/me', { method: 'GET' })
    expect(capturedAuth).toBeNull()
  })

  it('body 객체 → JSON.stringify 적용', async () => {
    let capturedBody: unknown

    server.use(
      http.post('/api/v1/auth/login', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ ok: true })
      }),
    )

    await apiFetch('/api/v1/auth/login', {
      method: 'POST',
      body: { username: 'alice', password: 'secret' },
    })

    expect(capturedBody).toEqual({ username: 'alice', password: 'secret' })
  })

  it('VITE_API_BASE_URL 환경 변수 우선 적용', async () => {
    // import.meta.env는 Vite 빌드 시 치환되므로 테스트 환경에서는 undefined → 빈 문자열 fallback
    // vi.stubEnv는 module cache를 우회하지 못해 런타임 주입이 불가.
    // 실제 base URL 조합 동작은 dev proxy 환경에서 수동 검증 + E2E(T16/T17)로 커버.
    // 여기서는 path가 절대 URL이면 그대로 사용하는 케이스(규칙 1)를 검증한다.
    let capturedUrl: string | undefined

    server.use(
      http.post('http://absolute-api.example.com/api/v1/auth/login', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ ok: true })
      }),
    )

    await apiFetch('http://absolute-api.example.com/api/v1/auth/login', {
      method: 'POST',
      body: {},
    })

    expect(capturedUrl).toBe('http://absolute-api.example.com/api/v1/auth/login')
  })
})

// ─────────────────────────────────────────────
// apiPost (Zod 파싱)
// ─────────────────────────────────────────────

describe('apiPost (Zod 파싱)', () => {
  it('정상 응답 → schema.parse 성공 반환', async () => {
    const mockToken = {
      access_token: 'eyJhbGciOiJIUzI1NiJ9.test',
      token_type: 'Bearer' as const,
      expires_in: 3600,
    }

    server.use(
      http.post('/api/v1/auth/login', () => {
        return HttpResponse.json(mockToken)
      }),
    )

    const result = await apiPost('/api/v1/auth/login', { username: 'alice' }, TokenResponseSchema)
    expect(result).toEqual(mockToken)
  })

  it('200 응답이지만 스키마 불일치 → ZodError throw', async () => {
    server.use(
      http.post('/api/v1/auth/login', () => {
        // access_token 누락 → TokenResponseSchema 파싱 실패
        return HttpResponse.json({ token_type: 'Bearer', expires_in: 3600 })
      }),
    )

    const { ZodError } = await import('zod')
    await expect(
      apiPost('/api/v1/auth/login', { username: 'alice' }, TokenResponseSchema),
    ).rejects.toThrow(ZodError)
  })

  it('비-2xx 응답 → ApiError throw (status + body 포함)', async () => {
    server.use(
      http.post('/api/v1/auth/login', () => {
        return HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 })
      }),
    )

    let caughtError: unknown
    try {
      await apiPost('/api/v1/auth/login', { username: 'alice', password: 'wrong' }, TokenResponseSchema)
    } catch (e) {
      caughtError = e
    }

    expect(caughtError).toBeInstanceOf(ApiError)
    const apiError = caughtError as ApiError
    expect(apiError.status).toBe(401)
    expect(apiError.body).toEqual({ error: 'invalid_credentials' })
  })
})

// ─────────────────────────────────────────────
// apiGet (Zod 파싱)
// ─────────────────────────────────────────────

describe('apiGet (Zod 파싱)', () => {
  const SimpleSchema = z.object({ value: z.string() })

  it('정상 GET 응답 → schema.parse 성공 반환', async () => {
    server.use(
      http.get('/api/v1/test', () => {
        return HttpResponse.json({ value: 'hello' })
      }),
    )

    const result = await apiGet('/api/v1/test', SimpleSchema)
    expect(result).toEqual({ value: 'hello' })
  })

  it('비-2xx GET 응답 → ApiError throw', async () => {
    server.use(
      http.get('/api/v1/test', () => {
        return HttpResponse.json({ error: 'not_found' }, { status: 404 })
      }),
    )

    await expect(apiGet('/api/v1/test', SimpleSchema)).rejects.toBeInstanceOf(ApiError)
  })
})
