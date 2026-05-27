// issue-tracking BC API client 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  issueResponseSchema,
  fetchIssue,
  fetchIssues,
  createIssue,
  updateIssue,
  deleteIssue,
} from './issues'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — IssueResponse 9 필드 + nullable timestamps
// ─────────────────────────────────────────────────────────────────────────────
const issueFixture = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  summary: '로그인 버튼이 클릭되지 않는 버그',
  currentStateKey: 'open',
  reporterId: 'f0e9d8c7-b6a5-4321-fedc-ba9876543210',
  version: 1,
  createdAt: '2024-01-15T09:00:00Z',
  updatedAt: '2024-01-15T10:30:00Z',
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
// T1-1. issueResponseSchema — 9 필드 파싱 + nullable timestamps
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema', () => {
  it('T1-1a: 9 필드가 모두 있는 IssueResponse를 파싱한다', () => {
    const result = issueResponseSchema.parse(issueFixture)

    expect(result.key).toBe('ATLAS-1')
    expect(result.id).toBe('a1b2c3d4-e5f6-7890-abcd-ef1234567890')
    expect(result.projectKey).toBe('ATLAS')
    expect(result.summary).toBe('로그인 버튼이 클릭되지 않는 버그')
    expect(result.currentStateKey).toBe('open')
    expect(result.reporterId).toBe('f0e9d8c7-b6a5-4321-fedc-ba9876543210')
    expect(result.version).toBe(1)
    expect(result.createdAt).toBe('2024-01-15T09:00:00Z')
    expect(result.updatedAt).toBe('2024-01-15T10:30:00Z')
  })

  it('T1-1b: createdAt/updatedAt이 null인 경우도 파싱 성공', () => {
    const result = issueResponseSchema.parse(issueFixtureNullTimestamps)

    expect(result.createdAt).toBeNull()
    expect(result.updatedAt).toBeNull()
  })

  it('T1-1c: 필수 필드 누락 시 ZodError throw', () => {
    expect(() => issueResponseSchema.parse({ key: 'ATLAS-1' })).toThrow()
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
// T1-5. updateIssue — PATCH /{key} body { summary?, expectedVersion }
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
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-6. deleteIssue — DELETE /{key} → 204 no content
// ─────────────────────────────────────────────────────────────────────────────
describe('deleteIssue', () => {
  it('T1-6a: DELETE 호출 시 에러 없이 완료된다 (204 no content)', async () => {
    await expect(deleteIssue('ATLAS-1')).resolves.toBeUndefined()
  })
})
