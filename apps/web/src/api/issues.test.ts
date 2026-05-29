// issue-tracking BC API client 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  issueResponseSchema,
  issueTransitionSchema,
  fetchIssue,
  fetchIssues,
  createIssue,
  updateIssue,
  deleteIssue,
  fetchIssueTransitions,
  transitionIssue,
} from './issues'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — IssueResponse 12 필드 + nullable timestamps
// ─────────────────────────────────────────────────────────────────────────────
const issueFixture = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  summary: '로그인 버튼이 클릭되지 않는 버그',
  currentStateKey: 'open',
  reporterId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  version: 1,
  createdAt: '2024-01-15T09:00:00Z',
  updatedAt: '2024-01-15T10:30:00Z',
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
}

const issueFixtureNullTimestamps = {
  ...issueFixture,
  key: 'ATLAS-2',
  createdAt: null,
  updatedAt: null,
}

const pageFixture = {
  content: [issueFixture, issueFixtureNullTimestamps],
  totalElements: 2,
  totalPages: 1,
  size: 20,
  number: 0,
  first: true,
  last: true,
  empty: false,
}

beforeEach(() => {
  server.use(
    // POST /api/v1/issues — 201 생성
    http.post('/api/v1/issues', async ({ request }) => {
      const body = await request.json() as { projectKey?: string; summary?: string }
      return HttpResponse.json(
        {
          data: {
            ...issueFixture,
            projectKey: body.projectKey ?? issueFixture.projectKey,
            summary: body.summary ?? issueFixture.summary,
          },
        },
        { status: 201 },
      )
    }),

    // GET /api/v1/issues/:key — 단건 조회
    http.get('/api/v1/issues/:key', ({ params }) => {
      if (params['key'] === issueFixture.key) {
        return HttpResponse.json({ data: issueFixture })
      }
      return HttpResponse.json({ message: 'Not Found' }, { status: 404 })
    }),

    // GET /api/v1/issues — 목록 (Spring Page, 래퍼 없음)
    http.get('/api/v1/issues', () => {
      return HttpResponse.json(pageFixture)
    }),

    // PATCH /api/v1/issues/:key — 수정
    http.patch('/api/v1/issues/:key', async ({ request, params }) => {
      const body = await request.json() as { summary?: string; expectedVersion?: number }
      return HttpResponse.json({
        data: {
          ...issueFixture,
          key: params['key'] as string,
          summary: body.summary ?? issueFixture.summary,
          version: (body.expectedVersion ?? issueFixture.version) + 1,
        },
      })
    }),

    // DELETE /api/v1/issues/:key — 204
    http.delete('/api/v1/issues/:key', () => {
      return new HttpResponse(null, { status: 204 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-1. issueResponseSchema — 12 필드 파싱 + nullable timestamps
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema', () => {
  it('T1-1a: 12 필드가 모두 있는 IssueResponse를 파싱한다', () => {
    const result = issueResponseSchema.parse(issueFixture)

    expect(result.key).toBe('ATLAS-1')
    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.projectKey).toBe('ATLAS')
    expect(result.summary).toBe('로그인 버튼이 클릭되지 않는 버그')
    expect(result.currentStateKey).toBe('open')
    expect(result.reporterId).toBe('f0e9d8c7-b6a5-4321-8edc-ba9876543210')
    expect(result.version).toBe(1)
    expect(result.createdAt).toBe('2024-01-15T09:00:00Z')
    expect(result.updatedAt).toBe('2024-01-15T10:30:00Z')
    expect(result.typeId).toBe(1)
    expect(result.typeKey).toBe('bug')
    expect(result.typeName).toBe('버그')
  })

  it('T1-1b: createdAt/updatedAt이 null인 경우도 파싱 성공', () => {
    const result = issueResponseSchema.parse(issueFixtureNullTimestamps)

    expect(result.createdAt).toBeNull()
    expect(result.updatedAt).toBeNull()
  })

  it('T1-1c: 필수 필드 누락 시 ZodError throw', () => {
    expect(() => issueResponseSchema.parse({ key: 'ATLAS-1' })).toThrow()
  })

  it('T1-1d: typeId가 없으면 ZodError를 throw한다', () => {
    const withoutTypeId = { ...issueFixture, typeId: undefined }
    expect(() => issueResponseSchema.parse(withoutTypeId)).toThrow()
  })

  it('T1-1e: typeKey가 빈 문자열이면 ZodError를 throw한다', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, typeKey: '' })).toThrow()
  })

  it('T1-1f: typeName이 빈 문자열이면 ZodError를 throw한다', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, typeName: '' })).toThrow()
  })

  it('T1-1g: typeId가 양수 정수가 아니면 ZodError를 throw한다', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, typeId: 0 })).toThrow()
    expect(() => issueResponseSchema.parse({ ...issueFixture, typeId: -1 })).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-2. fetchIssue — GET /{key}, { data: IssueResponse } 언래핑
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssue', () => {
  it('T1-2a: 존재하는 key로 단건 조회 시 IssueResponse를 반환한다', async () => {
    const result = await fetchIssue('ATLAS-1')

    expect(result.key).toBe('ATLAS-1')
    expect(result.summary).toBe('로그인 버튼이 클릭되지 않는 버그')
    expect(result.version).toBe(1)
  })

  it('T1-2b: 없는 key 조회 시 ApiError(404)를 throw한다', async () => {
    await expect(fetchIssue('NOT-EXISTS')).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-3. fetchIssues — GET /?projectKey=&page=&size=, Page<IssueResponse>
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssues', () => {
  it('T1-3a: 목록 조회 시 Spring Page 구조를 파싱해 반환한다', async () => {
    const result = await fetchIssues({ projectKey: 'ATLAS', page: 0, size: 20 })

    expect(result.content).toHaveLength(2)
    expect(result.totalElements).toBe(2)
    expect(result.totalPages).toBe(1)
    expect(result.first).toBe(true)
    expect(result.last).toBe(true)
  })

  it('T1-3b: content 배열 각 항목이 IssueResponse 스키마를 통과한다', async () => {
    const result = await fetchIssues({ projectKey: 'ATLAS', page: 0, size: 20 })

    for (const issue of result.content) {
      expect(typeof issue.key).toBe('string')
      expect(typeof issue.id).toBe('string')
      expect(typeof issue.projectKey).toBe('string')
      expect(typeof issue.summary).toBe('string')
      expect(typeof issue.currentStateKey).toBe('string')
      expect(typeof issue.reporterId).toBe('string')
      expect(typeof issue.version).toBe('number')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-4. createIssue — POST / body { projectKey, summary } → 201 { data: IssueResponse }
// ─────────────────────────────────────────────────────────────────────────────
describe('createIssue', () => {
  it('T1-4a: 올바른 body로 POST 호출 시 생성된 IssueResponse를 반환한다', async () => {
    const result = await createIssue({ projectKey: 'ATLAS', summary: '신규 이슈' })

    expect(result.projectKey).toBe('ATLAS')
    expect(result.summary).toBe('신규 이슈')
    expect(typeof result.key).toBe('string')
    expect(typeof result.id).toBe('string')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-5. updateIssue — PATCH /{key} body { summary?, typeId?, expectedVersion }
// ─────────────────────────────────────────────────────────────────────────────
describe('updateIssue', () => {
  it('T1-5a: summary와 expectedVersion으로 PATCH 호출 시 수정된 IssueResponse를 반환한다', async () => {
    const result = await updateIssue('ATLAS-1', { summary: '수정된 제목', expectedVersion: 1 })

    expect(result.key).toBe('ATLAS-1')
    expect(result.summary).toBe('수정된 제목')
    expect(result.version).toBe(2)
  })

  it('T1-5b: summary 없이 expectedVersion만 전달해도 동작한다', async () => {
    const result = await updateIssue('ATLAS-1', { expectedVersion: 1 })

    expect(result.key).toBe('ATLAS-1')
    expect(typeof result.version).toBe('number')
  })

  it('T1-5c: typeId를 PATCH body에 포함해 전달할 수 있다', async () => {
    let capturedBody: Record<string, unknown> = {}

    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: {
            ...issueFixture,
            key: params['key'] as string,
            typeId: capturedBody['typeId'] ?? issueFixture.typeId,
            typeKey: capturedBody['typeKey'] ?? issueFixture.typeKey,
            typeName: capturedBody['typeName'] ?? issueFixture.typeName,
          },
        })
      }),
    )

    await updateIssue('ATLAS-1', { typeId: 2, expectedVersion: 1 })

    expect(capturedBody['typeId']).toBe(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-6. deleteIssue — DELETE /{key} → 204 no content
// ─────────────────────────────────────────────────────────────────────────────
describe('deleteIssue', () => {
  it('T1-6a: DELETE 호출 시 에러 없이 완료된다 (204 no content)', async () => {
    await expect(deleteIssue('ATLAS-1')).resolves.toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-7. issueTransitionSchema — 전이 항목 Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('issueTransitionSchema', () => {
  it('T1-7a: 4개 string 필드가 모두 있는 전이 항목을 파싱한다', () => {
    const raw = { fromStateKey: 'open', toStateKey: 'in_progress', name: '작업 시작', key: 'open__in_progress' }
    const result = issueTransitionSchema.parse(raw)
    expect(result.fromStateKey).toBe('open')
    expect(result.toStateKey).toBe('in_progress')
    expect(result.name).toBe('작업 시작')
    expect(result.key).toBe('open__in_progress')
  })

  it('T1-7b: 빈 문자열 필드가 있으면 ZodError를 throw한다', () => {
    expect(() => issueTransitionSchema.parse({ fromStateKey: '', toStateKey: 'in_progress', name: '작업 시작', key: 'open__in_progress' })).toThrow()
  })

  it('T1-7c: 필드 누락 시 ZodError를 throw한다', () => {
    expect(() => issueTransitionSchema.parse({ fromStateKey: 'open', toStateKey: 'in_progress' })).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-8. fetchIssueTransitions — GET /{key}/transitions, { data: { transitions: [...] } }
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssueTransitions', () => {
  const transitionsFixture = [
    { fromStateKey: 'open', toStateKey: 'in_progress', name: '작업 시작', key: 'open__in_progress' },
    { fromStateKey: 'in_progress', toStateKey: 'done', name: '완료', key: 'in_progress__done' },
  ]

  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:key/transitions', ({ params }) => {
        if (params['key'] === 'ATLAS-1') {
          return HttpResponse.json({ data: { transitions: transitionsFixture } })
        }
        return HttpResponse.json({ message: 'Not Found' }, { status: 404 })
      }),
    )
  })

  it('T1-8a: 존재하는 이슈 key로 전이 목록을 조회해 배열로 반환한다', async () => {
    const result = await fetchIssueTransitions('ATLAS-1')
    expect(result).toHaveLength(2)
    expect(result[0]?.fromStateKey).toBe('open')
    expect(result[0]?.toStateKey).toBe('in_progress')
    expect(result[0]?.name).toBe('작업 시작')
    expect(result[0]?.key).toBe('open__in_progress')
  })

  it('T1-8b: 없는 key 조회 시 ApiError(404)를 throw한다', async () => {
    await expect(fetchIssueTransitions('NOT-EXISTS')).rejects.toThrow()
  })

  it('T1-8c: 전이가 없는 이슈의 경우 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )
    const result = await fetchIssueTransitions('ATLAS-1')
    expect(result).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-9. transitionIssue — POST /{key}/transition body { toStatusKey, expectedVersion }
// ─────────────────────────────────────────────────────────────────────────────
describe('transitionIssue', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/transition', async ({ request, params }) => {
        const body = await request.json() as { toStatusKey?: string; expectedVersion?: number }
        if (params['key'] === 'ATLAS-1') {
          return HttpResponse.json({
            data: {
              ...issueFixture,
              currentStateKey: body.toStatusKey ?? issueFixture.currentStateKey,
              version: (body.expectedVersion ?? issueFixture.version) + 1,
            },
          })
        }
        return HttpResponse.json({ message: 'Not Found' }, { status: 404 })
      }),
    )
  })

  it('T1-9a: 정상 전이 요청 시 변경된 상태키와 증가된 version을 가진 IssueResponse를 반환한다', async () => {
    const result = await transitionIssue('ATLAS-1', { toStatusKey: 'in_progress', expectedVersion: 1 })
    expect(result.currentStateKey).toBe('in_progress')
    expect(result.version).toBe(2)
  })

  it('T1-9b: 없는 key로 전이 시 ApiError(404)를 throw한다', async () => {
    await expect(
      transitionIssue('NOT-EXISTS', { toStatusKey: 'in_progress', expectedVersion: 1 }),
    ).rejects.toThrow()
  })

  it('T1-9c: 409 충돌(낙관적 잠금 실패) 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/transition', () =>
        HttpResponse.json({ message: 'Conflict' }, { status: 409 }),
      ),
    )
    await expect(
      transitionIssue('ATLAS-1', { toStatusKey: 'in_progress', expectedVersion: 0 }),
    ).rejects.toSatisfy((e) => e instanceof ApiError && (e as ApiError).status === 409)
  })

  it('T1-9d: request body에 toStatusKey와 expectedVersion이 포함되어 전달된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues/:key/transition', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: { ...issueFixture, currentStateKey: 'done', version: 2 } })
      }),
    )
    await transitionIssue('ATLAS-1', { toStatusKey: 'done', expectedVersion: 1 })
    expect(capturedBody['toStatusKey']).toBe('done')
    expect(capturedBody['expectedVersion']).toBe(1)
  })
})
