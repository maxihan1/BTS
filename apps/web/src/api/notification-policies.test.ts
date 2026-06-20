// 알림 정책 API 클라이언트 단위 테스트 — MSW + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  NOTIFICATION_EVENT_TYPES,
  RECIPIENT_ROLES,
  UNSUPPORTED_RECIPIENT_ROLES,
  CHANNELS,
  notificationPolicySchema,
  policyCatalogSchema,
  fetchCatalog,
  fetchPolicies,
  createPolicy,
  togglePolicy,
  deletePolicy,
} from './notification-policies'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — CSRF 헤더 검증용
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-notification-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture
// ─────────────────────────────────────────────────────────────────────────────

/** NON_NULL projectKey — 전역 정책이라 JSON에서 키 자체가 생략됨 */
const policyGlobalFixture = {
  id: '11111111-1111-4111-a111-111111111111',
  eventType: 'issue.created',
  recipientRole: 'REPORTER',
  channel: 'EMAIL',
  enabled: true,
  createdAt: '2026-06-01T00:00:00Z',
  updatedAt: '2026-06-01T00:00:00Z',
}

/** projectKey 포함 — 프로젝트 정책 (nullish 파싱 검증) */
const policyWithProjectKeyFixture = {
  ...policyGlobalFixture,
  id: '22222222-2222-4222-a222-222222222222',
  projectKey: 'ATLAS',
}

const catalogFixture = {
  eventTypes: NOTIFICATION_EVENT_TYPES.map((value) => ({
    value,
    publishable: value === 'issue.created' || value === 'issue.transitioned',
  })),
  recipientRoles: [...RECIPIENT_ROLES],
  channels: [...CHANNELS],
}

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-S. Zod 스키마 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('notificationPolicySchema', () => {
  it('T-NP-S1: 전역 정책(projectKey 없음)을 파싱한다 — .nullish() 통과', () => {
    const result = notificationPolicySchema.parse(policyGlobalFixture)
    expect(result.id).toBe(policyGlobalFixture.id)
    expect(result.eventType).toBe('issue.created')
    expect(result.recipientRole).toBe('REPORTER')
    expect(result.channel).toBe('EMAIL')
    expect(result.enabled).toBe(true)
    // projectKey 없으면 undefined (NON_NULL → 키 생략)
    expect(result.projectKey).toBeUndefined()
  })

  it('T-NP-S2: projectKey 포함 정책을 파싱한다', () => {
    const result = notificationPolicySchema.parse(policyWithProjectKeyFixture)
    expect(result.projectKey).toBe('ATLAS')
  })

  it('T-NP-S3: projectKey가 null이어도 파싱된다 — .nullish()', () => {
    const result = notificationPolicySchema.parse({ ...policyGlobalFixture, projectKey: null })
    expect(result.projectKey).toBeNull()
  })

  it('T-NP-S4: 필수 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => notificationPolicySchema.parse({ id: '11111111-1111-4111-a111-111111111111' })).toThrow()
  })
})

