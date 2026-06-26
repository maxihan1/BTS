// 저장된 필터 API 클라이언트 + Zod 스키마 단위 테스트 — FR-SR-03 Task-1
import { describe, it, expect, beforeEach } from 'vitest'
import { z } from 'zod'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  savedFilterSchema,
  shareDtoSchema,
  fetchOwnedFilters,
  fetchSharedFilters,
  fetchFilter,
  createFilter,
  updateFilter,
  deleteFilter,
  SAVED_FILTER_ERROR_CODES,
} from './saved-filters'

// ─────────────────────────────────────────────────────────────────────────────
// Fixtures — RFC4122 v4 UUID (Zod v4 엄격성 대응)
// ─────────────────────────────────────────────────────────────────────────────

const FILTER_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const OWNER_ID = 'f0e9d8c7-b6a5-4321-8edc-ba9876543210'
const TARGET_ID = 'c0ffee00-1234-4567-89ab-cd1234567890'

const shareFixture = {
  shareType: 'PROJECT',
  targetId: TARGET_ID,
}

const savedFilterFixture = {
  id: FILTER_ID,
  ownerId: OWNER_ID,
  name: '미완료 이슈 필터',
  aqlQuery: 'status != "DONE"',
  projectKey: 'ATLAS',
  createdAt: '2024-06-25T09:00:00Z',
  updatedAt: '2024-06-25T10:00:00Z',
  version: 1,
  isOwner: true,
  shares: [shareFixture],
}

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-1. shareDtoSchema — (e)(f) nullable targetId + shareType enum 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('shareDtoSchema — ShareDto Zod 파싱 (e)(f)', () => {
  it('T-SF-1a: PROJECT shareType + targetId 문자열이 파싱된다', () => {
    const result = shareDtoSchema.safeParse(shareFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.shareType).toBe('PROJECT')
    expect(result.data.targetId).toBe(TARGET_ID)
  })

  it('T-SF-1b: GROUP shareType이 파싱된다', () => {
    const result = shareDtoSchema.safeParse({ shareType: 'GROUP', targetId: TARGET_ID })
    expect(result.success).toBe(true)
  })

  it('T-SF-1c: (e) AUTHENTICATED shareType + targetId:null이 파싱된다 — .nullable()', () => {
    const result = shareDtoSchema.safeParse({ shareType: 'AUTHENTICATED', targetId: null })
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.targetId).toBeNull()
  })

  it('T-SF-1d: (f) 알 수 없는 shareType은 Zod가 거부한다', () => {
    const result = shareDtoSchema.safeParse({ shareType: 'INTERNAL', targetId: null })
    expect(result.success).toBe(false)
  })

  it('T-SF-1e: (f) shareType 누락 시 Zod가 거부한다', () => {
    const result = shareDtoSchema.safeParse({ targetId: null })
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-2. savedFilterSchema — (e) createdAt:null + (f) invalid shareType 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('savedFilterSchema — SavedFilterResponse Zod 파싱 (e)(f)', () => {
  it('T-SF-2a: 모든 필드가 채워진 응답이 파싱된다', () => {
    const result = savedFilterSchema.safeParse(savedFilterFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.id).toBe(FILTER_ID)
    expect(result.data.name).toBe('미완료 이슈 필터')
    expect(result.data.version).toBe(1)
    expect(result.data.isOwner).toBe(true)
    expect(result.data.shares).toHaveLength(1)
  })

  it('T-SF-2b: (e) createdAt:null이 파싱된다 — .nullable()', () => {
    const withNullDates = { ...savedFilterFixture, createdAt: null, updatedAt: null }
    const result = savedFilterSchema.safeParse(withNullDates)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.createdAt).toBeNull()
    expect(result.data.updatedAt).toBeNull()
  })

  it('T-SF-2c: (e) shares[].targetId:null이 파싱된다 — .nullable()', () => {
    const withNullTarget = {
      ...savedFilterFixture,
      shares: [{ shareType: 'AUTHENTICATED', targetId: null }],
    }
    const result = savedFilterSchema.safeParse(withNullTarget)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.shares[0]?.targetId).toBeNull()
  })

  it('T-SF-2d: (f) shares[].shareType이 알 수 없는 값이면 Zod가 거부한다', () => {
    const withInvalidType = {
      ...savedFilterFixture,
      shares: [{ shareType: 'UNKNOWN', targetId: null }],
    }
    expect(savedFilterSchema.safeParse(withInvalidType).success).toBe(false)
  })

  it('T-SF-2e: 필수 필드 누락 시 Zod가 거부한다', () => {
    const base: Record<string, unknown> = { ...savedFilterFixture }
    const required = ['id', 'ownerId', 'name', 'aqlQuery', 'projectKey', 'version', 'isOwner', 'shares']
    for (const key of required) {
      const without = Object.fromEntries(Object.entries(base).filter(([k]) => k !== key))
      expect(savedFilterSchema.safeParse(without).success, `"${key}" 누락 시 실패해야 함`).toBe(false)
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-3. fetchOwnedFilters — (a) GET /api/v1/filters bare array 단언
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchOwnedFilters — (a) GET /api/v1/filters bare array', () => {
  it('T-SF-3a: 성공 시 SavedFilterResponse 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/filters', () => HttpResponse.json([savedFilterFixture])),
    )
    const result = await fetchOwnedFilters()
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(FILTER_ID)
  })

  it('T-SF-3b: (a) {data:[...]} 래퍼가 있으면 Zod가 거부한다 — bare array 계약 명시적 단언', () => {
    const wrapped = { data: [savedFilterFixture] }
    expect(z.array(savedFilterSchema).safeParse(wrapped).success).toBe(false)
  })

  it('T-SF-3c: (a) {content:[...], totalElements} Page 래퍼가 있으면 Zod가 거부한다', () => {
    const paginated = { content: [savedFilterFixture], totalElements: 1 }
    expect(z.array(savedFilterSchema).safeParse(paginated).success).toBe(false)
  })

  it('T-SF-3d: 빈 배열을 반환하면 []를 반환한다', async () => {
    server.use(
      http.get('/api/v1/filters', () => HttpResponse.json([])),
    )
    const result = await fetchOwnedFilters()
    expect(result).toHaveLength(0)
  })

  it('T-SF-3e: 비-2xx 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/filters', () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.UNAUTHENTICATED },
          { status: 401 },
        ),
      ),
    )
    await expect(fetchOwnedFilters()).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-4. fetchSharedFilters — (b) bare array + page/size 파라미터
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchSharedFilters — (b) GET /api/v1/filters/shared bare array', () => {
  it('T-SF-4a: 성공 시 SavedFilterResponse 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/filters/shared', () => HttpResponse.json([savedFilterFixture])),
    )
    const result = await fetchSharedFilters(0, 50)
    expect(Array.isArray(result)).toBe(true)
    expect(result).toHaveLength(1)
    expect(result[0]?.id).toBe(FILTER_ID)
  })

  it('T-SF-4b: (b) page/size 쿼리 파라미터가 요청에 포함된다', async () => {
    let capturedSearch = ''
    server.use(
      http.get('/api/v1/filters/shared', ({ request }) => {
        capturedSearch = new URL(request.url).search
        return HttpResponse.json([savedFilterFixture])
      }),
    )
    await fetchSharedFilters(0, 50)
    expect(capturedSearch).toContain('page=0')
    expect(capturedSearch).toContain('size=50')
  })

  it('T-SF-4c: (b) 소유 필터와 동일한 z.array(savedFilterSchema) — {data:[...]} 래퍼 거부', () => {
    const wrapped = { data: [savedFilterFixture] }
    expect(z.array(savedFilterSchema).safeParse(wrapped).success).toBe(false)
  })

  it('T-SF-4d: (b) totalElements 없음 — Page 래퍼 거부', () => {
    const paginated = { content: [savedFilterFixture], totalElements: 1 }
    expect(z.array(savedFilterSchema).safeParse(paginated).success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-5. fetchFilter — (c) GET /api/v1/filters/{id}
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchFilter — (c) GET /api/v1/filters/{id}', () => {
  beforeEach(() => {
    server.use(
      http.get(`/api/v1/filters/${FILTER_ID}`, () => HttpResponse.json(savedFilterFixture)),
    )
  })

  it('T-SF-5a: 성공 시 SavedFilterResponse를 반환한다', async () => {
    const result = await fetchFilter(FILTER_ID)
    expect(result.id).toBe(FILTER_ID)
    expect(result.name).toBe('미완료 이슈 필터')
    expect(result.isOwner).toBe(true)
    expect(result.shares).toHaveLength(1)
  })

  it('T-SF-5b: 404 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/filters/${FILTER_ID}`, () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.NOT_FOUND },
          { status: 404 },
        ),
      ),
    )
    await expect(fetchFilter(FILTER_ID)).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-6. createFilter — (d) POST /api/v1/filters 201
// ─────────────────────────────────────────────────────────────────────────────

describe('createFilter — (d) POST /api/v1/filters', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/filters', () => HttpResponse.json(savedFilterFixture, { status: 201 })),
    )
  })

  it('T-SF-6a: 성공 시 SavedFilterResponse를 반환한다', async () => {
    const result = await createFilter({
      name: '미완료 이슈 필터',
      aqlQuery: 'status != "DONE"',
      projectKey: 'ATLAS',
    })
    expect(result.id).toBe(FILTER_ID)
    expect(result.name).toBe('미완료 이슈 필터')
  })

  it('T-SF-6b: shares 없이 PRIVATE 필터 생성이 가능하다', async () => {
    const result = await createFilter({
      name: '비공개 필터',
      aqlQuery: 'status = "TODO"',
      projectKey: 'ATLAS',
    })
    expect(result.id).toBe(FILTER_ID)
  })

  it('T-SF-6c: 이름 충돌(409) 시 ApiError를 throw한다', async () => {
    server.use(
      http.post('/api/v1/filters', () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.NAME_CONFLICT },
          { status: 409 },
        ),
      ),
    )
    await expect(
      createFilter({ name: '중복 이름', aqlQuery: '', projectKey: 'ATLAS' }),
    ).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-7. updateFilter — (d) PUT /api/v1/filters/{id} 200
