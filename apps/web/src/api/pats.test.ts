// pats API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  PAT_SCOPE_CATALOG,
  PatSchema,
  PatIssuedSchema,
  createPat,
  fetchPats,
  revokePat,
} from './pats'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — CSRF 헤더 검증용 (sessions.test.ts/trusted-devices.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-pat-xsrf-token; path=/'
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — 백엔드 PatDtos.kt 응답 필드와 1:1 (id/name/scopes/expiresAt/lastUsedAt/createdAt[/token])
// UUID는 Zod v4 RFC4122 엄격검증 대상이므로 v4 형식(3번째 그룹 4x, 4번째 그룹 8/9/a/b)으로 고정
// ─────────────────────────────────────────────────────────────────────────────
const patFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  name: 'CI deploy token',
  scopes: ['read:issues', 'write:issues'],
  expiresAt: '2026-08-01T10:00:00Z',
  lastUsedAt: '2026-07-01T09:00:00Z',
  createdAt: '2026-07-02T08:00:00Z',
}

/** 레거시 무기한 PAT — expiresAt/lastUsedAt null (V006 기존 행 호환) */
const patFixtureLegacyNullable = {
  id: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
  name: 'legacy unlimited token',
  scopes: ['*'],
  expiresAt: null,
  lastUsedAt: null,
  createdAt: '2026-01-10T07:00:00Z',
}

const issuedFixture = {
  id: 'c3d4e5f6-a7b8-4012-9def-012345678912',
  name: 'new token',
  scopes: ['read:projects'],
  token: 'pat_' + 'a'.repeat(48),
  expiresAt: '2026-10-02T08:00:00Z',
  createdAt: '2026-07-02T08:00:00Z',
}

