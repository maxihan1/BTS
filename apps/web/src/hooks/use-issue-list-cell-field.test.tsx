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

import { updateIssue, transitionIssue, changeAssignee } from '@/api/issues'
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

  // ───────────────────────────────────────────────────────────────────────────
  // C1 — 상세 캐시 무효화
  //
  // 와이드 폭 `/issues` 는 목록과 상세 페인을 **동시에** 마운트한다. 목록에서 값을 바꾸면
  // 서버 version 이 오르는데 페인 캐시(`['issue', key]`)는 그대로라, 이어서 페인에서 뭘
  // 바꾸면 409 VERSION_CONFLICT 가 나고 "다른 사용자가 이미 수정했습니다" 가 뜬다.
  // 다른 사용자는 없다. 전환 목록(`['issue-transitions', key]`)도 같은 이유로 낡는다.
  // ───────────────────────────────────────────────────────────────────────────
  /**
   * 저장 뒤 **이 이슈를 보여 주는 화면 전부**가 갱신된다 (Maxi 보고 2026-09-07).
   *
   * 🛑 종전 단언은 `invalidateQueries` 가 받은 **인자 모양**을 봤다. 그래서 이 목록 페이지·
   *    상세·전환 셋만 재고, **보드·백로그가 빠진 것**을 보지 못했다 — 같은 이슈를 보드에서
   *    보고 있으면 새로고침 전까지 옛 값이었다.
   *
   * 지금은 인자가 아니라 **캐시 상태**를 잰다. 접두로 덮든 정확한 키를 열거하든, 화면이
   * 실제로 갱신되면 통과하고 아니면 red 다 — 구현 방식이 바뀌어도 계약이 살아남는다.
   */
  it('저장 후 이 이슈를 보여 주는 화면 캐시가 전부 stale 이 된다', async () => {
    vi.mocked(updateIssue).mockResolvedValue(makeIssue({ priority: 1, version: 2 }))

    const BOARD_KEY = ['board', 'b-1', {}]
    const BACKLOG_KEY = ['backlog', 'ATLAS', 'b-1']
    const DETAIL_KEY = ['issue', 'ATLAS-1']
    const TRANSITIONS_KEY = ['issue-transitions', 'ATLAS-1']
    for (const key of [BOARD_KEY, BACKLOG_KEY, DETAIL_KEY, TRANSITIONS_KEY]) {
      queryClient.setQueryData(key, { seeded: true })
    }

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
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      for (const key of [LIST_KEY, BOARD_KEY, BACKLOG_KEY, DETAIL_KEY, TRANSITIONS_KEY]) {
        expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true)
      }
    })
  })

  it('실패해도 상세 캐시를 무효화한다 — onSettled 경로 (리뷰 C1)', async () => {
    // 실패 시에도 서버 상태를 다시 읽어야 한다. 성공 경로에만 걸면 409 를 맞은 뒤
    // 페인이 낡은 version 을 계속 들고 있어 같은 409 가 반복된다.
    vi.mocked(updateIssue).mockRejectedValue(new ApiError(409, { errorCode: 'VERSION_CONFLICT' }))
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

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

    const invalidatedKeys = invalidateSpy.mock.calls.map((call) =>
      JSON.stringify(call[0]?.queryKey),
    )
    expect(invalidatedKeys).toContain(JSON.stringify(['issue', 'ATLAS-1']))
  })

  // ───────────────────────────────────────────────────────────────────────────
  // C5 — 실패 문구는 정본을 소비한다
  //
  // 같은 실패가 상세와 목록에서 다른 말로 안내되면 사용자는 다른 일이 일어난 줄 안다.
  // 이 훅은 `extractErrorCode` 를 공유하면서 정작 문구는 새로 지어 자기모순이었다.
  // ───────────────────────────────────────────────────────────────────────────
  it.each([
    ['assignee', issueDetailStrings.assigneeChangeError],
    ['priority', issueDetailStrings.priorityChangeError],
  ] as const)(
    '%s 일반 실패는 i18n 정본 문구를 쓴다 (리뷰 C5)',
    async (field, expected) => {
      // 409/422 가 아닌 일반 실패 — 사유별 분기가 아니라 fallback 경로를 탄다
      vi.mocked(updateIssue).mockRejectedValue(new Error('network down'))
      vi.mocked(changeAssignee).mockRejectedValue(new Error('network down'))

      const { result } = renderHook(() => useIssueListCellField(LIST_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        result.current.mutate({
          issueKey: 'ATLAS-1',
          field,
          toAssigneeId: null,
          toPriority: 1,
          expectedVersion: 1,
        })
      })
      await waitFor(() => expect(result.current.isError).toBe(true))

      expect(vi.mocked(toast.error).mock.calls.at(-1)?.[0]).toBe(expected)
    },
  )

  it('무효화 대상 이슈 키는 vars 에서 온다 — 다른 이슈를 건드리지 않는다 (리뷰 C1)', async () => {
    queryClient.setQueryData(LIST_KEY, { content: [makeIssue(), makeIssue({ key: 'ATLAS-2' })] })
    vi.mocked(updateIssue).mockResolvedValue(makeIssue({ key: 'ATLAS-2', priority: 1, version: 2 }))
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useIssueListCellField(LIST_KEY), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-2',
        field: 'priority',
        toPriority: 1,
        expectedVersion: 1,
      })
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const invalidatedKeys = invalidateSpy.mock.calls.map((call) =>
      JSON.stringify(call[0]?.queryKey),
    )
    expect(invalidatedKeys).toContain(JSON.stringify(['issue', 'ATLAS-2']))
    expect(invalidatedKeys).not.toContain(JSON.stringify(['issue', 'ATLAS-1']))
  })
})
