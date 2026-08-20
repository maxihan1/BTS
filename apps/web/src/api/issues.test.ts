// issue-tracking BC API client 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
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
  IssueRedirectError,
  buildIssueFilterQuery,
  parseAmbiguousTransitionError,
} from './issues'
import { ApiError } from './client'
import { useChangeAssignee } from './useChangeAssignee'
import { useChangeComponents } from './useChangeComponents'
import { issueWatchersKey } from './issue-watchers'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — IssueResponse 22 필드 (12 기존 + 8 FR-IS-04 + 1 FR-IS-03 assigneeId + 1 FR-IS-10 customFields) + nullable timestamps
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
  // FR-IS-10 커스텀 필드
  customFields: { severity: 'critical', sprint: 'Sprint-3' } as Record<string, unknown>,
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
// T1-2c. fetchIssue — 308 redirect 분기 단위 테스트 (FR-MV-01 C1)
//
// MSW ServiceWorker는 opaque 308을 만들 수 없어 E2E에서 검증이 불가능하다.
// 따라서 전역 fetch를 vi.spyOn으로 일시 override해 redirected:true 응답을 주입한다.
// MSW는 각 테스트 afterEach에서 restore되므로 기존 테스트와 격리된다.
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssue — 308 redirect IssueRedirectError (FR-MV-01)', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>

  afterEach(() => {
    fetchSpy.mockRestore()
  })

  it('T1-2c-1: response.redirected=true 시 IssueRedirectError(newKey)를 throw한다', async () => {
    // fetch가 redirected:true + url=/api/v1/issues/NEW-1 인 Response를 반환하도록 주입
    const redirectedResponse = new Response(null, { status: 200 })
    Object.defineProperty(redirectedResponse, 'redirected', { value: true })
    Object.defineProperty(redirectedResponse, 'url', { value: 'http://localhost/api/v1/issues/NEW-1' })
    fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(redirectedResponse)

    await expect(fetchIssue('OLD-1')).rejects.toSatisfy(
      (e) => e instanceof IssueRedirectError && (e as IssueRedirectError).newKey === 'NEW-1',
    )
  })

  it('T1-2c-2: response.redirected=false 시 IssueRedirectError를 throw하지 않고 이슈를 반환한다', async () => {
    // 정상 200 응답 — redirected=false(기본값)
    const okResponse = new Response(JSON.stringify({ data: issueFixture }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
    fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(okResponse)

    const result = await fetchIssue('ATLAS-1')

    expect(result.key).toBe('ATLAS-1')
    expect(result.summary).toBe('로그인 버튼이 클릭되지 않는 버그')
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
// T1-7. issueTransitionSchema — 전환 항목 Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('issueTransitionSchema', () => {
  it('T1-7a: 4개 string 필드가 모두 있는 전환 항목을 파싱한다', () => {
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

  it('T1-8a: 존재하는 이슈 key로 전환 목록을 조회해 배열로 반환한다', async () => {
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

  it('T1-8c: 전환이 없는 이슈의 경우 빈 배열을 반환한다', async () => {
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

  it('T1-9a: 정상 전환 요청 시 변경된 상태키와 증가된 version을 가진 IssueResponse를 반환한다', async () => {
    const result = await transitionIssue('ATLAS-1', { toStatusKey: 'in_progress', expectedVersion: 1 })
    expect(result.currentStateKey).toBe('in_progress')
    expect(result.version).toBe(2)
  })

  it('T1-9b: 없는 key로 전환 시 ApiError(404)를 throw한다', async () => {
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
// T21. FR-WF-05 — transitionId 왕복 (ADR 2026-08-18 §D3)
//   같은 (from,to) 에 전환이 여럿일 수 있으므로 toStatusKey 만으로는 못 가른다.
//   서버가 409 AMBIGUOUS_TRANSITION + 후보 목록을 돌려주면 클라이언트가 후보 하나의
//   transitionId 를 되실어 재요청한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 후보 전환 1번의 1급 식별자 — backend AmbiguousTransitionStatusCodeIntegrationTest 와 같은 값. */
const CANDIDATE_ONE_ID = '33333333-3333-4333-8333-333333333333'
/** 후보 전환 2번의 1급 식별자. */
const CANDIDATE_TWO_ID = '44444444-4444-4444-8444-444444444444'

/** backend AmbiguousTransitionErrorResponse 직렬화 형태 — Task 23 RED 출력 실측. */
const AMBIGUOUS_BODY = {
  error: {
    code: 'AMBIGUOUS_TRANSITION',
    message: '이동할 수 있는 전환이 2개입니다. 어느 전환인지 골라 주세요.',
  },
  candidates: [
    { transitionId: CANDIDATE_ONE_ID, name: '조건부 승인' },
    { transitionId: CANDIDATE_TWO_ID, name: '즉시 완료' },
  ],
}

describe('issueTransitionSchema — transitionId·kind (T21)', () => {
  it('T21-1a: transitionId·kind 가 실려 오면 그대로 보존한다', () => {
    const parsed = issueTransitionSchema.parse({
      fromStateKey: 'open',
      toStateKey: 'done',
      name: '조건부 승인',
      key: 'open__done',
      toCategory: 'DONE',
      transitionId: CANDIDATE_ONE_ID,
      kind: 'NORMAL',
    })
    expect(parsed.transitionId).toBe(CANDIDATE_ONE_ID)
    expect(parsed.kind).toBe('NORMAL')
  })

  it('T21-1b: GLOBAL 전환의 kind 도 보존한다', () => {
    const parsed = issueTransitionSchema.parse({
      fromStateKey: 'open',
      toStateKey: 'done',
      name: '즉시 완료',
      key: 'GLOBAL__done',
      transitionId: CANDIDATE_TWO_ID,
      kind: 'GLOBAL',
    })
    expect(parsed.kind).toBe('GLOBAL')
  })

  it('T21-1c: 두 필드가 빠져도 파싱된다 — backend DTO 가 UUID?·String? 라 required 로 올리면 계약보다 엄격해진다', () => {
    const parsed = issueTransitionSchema.parse({
      fromStateKey: 'open',
      toStateKey: 'in_progress',
      name: 'Start Work',
      key: 'open__in_progress',
    })
    expect(parsed.transitionId).toBeUndefined()
    expect(parsed.kind).toBeUndefined()
  })

  it('T21-1d: null 이어도 파싱된다 (미계산 상태)', () => {
    const parsed = issueTransitionSchema.parse({
      fromStateKey: 'open',
      toStateKey: 'in_progress',
      name: 'Start Work',
      key: 'open__in_progress',
      transitionId: null,
      kind: null,
    })
    expect(parsed.transitionId).toBeNull()
    expect(parsed.kind).toBeNull()
  })

  it('T21-1e: transitionId 가 UUID 형식이 아니면 ZodError 를 throw 한다', () => {
    expect(() =>
      issueTransitionSchema.parse({
        fromStateKey: 'open',
        toStateKey: 'in_progress',
        name: 'Start Work',
        key: 'open__in_progress',
        transitionId: 'open__in_progress',
      }),
    ).toThrow()
  })
})

describe('fetchIssueTransitions — transitionId 왕복 전제 (T21)', () => {
  it('T21-2a: 목록 응답의 transitionId 가 Zod 에서 버려지지 않는다', async () => {
    server.use(
      http.get('/api/v1/issues/:key/transitions', () =>
        HttpResponse.json({
          data: {
            transitions: [
              {
                fromStateKey: 'open',
                toStateKey: 'done',
                name: '조건부 승인',
                key: 'open__done',
                toCategory: 'DONE',
                transitionId: CANDIDATE_ONE_ID,
                kind: 'NORMAL',
              },
            ],
          },
        }),
      ),
    )
    const result = await fetchIssueTransitions('ATLAS-1')
    expect(result[0]?.transitionId).toBe(CANDIDATE_ONE_ID)
    expect(result[0]?.kind).toBe('NORMAL')
  })
})

describe('transitionIssue — transitionId 재요청 (T21)', () => {
  it('T21-3a: transitionId 를 주면 request body 에 그대로 실려 나간다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues/:key/transition', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ data: { ...issueFixture, currentStateKey: 'done', version: 2 } })
      }),
    )
    await transitionIssue('ATLAS-1', {
      toStatusKey: 'done',
      expectedVersion: 1,
      transitionId: CANDIDATE_TWO_ID,
    })
    expect(capturedBody['transitionId']).toBe(CANDIDATE_TWO_ID)
  })

  it('T21-3b: transitionId 를 안 주면 body 에 키가 없다 — 기존 클라이언트 계약 보존', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues/:key/transition', async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>
        return HttpResponse.json({ data: { ...issueFixture, currentStateKey: 'done', version: 2 } })
      }),
    )
    await transitionIssue('ATLAS-1', { toStatusKey: 'done', expectedVersion: 1 })
    expect('transitionId' in capturedBody).toBe(false)
  })
})

describe('parseAmbiguousTransitionError (T21)', () => {
  it('T21-4a: 409 AMBIGUOUS_TRANSITION 이면 후보 전량과 안내 문구를 뽑는다', () => {
    const parsed = parseAmbiguousTransitionError(new ApiError(409, AMBIGUOUS_BODY))
    expect(parsed).not.toBeNull()
    expect(parsed?.candidates).toHaveLength(2)
    expect(parsed?.candidates[0]?.transitionId).toBe(CANDIDATE_ONE_ID)
    expect(parsed?.candidates[0]?.name).toBe('조건부 승인')
    expect(parsed?.message).toBe(AMBIGUOUS_BODY.error.message)
  })

  it('T21-4b: 같은 409 라도 VERSION_CONFLICT 면 null — 두 409 가 섞이면 안 된다', () => {
    expect(
      parseAmbiguousTransitionError(
        new ApiError(409, { errorCode: 'VERSION_CONFLICT', message: '버전 충돌' }),
      ),
    ).toBeNull()
  })

  it('T21-4c: 409 가 아니면 null', () => {
    expect(parseAmbiguousTransitionError(new ApiError(422, AMBIGUOUS_BODY))).toBeNull()
  })

  it('T21-4d: ApiError 가 아니면 null', () => {
    expect(parseAmbiguousTransitionError(new Error('boom'))).toBeNull()
  })

  it('T21-4e: 실제 409 응답을 transitionIssue 가 던진 에러에서 그대로 읽는다', async () => {
    server.use(
      http.post('/api/v1/issues/:key/transition', () =>
        HttpResponse.json(AMBIGUOUS_BODY, { status: 409 }),
      ),
    )
    const error = await transitionIssue('ATLAS-1', {
      toStatusKey: 'done',
      expectedVersion: 1,
    }).catch((e: unknown) => e)
    const parsed = parseAmbiguousTransitionError(error)
    expect(parsed?.candidates.map((c) => c.name)).toEqual(['조건부 승인', '즉시 완료'])
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
// T1-16. createIssue — FR-CM-03 componentIds POST body 전달 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('createIssue — FR-CM-03 componentIds', () => {
  it('T1-16a: componentIds 지정 시 POST body에 componentIds 배열이 포함된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json(
          { data: { ...issueFixture, projectKey: 'ATLAS', summary: '컴포넌트 지정 이슈' } },
          { status: 201 },
        )
      }),
    )

    await createIssue({
      projectKey: 'ATLAS',
      summary: '컴포넌트 지정 이슈',
      componentIds: ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222'],
    })

    expect(capturedBody['componentIds']).toEqual([
      '11111111-1111-4111-8111-111111111111',
      '22222222-2222-4222-8222-222222222222',
    ])
  })

  it('T1-16b: componentIds 미지정 시 POST body의 componentIds는 빈 배열이다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json(
          { data: { ...issueFixture, projectKey: 'ATLAS', summary: '기본 이슈' } },
          { status: 201 },
        )
      }),
    )

    await createIssue({ projectKey: 'ATLAS', summary: '기본 이슈' })

    expect(capturedBody['componentIds']).toEqual([])
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

