// sessions API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { sessionSchema, listSessions, revokeSession } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — spec §FR-2 정의 7 필드: sid/providerId/userAgent/ipAddress/lastSeenAt/createdAt/current
// ─────────────────────────────────────────────────────────────────────────────
const sessionFixture = {
  sid: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  providerId: 'local',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
  ipAddress: '10.0.0.5',
  lastSeenAt: '2026-05-29T10:00:00Z',
  createdAt: '2026-05-20T09:00:00Z',
  current: true,
}

const sessionFixtureOther = {
  sid: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
  providerId: 'local',
  userAgent: null,
  ipAddress: null,
  lastSeenAt: '2026-05-28T08:00:00Z',
  createdAt: '2026-05-10T07:00:00Z',
  current: false,
}

beforeEach(() => {
  server.use(
    // GET /api/v1/auth/sessions — 200 { sessions: [...] }
    http.get('/api/v1/auth/sessions', () => {
      return HttpResponse.json({ sessions: [sessionFixture, sessionFixtureOther] })
    }),

    // DELETE /api/v1/auth/sessions/{sid} — 204 No Content
    http.delete('/api/v1/auth/sessions/:sid', ({ request, params }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      const sid = params['sid']
      if (sid === 'bad-sid-no-xsrf' || xsrfHeader === null || xsrfHeader === '') {
        return new HttpResponse(null, { status: 403 })
      }
      return new HttpResponse(null, { status: 204 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-1. sessionSchema — spec §FR-2 7 필드 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('sessionSchema', () => {
  it('T4-1a: 7 필드가 모두 있는 세션을 파싱한다', () => {
    const result = sessionSchema.parse(sessionFixture)

    expect(result.sid).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.providerId).toBe('local')
    expect(result.userAgent).toBe('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')
    expect(result.ipAddress).toBe('10.0.0.5')
    expect(result.lastSeenAt).toBe('2026-05-29T10:00:00Z')
    expect(result.createdAt).toBe('2026-05-20T09:00:00Z')
    expect(result.current).toBe(true)
  })

  it('T4-1b: userAgent/ipAddress가 null인 세션도 파싱 성공 (EC-6)', () => {
    const result = sessionSchema.parse(sessionFixtureOther)

    expect(result.userAgent).toBeNull()
    expect(result.ipAddress).toBeNull()
    expect(result.current).toBe(false)
  })

  it('T4-1c: 필수 필드(sid) 누락 시 ZodError throw', () => {
    expect(() => sessionSchema.parse({ providerId: 'local', current: false })).toThrow()
  })

  it('T4-1d: deviceFingerprint 필드가 스키마에 없음 — spec NFR-2 (추가 필드는 strip됨)', () => {
    const withFingerprint = { ...sessionFixture, deviceFingerprint: 'fp-abc123' }
    const result = sessionSchema.parse(withFingerprint)
    // Zod strip 모드에서 추가 필드는 무시됨 — 타입에 존재하지 않아야 함
    expect('deviceFingerprint' in result).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-2. listSessions — GET /api/v1/auth/sessions → Session[]
// ─────────────────────────────────────────────────────────────────────────────
describe('listSessions', () => {
  it('T4-2a: 세션 목록을 Session[] 형태로 반환한다', async () => {
    const result = await listSessions()

    expect(result).toHaveLength(2)
    expect(result[0]?.sid).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result[0]?.current).toBe(true)
    expect(result[1]?.sid).toBe('b2c3d4e5-f6a7-5901-bcde-f01234567891')
    expect(result[1]?.current).toBe(false)
  })

  it('T4-2b: 각 항목이 sessionSchema를 통과한다', async () => {
    const result = await listSessions()

    for (const session of result) {
      expect(typeof session.sid).toBe('string')
      expect(typeof session.providerId).toBe('string')
      expect(typeof session.current).toBe('boolean')
      expect(typeof session.lastSeenAt).toBe('string')
      expect(typeof session.createdAt).toBe('string')
    }
  })

  it('T4-2c: 빈 세션 목록도 빈 배열로 반환한다 (EC-1)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () => {
        return HttpResponse.json({ sessions: [] })
      }),
    )
    const result = await listSessions()
    expect(result).toHaveLength(0)
  })

  it('T4-2d: 서버 401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    await expect(listSessions()).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-3. revokeSession — DELETE /api/v1/auth/sessions/{sid} + X-XSRF-TOKEN 헤더
// ─────────────────────────────────────────────────────────────────────────────
describe('revokeSession', () => {
  it('T4-3a: 유효한 sid로 DELETE 호출 시 204로 완료된다', async () => {
    // document.cookie에 XSRF-TOKEN 설정 (jsdom 환경)
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(revokeSession('b2c3d4e5-f6a7-5901-bcde-f01234567891')).resolves.toBeUndefined()
  })

  it('T4-3b: DELETE 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/auth/sessions/:sid', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=my-csrf-token'
    await revokeSession('b2c3d4e5-f6a7-5901-bcde-f01234567891')
    expect(capturedXsrf).toBe('my-csrf-token')
  })

  it('T4-3c: 서버 404 응답(IDOR/비활성 sid) 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/auth/sessions/:sid', () => {
        return HttpResponse.json({ message: 'Not Found' }, { status: 404 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(revokeSession('nonexistent-sid')).rejects.toThrow()
  })

  it('T4-3d: 서버 409 응답(현재 세션 종료 시도) 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/auth/sessions/:sid', () => {
        return HttpResponse.json(
          { error: 'cannot_revoke_current_session' },
          { status: 409 },
        )
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(revokeSession('current-sid')).rejects.toThrow()
  })
})
