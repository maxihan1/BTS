// identity-access 사용자 상태 메시지 API client 단위 테스트 — MSW + Zod 파싱 검증 (FR-PR-02 Task 6)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { statusResponseSchema, WhoamiResponseSchema } from './schemas'
import { fetchStatus, updateStatus } from './status'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — PATCH X-XSRF-TOKEN 검증용 (profile.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — StatusResponse (백엔드 StatusResponse DTO 1:1)
// ─────────────────────────────────────────────────────────────────────────────
const STATUS_FIXTURE_ACTIVE = {
  emoji: '🌴',
  text: '휴가 중',
  expiresAt: '2026-07-12T15:00:00Z',
}

const STATUS_FIXTURE_EMPTY = {
  emoji: null,
  text: null,
  expiresAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-ST-S. statusResponseSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('statusResponseSchema', () => {
  it('T-ST-S-1: 활성 상태(이모지+텍스트+만료)를 파싱한다', () => {
    const result = statusResponseSchema.parse(STATUS_FIXTURE_ACTIVE)
    expect(result.emoji).toBe('🌴')
    expect(result.text).toBe('휴가 중')
    expect(result.expiresAt).toBe('2026-07-12T15:00:00Z')
  })

  it('T-ST-S-2: emoji/text/expiresAt이 모두 null이어도 파싱 성공한다(미설정/해제)', () => {
    const result = statusResponseSchema.parse(STATUS_FIXTURE_EMPTY)
    expect(result.emoji).toBeNull()
    expect(result.text).toBeNull()
    expect(result.expiresAt).toBeNull()
  })

  it('T-ST-S-3: emoji가 문자열이 아니면 ZodError를 throw한다', () => {
    expect(() => statusResponseSchema.parse({ ...STATUS_FIXTURE_ACTIVE, emoji: 1 })).toThrow()
  })

  it('T-ST-S-4: text 필드 누락 시 ZodError를 throw한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { text: _text, ...without } = STATUS_FIXTURE_ACTIVE
    expect(() => statusResponseSchema.parse(without)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-ST-W. WhoamiResponseSchema — statusEmoji/statusText 확장 (FR-PR-02, mock fanout 방어)
// ─────────────────────────────────────────────────────────────────────────────
describe('WhoamiResponseSchema — statusEmoji/statusText 확장 (FR-PR-02)', () => {
  const BASE_WHOAMI = {
    username: 'alice',
    email: 'alice@example.com',
    authMethod: 'jwt',
    userId: 'usr-0001',
    mustChangePassword: false,
    isSystemAdmin: false,
    mfaEnrollmentRequired: false,
  }

  it('T-ST-W-1: statusEmoji/statusText 값이 있으면 파싱 성공하고 값을 그대로 노출한다', () => {
    const result = WhoamiResponseSchema.parse({
      ...BASE_WHOAMI,
      statusEmoji: '🌴',
      statusText: '휴가 중',
    })
    expect(result.statusEmoji).toBe('🌴')
    expect(result.statusText).toBe('휴가 중')
  })

  it('T-ST-W-2: statusEmoji/statusText가 null이어도 파싱 성공한다(미설정/만료/PAT)', () => {
    const result = WhoamiResponseSchema.parse({
      ...BASE_WHOAMI,
      statusEmoji: null,
      statusText: null,
    })
    expect(result.statusEmoji).toBeNull()
    expect(result.statusText).toBeNull()
  })

  it('T-ST-W-3: statusEmoji/statusText 키가 없어도 파싱 성공한다(기존 whoami mock fanout 회귀 방지)', () => {
    const result = WhoamiResponseSchema.parse(BASE_WHOAMI)
    expect(result.statusEmoji).toBeUndefined()
    expect(result.statusText).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-ST-1. fetchStatus — GET /api/v1/users/me/status
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchStatus', () => {
  it('T-ST-1-1: 200 응답을 StatusResponse로 파싱해 반환한다', async () => {
    server.use(http.get('/api/v1/users/me/status', () => HttpResponse.json(STATUS_FIXTURE_ACTIVE)))
    const result = await fetchStatus()
    expect(result.emoji).toBe('🌴')
    expect(result.text).toBe('휴가 중')
    expect(result.expiresAt).toBe('2026-07-12T15:00:00Z')
  })

  it('T-ST-1-2: 미설정(all-null) 응답도 파싱해 반환한다(EC1)', async () => {
    server.use(http.get('/api/v1/users/me/status', () => HttpResponse.json(STATUS_FIXTURE_EMPTY)))
    const result = await fetchStatus()
    expect(result.emoji).toBeNull()
    expect(result.text).toBeNull()
    expect(result.expiresAt).toBeNull()
  })

  it('T-ST-1-3: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/users/me/status', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(fetchStatus()).rejects.toBeInstanceOf(ApiError)
    await expect(fetchStatus()).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-ST-2. updateStatus — PATCH /api/v1/users/me/status (원자적 교체)
// ─────────────────────────────────────────────────────────────────────────────
describe('updateStatus', () => {
  it('T-ST-2-1: PATCH 후 갱신된 StatusResponse를 반환한다', async () => {
    server.use(http.patch('/api/v1/users/me/status', () => HttpResponse.json(STATUS_FIXTURE_ACTIVE)))
    const result = await updateStatus({
      emoji: '🌴',
      text: '휴가 중',
      expiresAt: '2026-07-12T15:00:00Z',
    })
    expect(result.emoji).toBe('🌴')
    expect(result.text).toBe('휴가 중')
  })

  it('T-ST-2-2: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/users/me/status', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(STATUS_FIXTURE_ACTIVE)
      }),
    )
    await updateStatus({ emoji: '🌴' })
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-ST-2-3: 해제(둘 다 빈값) 요청 바디가 그대로 실리고 응답은 all-null로 파싱된다(S5)', async () => {
    let capturedBody: unknown
    server.use(
      http.patch('/api/v1/users/me/status', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(STATUS_FIXTURE_EMPTY)
      }),
    )
    const result = await updateStatus({ emoji: '', text: '' })
    expect(capturedBody).toEqual({ emoji: '', text: '' })
    expect(result.emoji).toBeNull()
    expect(result.text).toBeNull()
  })

  it('T-ST-2-4: 400 STATUS_VALIDATION_FAILED → ApiError(400) throw', async () => {
    server.use(
      http.patch('/api/v1/users/me/status', () =>
        HttpResponse.json(
          { code: 'STATUS_VALIDATION_FAILED', message: '상태 텍스트가 너무 깁니다.' },
          { status: 400 },
        ),
      ),
    )
    let thrown: unknown
    try {
      await updateStatus({ text: 'x'.repeat(101) })
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(400)
    expect((thrown.body as { code?: string } | null)?.code).toBe('STATUS_VALIDATION_FAILED')
  })
})