// ─────────────────────────────────────────────────────────────────────────────

describe('updateFilter — (d) PUT /api/v1/filters/{id}', () => {
  beforeEach(() => {
    server.use(
      http.put(`/api/v1/filters/${FILTER_ID}`, () => HttpResponse.json(savedFilterFixture)),
    )
  })

  it('T-SF-7a: 성공 시 SavedFilterResponse를 반환한다', async () => {
    const result = await updateFilter(FILTER_ID, {
      name: '수정된 필터',
      aqlQuery: 'status = "IN_PROGRESS"',
      version: 1,
    })
    expect(result.id).toBe(FILTER_ID)
  })

  it('T-SF-7b: OCC 충돌(409) 시 ApiError를 throw한다', async () => {
    server.use(
      http.put(`/api/v1/filters/${FILTER_ID}`, () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.CONFLICT },
          { status: 409 },
        ),
      ),
    )
    await expect(
      updateFilter(FILTER_ID, { name: '필터', aqlQuery: '', version: 0 }),
    ).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-8. deleteFilter — (d) DELETE /api/v1/filters/{id} 204
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteFilter — (d) DELETE /api/v1/filters/{id} 204', () => {
  beforeEach(() => {
    server.use(
      http.delete(`/api/v1/filters/${FILTER_ID}`, () => new HttpResponse(null, { status: 204 })),
    )
  })

  it('T-SF-8a: 성공 시 204 → undefined를 반환한다', async () => {
    const result = await deleteFilter(FILTER_ID)
    expect(result).toBeUndefined()
  })

  it('T-SF-8b: 403 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.delete(`/api/v1/filters/${FILTER_ID}`, () =>
        HttpResponse.json(
          { errorCode: SAVED_FILTER_ERROR_CODES.FORBIDDEN },
          { status: 403 },
        ),
      ),
    )
    await expect(deleteFilter(FILTER_ID)).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-SF-9. SAVED_FILTER_ERROR_CODES — 에러 코드 상수 계약 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('SAVED_FILTER_ERROR_CODES — 백엔드 에러코드 상수 계약', () => {
  it('VALIDATION_FAILED가 SEARCH_VALIDATION_FAILED다', () => {
    expect(SAVED_FILTER_ERROR_CODES.VALIDATION_FAILED).toBe('SEARCH_VALIDATION_FAILED')
  })

  it('SYNTAX_ERROR가 SEARCH_SYNTAX_ERROR다', () => {
    expect(SAVED_FILTER_ERROR_CODES.SYNTAX_ERROR).toBe('SEARCH_SYNTAX_ERROR')
  })

  it('FORBIDDEN이 SEARCH_FILTER_FORBIDDEN다', () => {
    expect(SAVED_FILTER_ERROR_CODES.FORBIDDEN).toBe('SEARCH_FILTER_FORBIDDEN')
  })

  it('NOT_FOUND가 SEARCH_FILTER_NOT_FOUND다', () => {
    expect(SAVED_FILTER_ERROR_CODES.NOT_FOUND).toBe('SEARCH_FILTER_NOT_FOUND')
  })

  it('NAME_CONFLICT가 SEARCH_FILTER_NAME_CONFLICT다', () => {
    expect(SAVED_FILTER_ERROR_CODES.NAME_CONFLICT).toBe('SEARCH_FILTER_NAME_CONFLICT')
  })

  it('CONFLICT가 SEARCH_FILTER_CONFLICT다 (OCC)', () => {
    expect(SAVED_FILTER_ERROR_CODES.CONFLICT).toBe('SEARCH_FILTER_CONFLICT')
  })

  it('UNAUTHENTICATED가 SEARCH_UNAUTHENTICATED다', () => {
    expect(SAVED_FILTER_ERROR_CODES.UNAUTHENTICATED).toBe('SEARCH_UNAUTHENTICATED')
  })
})
