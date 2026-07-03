// 보드 퀵필터 API 클라이언트 단위 테스트 — Zod 스키마 계약 + CRUD fetch 함수 검증 (FR-UX-01)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { boardDetailSchema } from '@/api/boards'
import {
  quickFilterSchema,
  createQuickFilter,
  updateQuickFilter,
  deleteQuickFilter,
} from './board-quick-filters'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — Zod v4 RFC4122 UUID 형식 필수
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
const FILTER_ID = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'

const quickFilterFixture = {
  filterId: FILTER_ID,
  name: '내 버그',
  query: 'assignee=c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f&label=bug',
}

// ─────────────────────────────────────────────────────────────────────────────
// quickFilterSchema — 유효/무효 픽스처 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('quickFilterSchema — 유효 픽스처 파싱', () => {
  it('T-QF-1a: filterId/name/query 모두 파싱된다', () => {
    const result = quickFilterSchema.safeParse(quickFilterFixture)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.filterId).toBe(FILTER_ID)
    expect(result.data.name).toBe('내 버그')
    expect(result.data.query).toBe(quickFilterFixture.query)
  })

  it('T-QF-1b: filterId가 UUID 형식이 아니면 거부한다', () => {
    const invalid = { ...quickFilterFixture, filterId: 'not-a-uuid' }
    const result = quickFilterSchema.safeParse(invalid)
    expect(result.success).toBe(false)
  })

  it('T-QF-1c: name 필드 누락 시 거부한다', () => {
    const missing: Record<string, unknown> = { ...quickFilterFixture }
    delete missing['name']
    const result = quickFilterSchema.safeParse(missing)
    expect(result.success).toBe(false)
  })

  it('T-QF-1d: query 필드 누락 시 거부한다', () => {
    const missing: Record<string, unknown> = { ...quickFilterFixture }
    delete missing['query']
    const result = quickFilterSchema.safeParse(missing)
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// createQuickFilter — POST /api/v1/boards/{boardId}/quick-filters
// ─────────────────────────────────────────────────────────────────────────────

describe('createQuickFilter — POST /api/v1/boards/{boardId}/quick-filters', () => {
  it('T-QF-2a: name·query를 body로 POST하고 QuickFilter를 반환한다', async () => {
    let capturedUrl: string | null = null
    let capturedMethod: string | null = null
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/boards/:boardId/quick-filters', async ({ request, params }) => {
        capturedUrl = request.url
        capturedMethod = request.method
        capturedBody = await request.json()
        expect(params['boardId']).toBe(BOARD_ID)
        return HttpResponse.json({ data: quickFilterFixture }, { status: 201 })
      }),
    )
    const result = await createQuickFilter(BOARD_ID, { name: '내 버그', query: 'label=bug' })
    expect(capturedMethod).toBe('POST')
    expect(capturedUrl).toContain(`/api/v1/boards/${BOARD_ID}/quick-filters`)
    expect((capturedBody as Record<string, unknown>)['name']).toBe('내 버그')
    expect((capturedBody as Record<string, unknown>)['query']).toBe('label=bug')
    expect(result.filterId).toBe(FILTER_ID)
    expect(result.name).toBe('내 버그')
  })

  it('T-QF-2b: 400 응답 시 ApiError가 throw된다 (EC1 빈 query)', async () => {
    server.use(
      http.post('/api/v1/boards/:boardId/quick-filters', () =>
        HttpResponse.json({ errorCode: 'AGILE_QUICK_FILTER_EMPTY_QUERY' }, { status: 400 }),
      ),
    )
    await expect(createQuickFilter(BOARD_ID, { name: '빈 필터', query: '' })).rejects.toMatchObject({
      status: 400,
    })
  })

  it('T-QF-2c: 409 응답 시 ApiError가 throw된다 (EC2 이름 중복)', async () => {
    server.use(
      http.post('/api/v1/boards/:boardId/quick-filters', () =>
        HttpResponse.json({ errorCode: 'AGILE_QUICK_FILTER_NAME_CONFLICT' }, { status: 409 }),
      ),
    )
    await expect(
      createQuickFilter(BOARD_ID, { name: '내 버그', query: 'label=bug' }),
    ).rejects.toMatchObject({ status: 409 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateQuickFilter — PATCH /api/v1/boards/{boardId}/quick-filters/{filterId}
// ─────────────────────────────────────────────────────────────────────────────

describe('updateQuickFilter — PATCH /api/v1/boards/{boardId}/quick-filters/{filterId}', () => {
  it('T-QF-3a: PATCH 메서드로 호출하고 수정된 QuickFilter를 반환한다', async () => {
    let capturedMethod: string | null = null
    let capturedBody: unknown = null
    const updated = { ...quickFilterFixture, name: '긴급 버그' }
    server.use(
      http.patch('/api/v1/boards/:boardId/quick-filters/:filterId', async ({ request, params }) => {
        capturedMethod = request.method
        capturedBody = await request.json()
        expect(params['boardId']).toBe(BOARD_ID)
        expect(params['filterId']).toBe(FILTER_ID)
        return HttpResponse.json({ data: updated })
      }),
    )
    const result = await updateQuickFilter(BOARD_ID, FILTER_ID, {
      name: '긴급 버그',
      query: 'label=bug',
    })
    expect(capturedMethod).toBe('PATCH')
    expect((capturedBody as Record<string, unknown>)['name']).toBe('긴급 버그')
    expect(result.name).toBe('긴급 버그')
  })

  it('T-QF-3b: 404 응답 시 ApiError가 throw된다 (EC5 타 보드 소속)', async () => {
    server.use(
      http.patch('/api/v1/boards/:boardId/quick-filters/:filterId', () =>
        HttpResponse.json({ errorCode: 'AGILE_QUICK_FILTER_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(
      updateQuickFilter(BOARD_ID, FILTER_ID, { name: '내 버그', query: 'label=bug' }),
    ).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteQuickFilter — DELETE /api/v1/boards/{boardId}/quick-filters/{filterId}
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteQuickFilter — DELETE /api/v1/boards/{boardId}/quick-filters/{filterId}', () => {
  it('T-QF-4a: DELETE 메서드로 호출하고 204 성공 시 아무것도 반환하지 않는다', async () => {
    let capturedMethod: string | null = null
    server.use(
      http.delete('/api/v1/boards/:boardId/quick-filters/:filterId', ({ request, params }) => {
        capturedMethod = request.method
        expect(params['boardId']).toBe(BOARD_ID)
        expect(params['filterId']).toBe(FILTER_ID)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await expect(deleteQuickFilter(BOARD_ID, FILTER_ID)).resolves.toBeUndefined()
    expect(capturedMethod).toBe('DELETE')
  })

  it('T-QF-4b: 403 응답 시 ApiError가 throw된다 (CREATE 권한 없음)', async () => {
    server.use(
      http.delete('/api/v1/boards/:boardId/quick-filters/:filterId', () =>
        HttpResponse.json({ errorCode: 'AGILE_QUICK_FILTER_FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(deleteQuickFilter(BOARD_ID, FILTER_ID)).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// boardDetailSchema — quickFilters 필드 통합 (FR-UX-01)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardDetailSchema — quickFilters 필드 (FR-UX-01)', () => {
  const baseBoard = {
    boardId: BOARD_ID,
    projectKey: 'ATLAS',
    name: 'ATLAS 보드',
    columns: [],
    truncated: false,
    unplacedCount: 0,
    swimlaneField: 'NONE' as const,
  }

  it('T-QF-5a: quickFilters 필드가 없으면 빈 배열로 기본값 처리된다 (레거시 응답·인라인 mock 방어)', () => {
    const result = boardDetailSchema.safeParse(baseBoard)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.quickFilters).toEqual([])
  })

  it('T-QF-5b: quickFilters 배열이 있으면 QuickFilter[]로 파싱된다', () => {
    const withFilters = { ...baseBoard, quickFilters: [quickFilterFixture] }
    const result = boardDetailSchema.safeParse(withFilters)
    expect(result.success).toBe(true)
    if (!result.success) return
    expect(result.data.quickFilters).toHaveLength(1)
    expect(result.data.quickFilters[0]?.filterId).toBe(FILTER_ID)
  })

  it('T-QF-5c: quickFilters 항목 중 filterId가 잘못되면 전체 파싱이 거부된다', () => {
    const invalid = { ...baseBoard, quickFilters: [{ ...quickFilterFixture, filterId: 'bad' }] }
    const result = boardDetailSchema.safeParse(invalid)
    expect(result.success).toBe(false)
  })
})
