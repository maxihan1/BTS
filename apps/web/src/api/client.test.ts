// apiFetch / apiPost / apiGet / ApiError 단위 테스트 — msw로 HTTP 가로채기 + FormData 분기 검증
import { describe, it, expect, afterEach, vi } from 'vitest'
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
      user: { username: 'alice', email: 'alice@example.com', authMethod: 'local', userId: '1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
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
    // 401 은 T9 인터셉터가 /refresh 자동 호출 흐름으로 잡으므로 500 으로 검증.
    // 401 의 인터셉터 동작은 interceptor.test.ts 가 별도로 검증.
    server.use(
      http.post('/api/v1/auth/login', () => {
        return HttpResponse.json({ error: 'server_error' }, { status: 500 })
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
    expect(apiError.status).toBe(500)
    expect(apiError.body).toEqual({ error: 'server_error' })
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

// ─────────────────────────────────────────────
// Task-1: apiFetch FormData body 분기
// ─────────────────────────────────────────────

describe('apiFetch — FormData body 분기 (Task-1)', () => {
  it('T1-A: body가 FormData면 Content-Type 헤더를 자동으로 application/json으로 설정하지 않는다', async () => {
    let capturedContentType: string | null = 'placeholder'

    server.use(
      http.post('/api/v1/test-formdata', ({ request }) => {
        capturedContentType = request.headers.get('content-type')
        return HttpResponse.json({ ok: true }, { status: 200 })
      }),
    )

    const formData = new FormData()
    formData.append('file', new Blob(['hello'], { type: 'text/plain' }), 'hello.txt')

    await apiFetch('/api/v1/test-formdata', { method: 'POST', body: formData })

    // 브라우저가 'multipart/form-data; boundary=...' 를 자동 설정하도록 위임해야 한다.
    // application/json 이 들어가면 안 된다.
    expect(capturedContentType).not.toContain('application/json')
  })

  it('T1-B: body가 FormData면 JSON.stringify 없이 FormData 원본 그대로 전달된다', async () => {
    let receivedMetaValue: FormDataEntryValue | null = null

    server.use(
      http.post('/api/v1/test-formdata-raw', async ({ request }) => {
        const fd = await request.formData()
        // msw formData() 반환 타입이 환경에 따라 다를 수 있으므로 즉시 get으로 추출
        receivedMetaValue = fd.get('meta')
        return HttpResponse.json({ ok: true }, { status: 200 })
      }),
    )

    const formData = new FormData()
    formData.append('file', new Blob(['world'], { type: 'application/octet-stream' }), 'world.bin')
    formData.append('meta', 'test-value')

    await apiFetch('/api/v1/test-formdata-raw', { method: 'POST', body: formData })

    // FormData 원본이 그대로 전달됐다면 'meta' 필드를 파싱할 수 있다
    expect(receivedMetaValue).toBe('test-value')
  })

  it('T1-C: body가 일반 객체(JSON)면 Content-Type: application/json 자동 설정 (기존 동작 불변)', async () => {
    let capturedContentType: string | null = null

    server.use(
      http.post('/api/v1/test-json-body', ({ request }) => {
        capturedContentType = request.headers.get('content-type')
        return HttpResponse.json({ ok: true }, { status: 200 })
      }),
    )

    await apiFetch('/api/v1/test-json-body', { method: 'POST', body: { summary: 'hello' } })

    expect(capturedContentType).toContain('application/json')
  })

  it('T1-D: FormData body 전송 시 Authorization 헤더는 정상 포함된다 (401 refresh 인프라 불변)', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'fd-test-token',
      user: { username: 'alice', email: 'alice@example.com', authMethod: 'local', userId: '1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })

    let capturedAuth: string | null = null

    server.use(
      http.post('/api/v1/test-formdata-auth', ({ request }) => {
        capturedAuth = request.headers.get('authorization')
        return HttpResponse.json({ ok: true }, { status: 200 })
      }),
    )

    const formData = new FormData()
    formData.append('file', new Blob(['auth'], { type: 'text/plain' }), 'auth.txt')

    await apiFetch('/api/v1/test-formdata-auth', { method: 'POST', body: formData })

    expect(capturedAuth).toBe('Bearer fd-test-token')
  })

  it(
    'T1-E: 401 → refresh → retry 시 FormData body가 그대로 전송된다 (ReadableStream으로 교체 시 회귀 가드)',
    async () => {
      // 초기 토큰을 stale-token으로 세팅
      useAuthStore.getState().setSession({
        accessToken: 'stale-token',
        user: { username: 'alice', email: 'alice@example.com', authMethod: 'local', userId: '1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
      })

      // 수집 배열: 1차(401) 요청, 2차(retry) 요청의 Content-Type 및 formData
      const capturedRequests: Array<{ auth: string | null; contentType: string | null; metaValue: string | null }> = []

      server.use(
        // /refresh 엔드포인트 — 새 토큰 반환
        http.post('/api/v1/auth/refresh', () =>
          HttpResponse.json({ access_token: 'fresh-token' }),
        ),
        http.post('/api/v1/test-formdata-retry', async ({ request }) => {
          const auth = request.headers.get('authorization')
          const contentType = request.headers.get('content-type')
          let metaValue: string | null = null
          try {
            // FormData 파싱 시도 (multipart면 성공, JSON이면 실패)
            const fd = await request.formData()
            metaValue = fd.get('meta') as string | null
          } catch {
            metaValue = null
          }
          capturedRequests.push({ auth, contentType, metaValue })

          // 첫 번째 요청만 401 반환, 이후는 200
          if (capturedRequests.length === 1) {
            return new HttpResponse(null, { status: 401 })
          }
          return HttpResponse.json({ ok: true }, { status: 200 })
        }),
      )

      const formData = new FormData()
      formData.append('file', new Blob(['data'], { type: 'application/octet-stream' }), 'data.bin')
      formData.append('meta', 'retry-guard-value')

      await apiFetch('/api/v1/test-formdata-retry', { method: 'POST', body: formData })

      // 요청이 정확히 2회 발생해야 한다 (1차 401 + retry)
      expect(capturedRequests).toHaveLength(2)

      // retry(2차) 요청 검증
      const retryRequest = capturedRequests[1]
      expect(retryRequest).toBeDefined()

      // FormData가 그대로 전송됐다면 meta 필드를 파싱할 수 있어야 한다
      expect(retryRequest!.metaValue).toBe('retry-guard-value')

      // Content-Type은 application/json이 아니어야 한다 (FormData → multipart/form-data)
      expect(retryRequest!.contentType).not.toContain('application/json')

      // refresh 후 새 토큰으로 Authorization이 갱신되어야 한다
      expect(retryRequest!.auth).toBe('Bearer fresh-token')
    },
  )
})

// ─────────────────────────────────────────────
// Task-1: apiFetch 문자열 body pass-through (FR-AT-06 D6 FR11 · NFR5)
// ─────────────────────────────────────────────

describe('apiFetch — 문자열 body pass-through (Task-1)', () => {
  afterEach(() => {
    // globalThis.fetch를 직접 spy했으므로 다음 테스트가 msw로 되돌아가도록 반드시 복원한다
    vi.restoreAllMocks()
  })

  it('문자열 body는 JSON.stringify 없이 원문 그대로 전송한다', async () => {
    const yaml = 'version: 1\nprojectKey: PROJ\nrules: []\n'
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
    await apiFetch('/api/v1/x', { method: 'POST', body: yaml, headers: { 'Content-Type': 'application/yaml' } })
    const init = fetchSpy.mock.calls[0]?.[1]
    expect(init?.body).toBe(yaml) // JSON.stringify 였다면 따옴표로 감싸였을 것
  })

  it('문자열 body에 Content-Type: application/json 을 자동 부여하지 않는다', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
    await apiFetch('/api/v1/x', { method: 'POST', body: 'plain text' })
    const headers = new Headers(fetchSpy.mock.calls[0]?.[1]?.headers)
    expect(headers.get('content-type')).toBeNull()
  })

  it('객체 body는 기존대로 JSON.stringify + application/json 을 유지한다 (회귀 가드)', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
    await apiFetch('/api/v1/x', { method: 'POST', body: { a: 1 } })
    const init = fetchSpy.mock.calls[0]?.[1]
    expect(init?.body).toBe('{"a":1}')
    expect(new Headers(init?.headers).get('content-type')).toBe('application/json')
  })
})

// ─────────────────────────────────────────────
// 401 재시도와 취소 신호 (`ApiFetchOptions.signal`)
//
// `signal` KDoc 이 「401 재시도에도 같은 signal 이 실린다」를 주장하는데 이 파일에 그 주장을
// 재는 단언이 하나도 없었다. `lib/delete-timeout.test.ts` 의 사슬 테스트도 stub fetch 가 끝내
// 응답하지 않아 **401 분기에 진입조차 하지 않는다** — 그래서 여기서 잰다.
// ─────────────────────────────────────────────

describe('apiFetch — 401 재시도와 취소 신호', () => {
  const BOARD_PATH = '/api/v1/boards/e1f2a3b4-c5d6-4789-abcd-ef0123456789'

  afterEach(() => {
    // globalThis.fetch를 직접 spy했으므로 다음 테스트가 msw로 되돌아가도록 반드시 복원한다
    vi.restoreAllMocks()
  })

  it('T-SIG-1: 401 → refresh 200 → 재시도 fetch 에 첫 요청과 같은 signal 인스턴스가 실린다', async () => {
    const controller = new AbortController()
    const signals: (AbortSignal | null | undefined)[] = []

    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
      signals.push(init?.signal)
      // 1회차 = DELETE(401) · 2회차 = /auth/refresh(200) · 3회차 = 재시도 DELETE(204)
      if (signals.length === 1) return Promise.resolve(new Response(null, { status: 401 }))
      if (signals.length === 2) return Promise.resolve(new Response(JSON.stringify({ access_token: 'fresh-token' })))
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    await apiFetch(BOARD_PATH, { method: 'DELETE', signal: controller.signal })

    expect(fetchSpy).toHaveBeenCalledTimes(3)
    // 재시도에는 **같은 인스턴스**가 실려야 한다. 여기서 새 AbortController 를 만들면
    // 상한(`lib/delete-timeout.ts`)이 재시도 요청을 끊지 못해 삭제가 서버에서 계속 산다.
    expect(signals[0]).toBe(controller.signal)
    expect(signals[2]).toBe(controller.signal)
    // 그 사이의 refresh 에는 signal 이 **없다.** `refreshPromise` 는 여러 요청이 공유하는 전역
    // lock 이라 한 요청의 abort 가 다른 요청들의 refresh 까지 죽이기 때문이다(의도된 설계).
    // 그래서 이 대기 구간은 abort 를 관측하지 못하고, 상한이 abort 와 별개로 거절을 낸다.
    expect(signals[1]).toBeUndefined()
  })

  it('T-SIG-2: 재시도 진입 전에 abort 되면 재시도가 AbortError 로 거절된다', async () => {
    const controller = new AbortController()
    let deleteRequests = 0

    server.use(
      http.delete(BOARD_PATH, () => {
        deleteRequests += 1
        return new HttpResponse(null, { status: 401 })
      }),
      http.post('/api/v1/auth/refresh', () => {
        // refresh 를 기다리는 사이에 상한이 끊은 상황을 만든다 — 재시도는 이미 abort 된
        // signal 로 들어간다.
        controller.abort()
        return HttpResponse.json({ access_token: 'fresh-token' })
      }),
    )

    let caught: unknown
    try {
      await apiFetch(BOARD_PATH, { method: 'DELETE', signal: controller.signal })
    } catch (error) {
      caught = error
    }

    // 재시도가 서버에 닿지 않았다 — signal 이 실렸다는 증거는 「요청이 안 갔다」쪽이 더 세다.
    expect(deleteRequests).toBe(1)
    expect((caught as Error | undefined)?.name).toBe('AbortError')
  })
})