// ─────────────────────────────────────────────────────────────────────────────
// T1-17. issueResponseSchema — FR-IS-10 customFields 필드 파싱 회귀가드
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema — FR-IS-10 customFields', () => {
  it('T1-17a: customFields 키-값 맵이 있는 응답을 파싱한다', () => {
    const result = issueResponseSchema.parse(issueFixture)
    expect(result.customFields).toEqual({ severity: 'critical', sprint: 'Sprint-3' })
  })

  it('T1-17b: customFields가 {} 빈 맵이어도 파싱 성공한다', () => {
    const result = issueResponseSchema.parse({ ...issueFixture, customFields: {} })
    expect(result.customFields).toEqual({})
  })

  it('T1-17c: customFields 필드가 누락되면 default {}로 fallback된다 — 기존 인라인 mock 파급 방지', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { customFields: _cf, ...withoutCustomFields } = issueFixture
    const result = issueResponseSchema.parse(withoutCustomFields)
    expect(result.customFields).toEqual({})
  })

  it('T1-17d: customFields 값이 null을 포함한 임의 JSON 타입을 허용한다', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      customFields: { text: 'hello', num: 42, flag: true, empty: null },
    })
    expect(result.customFields['text']).toBe('hello')
    expect(result.customFields['num']).toBe(42)
    expect(result.customFields['flag']).toBe(true)
    expect(result.customFields['empty']).toBeNull()
  })

  it('T1-17e: customFields가 배열이면 ZodError를 throw한다 — Record 계약', () => {
    expect(() => issueResponseSchema.parse({ ...issueFixture, customFields: ['not', 'a', 'map'] })).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-18. createIssue — FR-IS-10 customFields POST body 직렬화 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('createIssue — FR-IS-10 customFields', () => {
  it('T1-18a: customFields 지정 시 POST body에 customFields 맵이 포함된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: issueFixture }, { status: 201 })
      }),
    )

    await createIssue({
      projectKey: 'ATLAS',
      summary: '커스텀 필드 이슈',
      customFields: { severity: 'high', sprint: 'Sprint-1' },
    })

    expect(capturedBody['customFields']).toEqual({ severity: 'high', sprint: 'Sprint-1' })
  })

  it('T1-18b: customFields 미지정 시 POST body에 customFields 키가 없다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: issueFixture }, { status: 201 })
      }),
    )

    await createIssue({ projectKey: 'ATLAS', summary: '기본 이슈' })

    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'customFields')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-20. issueResponseSchema — FR-LK-01 parent nullish 필드 파싱 회귀가드
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema — FR-LK-01 parent 필드', () => {
  it('T1-20a: parent { key, summary } 가 포함된 응답을 파싱하고 값이 채워진다', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      parent: { key: 'BTS-5', summary: '부모 이슈' },
    })
    expect(result.parent).toEqual({ key: 'BTS-5', summary: '부모 이슈' })
    expect(result.parent?.key).toBe('BTS-5')
    expect(result.parent?.summary).toBe('부모 이슈')
  })

  it('T1-20b: parent 키가 생략된(undefined) 응답도 파싱 성공한다 — nullish()', () => {
    const result = issueResponseSchema.parse(issueFixture)
    // parent 키 자체가 없으므로 undefined 또는 null 모두 허용
    expect(result.parent == null).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-22. issueResponseSchema — FR-PL-01 일정 3필드 파싱 회귀가드
//
// 백엔드 IssueResponse에 startDate/dueDate/targetDate: LocalDate? 3필드 추가.
// @JsonFormat(STRING, "yyyy-MM-dd") 직렬화 → 프론트 Zod: z.string().nullable()
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema — FR-PL-01 일정 3필드', () => {
  it('T1-22a: startDate/dueDate/targetDate 가 "yyyy-MM-dd" 문자열인 경우 파싱 성공', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      startDate: '2026-06-01',
      dueDate: '2026-06-30',
      targetDate: '2026-07-15',
    })
    expect(result.startDate).toBe('2026-06-01')
    expect(result.dueDate).toBe('2026-06-30')
    expect(result.targetDate).toBe('2026-07-15')
  })

  it('T1-22b: startDate/dueDate/targetDate 가 null 인 경우도 파싱 성공', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      startDate: null,
      dueDate: null,
      targetDate: null,
    })
    // optional() 스키마에서 명시 null 은 null 그대로 파싱된다
    expect(result.startDate).toBeNull()
    expect(result.dueDate).toBeNull()
    expect(result.targetDate).toBeNull()
  })

  it('T1-22c: 일정 3필드가 누락된 경우 파싱 성공 + undefined — 기존 인라인 mock 파급 방지', () => {
    // optional()로 두어 기존 인라인 mock(필드 미포함)이 TS 컴파일 에러 없이 통과한다
    // (zod-schema-strengthen-inline-mock-fanout 교훈 — securityLevelId 패턴).
    // 필드가 없으면 undefined가 된다. null이 아님 — IssueResponse 타입에서 optional 처리.
    const result = issueResponseSchema.parse(issueFixture)
    expect(result.startDate).toBeUndefined()
    expect(result.dueDate).toBeUndefined()
    expect(result.targetDate).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-23. updateIssue — FR-PL-01 일정 3필드 PATCH 3-state 계약 검증
//
// updateIssue 는 input 을 body에 직접 전달한다 (JSON.stringify undefined 키 자동 누락).
// - 무변경 = 키를 input 에서 생략 (undefined → JSON.stringify 가 키 제거)
// - 클리어 = 키를 명시 null 로 포함 (null → JSON 에 "dueDate":null)
// - 설정   = "yyyy-MM-dd" 문자열로 포함
// ─────────────────────────────────────────────────────────────────────────────
describe('updateIssue — FR-PL-01 일정 3필드 3-state PATCH', () => {
  it('T1-23a: 날짜 설정 시 "yyyy-MM-dd" 문자열로 PATCH body 에 포함된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: {
            ...issueFixture,
            key: params['key'] as string,
            startDate: capturedBody['startDate'] as string | null ?? null,
            dueDate: capturedBody['dueDate'] as string | null ?? null,
            targetDate: capturedBody['targetDate'] as string | null ?? null,
          },
        })
      }),
    )

    const result = await updateIssue('ATLAS-1', {
      startDate: '2026-06-01',
      dueDate: '2026-06-30',
      targetDate: '2026-07-15',
      expectedVersion: 1,
    })

    // body 에 yyyy-MM-dd 문자열이 포함되었는지 검증
    expect(capturedBody['startDate']).toBe('2026-06-01')
    expect(capturedBody['dueDate']).toBe('2026-06-30')
    expect(capturedBody['targetDate']).toBe('2026-07-15')
    // 응답 파싱도 성공해야 한다
    expect(result.startDate).toBe('2026-06-01')
    expect(result.dueDate).toBe('2026-06-30')
    expect(result.targetDate).toBe('2026-07-15')
  })

  it('T1-23b: 클리어(clear) 시 해당 키가 null 로 명시 포함된다 (키 생략 아님)', async () => {
    // C2 리뷰 핵심 — 클리어는 "dueDate":null (키 있음+값 null) 이어야 한다.
    // 키가 생략되면 백엔드가 무변경으로 처리해 클리어되지 않는다.
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: {
            ...issueFixture,
            key: params['key'] as string,
            startDate: null,
            dueDate: null,
            targetDate: null,
          },
        })
      }),
    )

    await updateIssue('ATLAS-1', {
      startDate: null,
      dueDate: null,
      targetDate: null,
      expectedVersion: 1,
    })

    // 키가 존재하고 값이 null 이어야 한다 (Object.prototype.hasOwnProperty 로 키 존재 검증)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'startDate')).toBe(true)
    expect(capturedBody['startDate']).toBeNull()
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'dueDate')).toBe(true)
    expect(capturedBody['dueDate']).toBeNull()
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'targetDate')).toBe(true)
    expect(capturedBody['targetDate']).toBeNull()
  })

  it('T1-23c: 무변경(undefined) 시 해당 키가 PATCH body 에서 생략된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: { ...issueFixture, key: params['key'] as string },
        })
      }),
    )

    // 날짜 필드 미전달 → 키 자체가 body 에서 제외되어야 한다
    await updateIssue('ATLAS-1', { summary: '제목만 수정', expectedVersion: 1 })

    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'startDate')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'dueDate')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'targetDate')).toBe(false)
  })

  it('T1-23d: MSW stateful — 날짜 설정 후 GET 단건 조회 시 반영된 날짜가 반환된다', async () => {
    // msw-mutation-stateful-refetch 교훈 — mutation 결과가 invalidate 후 refetch 시에도 유지돼야 한다.
    // stateful 시나리오: PATCH 후 issueStore에 보관, GET 단건 재조회 시 반영.
    // beforeEach 의 기본 핸들러는 날짜를 echo하지 않으므로 stateful store 핸들러를 별도 설정.
    const store: { issue: typeof issueFixture & { startDate: string | null; dueDate: string | null } } = {
      issue: { ...issueFixture, startDate: null, dueDate: null },
    }

    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        const body = await request.json() as { startDate?: string | null; dueDate?: string | null; expectedVersion?: number }
        store.issue = {
          ...store.issue,
          key: params['key'] as string,
          startDate: body.startDate !== undefined ? body.startDate : store.issue.startDate,
          dueDate: body.dueDate !== undefined ? body.dueDate : store.issue.dueDate,
          version: (body.expectedVersion ?? store.issue.version) + 1,
        }
        return HttpResponse.json({ data: store.issue })
      }),
      http.get('/api/v1/issues/:key', () => {
        return HttpResponse.json({ data: store.issue })
      }),
    )

    const patchResult = await updateIssue('ATLAS-1', {
      startDate: '2026-06-01',
      dueDate: '2026-06-30',
      expectedVersion: 0,
    })
    expect(patchResult.startDate).toBe('2026-06-01')
    expect(patchResult.dueDate).toBe('2026-06-30')

    // invalidate 후 GET 단건 재조회 — store 에 보관된 값 반영 확인
    const fetched = await fetchIssue('ATLAS-1')
    expect(fetched.startDate).toBe('2026-06-01')
    expect(fetched.dueDate).toBe('2026-06-30')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-19. updateIssue — FR-IS-10 customFields PATCH body 직렬화 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('updateIssue — FR-IS-10 customFields', () => {
  it('T1-19a: customFields 맵 전달 시 PATCH body에 포함된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: { ...issueFixture, key: params['key'] as string },
        })
      }),
    )

    await updateIssue('ATLAS-1', { customFields: { sprint: 'Sprint-2' }, expectedVersion: 1 })

    expect(capturedBody['customFields']).toEqual({ sprint: 'Sprint-2' })
  })

  it('T1-19b: customFields=null 전달 시 PATCH body에 null이 포함된다 (무변경 3-state)', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: { ...issueFixture, key: params['key'] as string },
        })
      }),
    )

    await updateIssue('ATLAS-1', { customFields: null, expectedVersion: 1 })

    expect(capturedBody['customFields']).toBeNull()
  })

  it('T1-19c: customFields 미전달 시 PATCH body에 customFields 키가 없다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: { ...issueFixture, key: params['key'] as string },
        })
      }),
    )

    await updateIssue('ATLAS-1', { summary: '제목만 수정', expectedVersion: 1 })

    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'customFields')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-24. issueResponseSchema — FR-TT-01 추정 3필드 파싱 회귀가드
