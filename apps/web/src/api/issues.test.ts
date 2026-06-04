// issue-tracking BC API client 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  issueResponseSchema,
  issueTransitionSchema,
  bulkAvailableTransitionsSchema,
  fetchIssue,
  fetchIssues,
  createIssue,
  updateIssue,
  deleteIssue,
  fetchIssueTransitions,
  transitionIssue,
  changeAssignee,
  fetchBulkAvailableTransitions,
  downloadIssuePdf,
} from './issues'
import { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — IssueResponse 21 필드 (12 기존 + 8 FR-IS-04 + 1 FR-IS-03 assigneeId) + nullable timestamps
// ─────────────────────────────────────────────────────────────────────────────
const issueFixture = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  summary: '로그인 버튼이 클릭되지 않는 버그',
  currentStateKey: 'open',
  reporterId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  assigneeId: null,
  version: 1,
  createdAt: '2024-01-15T09:00:00Z',
  updatedAt: '2024-01-15T10:30:00Z',
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  // FR-IS-04 신규 8필드
  description: '## 재현 방법\n1. 로그인 페이지 접속\n2. 버튼 클릭',
  descriptionHtml: '<h2>재현 방법</h2><ol><li>로그인 페이지 접속</li><li>버튼 클릭</li></ol>',
  priority: 3,
  priorityName: 'Medium',
  labels: ['frontend', 'ux'],
  environment: 'Chrome 125, macOS 14',
  impact: 2,
  impactName: 'Medium',
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
// T1-10. issueResponseSchema — FR-IS-04 신규 8필드 파싱 + backend DTO 계약 회귀가드
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema — FR-IS-04 신규 8필드', () => {
  it('T1-10a: 신규 8필드가 모두 있는 IssueResponse를 파싱한다', () => {
    const result = issueResponseSchema.parse(issueFixture)

    expect(result.description).toBe('## 재현 방법\n1. 로그인 페이지 접속\n2. 버튼 클릭')
    expect(result.descriptionHtml).toBe(
      '<h2>재현 방법</h2><ol><li>로그인 페이지 접속</li><li>버튼 클릭</li></ol>',
    )
    expect(result.priority).toBe(3)
    expect(result.priorityName).toBe('Medium')
    expect(result.labels).toEqual(['frontend', 'ux'])
    expect(result.environment).toBe('Chrome 125, macOS 14')
    expect(result.impact).toBe(2)
    expect(result.impactName).toBe('Medium')
  })

  it('T1-10b: description/descriptionHtml/environment/impact/impactName이 null이어도 파싱 성공', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      description: null,
      descriptionHtml: null,
      environment: null,
      impact: null,
      impactName: null,
    })

    expect(result.description).toBeNull()
    expect(result.descriptionHtml).toBeNull()
    expect(result.environment).toBeNull()
    expect(result.impact).toBeNull()
    expect(result.impactName).toBeNull()
  })

  it('T1-10c: labels가 빈 배열이어도 파싱 성공', () => {
    const result = issueResponseSchema.parse({ ...issueFixture, labels: [] })
    expect(result.labels).toEqual([])
  })

  it('T1-10d: priority가 1~5 범위를 벗어나면 ZodError를 throw한다', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, priority: 0 })).toThrow()
    expect(() => issueResponseSchema.parse({ ...issueFixture, priority: 6 })).toThrow()
  })

  it('T1-10e: priority가 정수가 아니면 ZodError를 throw한다', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, priority: 2.5 })).toThrow()
  })

  it('T1-10f: impact가 1~3 범위를 벗어나면 ZodError를 throw한다', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, impact: 0 })).toThrow()
    expect(() => issueResponseSchema.parse({ ...issueFixture, impact: 4 })).toThrow()
  })

  it('T1-10g: priority 필드가 누락되면 ZodError를 throw한다 — backend non-null 계약 회귀가드', () => {
    const withoutPriority: Record<string, unknown> = { ...issueFixture }
    delete withoutPriority.priority
    expect(() => issueResponseSchema.parse(withoutPriority)).toThrow()
  })

  it('T1-10h: labels 필드가 누락되면 ZodError를 throw한다 — backend List<String> 계약 회귀가드', () => {
    const withoutLabels: Record<string, unknown> = { ...issueFixture }
    delete withoutLabels.labels
    expect(() => issueResponseSchema.parse(withoutLabels)).toThrow()
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

// ─────────────────────────────────────────────────────────────────────────────
// T1-11. issueResponseSchema — FR-IS-03 assigneeId nullable 필드 회귀가드
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema — FR-IS-03 assigneeId 필드', () => {
  it('T1-11a: assigneeId가 null이어도 파싱 성공한다', () => {
    const result = issueResponseSchema.parse({ ...issueFixture, assigneeId: null })
    expect(result.assigneeId).toBeNull()
  })

  it('T1-11b: assigneeId가 유효한 UUID이면 파싱 성공한다', () => {
    const uuid = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'
    const result = issueResponseSchema.parse({ ...issueFixture, assigneeId: uuid })
    expect(result.assigneeId).toBe(uuid)
  })

  it('T1-11c: assigneeId가 UUID 형식이 아닌 문자열이면 ZodError를 throw한다', () => {
    expect(() =>
      issueResponseSchema.parse({ ...issueFixture, assigneeId: 'not-a-uuid' }),
    ).toThrow()
  })

  it('T1-11d: assigneeId 필드가 누락되면 ZodError를 throw한다 (계약 엄격성 회귀가드)', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { assigneeId: _assigneeId, ...withoutAssigneeId } = issueFixture
    // 백엔드 IssueResponse는 assigneeId를 null이라도 항상 직렬화한다.
    // 따라서 nullable()만 사용하고 optional()은 쓰지 않는다 — 키 부재는 계약 위반이므로 실패해야 한다.
    // 미래에 누가 .optional()을 다시 추가하면 이 테스트가 회귀를 잡는다.
    expect(() => issueResponseSchema.parse(withoutAssigneeId)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-12. changeAssignee — PATCH /issues/{key}/assignee
// ─────────────────────────────────────────────────────────────────────────────
describe('changeAssignee', () => {
  const assigneeId = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'

  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', async ({ request, params }) => {
        const body = await request.json() as { assigneeId: string | null; expectedVersion: number }
        return HttpResponse.json({
          data: {
            ...issueFixture,
            key: params['key'] as string,
            assigneeId: body.assigneeId,
            version: body.expectedVersion + 1,
          },
        })
      }),
    )
  })

  it('T1-12a: assigneeId(UUID) 전달 시 PATCH 호출 후 IssueResponse를 반환한다', async () => {
    const result = await changeAssignee('ATLAS-1', { assigneeId, expectedVersion: 1 })
    expect(result.assigneeId).toBe(assigneeId)
    expect(result.version).toBe(2)
  })

  it('T1-12b: assigneeId=null 전달 시 담당자 해제 응답을 반환한다', async () => {
    const result = await changeAssignee('ATLAS-1', { assigneeId: null, expectedVersion: 1 })
    expect(result.assigneeId).toBeNull()
  })

  it('T1-12c: request body에 assigneeId와 expectedVersion이 포함되어 전달된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key/assignee', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: { ...issueFixture, assigneeId, version: 2 } })
      }),
    )
    await changeAssignee('ATLAS-1', { assigneeId, expectedVersion: 1 })
    expect(capturedBody['assigneeId']).toBe(assigneeId)
    expect(capturedBody['expectedVersion']).toBe(1)
  })

  it('T1-12d: 409 버전 충돌 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
    await expect(changeAssignee('ATLAS-1', { assigneeId, expectedVersion: 0 })).rejects.toSatisfy(
      (e) => e instanceof ApiError && (e as ApiError).status === 409,
    )
  })

  it('T1-12e: 422 ASSIGNEE_NOT_FOUND 시 ApiError(422)를 throw한다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'ASSIGNEE_NOT_FOUND' }, { status: 422 }),
      ),
    )
    await expect(
      changeAssignee('ATLAS-1', { assigneeId: 'nonexistent-uuid-0000-000000000000', expectedVersion: 1 }),
    ).rejects.toSatisfy(
      (e) => e instanceof ApiError && (e as ApiError).status === 422,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-13. bulkAvailableTransitionsSchema — Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('bulkAvailableTransitionsSchema', () => {
  const validPayload = {
    transitions: [
      { fromStateKey: 'open', toStateKey: 'closed', name: 'Cancel', key: 'open__closed' },
    ],
    unresolvedIssueKeys: [],
  }

  it('T1-13a: transitions + unresolvedIssueKeys가 모두 있는 응답을 파싱한다', () => {
    const result = bulkAvailableTransitionsSchema.parse(validPayload)
    expect(result.transitions).toHaveLength(1)
    expect(result.transitions[0]?.fromStateKey).toBe('open')
    expect(result.transitions[0]?.toStateKey).toBe('closed')
    expect(result.unresolvedIssueKeys).toEqual([])
  })

  it('T1-13b: transitions이 빈 배열이어도 파싱 성공한다', () => {
    const result = bulkAvailableTransitionsSchema.parse({
      transitions: [],
      unresolvedIssueKeys: ['ATLAS-9'],
    })
    expect(result.transitions).toHaveLength(0)
    expect(result.unresolvedIssueKeys).toEqual(['ATLAS-9'])
  })

  it('T1-13c: transitions 필드 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      bulkAvailableTransitionsSchema.parse({ unresolvedIssueKeys: [] }),
    ).toThrow()
  })

  it('T1-13d: unresolvedIssueKeys 필드 누락 시 ZodError를 throw한다', () => {
    expect(() =>
      bulkAvailableTransitionsSchema.parse({ transitions: [] }),
    ).toThrow()
  })

  it('T1-13e: transitions 항목에 빈 문자열 필드가 있으면 ZodError를 throw한다', () => {
    expect(() =>
      bulkAvailableTransitionsSchema.parse({
        transitions: [{ fromStateKey: '', toStateKey: 'closed', name: 'Cancel', key: 'open__closed' }],
        unresolvedIssueKeys: [],
      }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-14. fetchBulkAvailableTransitions — POST /bulk-transitions/available
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchBulkAvailableTransitions', () => {
  const transitionFixture = {
    fromStateKey: 'open',
    toStateKey: 'closed',
    name: 'Cancel',
    key: 'open__closed',
  }

  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/bulk-transitions/available', async ({ request }) => {
        const body = await request.json() as { issueKeys?: string[] }
        const keys = body.issueKeys ?? []
        // 빈 배열 → 400
        if (keys.length === 0) {
          return HttpResponse.json(
            { errorCode: 'ISSUE_BULK_VALIDATION_FAILED', message: 'issueKeys must not be empty' },
            { status: 400 },
          )
        }
        return HttpResponse.json({
          data: {
            transitions: [transitionFixture],
            unresolvedIssueKeys: [],
          },
        })
      }),
    )
  })

  it('T1-14a: issueKeys 배열로 POST 호출 시 { transitions, unresolvedIssueKeys }를 반환한다', async () => {
    const result = await fetchBulkAvailableTransitions(['ATLAS-1', 'ATLAS-3'])
    expect(result.transitions).toHaveLength(1)
    expect(result.transitions[0]?.toStateKey).toBe('closed')
    expect(result.unresolvedIssueKeys).toEqual([])
  })

  it('T1-14b: request body에 issueKeys 배열이 포함되어 전달된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues/bulk-transitions/available', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: { transitions: [], unresolvedIssueKeys: [] } })
      }),
    )
    await fetchBulkAvailableTransitions(['ATLAS-1', 'ATLAS-3'])
    expect(capturedBody['issueKeys']).toEqual(['ATLAS-1', 'ATLAS-3'])
  })

  it('T1-14c: unresolvedIssueKeys가 있는 응답도 파싱해 반환한다', async () => {
    server.use(
      http.post('/api/v1/issues/bulk-transitions/available', () =>
        HttpResponse.json({
          data: {
            transitions: [],
            unresolvedIssueKeys: ['ATLAS-9', 'ATLAS-99'],
          },
        }),
      ),
    )
    const result = await fetchBulkAvailableTransitions(['ATLAS-1', 'ATLAS-9', 'ATLAS-99'])
    expect(result.unresolvedIssueKeys).toEqual(['ATLAS-9', 'ATLAS-99'])
    expect(result.transitions).toHaveLength(0)
  })

  it('T1-14d: 400 응답 시 ApiError(400)를 throw한다', async () => {
    await expect(
      fetchBulkAvailableTransitions([]),
    ).rejects.toSatisfy(
      (e) => e instanceof ApiError && (e as ApiError).status === 400,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-15. downloadIssuePdf — GET /{key}/pdf → Blob (바이너리, Zod 파싱 없음)
// ─────────────────────────────────────────────────────────────────────────────
describe('downloadIssuePdf', () => {
  const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34]) // %PDF-1.4

  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:key/pdf', ({ params }) => {
        if (params['key'] === 'ATLAS-1') {
          return new HttpResponse(pdfBytes, {
            headers: {
              'Content-Type': 'application/pdf',
              'Content-Disposition': 'attachment; filename="ATLAS-1.pdf"',
            },
          })
        }
        return HttpResponse.json({ message: 'Not Found' }, { status: 404 })
      }),
    )
  })

  it('T1-15a: 존재하는 이슈 key로 GET 호출 시 Blob을 반환한다', async () => {
    const result = await downloadIssuePdf('ATLAS-1')
    // jsdom 환경에서 globalThis.Blob과 Response.blob()의 Blob이 다른 클래스일 수 있어
    // instanceof 대신 Blob 덕 타이핑(size, type, arrayBuffer 메서드)으로 검증한다.
    expect(typeof result.size).toBe('number')
    expect(result.size).toBeGreaterThan(0)
    expect(typeof result.arrayBuffer).toBe('function')
  })

  it('T1-15b: 반환된 Blob의 type이 application/pdf다', async () => {
    const result = await downloadIssuePdf('ATLAS-1')
    expect(result.type).toBe('application/pdf')
  })

  it('T1-15c: 5xx 서버 오류 시 ApiError(status)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/pdf', () =>
        HttpResponse.json({ message: 'Internal Server Error' }, { status: 500 }),
      ),
    )
    await expect(downloadIssuePdf('ATLAS-1')).rejects.toSatisfy(
      (e) => e instanceof ApiError && (e as ApiError).status === 500,
    )
  })

  it('T1-15d: 404 응답 시 ApiError(404)를 throw한다', async () => {
    await expect(downloadIssuePdf('NOT-EXISTS')).rejects.toSatisfy(
      (e) => e instanceof ApiError && (e as ApiError).status === 404,
    )
  })
})
