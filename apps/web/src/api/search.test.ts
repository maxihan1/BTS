// AQL 검색 API 클라이언트 단위 테스트 — FR-SR-02 D6 Task-3 / FR-EX-02 D6 Task-1
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  searchAql,
  submitExportJob,
  fetchExportJobStatus,
  downloadExportJobResult,
} from './search'

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

// ─────────────────────────────────────────────────────────────────────────────
// 비동기 Export Jobs API 테스트 — FR-EX-02 D6 Task-1
// ─────────────────────────────────────────────────────────────────────────────

// 픽스처 — 백엔드 ExportJobResponse DTO @JsonInclude(NON_NULL) 직렬화 형태
const EXPORT_JOB_ID = '00000000-0000-4000-a000-000000000099'

// PENDING: rowCount/errorCode 키 자체가 없음 (@JsonInclude NON_NULL)
const PENDING_JOB_FIXTURE = {
  jobId: EXPORT_JOB_ID,
  status: 'PENDING',
  progress: 0,
  format: 'CSV',
  downloadReady: false,
}

const RUNNING_JOB_FIXTURE = {
  jobId: EXPORT_JOB_ID,
  status: 'RUNNING',
  progress: 42,
  format: 'CSV',
  downloadReady: false,
}

// COMPLETED: rowCount 존재, errorCode 키 없음
const COMPLETED_JOB_FIXTURE = {
  jobId: EXPORT_JOB_ID,
  status: 'COMPLETED',
  progress: 100,
  rowCount: 15000,
  format: 'CSV',
  downloadReady: true,
}