//
// 백엔드 IssueResponse에 originalEstimateSeconds/timeSpentSeconds/remainingEstimateSeconds 추가.
// @JsonInclude(NON_NULL) 미적용 → 3필드는 항상 직렬화되고 null도 키와 함께 내려온다.
// .optional()은 기존 인라인 mock(estimate 필드 0개) fanout 회피용일 뿐
// 키 부재 허용 의도가 아님 (zod-schema-strengthen-inline-mock-fanout 교훈).
// ─────────────────────────────────────────────────────────────────────────────
describe('issueResponseSchema — FR-TT-01 추정 3필드', () => {
  it('T1-24a: originalEstimateSeconds/timeSpentSeconds/remainingEstimateSeconds 값이 있으면 파싱 성공', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      originalEstimateSeconds: 3600,
      timeSpentSeconds: 1800,
      remainingEstimateSeconds: 1800,
    })
    expect(result.originalEstimateSeconds).toBe(3600)
    expect(result.timeSpentSeconds).toBe(1800)
    expect(result.remainingEstimateSeconds).toBe(1800)
  })

  it('T1-24b: originalEstimateSeconds/remainingEstimateSeconds가 null이어도 파싱 성공 (미설정)', () => {
    const result = issueResponseSchema.parse({
      ...issueFixture,
      originalEstimateSeconds: null,
      timeSpentSeconds: 0,
      remainingEstimateSeconds: null,
    })
    expect(result.originalEstimateSeconds).toBeNull()
    expect(result.timeSpentSeconds).toBe(0)
    expect(result.remainingEstimateSeconds).toBeNull()
  })

  it('T1-24c: 추정 3필드가 모두 누락된 경우 파싱 성공 + undefined — 기존 인라인 mock 파급 방지', () => {
    // .optional()로 두어 기존 인라인 mock(필드 미포함)이 TS 컴파일 에러 없이 통과한다
    const result = issueResponseSchema.parse(issueFixture)
    expect(result.originalEstimateSeconds).toBeUndefined()
    expect(result.timeSpentSeconds).toBeUndefined()
    expect(result.remainingEstimateSeconds).toBeUndefined()
  })

  it('T1-24d: timeSpentSeconds는 nullable이 아닌 number — 숫자 아닌 값은 ZodError를 throw한다', () => {
    expect(() =>
      issueResponseSchema.parse({
        ...issueFixture,
        originalEstimateSeconds: 3600,
        timeSpentSeconds: 'invalid',
        remainingEstimateSeconds: null,
      }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-25. updateIssue — FR-TT-01 추정 2필드 PATCH 3-state 계약 검증
//
// - undefined(미전달) = 키 생략 (JSON.stringify가 undefined 키를 제거)
// - null = 클리어 (키 존재 + 값 null → 백엔드가 추정 초기화로 처리)
// - number = 설정
// ─────────────────────────────────────────────────────────────────────────────
describe('updateIssue — FR-TT-01 추정 3-state PATCH', () => {
  it('T1-25a: originalEstimateSeconds/remainingEstimateSeconds 값 전달 시 PATCH body에 포함된다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: {
            ...issueFixture,
            key: params['key'] as string,
            originalEstimateSeconds: capturedBody['originalEstimateSeconds'] as number ?? null,
            timeSpentSeconds: 0,
            remainingEstimateSeconds: capturedBody['remainingEstimateSeconds'] as number | null ?? null,
          },
        })
      }),
    )

    const result = await updateIssue('ATLAS-1', {
      originalEstimateSeconds: 3600,
      remainingEstimateSeconds: 1800,
      expectedVersion: 1,
    })

    expect(capturedBody['originalEstimateSeconds']).toBe(3600)
    expect(capturedBody['remainingEstimateSeconds']).toBe(1800)
    expect(result.originalEstimateSeconds).toBe(3600)
    expect(result.remainingEstimateSeconds).toBe(1800)
  })

  it('T1-25b: null 전달 시 해당 키가 null로 명시 포함된다 (클리어, 키 생략 아님)', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: {
            ...issueFixture,
            key: params['key'] as string,
            originalEstimateSeconds: null,
            timeSpentSeconds: 0,
            remainingEstimateSeconds: null,
          },
        })
      }),
    )

    await updateIssue('ATLAS-1', {
      originalEstimateSeconds: null,
      remainingEstimateSeconds: null,
      expectedVersion: 1,
    })

    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'originalEstimateSeconds')).toBe(true)
    expect(capturedBody['originalEstimateSeconds']).toBeNull()
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'remainingEstimateSeconds')).toBe(true)
    expect(capturedBody['remainingEstimateSeconds']).toBeNull()
  })

  it('T1-25c: 미전달 시 PATCH body에서 키가 생략된다 (무변경)', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request, params }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: { ...issueFixture, key: params['key'] as string },
        })
      }),
    )

    await updateIssue('ATLAS-1', { summary: '제목만 수정', expectedVersion: 1 })

    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'originalEstimateSeconds')).toBe(false)
    expect(Object.prototype.hasOwnProperty.call(capturedBody, 'remainingEstimateSeconds')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-21. FR-WT-01 FR-7 — 담당자/컴포넌트 변경 시 watcher 목록 자동 갱신 (RED)
//
// 백엔드가 담당자/컴포넌트 변경 시 해당 인물을 자동 watcher로 등록하므로(FR-WT-01),
// 각 mutation onSettled에서 issueWatchersKey(key) invalidate가 추가로 호출되어야 한다.
// ─────────────────────────────────────────────────────────────────────────────

/** 훅 테스트용 QueryClient + Provider 래퍼 생성 헬퍼 */
function createQueryWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }

  return { queryClient, Wrapper }
}

