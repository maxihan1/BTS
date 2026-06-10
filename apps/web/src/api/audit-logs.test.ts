// 감사 로그 API 클라이언트 단위 테스트 — Zod 파싱 + URLSearchParams 조립 + 에러 전파
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { fetchAuditLogs, AUTH_EVENT_TYPES, auditLogPageSchema } from './audit-logs'
import { ApiError } from './client'

// Zod v4 .uuid()는 RFC 4122 버전(13번째 자리 4, 17번째 자리 8~b)을 검증한다.
// nil UUID(전부 0)는 거부되므로 유효한 v4 형식 fixture를 사용한다 (zod-v4-uuid-fixture-strictness).
const baseEntry = {
  id: 42,
  userId: '11111111-1111-4111-8111-111111111111',
  username: 'alice',
  displayName: 'Alice',
  eventType: 'LOGIN_SUCCESS',
  providerId: 'local',
  ipAddress: '203.0.113.1',
  userAgent: 'Mozilla/5.0',
  metadata: { sid: 's1' },
  createdAt: '2026-06-01T10:00:00Z',
}

const baseResponse = {
  items: [baseEntry],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
}

describe('fetchAuditLogs', () => {
  it('파라미터 없이 호출 시 쿼리스트링 없이 요청하고 응답을 파싱한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(baseResponse)
      }),
    )

    const result = await fetchAuditLogs({})
    expect(result.items).toHaveLength(1)
    expect(result.page).toBe(0)
    expect(result.totalElements).toBe(1)
    // 파라미터가 없으면 ? 없이 요청
    expect(capturedUrl).not.toContain('eventType')
    expect(capturedUrl).not.toContain('userId')
  })

  it('eventType 파라미터를 쿼리스트링에 포함한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(baseResponse)
      }),
    )

    await fetchAuditLogs({ eventType: 'LOGIN_SUCCESS' })
    expect(capturedUrl).toContain('eventType=LOGIN_SUCCESS')
  })

  it('userId/page/size 파라미터를 쿼리스트링에 포함한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(baseResponse)
      }),
    )

    await fetchAuditLogs({
      userId: '11111111-1111-4111-8111-111111111111',
      page: 2,
      size: 10,
    })
    expect(capturedUrl).toContain('userId=11111111-1111-4111-8111-111111111111')
    expect(capturedUrl).toContain('page=2')
    expect(capturedUrl).toContain('size=10')
  })

  it('from/to 파라미터를 쿼리스트링에 포함한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(baseResponse)
      }),
    )

    await fetchAuditLogs({ from: '2026-06-01T00:00:00.000Z', to: '2026-06-01T23:59:59.999Z' })
    expect(capturedUrl).toContain('from=')
    expect(capturedUrl).toContain('to=')
  })

  it('undefined 파라미터는 쿼리스트링에서 생략된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(baseResponse)
      }),
    )

    await fetchAuditLogs({ eventType: undefined, userId: undefined })
    expect(capturedUrl).not.toContain('eventType')
    expect(capturedUrl).not.toContain('userId')
  })

  it('userId/username/displayName/ipAddress/userAgent null 허용 — 파싱 성공', async () => {
    const nullEntry = {
      id: 99,
      userId: null,
      username: null,
      displayName: null,
      eventType: 'LOGIN_FAILURE',
      providerId: 'local',
      ipAddress: null,
      userAgent: null,
      metadata: {},
      createdAt: '2026-06-01T10:00:00Z',
    }
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () =>
        HttpResponse.json({ items: [nullEntry], page: 0, size: 50, totalElements: 1, totalPages: 1 }),
      ),
    )

    const result = await fetchAuditLogs({})
    const item = result.items[0]
    expect(item?.userId).toBeNull()
    expect(item?.username).toBeNull()
    expect(item?.displayName).toBeNull()
    expect(item?.ipAddress).toBeNull()
    expect(item?.userAgent).toBeNull()
  })

  it('@JsonInclude(NON_NULL) — 키 자체가 없어도 파싱 성공 (nullish)', async () => {
    // 백엔드 @JsonInclude(NON_NULL)로 null 필드는 키 자체가 생략됨
    const missingKeysEntry = {
      id: 88,
      eventType: 'LOGIN_FAILURE',
      providerId: 'local',
      metadata: {},
      createdAt: '2026-06-01T10:00:00Z',
      // userId, username, displayName, ipAddress, userAgent 키 없음
    }
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () =>
        HttpResponse.json({ items: [missingKeysEntry], page: 0, size: 50, totalElements: 1, totalPages: 1 }),
      ),
    )

    // 파싱 실패 없이 성공해야 함
    const result = await fetchAuditLogs({})
    const item = result.items[0]
    expect(item?.id).toBe(88)
    expect(item?.eventType).toBe('LOGIN_FAILURE')
    // nullish이므로 undefined 또는 null 모두 허용
    expect(item?.userId == null).toBe(true)
  })

  it('빈 items 응답을 정상 파싱한다', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () =>
        HttpResponse.json({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
      ),
    )

    const result = await fetchAuditLogs({})
    expect(result.items).toHaveLength(0)
    expect(result.totalElements).toBe(0)
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )

    await expect(fetchAuditLogs({})).rejects.toThrow(ApiError)
    await expect(fetchAuditLogs({})).rejects.toMatchObject({ status: 403 })
  })

  it('401 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    // 401은 refresh 후 retry하므로 결과적으로 에러
    await expect(fetchAuditLogs({})).rejects.toBeInstanceOf(Error)
  })
})

describe('AUTH_EVENT_TYPES', () => {
  it('12종의 이벤트 타입을 포함한다', () => {
    expect(AUTH_EVENT_TYPES).toHaveLength(12)
  })

  it('백엔드 AuthEventType enum 12종을 모두 포함한다', () => {
    const expected = [
      'LOGIN_SUCCESS',
      'LOGIN_FAILURE',
      'LOGOUT',
      'LOGOUT_ALL_DEVICES',
      'TOKEN_REFRESHED',
      'SUSPICIOUS_REFRESH_REPLAY',
      'USER_PROVISIONED',
      'PAT_USED',
      'LDAP_UNAVAILABLE',
      'PROJECT_MEMBER_ADDED',
      'PROJECT_ROLE_CHANGED',
      'PROJECT_MEMBER_REMOVED',
    ]
    expect(AUTH_EVENT_TYPES).toEqual(expect.arrayContaining(expected))
    expect(AUTH_EVENT_TYPES.length).toBe(expected.length)
  })
})

describe('auditLogPageSchema', () => {
  it('정상 응답을 파싱한다', () => {
    const result = auditLogPageSchema.parse(baseResponse)
    expect(result.items[0]?.id).toBe(42)
    expect(result.items[0]?.eventType).toBe('LOGIN_SUCCESS')
  })
})
