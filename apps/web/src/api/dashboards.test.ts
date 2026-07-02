// 대시보드 API 클라이언트 단위 테스트 — Zod 스키마 파싱 + CRUD 함수 계약 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  dashboardSchema,
  dashboardPageSchema,
  listDashboards,
  getDashboard,
  createDashboard,
  patchDashboard,
  deleteDashboard,
  issuedShareTokenSchema,
  shareTokenSummarySchema,
  shareTokenListSchema,
  publicDashboardSchema,
  issueShareToken,
  listShareTokens,
  revokeShareToken,
  getPublicDashboard,
} from './dashboards'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Mock 설정
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('./client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client')>()
  return {
    ...actual,
    apiGet: vi.fn(),
    apiFetch: vi.fn(),
  }
})

vi.mock('./sessions', () => ({
  readXsrfToken: vi.fn(() => 'test-xsrf-token'),
}))

const mockApiGet = vi.mocked(
  (await import('./client')).apiGet,
)
const mockApiFetch = vi.mocked(
  (await import('./client')).apiFetch,
)

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const DASHBOARD_ID = '550e8400-e29b-41d4-a716-446655440000'
const OWNER_ID = '550e8400-e29b-41d4-a716-446655440001'
const USER_ID_1 = '550e8400-e29b-41d4-a716-446655440002'

/** description 포함된 대시보드 응답 픽스처 */
const dashboardWithDescription = {
  id: DASHBOARD_ID,
  ownerId: OWNER_ID,
  name: '내 대시보드',
  description: '설명입니다',
  visibility: 'PRIVATE',
  layout: '[]',
  sharedUserIds: [USER_ID_1],
  createdAt: '2026-06-23T00:00:00Z',
  updatedAt: '2026-06-23T00:00:00Z',
  version: 1,
}

/** description 없는 대시보드 응답 픽스처 (@JsonInclude NON_NULL — 키 자체 생략) */
const dashboardWithoutDescription = {
  id: DASHBOARD_ID,
  ownerId: OWNER_ID,
  name: '설명 없는 대시보드',
  // description 키 없음
  visibility: 'TEAM',
  layout: '{"widgets":[]}',
  sharedUserIds: [],
  createdAt: '2026-06-23T00:00:00Z',
  updatedAt: '2026-06-23T00:00:00Z',
  version: 0,
}