/** issueWatchersKey 검증용 MSW 픽스처 — IssueResponse 최소 필드 */
const watcherInvalidateFixture = issueFixture

describe('FR-WT-01 FR-7 — 담당자 변경 시 watcher 목록 자동 갱신', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', async ({ params }) => {
        return HttpResponse.json({
          data: { ...watcherInvalidateFixture, key: params['key'] as string, version: 2 },
        })
      }),
      http.get('/api/v1/issues/:key/watchers', () =>
        HttpResponse.json({ data: { watchers: [], count: 0, isWatching: false } }),
      ),
    )
  })

  it('T1-21a: useChangeAssignee 성공 후 issueWatchersKey(key) invalidate가 호출된다', async () => {
    const { queryClient, Wrapper } = createQueryWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId: null, expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueWatchersKey('ATLAS-1') })
  })
})

describe('FR-WT-01 FR-7 — 컴포넌트 변경 시 watcher 목록 자동 갱신', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/components', async ({ params }) => {
        return HttpResponse.json({
          data: { ...watcherInvalidateFixture, key: params['key'] as string, version: 2 },
        })
      }),
      http.get('/api/v1/issues/:key/watchers', () =>
        HttpResponse.json({ data: { watchers: [], count: 0, isWatching: false } }),
      ),
    )
  })

  it('T1-21b: useChangeComponents 성공 후 issueWatchersKey(key) invalidate가 호출된다', async () => {
    const { queryClient, Wrapper } = createQueryWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: issueWatchersKey('ATLAS-1') })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-27. buildIssueFilterQuery — 헬퍼 직접 단위 테스트 (FR-SR-01 D6/D7)
