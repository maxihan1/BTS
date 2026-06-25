// AQL 검색 API 클라이언트 단위 테스트 — FR-SR-02 D6 Task-3
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import { searchAql } from './search'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 백엔드 AqlSearchHit DTO 1:1 대응 (labels 필드 없음 — invent 금지)
// ─────────────────────────────────────────────────────────────────────────────

const HIT_FIXTURE = {
  key: 'ATLAS-1',
  summary: '로그인 버튼이 동작하지 않음',
  typeKey: 'bug',
  currentStateKey: 'open',
  assigneeId: '00000000-0000-4000-a000-000000000001',
  priority: 2,
  priorityName: 'High',
  projectKey: 'ATLAS',
  updatedAt: '2026-06-25T10:00:00Z',
}

const PAGE_FIXTURE = {
  content: [HIT_FIXTURE],
  totalElements: 1,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
}

const EMPTY_PAGE_FIXTURE = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: true,
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 MSW 핸들러 등록 (각 describe에서 override)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(
    http.post('/api/v1/search/aql', () =>
      HttpResponse.json(PAGE_FIXTURE),
    ),
  )
})

afterEach(() => {
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// 200 성공 — Page<AqlSearchHit> Zod 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('searchAql — 200 성공', () => {
  it('Page<AqlSearchHit> 응답을 Zod로 파싱해 반환한다', async () => {
    const result = await searchAql({ projectKey: 'ATLAS', query: 'status = open' })

    expect(result.content).toHaveLength(1)
    expect(result.totalElements).toBe(1)
    expect(result.totalPages).toBe(1)
    expect(result.size).toBe(20)
    expect(result.number).toBe(0)
    expect(result.first).toBe(true)
    expect(result.last).toBe(true)
    expect(result.empty).toBe(false)
  })

  it('AqlSearchHit 필드 9종(key/summary/typeKey/currentStateKey/assigneeId/priority/priorityName/projectKey/updatedAt)을 정확히 파싱한다', async () => {
    const result = await searchAql({ projectKey: 'ATLAS', query: 'status = open' })
    const hit = result.content[0]

    expect(hit?.key).toBe('ATLAS-1')
    expect(hit?.summary).toBe('로그인 버튼이 동작하지 않음')
    expect(hit?.typeKey).toBe('bug')
    expect(hit?.currentStateKey).toBe('open')
    expect(hit?.assigneeId).toBe('00000000-0000-4000-a000-000000000001')
    expect(typeof hit?.priority).toBe('number')
    expect(hit?.priority).toBe(2)
    expect(hit?.priorityName).toBe('High')
    expect(hit?.projectKey).toBe('ATLAS')
    expect(hit?.updatedAt).toBe('2026-06-25T10:00:00Z')
  })

  it('assigneeId가 null인 경우 nullable로 파싱된다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json({
          ...PAGE_FIXTURE,
          content: [{ ...HIT_FIXTURE, assigneeId: null }],
        }),
      ),
    )

    const result = await searchAql({ projectKey: 'ATLAS', query: 'status = open' })
    expect(result.content[0]?.assigneeId).toBeNull()
  })

  it('0건 결과(empty=true)를 정상 파싱한다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(EMPTY_PAGE_FIXTURE),
      ),
    )

    const result = await searchAql({ projectKey: 'ATLAS', query: 'status = closed' })
    expect(result.content).toHaveLength(0)
    expect(result.empty).toBe(true)
    expect(result.totalElements).toBe(0)
  })

  it('page/size 파라미터가 요청 body에 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/search/aql', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(PAGE_FIXTURE)
      }),
    )

    await searchAql({ projectKey: 'ATLAS', query: 'priority = 1', page: 2, size: 10 })
    expect(capturedBody).toMatchObject({ projectKey: 'ATLAS', query: 'priority = 1', page: 2, size: 10 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 400 — ProblemDetail ApiError throw
// ─────────────────────────────────────────────────────────────────────────────

describe('searchAql — 400 에러', () => {
  it('문법오류(SEARCH_SYNTAX_ERROR) 400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(
          {
            errorCode: 'SEARCH_SYNTAX_ERROR',
            detail: 'Unexpected token at position 7',
            position: 7,
            title: 'AQL syntax error',
            status: 400,
          },
          { status: 400 },
        ),
      ),
    )

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'status === open' }),
    ).rejects.toBeInstanceOf(ApiError)

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'status === open' }),
    ).rejects.toMatchObject({ status: 400 })
  })

  it('400 ApiError의 body에 errorCode/detail/position 봉투가 보존된다', async () => {
    const problemDetail = {
      errorCode: 'SEARCH_SYNTAX_ERROR',
      detail: 'Unexpected token at position 7',
      position: 7,
      title: 'AQL syntax error',
      status: 400,
    }
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(problemDetail, { status: 400 }),
      ),
    )

    let caught: unknown = null
    try {
      await searchAql({ projectKey: 'ATLAS', query: 'bad query' })
    } catch (e) {
      caught = e
    }

    expect(caught).toBeInstanceOf(ApiError)
    const apiError = caught as ApiError
    expect((apiError.body as Record<string, unknown>)['errorCode']).toBe('SEARCH_SYNTAX_ERROR')
    expect((apiError.body as Record<string, unknown>)['detail']).toBe('Unexpected token at position 7')
    expect((apiError.body as Record<string, unknown>)['position']).toBe(7)
  })

  it('미지원 필드(SEARCH_FIELD_NOT_YET_SUPPORTED) 400도 ApiError로 throw한다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(
          {
            errorCode: 'SEARCH_FIELD_NOT_YET_SUPPORTED',
            detail: 'Field "assignee" is not yet supported',
            title: 'Unsupported field',
            status: 400,
          },
          { status: 400 },
        ),
      ),
    )

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'assignee = alice' }),
    ).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 401 — refresh도 실패시켜 진짜 401 분기 도달 (C4)
// ─────────────────────────────────────────────────────────────────────────────

describe('searchAql — 401 미인증', () => {
  it('refresh도 실패하면 ApiError(401)를 throw한다', async () => {
    // search 엔드포인트 → 401 반환
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(
          { errorCode: 'SEARCH_UNAUTHENTICATED' },
          { status: 401 },
        ),
      ),
      // refresh도 401 실패 → apiFetch가 ApiError(401) throw
      http.post('/api/v1/auth/refresh', () =>
        HttpResponse.json(
          { error: 'invalid_grant' },
          { status: 401 },
        ),
      ),
    )

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'status = open' }),
    ).rejects.toBeInstanceOf(ApiError)

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'status = open' }),
    ).rejects.toMatchObject({ status: 401 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 403 — 접근 권한 없음
// ─────────────────────────────────────────────────────────────────────────────

describe('searchAql — 403 권한 없음', () => {
  it('BROWSE 권한 없음(403) 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/search/aql', () =>
        HttpResponse.json(
          {
            errorCode: 'SEARCH_ACCESS_DENIED',
            detail: 'BROWSE permission required',
            title: 'Access denied',
            status: 403,
          },
          { status: 403 },
        ),
      ),
    )

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'status = open' }),
    ).rejects.toBeInstanceOf(ApiError)

    await expect(
      searchAql({ projectKey: 'ATLAS', query: 'status = open' }),
    ).rejects.toMatchObject({ status: 403 })
  })
})
