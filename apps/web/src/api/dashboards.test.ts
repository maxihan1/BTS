// 대시보드 API 클라이언트 단위 테스트 — Zod 스키마 파싱 + CRUD 함수 계약 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  dashboardSchema,
  dashboardPageSchema,
  listDashboards,
  getDashboard,
  createDashboard,
  patchDashboard,
  deleteDashboard,
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
      json: vi.fn().mockResolvedValue({ errorCode: 'NOTIF_DASHBOARD_VERSION_CONFLICT' }),
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