// ─────────────────────────────────────────────────────────────────────────────
describe('buildIssueFilterQuery', () => {
  it('T1-27a: filter=undefined 이면 params를 변경하지 않는다', () => {
    const params = new URLSearchParams({ projectKey: 'ATLAS' })
    buildIssueFilterQuery(params, undefined)
    expect(params.toString()).toBe('projectKey=ATLAS')
  })

  it('T1-27b: 빈 배열·includeUnassigned=false → 키 미추가', () => {
    const params = new URLSearchParams()
    buildIssueFilterQuery(params, { statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
    expect(params.toString()).toBe('')
  })

  it('T1-27c: statusKeys → status 반복 파라미터', () => {
    const params = new URLSearchParams()
    buildIssueFilterQuery(params, { statusKeys: ['open', 'done'], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] })
    expect(params.getAll('status')).toEqual(['open', 'done'])
  })

  it('T1-27d: assigneeIds + includeUnassigned → assignee 반복 + unassigned 센티널', () => {
    const params = new URLSearchParams()
    buildIssueFilterQuery(params, { statusKeys: [], assigneeIds: ['uuid-1'], includeUnassigned: true, labels: [], componentIds: [] })
    expect(params.getAll('assignee')).toEqual(['uuid-1', 'unassigned'])
  })

  it('T1-27e: labels → label 반복, componentIds → component 반복', () => {
    const params = new URLSearchParams()
    buildIssueFilterQuery(params, { statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: ['bug', 'ux'], componentIds: ['c-1'] })
    expect(params.getAll('label')).toEqual(['bug', 'ux'])
    expect(params.getAll('component')).toEqual(['c-1'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T1-26. buildIssueFilterQuery + fetchIssues filter 확장 (FR-SR-01 D6/D7)
//
// fetch mock의 호출 URL을 캡처해 query string 단언.
// 기존 T1-3 응답 테스트와 격리 — 별도 describe + server.use 오버라이드.
// ─────────────────────────────────────────────────────────────────────────────
describe('buildIssueFilterQuery + fetchIssues — FR-SR-01 필터 query string 조립', () => {
  it('T1-26a: statusKeys 2개 → status 파라미터 2회 반복', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      filter: { statusKeys: ['open', 'in_progress'], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.getAll('status')).toEqual(['open', 'in_progress'])
    expect(params.has('assignee')).toBe(false)
  })

  it('T1-26b: assigneeIds + includeUnassigned=true → assignee 파라미터 + unassigned 센티널', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      filter: { statusKeys: [], assigneeIds: ['a1b2c3d4-e5f6-4890-abcd-ef1234567891'], includeUnassigned: true, labels: [], componentIds: [] },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.getAll('assignee')).toContain('a1b2c3d4-e5f6-4890-abcd-ef1234567891')
    expect(params.getAll('assignee')).toContain('unassigned')
  })

  it('T1-26c: labels → label 파라미터', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      filter: { statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: ['bug'], componentIds: [] },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.getAll('label')).toEqual(['bug'])
  })

  it('T1-26d: componentIds → component 파라미터', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      filter: { statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: ['c1c2c3c4-d5d6-4890-abcd-ef1234567890'] },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.getAll('component')).toEqual(['c1c2c3c4-d5d6-4890-abcd-ef1234567890'])
  })

  it('T1-26e: filter 미전달 → 필터 param 없음 (projectKey/page/size만, 하위호환)', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({ projectKey: 'ATLAS', page: 0, size: 20 })

    const params = new URL(capturedUrl).searchParams
    expect(params.has('status')).toBe(false)
    expect(params.has('assignee')).toBe(false)
    expect(params.has('label')).toBe(false)
    expect(params.has('component')).toBe(false)
    expect(params.get('projectKey')).toBe('ATLAS')
  })

  it('T1-26f: 빈 배열 필터 → 필터 param 없음 (빈 배열은 키 생략)', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      filter: { statusKeys: [], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.has('status')).toBe(false)
    expect(params.has('assignee')).toBe(false)
    expect(params.has('label')).toBe(false)
    expect(params.has('component')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2. fetchIssues — sort 파라미터 (FR-UX-06 Phase 5 PR18 Task 2)
//
// 정렬 필드 계약 5종 — key · summary · priority · createdAt · updatedAt.
// sort 미전달 시 기존 URL(sort 파라미터 없음) 유지 = 하위호환.
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchIssues — sort 파라미터 (PR18 Task 2)', () => {
  it('T2-1a: sort 전달 시 URL에 sort=<field>,<dir> 파라미터가 추가된다 (priority,desc)', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      sort: { field: 'priority', dir: 'desc' },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.get('sort')).toBe('priority,desc')
  })

  it('T2-1b: sort dir=asc 전달 시 URL에 sort=<field>,asc 파라미터가 추가된다 (key,asc)', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({
      projectKey: 'ATLAS',
      page: 0,
      size: 20,
      sort: { field: 'key', dir: 'asc' },
    })

    const params = new URL(capturedUrl).searchParams
    expect(params.get('sort')).toBe('key,asc')
  })

  it('T2-1c: sort 미전달 시 URL에 sort 파라미터가 없다 (하위호환)', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(pageFixture)
      }),
    )

    await fetchIssues({ projectKey: 'ATLAS', page: 0, size: 20 })

    const params = new URL(capturedUrl).searchParams
    expect(params.has('sort')).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-F2-1. createIssue — FR-UX-09 F2 신규 5필드 전송 + assigneeId 3-state