beforeEach(() => {
  server.use(
    // POST /api/v1/users/me/pats — 201 발급
    http.post('/api/v1/users/me/pats', async ({ request }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      if (xsrfHeader === null || xsrfHeader === '') {
        return HttpResponse.json({ error: 'session_management_requires_interactive_login' }, { status: 403 })
      }
      const body = (await request.json()) as { name?: string; scopes?: string[]; expiresInDays?: number }
      if (body.name === '') {
        return HttpResponse.json({ error: 'invalid_name' }, { status: 400 })
      }
      if (Array.isArray(body.scopes) && body.scopes.includes('unknown:scope')) {
        return HttpResponse.json({ error: 'invalid_scope' }, { status: 400 })
      }
      if (body.expiresInDays === 9999) {
        return HttpResponse.json({ error: 'invalid_expiry' }, { status: 400 })
      }
      if (body.name === 'quota-exceeded-name') {
        return HttpResponse.json({ error: 'quota_exceeded' }, { status: 403 })
      }
      return HttpResponse.json(issuedFixture, { status: 201 })
    }),

    // GET /api/v1/users/me/pats — 200 { pats: [...] }
    http.get('/api/v1/users/me/pats', () => {
      return HttpResponse.json({ pats: [patFixture, patFixtureLegacyNullable] })
    }),

    // DELETE /api/v1/users/me/pats/:id — 204
    http.delete('/api/v1/users/me/pats/:id', ({ request, params }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      if (xsrfHeader === null || xsrfHeader === '') {
        return HttpResponse.json({ error: 'session_management_requires_interactive_login' }, { status: 403 })
      }
      const id = params['id']
      if (id === 'not-found-id') {
        return HttpResponse.json({ error: 'not_found' }, { status: 404 })
      }
      return new HttpResponse(null, { status: 204 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T0. PAT_SCOPE_CATALOG — 백엔드 PatScopeCatalog.SUPPORTED 5종 미러
// ─────────────────────────────────────────────────────────────────────────────
describe('PAT_SCOPE_CATALOG', () => {
  it('T0-a: 백엔드 화이트리스트 5종을 정확히 미러한다', () => {
    expect(PAT_SCOPE_CATALOG).toEqual(['read:issues', 'write:issues', 'read:projects', 'write:projects', '*'])
  })

  it('T0-b: 정확히 5개다', () => {
    expect(PAT_SCOPE_CATALOG).toHaveLength(5)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1. PatSchema — 목록 요약 항목 파싱 (token 필드 없음)
// ─────────────────────────────────────────────────────────────────────────────
describe('PatSchema', () => {
  it('T1-a: 6 필드가 모두 있는 PAT를 파싱한다', () => {
    const result = PatSchema.parse(patFixture)

    expect(result.id).toBe(patFixture.id)
    expect(result.name).toBe('CI deploy token')
    expect(result.scopes).toEqual(['read:issues', 'write:issues'])
    expect(result.expiresAt).toBe('2026-08-01T10:00:00Z')
    expect(result.lastUsedAt).toBe('2026-07-01T09:00:00Z')
    expect(result.createdAt).toBe('2026-07-02T08:00:00Z')
  })

  it('T1-b: 레거시 무기한 PAT — expiresAt/lastUsedAt이 null이어도 파싱 성공', () => {
    const result = PatSchema.parse(patFixtureLegacyNullable)

    expect(result.expiresAt).toBeNull()
    expect(result.lastUsedAt).toBeNull()
    expect(result.scopes).toEqual(['*'])
  })

  it('T1-c: 필수 필드(id) 누락 시 ZodError throw', () => {
    expect(() =>
      PatSchema.parse({
        name: 'x',
        scopes: ['read:issues'],
        expiresAt: null,
        lastUsedAt: null,
        createdAt: '2026-01-01T00:00:00Z',
      }),
    ).toThrow()
  })

  it('T1-d: id가 UUID 형식이 아니면 ZodError throw', () => {
    expect(() => PatSchema.parse({ ...patFixture, id: 'not-a-uuid' })).toThrow()
  })

  it('T1-e: token/token_hash/userId 필드는 스키마에 없어 응답에 포함돼도 무시된다', () => {
    const withSecret = { ...patFixture, token: 'pat_leak', tokenHash: 'hash', userId: 'u1' }
    const result = PatSchema.parse(withSecret)
    expect('token' in result).toBe(false)
    expect('tokenHash' in result).toBe(false)
    expect('userId' in result).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2. PatIssuedSchema — 발급 응답 파싱 (token 포함, 1회 노출)
// ─────────────────────────────────────────────────────────────────────────────
describe('PatIssuedSchema', () => {
  it('T2-a: token 포함 7 필드를 파싱한다', () => {
    const result = PatIssuedSchema.parse(issuedFixture)

    expect(result.id).toBe(issuedFixture.id)
    expect(result.token).toBe(issuedFixture.token)
    expect(result.token.startsWith('pat_')).toBe(true)
    expect(result.expiresAt).toBe(issuedFixture.expiresAt)
  })

  it('T2-b: expiresAt이 null인 레거시 발급 응답도 파싱 성공', () => {
    const result = PatIssuedSchema.parse({ ...issuedFixture, expiresAt: null })
    expect(result.expiresAt).toBeNull()
  })

  it('T2-c: 필수 필드(token) 누락 시 ZodError throw', () => {
    const { token, ...withoutToken } = issuedFixture
    void token
    expect(() => PatIssuedSchema.parse(withoutToken)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3. createPat — POST /api/v1/users/me/pats
// ─────────────────────────────────────────────────────────────────────────────
describe('createPat', () => {
  it('T3-a: 유효한 요청으로 201 발급 응답을 PatIssued로 반환한다', async () => {
    const result = await createPat({ name: 'new token', scopes: ['read:projects'], expiresInDays: 90 })

    expect(result.id).toBe(issuedFixture.id)
    expect(result.token).toBe(issuedFixture.token)
    expect(result.scopes).toEqual(['read:projects'])
  })

  it('T3-b: 요청 바디에 name/scopes/expiresInDays를 그대로 전송한다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/users/me/pats', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(issuedFixture, { status: 201 })
      }),
    )
    await createPat({ name: 'ci token', scopes: ['read:issues', 'write:issues'], expiresInDays: 30 })

    expect(capturedBody).toEqual({ name: 'ci token', scopes: ['read:issues', 'write:issues'], expiresInDays: 30 })
  })

  it('T3-c: POST 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/users/me/pats', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(issuedFixture, { status: 201 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=create-csrf-token; path=/'
    await createPat({ name: 'x', scopes: ['read:issues'], expiresInDays: 30 })
    expect(capturedXsrf).toBe('create-csrf-token')
  })

  it('T3-d: 서버 400 invalid_name 시 ApiError(400)를 throw한다', async () => {
    await expect(createPat({ name: '', scopes: ['read:issues'], expiresInDays: 30 })).rejects.toThrow(ApiError)
  })

  it('T3-e: 서버 400 invalid_scope 시 ApiError(400)를 throw한다', async () => {
    await expect(
      createPat({ name: 'x', scopes: ['unknown:scope' as never], expiresInDays: 30 }),
    ).rejects.toThrow(ApiError)
  })

  it('T3-f: 서버 400 invalid_expiry 시 ApiError(400)를 throw한다', async () => {
    await expect(createPat({ name: 'x', scopes: ['read:issues'], expiresInDays: 9999 })).rejects.toThrow(ApiError)
  })

  it('T3-g: 서버 403 quota_exceeded 시 ApiError(403)를 throw한다', async () => {
    await expect(
      createPat({ name: 'quota-exceeded-name', scopes: ['read:issues'], expiresInDays: 30 }),
    ).rejects.toThrow(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4. fetchPats — GET /api/v1/users/me/pats
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchPats', () => {
  it('T4-a: PAT 목록을 Pat[] 형태로 반환한다 (token 필드 없음)', async () => {
    const result = await fetchPats()

    expect(result).toHaveLength(2)
    expect(result[0]?.id).toBe(patFixture.id)
    expect(result[0] !== undefined && 'token' in result[0]).toBe(false)
    expect(result[1]?.expiresAt).toBeNull()
  })

  it('T4-b: 래퍼 { pats: [...] }를 언래핑해 배열만 반환한다', async () => {
    server.use(
      http.get('/api/v1/users/me/pats', () => {
        return HttpResponse.json({ pats: [patFixture] })
      }),
    )
    const result = await fetchPats()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(1)
  })

  it('T4-c: 발급 이력이 없으면 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/users/me/pats', () => {
        return HttpResponse.json({ pats: [] })
      }),
    )
    const result = await fetchPats()
    expect(result).toHaveLength(0)
  })

  it('T4-d: 서버 401 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/users/me/pats', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    await expect(fetchPats()).rejects.toThrow(ApiError)
  })

  it('T4-e: 서버 403 PAT 인증 호출 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/users/me/pats', () => {
        return HttpResponse.json({ error: 'session_management_requires_interactive_login' }, { status: 403 })
      }),
    )
    await expect(fetchPats()).rejects.toThrow(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5. revokePat — DELETE /api/v1/users/me/pats/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('revokePat', () => {
  it('T5-a: 유효한 id로 DELETE 호출 시 204로 완료된다', async () => {
    await expect(revokePat(patFixture.id)).resolves.toBeUndefined()
  })

  it('T5-b: DELETE 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/users/me/pats/:id', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=revoke-csrf-token; path=/'
    await revokePat(patFixture.id)
    expect(capturedXsrf).toBe('revoke-csrf-token')
  })

  it('T5-c: 서버 404 not_found(IDOR/미존재) 시 ApiError(404)를 throw한다', async () => {
    await expect(revokePat('not-found-id')).rejects.toThrow(ApiError)
  })

  it('T5-d: 서버 403 PAT 인증 호출 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/users/me/pats/:id', () => {
        return HttpResponse.json({ error: 'session_management_requires_interactive_login' }, { status: 403 })
      }),
    )
    await expect(revokePat(patFixture.id)).rejects.toThrow(ApiError)
  })
})