const dashboardPageFixture = {
  items: [dashboardWithDescription, dashboardWithoutDescription],
  total: 2,
  limit: 20,
  offset: 0,
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 토큰 픽스처 (FR-DB-03 D6/D7 — 백엔드 PR1 #216 DTO 1:1)
// ─────────────────────────────────────────────────────────────────────────────

const SHARE_ID = '550e8400-e29b-41d4-a716-446655440010'
const SHARE_TOKEN = 'aBcDeF1234567890aBcDeF1234567890aBcDeF12'

/** 공유 토큰 발급 응답 픽스처 (expiresAt 포함) — IssuedShareTokenResponse */
const issuedShareTokenFixture = {
  id: SHARE_ID,
  token: SHARE_TOKEN,
  createdAt: '2026-07-02T00:00:00Z',
  expiresAt: '2026-08-01T00:00:00Z',
}

/** 공유 토큰 발급 응답 픽스처 (expiresAt 키 없음 — @JsonInclude NON_NULL 무기한 발급) */
const issuedShareTokenFixtureNoExpiry = {
  id: SHARE_ID,
  token: SHARE_TOKEN,
  createdAt: '2026-07-02T00:00:00Z',
  // expiresAt 키 없음
}

/** 공유 토큰 요약 픽스처 (목록 응답 — token/tokenHash 필드 없음) — ShareTokenSummaryResponse */
const shareTokenSummaryFixture = {
  id: SHARE_ID,
  createdAt: '2026-07-02T00:00:00Z',
  expiresAt: '2026-08-01T00:00:00Z',
}

const shareTokenListFixture = {
  items: [shareTokenSummaryFixture],
}

/** 익명 공개 대시보드 응답 픽스처 — layout은 정화된 JSON *문자열* (파싱된 객체 아님) */
const publicDashboardFixture = {
  name: '공개 대시보드',
  description: '공유용 설명',
  layout: '[{"i":"a","x":0,"y":0,"w":2,"h":2,"gadgetType":"text_widget"}]',
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardSchema', () => {
  it('description 있는 응답을 파싱한다', () => {
    const result = dashboardSchema.parse(dashboardWithDescription)
    expect(result.id).toBe(DASHBOARD_ID)
    expect(result.description).toBe('설명입니다')
    expect(result.sharedUserIds).toHaveLength(1)
    expect(result.version).toBe(1)
  })

  it('description 키가 없어도 파싱한다 (@JsonInclude NON_NULL)', () => {
    const result = dashboardSchema.parse(dashboardWithoutDescription)
    expect(result.description).toBeUndefined()
    expect(result.sharedUserIds).toHaveLength(0)
    expect(result.version).toBe(0)
  })

  it('description이 null이어도 파싱한다', () => {
    const result = dashboardSchema.parse({ ...dashboardWithDescription, description: null })
    expect(result.description).toBeNull()
  })

  it('visibility는 PRIVATE|TEAM|ORG 문자열로 파싱한다', () => {
    const orgs = dashboardSchema.parse({ ...dashboardWithDescription, visibility: 'ORG' })
    expect(orgs.visibility).toBe('ORG')
  })

  it('sharedUserIds가 비-UUID이면 파싱 실패한다', () => {
    expect(() =>
      dashboardSchema.parse({ ...dashboardWithDescription, sharedUserIds: ['not-a-uuid'] }),
    ).toThrow()
  })

  it('id가 UUID 형식이 아니면 파싱 실패한다', () => {
    expect(() => dashboardSchema.parse({ ...dashboardWithDescription, id: 'invalid' })).toThrow()
  })

  it('version이 숫자가 아니면 파싱 실패한다', () => {
    expect(() =>
      dashboardSchema.parse({ ...dashboardWithDescription, version: 'one' }),
    ).toThrow()
  })

  it('layout은 임의 문자열을 허용한다', () => {
    const result = dashboardSchema.parse({ ...dashboardWithDescription, layout: '{"col":1}' })
    expect(result.layout).toBe('{"col":1}')
  })
})

describe('dashboardPageSchema', () => {
  it('items/total/limit/offset을 파싱한다', () => {
    const result = dashboardPageSchema.parse(dashboardPageFixture)
    expect(result.items).toHaveLength(2)
    expect(result.total).toBe(2)
    expect(result.limit).toBe(20)
    expect(result.offset).toBe(0)
  })

  it('items가 없으면 파싱 실패한다', () => {
    expect(() =>
      dashboardPageSchema.parse({ total: 0, limit: 20, offset: 0 }),
    ).toThrow()
  })
})

describe('issuedShareTokenSchema', () => {
  it('expiresAt 있는 응답을 파싱한다', () => {
    const result = issuedShareTokenSchema.parse(issuedShareTokenFixture)
    expect(result.id).toBe(SHARE_ID)
    expect(result.token).toBe(SHARE_TOKEN)
    expect(result.expiresAt).toBe('2026-08-01T00:00:00Z')
  })

  it('expiresAt 키가 없어도 파싱한다 (@JsonInclude NON_NULL — 무기한 발급)', () => {
    const result = issuedShareTokenSchema.parse(issuedShareTokenFixtureNoExpiry)
    expect(result.expiresAt).toBeUndefined()
  })

  it('token이 문자열이 아니면 파싱 실패한다', () => {
    expect(() =>
      issuedShareTokenSchema.parse({ ...issuedShareTokenFixture, token: 123 }),
    ).toThrow()
  })

  it('id가 UUID 형식이 아니면 파싱 실패한다', () => {
    expect(() =>
      issuedShareTokenSchema.parse({ ...issuedShareTokenFixture, id: 'invalid' }),
    ).toThrow()
  })
})

describe('shareTokenSummarySchema', () => {
  it('id/createdAt/expiresAt을 파싱한다', () => {
    const result = shareTokenSummarySchema.parse(shareTokenSummaryFixture)
    expect(result.id).toBe(SHARE_ID)
    expect(result.expiresAt).toBe('2026-08-01T00:00:00Z')
  })

  it('expiresAt 키가 없어도 파싱한다 (@JsonInclude NON_NULL)', () => {
    const withoutExpiry = { id: SHARE_ID, createdAt: shareTokenSummaryFixture.createdAt }
    const result = shareTokenSummarySchema.parse(withoutExpiry)
    expect(result.expiresAt).toBeUndefined()
  })

  it('회귀가드: 응답에 token 필드가 섞여도 파싱 결과에 노출되지 않는다 (백엔드 DTO에 필드 자체 없음)', () => {
    const leaked = { ...shareTokenSummaryFixture, token: 'leaked-plaintext' }
    const result = shareTokenSummarySchema.parse(leaked)
    expect((result as unknown as Record<string, unknown>)['token']).toBeUndefined()
  })
})

describe('shareTokenListSchema', () => {
  it('items 배열을 파싱한다', () => {
    const result = shareTokenListSchema.parse(shareTokenListFixture)
    expect(result.items).toHaveLength(1)
    expect(result.items[0]?.id).toBe(SHARE_ID)
  })

  it('items가 없으면 파싱 실패한다', () => {
    expect(() => shareTokenListSchema.parse({})).toThrow()
  })
})

describe('publicDashboardSchema', () => {
  it('name/description/layout(JSON 문자열)을 파싱한다', () => {
    const result = publicDashboardSchema.parse(publicDashboardFixture)
    expect(result.name).toBe(publicDashboardFixture.name)
    expect(result.layout).toBe(publicDashboardFixture.layout)
    expect(typeof result.layout).toBe('string')
  })

  it('description이 null이어도 파싱한다', () => {
    const result = publicDashboardSchema.parse({ ...publicDashboardFixture, description: null })
    expect(result.description).toBeNull()
  })

  it('description 키가 없어도 파싱한다', () => {
    const withoutDescription = { name: publicDashboardFixture.name, layout: publicDashboardFixture.layout }
    const result = publicDashboardSchema.parse(withoutDescription)
    expect(result.description).toBeUndefined()
  })

  it('layout이 문자열이 아니면(파싱된 객체) 파싱 실패한다', () => {
    expect(() =>
      publicDashboardSchema.parse({ ...publicDashboardFixture, layout: [] }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('listDashboards', () => {
  it('apiGet으로 GET /api/v1/dashboards를 호출한다', async () => {
    mockApiGet.mockResolvedValueOnce({ data: dashboardPageFixture })

    const result = await listDashboards(20, 0)

    expect(mockApiGet).toHaveBeenCalledOnce()
    const [calledPath] = mockApiGet.mock.calls[0] ?? []
    expect(calledPath).toBe('/api/v1/dashboards?limit=20&offset=0')
    expect(result.items).toHaveLength(2)
    expect(result.total).toBe(2)
  })

  it('비-2xx 시 ApiError를 전파한다', async () => {
    mockApiGet.mockRejectedValueOnce(new ApiError(403, { errorCode: 'NOTIF_DASHBOARD_FORBIDDEN' }))
    await expect(listDashboards(20, 0)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('getDashboard', () => {
  it('apiGet으로 GET /api/v1/dashboards/:id를 호출한다', async () => {
    mockApiGet.mockResolvedValueOnce({ data: dashboardWithDescription })

    const result = await getDashboard(DASHBOARD_ID)

    expect(mockApiGet).toHaveBeenCalledOnce()
    const [calledPath] = mockApiGet.mock.calls[0] ?? []
    expect(calledPath).toBe(`/api/v1/dashboards/${DASHBOARD_ID}`)
    expect(result.id).toBe(DASHBOARD_ID)
  })

  it('404이면 ApiError를 전파한다', async () => {
    mockApiGet.mockRejectedValueOnce(new ApiError(404, { errorCode: 'NOTIF_DASHBOARD_NOT_FOUND' }))
    await expect(getDashboard(DASHBOARD_ID)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('createDashboard', () => {
  it('apiFetch POST + X-XSRF-TOKEN 헤더를 포함한다', async () => {
    const mockRes = {
      ok: true,
      json: vi.fn().mockResolvedValue({ data: dashboardWithDescription }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    const result = await createDashboard({
      name: '새 대시보드',
      visibility: 'PRIVATE',
    })

    expect(mockApiFetch).toHaveBeenCalledOnce()
    const [calledPath, calledOptions] = mockApiFetch.mock.calls[0] ?? []
    expect(calledPath).toBe('/api/v1/dashboards')
    expect((calledOptions as { method?: string }).method).toBe('POST')
    expect(
      (calledOptions as { headers?: Record<string, string> }).headers?.['X-XSRF-TOKEN'],
    ).toBe('test-xsrf-token')
    expect(result.id).toBe(DASHBOARD_ID)
  })

  it('비-2xx 시 ApiError를 throw한다', async () => {
    const mockRes = {
      ok: false,
      status: 400,
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_INVALID' }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await expect(
      createDashboard({ name: '', visibility: 'PRIVATE' }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

describe('patchDashboard', () => {
  it('apiFetch PATCH + X-XSRF-TOKEN 헤더 + version 필드를 포함한다', async () => {
    const mockRes = {
      ok: true,
      json: vi.fn().mockResolvedValue({ data: dashboardWithDescription }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    const result = await patchDashboard(DASHBOARD_ID, { name: '수정됨', version: 1 })

    expect(mockApiFetch).toHaveBeenCalledOnce()
    const [calledPath, calledOptions] = mockApiFetch.mock.calls[0] ?? []
    expect(calledPath).toBe(`/api/v1/dashboards/${DASHBOARD_ID}`)
    expect((calledOptions as { method?: string }).method).toBe('PATCH')
    expect(
      (calledOptions as { headers?: Record<string, string> }).headers?.['X-XSRF-TOKEN'],
    ).toBe('test-xsrf-token')
    const body = (calledOptions as { body?: unknown }).body as Record<string, unknown>
    expect(body['version']).toBe(1)
    expect(result.id).toBe(DASHBOARD_ID)
  })

  it('409 충돌 시 ApiError를 throw한다', async () => {
    const mockRes = {
      ok: false,
      status: 409,
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_CONFLICT' }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await expect(
      patchDashboard(DASHBOARD_ID, { version: 0 }),
    ).rejects.toBeInstanceOf(ApiError)
  })
})

describe('deleteDashboard', () => {
  it('apiFetch DELETE + X-XSRF-TOKEN 헤더를 포함한다', async () => {
    const mockRes = {
      ok: true,
      json: vi.fn().mockResolvedValue(null),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await deleteDashboard(DASHBOARD_ID)

    expect(mockApiFetch).toHaveBeenCalledOnce()
    const [calledPath, calledOptions] = mockApiFetch.mock.calls[0] ?? []
    expect(calledPath).toBe(`/api/v1/dashboards/${DASHBOARD_ID}`)
    expect((calledOptions as { method?: string }).method).toBe('DELETE')
    expect(
      (calledOptions as { headers?: Record<string, string> }).headers?.['X-XSRF-TOKEN'],
    ).toBe('test-xsrf-token')
  })

  it('비-2xx 시 ApiError를 throw한다', async () => {
    const mockRes = {
      ok: false,
      status: 404,
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_NOT_FOUND' }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await expect(deleteDashboard(DASHBOARD_ID)).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 공유 토큰 CRUD API 함수 단위 테스트 (FR-DB-03 D6/D7)
// ─────────────────────────────────────────────────────────────────────────────

describe('issueShareToken', () => {
  it('apiFetch POST + X-XSRF-TOKEN 헤더 + expiresAt 바디를 포함한다', async () => {
    const mockRes = {
      ok: true,
      json: vi.fn().mockResolvedValue({ data: issuedShareTokenFixture }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    const result = await issueShareToken(DASHBOARD_ID, { expiresAt: '2026-08-01T00:00:00Z' })

    expect(mockApiFetch).toHaveBeenCalledOnce()
    const [calledPath, calledOptions] = mockApiFetch.mock.calls[0] ?? []
    expect(calledPath).toBe(`/api/v1/dashboards/${DASHBOARD_ID}/shares`)
    expect((calledOptions as { method?: string }).method).toBe('POST')
    expect(
      (calledOptions as { headers?: Record<string, string> }).headers?.['X-XSRF-TOKEN'],
    ).toBe('test-xsrf-token')
    const body = (calledOptions as { body?: unknown }).body as Record<string, unknown>
    expect(body['expiresAt']).toBe('2026-08-01T00:00:00Z')
    expect(result.token).toBe(SHARE_TOKEN)
    expect(result.id).toBe(SHARE_ID)
  })

  it('expiresAt 생략 시에도 호출 가능하다 (무기한 발급)', async () => {
    const mockRes = {
      ok: true,
      json: vi.fn().mockResolvedValue({ data: issuedShareTokenFixtureNoExpiry }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    const result = await issueShareToken(DASHBOARD_ID)

    expect(mockApiFetch).toHaveBeenCalledOnce()
    const [, calledOptions] = mockApiFetch.mock.calls[0] ?? []
    const body = (calledOptions as { body?: unknown }).body as Record<string, unknown>
    expect(body['expiresAt']).toBeUndefined()
    expect(result.expiresAt).toBeUndefined()
    expect(result.token).toBe(SHARE_TOKEN)
  })

  it('400 발급 상한 초과 시 ApiError를 throw한다', async () => {
    const mockRes = {
      ok: false,
      status: 400,
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_SHARE_LIMIT_EXCEEDED' }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await expect(issueShareToken(DASHBOARD_ID)).rejects.toBeInstanceOf(ApiError)
  })

  it('403 소유자 아님 시 ApiError를 throw한다', async () => {
    const mockRes = {
      ok: false,
      status: 403,
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_FORBIDDEN' }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await expect(issueShareToken(DASHBOARD_ID)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('listShareTokens', () => {
  it('apiGet으로 GET /api/v1/dashboards/:id/shares를 호출한다', async () => {
    mockApiGet.mockResolvedValueOnce({ data: shareTokenListFixture })

    const result = await listShareTokens(DASHBOARD_ID)

    expect(mockApiGet).toHaveBeenCalledOnce()
    const [calledPath] = mockApiGet.mock.calls[0] ?? []
    expect(calledPath).toBe(`/api/v1/dashboards/${DASHBOARD_ID}/shares`)
    expect(result.items).toHaveLength(1)
    expect(result.items[0]?.id).toBe(SHARE_ID)
  })

  // 회귀가드(응답에 token 필드가 섞여도 노출되지 않음)는 shareTokenSummarySchema
  // describe 블록에서 실제 Zod 파싱으로 검증한다. 이 describe는 apiGet을 완전히
  // mock하므로(vi.mock('./client')) schema.parse가 실제로 실행되지 않아 여기서는
  // 검증할 수 없다(mock이 스키마 파싱을 우회).

  it('403 시 ApiError를 전파한다', async () => {
    mockApiGet.mockRejectedValueOnce(new ApiError(403, { errorCode: 'NOTIF_DASHBOARD_FORBIDDEN' }))
    await expect(listShareTokens(DASHBOARD_ID)).rejects.toBeInstanceOf(ApiError)
  })
})

describe('revokeShareToken', () => {
  it('apiFetch DELETE + X-XSRF-TOKEN 헤더를 포함한다', async () => {
    const mockRes = {
      ok: true,
      json: vi.fn().mockResolvedValue(null),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await revokeShareToken(DASHBOARD_ID, SHARE_ID)

    expect(mockApiFetch).toHaveBeenCalledOnce()
    const [calledPath, calledOptions] = mockApiFetch.mock.calls[0] ?? []
    expect(calledPath).toBe(`/api/v1/dashboards/${DASHBOARD_ID}/shares/${SHARE_ID}`)
    expect((calledOptions as { method?: string }).method).toBe('DELETE')
    expect(
      (calledOptions as { headers?: Record<string, string> }).headers?.['X-XSRF-TOKEN'],
    ).toBe('test-xsrf-token')
  })

  it('404 공유 토큰 미존재 시 ApiError를 throw한다', async () => {
    const mockRes = {
      ok: false,
      status: 404,
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_SHARE_NOT_FOUND' }),
    } as unknown as Response
    mockApiFetch.mockResolvedValueOnce(mockRes)

    await expect(revokeShareToken(DASHBOARD_ID, SHARE_ID)).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 익명 공개 조회 API 함수 단위 테스트 — raw fetch (apiFetch 금지) 회귀가드 포함
// ─────────────────────────────────────────────────────────────────────────────

describe('getPublicDashboard', () => {
  it('raw fetch로 GET /api/v1/public/dashboards/:token을 호출하고 파싱한다', async () => {
    server.use(
      http.get(`/api/v1/public/dashboards/${SHARE_TOKEN}`, () =>
        HttpResponse.json({ data: publicDashboardFixture }, { status: 200 }),
      ),
    )

    const result = await getPublicDashboard(SHARE_TOKEN)

    expect(result.name).toBe(publicDashboardFixture.name)
    expect(result.description).toBe(publicDashboardFixture.description)
    expect(result.layout).toBe(publicDashboardFixture.layout)
  })

  it('회귀가드: apiFetch(mock)가 호출되지 않는다 (raw fetch 사용)', async () => {
    server.use(
      http.get(`/api/v1/public/dashboards/${SHARE_TOKEN}`, () =>
        HttpResponse.json({ data: publicDashboardFixture }, { status: 200 }),
      ),
    )

    await getPublicDashboard(SHARE_TOKEN)

    expect(mockApiFetch).not.toHaveBeenCalled()
  })

  it('404 시 ApiError(404)를 throw한다 (로그인 리다이렉트 없음)', async () => {
    server.use(
      http.get('/api/v1/public/dashboards/invalid-token', () =>
        HttpResponse.json({ errorCode: 'NOTIF_DASHBOARD_NOT_FOUND' }, { status: 404 }),
      ),
    )

    let thrown: unknown
    try {
      await getPublicDashboard('invalid-token')
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })

  it('401을 받아도 /refresh를 호출하지 않는다 (raw fetch — 익명 경로 회귀가드)', async () => {
    let refreshCallCount = 0
    server.use(
      http.get(`/api/v1/public/dashboards/${SHARE_TOKEN}`, () =>
        HttpResponse.json({ errorCode: 'NOTIF_DASHBOARD_NOT_FOUND' }, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount++
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )

    let thrown: unknown
    try {
      await getPublicDashboard(SHARE_TOKEN)
    } catch (e) {
      thrown = e
    }
    expect(refreshCallCount).toBe(0)
    expect(thrown).toBeInstanceOf(ApiError)
  })
})