//
// 백엔드 CreateIssueRequest(PR #328)가 typeId·description·assigneeId·priority·labels 를
// 받는데 프론트가 보내지 않던 갭을 닫는다. assigneeId 는 JsonNullable 3-state 라
// **키 부재 / 명시 null / 값** 세 가지가 서로 다른 서버 동작을 부른다.
// ─────────────────────────────────────────────────────────────────────────────
describe('createIssue — FR-UX-09 F2 신규 5필드', () => {
  /** POST /api/v1/issues 요청 본문을 캡처하는 핸들러를 등록한다. */
  function captureCreateBody(): { get: () => Record<string, unknown> } {
    let captured: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        captured = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: issueFixture }, { status: 201 })
      }),
    )
    return { get: () => captured }
  }

  it('T-F2-1a: typeId·description·priority·labels 를 넘기면 POST body 에 그대로 실린다', async () => {
    const body = captureCreateBody()

    await createIssue({
      projectKey: 'ATLAS',
      summary: '5필드 이슈',
      typeId: 2,
      description: '본문입니다',
      priority: 1,
      labels: ['backend', 'urgent'],
    })

    expect(body.get()['typeId']).toBe(2)
    expect(body.get()['description']).toBe('본문입니다')
    expect(body.get()['priority']).toBe(1)
    expect(body.get()['labels']).toEqual(['backend', 'urgent'])
  })

  it('T-F2-1b: 미전달 시 POST body 에 해당 키 자체가 없다 (서버 기본값 위임)', async () => {
    const body = captureCreateBody()

    await createIssue({ projectKey: 'ATLAS', summary: '기본 이슈' })

    expect('typeId' in body.get()).toBe(false)
    expect('description' in body.get()).toBe(false)
    expect('priority' in body.get()).toBe(false)
    expect('labels' in body.get()).toBe(false)
  })

  it('T-F2-1c: assigneeId 미전달 시 POST body 에 키 자체가 없다 (자동 배정 유지)', async () => {
    const body = captureCreateBody()

    await createIssue({ projectKey: 'ATLAS', summary: '자동 배정 이슈' })

    expect('assigneeId' in body.get()).toBe(false)
  })

  it('T-F2-1d: assigneeId=null 전달 시 POST body 에 null 이 실린다 (자동 배정 비활성)', async () => {
    const body = captureCreateBody()

    await createIssue({ projectKey: 'ATLAS', summary: '미할당 확정 이슈', assigneeId: null })

    expect('assigneeId' in body.get()).toBe(true)
    expect(body.get()['assigneeId']).toBeNull()
  })

  it('T-F2-1e: assigneeId 에 값 전달 시 POST body 에 그 값이 실린다', async () => {
    const body = captureCreateBody()

    await createIssue({
      projectKey: 'ATLAS',
      summary: '담당자 지정 이슈',
      assigneeId: '33333333-3333-4333-8333-333333333333',
    })

    expect(body.get()['assigneeId']).toBe('33333333-3333-4333-8333-333333333333')
  })
})