// FAILED: errorCode 존재, rowCount 키 없음
const FAILED_JOB_FIXTURE = {
  jobId: EXPORT_JOB_ID,
  status: 'FAILED',
  progress: 23,
  errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
  format: 'CSV',
  downloadReady: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// submitExportJob — POST /api/v1/search/export-jobs (202 Accepted)
// ─────────────────────────────────────────────────────────────────────────────

describe('submitExportJob — 202 잡 접수', () => {
  it('jobId와 PENDING 상태를 반환한다', async () => {
    server.use(
      http.post('/api/v1/search/export-jobs', () =>
        HttpResponse.json(
          { jobId: EXPORT_JOB_ID, status: 'PENDING' },
          { status: 202 },
        ),
      ),
    )

    const result = await submitExportJob({
      projectKey: 'ATLAS',
      query: 'status = open',
      format: 'CSV',
    })

    expect(result.jobId).toBe(EXPORT_JOB_ID)
    expect(result.status).toBe('PENDING')
  })

  it('요청 body에 projectKey/query/format이 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/search/export-jobs', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ jobId: EXPORT_JOB_ID, status: 'PENDING' }, { status: 202 })
      }),
    )

    await submitExportJob({
      projectKey: 'ATLAS',
      query: 'priority = 1',
      format: 'XLSX',
      columns: ['KEY', 'SUMMARY'],
    })

    expect(capturedBody).toMatchObject({
      projectKey: 'ATLAS',
      query: 'priority = 1',
      format: 'XLSX',
      columns: ['KEY', 'SUMMARY'],
    })
  })

  it('400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/search/export-jobs', () =>
        HttpResponse.json(
          { errorCode: 'SEARCH_SYNTAX_ERROR', detail: 'Invalid AQL', status: 400 },
          { status: 400 },
        ),
      ),
    )

    await expect(
      submitExportJob({ projectKey: 'ATLAS', query: 'invalid!!', format: 'CSV' }),
    ).rejects.toBeInstanceOf(ApiError)

    await expect(
      submitExportJob({ projectKey: 'ATLAS', query: 'invalid!!', format: 'CSV' }),
    ).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchExportJobStatus — GET /api/v1/search/export-jobs/{id}
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchExportJobStatus — 잡 상태 조회', () => {
  it('PENDING 상태 — rowCount/errorCode 키가 없어도 ZodError 없이 파싱한다 (nullish 검증)', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}`, () =>
        HttpResponse.json(PENDING_JOB_FIXTURE),
      ),
    )

    const result = await fetchExportJobStatus(EXPORT_JOB_ID)

    expect(result.jobId).toBe(EXPORT_JOB_ID)
    expect(result.status).toBe('PENDING')
    expect(result.progress).toBe(0)
    expect(result.downloadReady).toBe(false)
    // nullish: 키 자체가 없으면 undefined — null이 아님
    expect(result.rowCount).toBeUndefined()
    expect(result.errorCode).toBeUndefined()
  })

  it('RUNNING 상태 — progress/status 필드를 정확히 파싱한다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}`, () =>
        HttpResponse.json(RUNNING_JOB_FIXTURE),
      ),
    )

    const result = await fetchExportJobStatus(EXPORT_JOB_ID)

    expect(result.status).toBe('RUNNING')
    expect(result.progress).toBe(42)
    expect(result.downloadReady).toBe(false)
    expect(result.rowCount).toBeUndefined()
  })

  it('COMPLETED 상태 — rowCount가 파싱되고 downloadReady가 true다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}`, () =>
        HttpResponse.json(COMPLETED_JOB_FIXTURE),
      ),
    )

    const result = await fetchExportJobStatus(EXPORT_JOB_ID)

    expect(result.status).toBe('COMPLETED')
    expect(result.progress).toBe(100)
    expect(result.rowCount).toBe(15000)
    expect(result.downloadReady).toBe(true)
    expect(result.errorCode).toBeUndefined()
  })

  it('FAILED 상태 — errorCode가 파싱된다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}`, () =>
        HttpResponse.json(FAILED_JOB_FIXTURE),
      ),
    )

    const result = await fetchExportJobStatus(EXPORT_JOB_ID)

    expect(result.status).toBe('FAILED')
    expect(result.errorCode).toBe('SEARCH_EXPORT_LIMIT_EXCEEDED')
    expect(result.downloadReady).toBe(false)
    expect(result.rowCount).toBeUndefined()
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}`, () =>
        HttpResponse.json(
          { errorCode: 'EXPORT_JOB_NOT_FOUND', status: 404 },
          { status: 404 },
        ),
      ),
    )

    await expect(fetchExportJobStatus(EXPORT_JOB_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchExportJobStatus(EXPORT_JOB_ID)).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// downloadExportJobResult — GET /api/v1/search/export-jobs/{id}/download
// ─────────────────────────────────────────────────────────────────────────────

describe('downloadExportJobResult — 파일 다운로드', () => {
  it('200 응답 시 blob과 filename을 반환한다', async () => {
    const csvContent = 'key,summary\nATLAS-1,테스트 이슈'
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}/download`, () =>
        new HttpResponse(csvContent, {
          status: 200,
          headers: {
            'content-type': 'text/csv; charset=utf-8',
            'content-disposition': 'attachment; filename="ATLAS-export-20260630T000000Z.csv"',
          },
        }),
      ),
    )

    const result = await downloadExportJobResult(EXPORT_JOB_ID)

    // Node.js 테스트 환경에서 instanceof Blob은 클래스 컨텍스트 불일치로 실패할 수 있음
    // blob 객체 속성으로 검증 (jsdom↔실브라우저 selectionStart 메모리 참조)
    expect(result.blob).toBeTruthy()
    expect(result.blob.size).toBeGreaterThan(0)
    expect(result.filename).toBe('ATLAS-export-20260630T000000Z.csv')
  })

  it('Content-Disposition 헤더가 없으면 기본 파일명을 사용한다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}/download`, () =>
        new HttpResponse('data', {
          status: 200,
          headers: { 'content-type': 'application/octet-stream' },
        }),
      ),
    )

    const result = await downloadExportJobResult(EXPORT_JOB_ID)

    expect(result.blob).toBeTruthy()
    expect(result.blob.size).toBeGreaterThan(0)
    expect(typeof result.filename).toBe('string')
    expect(result.filename.length).toBeGreaterThan(0)
  })

  it('409(SEARCH_EXPORT_NOT_READY) 응답 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}/download`, () =>
        HttpResponse.json(
          { errorCode: 'SEARCH_EXPORT_NOT_READY', status: 409 },
          { status: 409 },
        ),
      ),
    )

    await expect(downloadExportJobResult(EXPORT_JOB_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(downloadExportJobResult(EXPORT_JOB_ID)).rejects.toMatchObject({ status: 409 })
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/search/export-jobs/${EXPORT_JOB_ID}/download`, () =>
        HttpResponse.json(
          { errorCode: 'EXPORT_JOB_NOT_FOUND', status: 404 },
          { status: 404 },
        ),
      ),
    )

    await expect(downloadExportJobResult(EXPORT_JOB_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(downloadExportJobResult(EXPORT_JOB_ID)).rejects.toMatchObject({ status: 404 })
  })
})
