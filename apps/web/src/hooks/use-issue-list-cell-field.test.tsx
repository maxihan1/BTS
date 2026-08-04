// 목록 셀 필드 변경 mutation 훅 테스트 — 낙관적 patch · 롤백 · 사유별 안내 (FR-UX-11 F9 D-4·FR15)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/issues 전체 mock — 실제 HTTP 요청 없이 단위 테스트 (use-change-card-field.test.tsx 관례)
vi.mock('@/api/issues')
// sonner toast mock — 전역 셋업에 없으므로 파일별로 선언한다 (use-change-card-field.test.tsx 관례)
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { updateIssue, transitionIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { toast } from 'sonner'
import { issueDetailStrings } from '@/i18n/ko'
import { useIssueListCellField, patchIssueInList } from './use-issue-list-cell-field'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const ISSUE_ID = 'i1000000-0000-4000-a000-000000000001'
const REPORTER_ID = 'u1000000-0000-4000-a000-000000000001'

/**
 * issueResponseSchema 의 필수 필드를 **전부** 채운 픽스처.
 *
 * `as IssueResponse` 부분 캐스팅을 쓰지 않는다 — 캐스팅은 스키마에 필수 필드가 추가돼도
 * 조용히 통과해 인라인 mock 이 정본과 어긋난 것을 감춘다
 * (learnings `zod-schema-strengthen-inline-mock-fanout`). 형제 테스트
 * `use-change-card-field.test.tsx:65` 가 같은 이유로 전 필드를 채운다.
 */
function makeIssue(overrides: Partial<IssueResponse> = {}): IssueResponse {
  return {
    key: 'ATLAS-1',
    id: ISSUE_ID,
    projectKey: 'ATLAS',
    summary: '테스트 이슈',
    currentStateKey: 'TODO',
    reporterId: REPORTER_ID,
    assigneeId: null,
    componentIds: [],
    version: 1,
    createdAt: null,
    updatedAt: '2026-08-04T00:00:00Z',
    typeId: 1,
    typeKey: 'TASK',
    typeName: 'Task',
    description: null,
    descriptionHtml: null,
    priority: 3,
    priorityName: 'Medium',
    labels: [],
    environment: null,
    impact: null,
    impactName: null,
    customFields: {},
    restrictedFields: [],
    noneditableFields: [],
    affectsVersionIds: [],
    fixVersionIds: [],
    ...overrides,
  }
}

/** 목록 쿼리키 — `['issues', projectKey, page, filter, sort]` 형태 */
const LIST_KEY = ['issues', 'ATLAS', 0, {}, null] as const

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// patchIssueInList — 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

describe('patchIssueInList (순수 함수)', () => {
  it('대상 이슈의 지정 필드만 바꾸고 나머지 행은 그대로 둔다', () => {
    const page = { content: [makeIssue(), makeIssue({ key: 'ATLAS-2' })] }

    const next = patchIssueInList(page, 'ATLAS-1', { priority: 1 })

    expect(next.content[0]?.priority).toBe(1)
    expect(next.content[0]?.summary).toBe('테스트 이슈') // 다른 필드 보존
    expect(next.content[1]).toBe(page.content[1]) // 무관 행은 참조까지 동일
    expect(page.content[0]?.priority).toBe(3) // 원본 불변
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useIssueListCellField — mutation 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useIssueListCellField', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    queryClient.setQueryData(LIST_KEY, { content: [makeIssue()] })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('저장 실패 시 낙관적 변경을 스냅샷으로 되돌린다', async () => {
    vi.mocked(updateIssue).mockRejectedValue(new Error('save failed'))

    const { result } = renderHook(() => useIssueListCellField(LIST_KEY), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'priority',
        toPriority: 1,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<{ content: IssueResponse[] }>(LIST_KEY)
    expect(cached?.content[0]?.priority).toBe(3) // 롤백됨
  })

  it('409 TRANSITION_NOT_ALLOWED 와 VERSION_CONFLICT 를 다른 문구로 안내한다 (FR15)', async () => {
    vi.mocked(transitionIssue)
      .mockRejectedValueOnce(new ApiError(409, { errorCode: 'TRANSITION_NOT_ALLOWED' }))
      .mockRejectedValueOnce(new ApiError(409, { errorCode: 'VERSION_CONFLICT' }))

    const { result } = renderHook(() => useIssueListCellField(LIST_KEY), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'status',
        toStatusKey: 'DONE',
        expectedVersion: 1,
      })
    })
    await waitFor(() => expect(vi.mocked(toast.error).mock.calls).toHaveLength(1))
    const firstMessage = vi.mocked(toast.error).mock.calls.at(-1)?.[0]

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'status',
        toStatusKey: 'DONE',
        expectedVersion: 1,
      })
    })
    await waitFor(() => expect(vi.mocked(toast.error).mock.calls).toHaveLength(2))
    const secondMessage = vi.mocked(toast.error).mock.calls.at(-1)?.[0]

    // 사유별 정확한 키로 매핑되는지까지 본다 — "서로 다르기만" 하면 키 drift 를 놓친다
    // (learnings `error-key 매핑은 공유 util 경유`).
    expect(firstMessage).toBe(issueDetailStrings.transitionNotAllowedError)
    expect(secondMessage).toBe(issueDetailStrings.transitionVersionConflictError)
    expect(firstMessage).not.toBe(secondMessage)
  })
})