describe('policyCatalogSchema', () => {
  it('T-NP-S5: 카탈로그 응답을 파싱한다 — eventTypes 9종 + recipientRoles 9종 + channels 5종', () => {
    const result = policyCatalogSchema.parse(catalogFixture)
    expect(result.eventTypes).toHaveLength(9)
    expect(result.recipientRoles).toHaveLength(9)
    expect(result.channels).toHaveLength(5)
  })

  it('T-NP-S6: eventTypes[].publishable 필드가 boolean으로 파싱된다', () => {
    const result = policyCatalogSchema.parse(catalogFixture)
    const issueCreated = result.eventTypes.find((e) => e.value === 'issue.created')
    expect(issueCreated?.publishable).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-E. enum 미러 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('enum 미러', () => {
  it('T-NP-E1: NOTIFICATION_EVENT_TYPES가 wireValue 9종을 포함한다', () => {
    expect(NOTIFICATION_EVENT_TYPES).toHaveLength(9)
    expect(NOTIFICATION_EVENT_TYPES).toContain('issue.created')
    expect(NOTIFICATION_EVENT_TYPES).toContain('issue.assigned')
    expect(NOTIFICATION_EVENT_TYPES).toContain('issue.transitioned')
    expect(NOTIFICATION_EVENT_TYPES).toContain('issue.commented')
    expect(NOTIFICATION_EVENT_TYPES).toContain('issue.due_soon')
    expect(NOTIFICATION_EVENT_TYPES).toContain('issue.overdue')
    expect(NOTIFICATION_EVENT_TYPES).toContain('sprint.started')
    expect(NOTIFICATION_EVENT_TYPES).toContain('sprint.ended')
    expect(NOTIFICATION_EVENT_TYPES).toContain('automation.failed')
  })

  it('T-NP-E2: RECIPIENT_ROLES가 NAME 9종을 포함한다', () => {
    expect(RECIPIENT_ROLES).toHaveLength(9)
    expect(RECIPIENT_ROLES).toContain('REPORTER')
    expect(RECIPIENT_ROLES).toContain('ASSIGNEE')
    expect(RECIPIENT_ROLES).toContain('PREVIOUS_ASSIGNEE')
    expect(RECIPIENT_ROLES).toContain('WATCHER')
    expect(RECIPIENT_ROLES).toContain('COMPONENT_LEAD')
    expect(RECIPIENT_ROLES).toContain('MENTIONED')
    expect(RECIPIENT_ROLES).toContain('PROJECT_MEMBER')
    expect(RECIPIENT_ROLES).toContain('RULE_OWNER')
    expect(RECIPIENT_ROLES).toContain('PROJECT_ADMIN')
  })

  it('T-NP-E3: CHANNELS가 NAME 5종을 포함한다', () => {
    expect(CHANNELS).toHaveLength(5)
    expect(CHANNELS).toContain('EMAIL')
    expect(CHANNELS).toContain('IN_APP')
    expect(CHANNELS).toContain('SLACK')
    expect(CHANNELS).toContain('TEAMS')
    expect(CHANNELS).toContain('WEBHOOK')
  })

  it('T-NP-E4: UNSUPPORTED_RECIPIENT_ROLES가 RULE_OWNER를 포함한다', () => {
    expect(UNSUPPORTED_RECIPIENT_ROLES).toContain('RULE_OWNER')
  })

  it('T-NP-E5: UNSUPPORTED_RECIPIENT_ROLES의 모든 원소가 RECIPIENT_ROLES의 부분집합이다 (오타 drift 차단)', () => {
    for (const role of UNSUPPORTED_RECIPIENT_ROLES) {
      expect(RECIPIENT_ROLES).toContain(role)
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-1. fetchCatalog — GET /api/v1/notification-policies/catalog
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchCatalog', () => {
  it('T-NP-1a: 200 응답의 data 래퍼를 unwrap해 PolicyCatalog를 반환한다', async () => {
    server.use(
      http.get('/api/v1/notification-policies/catalog', () =>
        HttpResponse.json({ data: catalogFixture }),
      ),
    )
    const result = await fetchCatalog()
    expect(result.eventTypes).toHaveLength(9)
    expect(result.recipientRoles).toHaveLength(9)
    expect(result.channels).toHaveLength(5)
  })

  it('T-NP-1b: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (GET 읽기 요청)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.get('/api/v1/notification-policies/catalog', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json({ data: catalogFixture })
      }),
    )
    await fetchCatalog()
    expect(capturedXsrf).toBeNull()
  })

  it('T-NP-1c: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/notification-policies/catalog', () =>
        HttpResponse.json({ type: 'about:blank' }, { status: 401 }),
      ),
    )
    let thrown: unknown
    try {
      await fetchCatalog()
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-2. fetchPolicies — GET /api/v1/notification-policies
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchPolicies', () => {
  it('T-NP-2a: 200 응답의 data 래퍼를 unwrap해 NotificationPolicy[] 반환', async () => {
    server.use(
      http.get('/api/v1/notification-policies', () =>
        HttpResponse.json({ data: [policyGlobalFixture] }),
      ),
    )
    const result = await fetchPolicies()
    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(policyGlobalFixture.id)
  })

  it('T-NP-2b: 전역 정책(projectKey 없음)이 포함된 목록을 파싱한다', async () => {
    server.use(
      http.get('/api/v1/notification-policies', () =>
        HttpResponse.json({ data: [policyGlobalFixture] }),
      ),
    )
    const result = await fetchPolicies()
    expect(result[0]?.projectKey).toBeUndefined()
  })

  it('T-NP-2c: X-XSRF-TOKEN 헤더가 요청에 포함되지 않는다 (GET 읽기 요청)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.get('/api/v1/notification-policies', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json({ data: [] })
      }),
    )
    await fetchPolicies()
    expect(capturedXsrf).toBeNull()
  })

  it('T-NP-2d: 빈 목록도 파싱된다', async () => {
    server.use(
      http.get('/api/v1/notification-policies', () =>
        HttpResponse.json({ data: [] }),
      ),
    )
    const result = await fetchPolicies()
    expect(result).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-3. createPolicy — POST /api/v1/notification-policies
// ─────────────────────────────────────────────────────────────────────────────
describe('createPolicy', () => {
  const createBody = {
    eventType: 'issue.created',
    recipientRole: 'REPORTER',
    channel: 'EMAIL',
    enabled: true,
  }

  it('T-NP-3a: 201 응답의 data 래퍼를 unwrap해 NotificationPolicy 반환', async () => {
    server.use(
      http.post('/api/v1/notification-policies', () =>
        HttpResponse.json({ data: policyGlobalFixture }, { status: 201 }),
      ),
    )
    const result = await createPolicy(createBody)
    expect(result.id).toBe(policyGlobalFixture.id)
    expect(result.eventType).toBe('issue.created')
  })

  it('T-NP-3b: X-XSRF-TOKEN 헤더가 요청에 포함된다 (상태 변경)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/notification-policies', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json({ data: policyGlobalFixture }, { status: 201 })
      }),
    )
    await createPolicy(createBody)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-NP-3c: 요청 바디에 eventType/recipientRole/channel이 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/notification-policies', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: policyGlobalFixture }, { status: 201 })
      }),
    )
    await createPolicy(createBody)
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.eventType).toBe('issue.created')
    expect(body?.recipientRole).toBe('REPORTER')
    expect(body?.channel).toBe('EMAIL')
  })

  it('T-NP-3d: 409 NOTIF_POLICY_DUPLICATE → ApiError(409) throw, errorCode 대문자', async () => {
    server.use(
      http.post('/api/v1/notification-policies', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            errorCode: 'NOTIF_POLICY_DUPLICATE',
          },
          { status: 409 },
        ),
      ),
    )
    let thrown: unknown
    try {
      await createPolicy(createBody)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(409)
    // 대문자 errorCode — mfa.ts 소문자 패턴과 다름
    const body = thrown.body as Record<string, unknown> | null
    expect(body?.errorCode).toBe('NOTIF_POLICY_DUPLICATE')
  })

  it('T-NP-3e: 전역 정책 생성 시 projectKey 없이 요청한다 (D1=전역)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/notification-policies', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ data: policyGlobalFixture }, { status: 201 })
      }),
    )
    // projectKey 생략해서 호출
    await createPolicy({ eventType: 'issue.created', recipientRole: 'REPORTER', channel: 'EMAIL', enabled: true })
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.projectKey).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-4. togglePolicy — PATCH /api/v1/notification-policies/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('togglePolicy', () => {
  const policyId = policyGlobalFixture.id

  it('T-NP-4a: 204 응답 → void 반환 (에러 없음)', async () => {
    server.use(
      http.patch(`/api/v1/notification-policies/${policyId}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(togglePolicy(policyId, false)).resolves.toBeUndefined()
  })

  it('T-NP-4b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch(`/api/v1/notification-policies/${policyId}`, ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await togglePolicy(policyId, false)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-NP-4c: 요청 바디에 enabled가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.patch(`/api/v1/notification-policies/${policyId}`, async ({ request }) => {
        capturedBody = await request.json()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await togglePolicy(policyId, false)
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.enabled).toBe(false)
  })

  it('T-NP-4d: 404 → ApiError(404) throw', async () => {
    server.use(
      http.patch(`/api/v1/notification-policies/${policyId}`, () =>
        HttpResponse.json(
          { type: 'about:blank', errorCode: 'NOTIF_POLICY_NOT_FOUND' },
          { status: 404 },
        ),
      ),
    )
    let thrown: unknown
    try {
      await togglePolicy(policyId, false)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NP-5. deletePolicy — DELETE /api/v1/notification-policies/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('deletePolicy', () => {
  const policyId = policyGlobalFixture.id

  it('T-NP-5a: 204 응답 → void 반환 (에러 없음)', async () => {
    server.use(
      http.delete(`/api/v1/notification-policies/${policyId}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    await expect(deletePolicy(policyId)).resolves.toBeUndefined()
  })

  it('T-NP-5b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete(`/api/v1/notification-policies/${policyId}`, ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deletePolicy(policyId)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-NP-5c: 404 → ApiError(404) throw', async () => {
    server.use(
      http.delete(`/api/v1/notification-policies/${policyId}`, () =>
        HttpResponse.json(
          { type: 'about:blank', errorCode: 'NOTIF_POLICY_NOT_FOUND' },
          { status: 404 },
        ),
      ),
    )
    let thrown: unknown
    try {
      await deletePolicy(policyId)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })

  it('T-NP-5d: 요청 바디가 없다 (DELETE는 body 없음)', async () => {
    let capturedContentType: string | null = null
    server.use(
      http.delete(`/api/v1/notification-policies/${policyId}`, ({ request }) => {
        capturedContentType = request.headers.get('content-type')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deletePolicy(policyId)
    // body 없으므로 Content-Type application/json이 자동 추가되지 않아야 함
    expect(capturedContentType).toBeNull()
  })
})
