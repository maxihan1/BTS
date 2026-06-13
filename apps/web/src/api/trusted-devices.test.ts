// 신뢰 디바이스 API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  trustedDeviceSchema,
  listTrustedDevices,
  revokeTrustedDevice,
  revokeAllTrustedDevices,
} from './trusted-devices'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — spec §FR-MF-05 필드: id/label/createdAt/lastUsedAt/expiresAt
// ─────────────────────────────────────────────────────────────────────────────
const deviceFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  label: 'Chrome on macOS',
  createdAt: '2026-05-01T10:00:00Z',
  lastUsedAt: '2026-05-29T10:00:00Z',
  expiresAt: '2026-05-31T10:00:00Z',
}

/** label/lastUsedAt null 케이스 — User-Agent 헤더 없이 등록한 디바이스 */
const deviceFixtureNullable = {
  id: 'b2c3d4e5-f6a7-5901-bcde-f01234567891',
  label: null,
  createdAt: '2026-05-10T08:00:00Z',
  lastUsedAt: null,
  expiresAt: '2026-06-09T08:00:00Z',
}

beforeEach(() => {
  server.use(
    // GET /api/v1/auth/mfa/trusted-devices — 200 { devices: [...] }
    http.get('/api/v1/auth/mfa/trusted-devices', () => {
      return HttpResponse.json({ devices: [deviceFixture, deviceFixtureNullable] })
    }),

    // DELETE /api/v1/auth/mfa/trusted-devices/:id — 204 No Content
    http.delete('/api/v1/auth/mfa/trusted-devices/:id', ({ request, params }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      if (xsrfHeader === null || xsrfHeader === '') {
        return new HttpResponse(null, { status: 403 })
      }
      const id = params['id']
      if (id === 'not-found-id') {
        return HttpResponse.json({ error: 'not_found' }, { status: 404 })
      }
      return new HttpResponse(null, { status: 204 })
    }),

    // DELETE /api/v1/auth/mfa/trusted-devices — 204 No Content (전체 취소)
    http.delete('/api/v1/auth/mfa/trusted-devices', ({ request }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      if (xsrfHeader === null || xsrfHeader === '') {
        return new HttpResponse(null, { status: 403 })
      }
      return new HttpResponse(null, { status: 204 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T1. trustedDeviceSchema — spec §FR-MF-05 5 필드 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('trustedDeviceSchema', () => {
  it('T1-a: 5 필드가 모두 있는 디바이스를 파싱한다', () => {
    const result = trustedDeviceSchema.parse(deviceFixture)

    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.label).toBe('Chrome on macOS')
    expect(result.createdAt).toBe('2026-05-01T10:00:00Z')
    expect(result.lastUsedAt).toBe('2026-05-29T10:00:00Z')
    expect(result.expiresAt).toBe('2026-05-31T10:00:00Z')
  })

  it('T1-b: label/lastUsedAt가 null인 디바이스도 파싱 성공', () => {
    const result = trustedDeviceSchema.parse(deviceFixtureNullable)

    expect(result.label).toBeNull()
    expect(result.lastUsedAt).toBeNull()
    expect(result.id).toBe('b2c3d4e5-f6a7-5901-bcde-f01234567891')
  })

  it('T1-c: 필수 필드(id) 누락 시 ZodError throw', () => {
    expect(() =>
      trustedDeviceSchema.parse({ label: 'test', createdAt: '2026-01-01T00:00:00Z', expiresAt: '2026-01-31T00:00:00Z' }),
    ).toThrow()
  })

  it('T1-d: id가 UUID 형식이 아니면 ZodError throw', () => {
    expect(() =>
      trustedDeviceSchema.parse({ ...deviceFixture, id: 'not-a-uuid' }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2. listTrustedDevices — GET /api/v1/auth/mfa/trusted-devices → TrustedDevice[]
// ─────────────────────────────────────────────────────────────────────────────
describe('listTrustedDevices', () => {
  it('T2-a: 디바이스 목록을 TrustedDevice[] 형태로 반환한다', async () => {
    const result = await listTrustedDevices()

    expect(result).toHaveLength(2)
    expect(result[0]?.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result[0]?.label).toBe('Chrome on macOS')
    expect(result[1]?.id).toBe('b2c3d4e5-f6a7-5901-bcde-f01234567891')
    expect(result[1]?.label).toBeNull()
  })

  it('T2-b: 래퍼 { devices: [...] }를 언래핑해 배열만 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/trusted-devices', () => {
        return HttpResponse.json({ devices: [deviceFixture] })
      }),
    )
    const result = await listTrustedDevices()

    // 반환값이 배열이어야 하며 래퍼 객체가 아니어야 함
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(1)
  })

  it('T2-c: 빈 목록도 빈 배열로 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/trusted-devices', () => {
        return HttpResponse.json({ devices: [] })
      }),
    )
    const result = await listTrustedDevices()
    expect(result).toHaveLength(0)
  })

  it('T2-d: 서버 401 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/auth/mfa/trusted-devices', () => {
        return HttpResponse.json({ message: 'Unauthorized' }, { status: 401 })
      }),
    )
    await expect(listTrustedDevices()).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3. revokeTrustedDevice — DELETE /api/v1/auth/mfa/trusted-devices/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('revokeTrustedDevice', () => {
  it('T3-a: 유효한 id로 DELETE 호출 시 204로 완료된다', async () => {
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(
      revokeTrustedDevice('a1b2c3d4-e5f6-4890-abcd-ef1234567890'),
    ).resolves.toBeUndefined()
  })

  it('T3-b: DELETE 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/auth/mfa/trusted-devices/:id', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=my-csrf-token'
    await revokeTrustedDevice('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(capturedXsrf).toBe('my-csrf-token')
  })

  it('T3-c: 서버 404 응답(IDOR/미존재) 시 ApiError(404)를 throw한다', async () => {
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(revokeTrustedDevice('not-found-id')).rejects.toThrow()
  })

  it('T3-d: 서버 403 응답(PAT 인증) 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/auth/mfa/trusted-devices/:id', () => {
        return HttpResponse.json(
          { error: 'session_management_requires_interactive_login' },
          { status: 403 },
        )
      }),
    )
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(revokeTrustedDevice('a1b2c3d4-e5f6-4890-abcd-ef1234567890')).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4. revokeAllTrustedDevices — DELETE /api/v1/auth/mfa/trusted-devices
// ─────────────────────────────────────────────────────────────────────────────
describe('revokeAllTrustedDevices', () => {
  it('T4-a: DELETE 호출 시 204로 완료된다', async () => {
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    await expect(revokeAllTrustedDevices()).resolves.toBeUndefined()
  })

  it('T4-b: DELETE 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/auth/mfa/trusted-devices', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=bulk-csrf-token'
    await revokeAllTrustedDevices()
    expect(capturedXsrf).toBe('bulk-csrf-token')
  })

  it('T4-c: 204 응답이므로 반환값이 undefined이다', async () => {
    document.cookie = 'XSRF-TOKEN=test-csrf-token'
    const result = await revokeAllTrustedDevices()
    expect(result).toBeUndefined()
  })
})
