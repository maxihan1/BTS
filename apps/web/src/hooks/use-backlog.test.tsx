// 백로그·스프린트 TanStack Query 훅 단위 테스트 (FR-BL-01/02 D6/D7)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/backlog 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/backlog')

import {
  fetchBacklog,
  rerankIssue,
  assignToSprint,
  unassignFromSprint,
  createSprint,
  startSprint,
  completeSprint,
} from '@/api/backlog'
import type { BacklogView, IssueRankResult, SprintMeta } from '@/api/backlog'
import {
  useBacklog,
  useRerankIssue,
  useAssignToSprint,
  useUnassignFromSprint,
  useCreateSprint,
  useStartSprint,
  useCompleteSprint,
  backlogKeys,
} from './use-backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

const MOCK_SPRINT_META: SprintMeta = {
  sprintId: SPRINT_ID,
  name: '스프린트 1',
  goal: '첫 번째 스프린트 목표',
  status: 'PLANNED',
  startDate: null,
  endDate: null,
  version: 0,
}

const MOCK_BACKLOG_VIEW: BacklogView = {
  backlog: [
    {
      key: 'ATLAS-1',
      summary: '첫 번째 이슈',
      currentStateKey: 'open',
      assigneeId: '00000000-0000-4000-8000-000000000001',
      priority: 1,
      rank: '0|hzzzzz:',
      version: 0,
      epicKey: null,
    },
  ],
  sprints: [
    {
      sprint: MOCK_SPRINT_META,
      issues: [
        {
          key: 'ATLAS-3',
          summary: '세 번째 이슈',
          currentStateKey: 'open',
          assigneeId: null,
          priority: 2,
          rank: '0|i00007:',
          version: 0,
          epicKey: null,
        },
      ],
    },
  ],
  truncated: false,
}

const MOCK_RERANK_RESULT: IssueRankResult = {
  key: 'ATLAS-1',
  rank: '0|i00003:',
  version: 1,
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 팩토리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useBacklog
// ─────────────────────────────────────────────────────────────────────────────

describe('useBacklog', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(fetchBacklog).mockResolvedValue(MOCK_BACKLOG_VIEW)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-QUERY-1: projectKey가 있으면 fetchBacklog를 호출하고 BacklogView를 반환한다', async () => {
    const { result } = renderHook(() => useBacklog('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchBacklog).toHaveBeenCalledWith('ATLAS')
    expect(result.current.data).toEqual(MOCK_BACKLOG_VIEW)
  })

  it('T-BL-QUERY-2: projectKey가 빈 문자열이면 enabled=false로 fetchBacklog를 호출하지 않는다', async () => {
    const { result } = renderHook(() => useBacklog(''), {
      wrapper: createWrapper(queryClient),
    })

    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchBacklog).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// backlogKeys 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogKeys', () => {
  it('T-BL-KEYS-1: backlogKeys.detail이 projectKey를 포함한 tuple을 반환한다', () => {
    const key = backlogKeys.detail('ATLAS')
    expect(key).toEqual(['backlog', 'ATLAS'])
  })

  it('T-BL-KEYS-2: 다른 projectKey는 다른 tuple을 반환한다', () => {
    expect(backlogKeys.detail('ATLAS')).not.toEqual(backlogKeys.detail('BETA'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useRerankIssue
// ─────────────────────────────────────────────────────────────────────────────

describe('useRerankIssue', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(rerankIssue).mockResolvedValue(MOCK_RERANK_RESULT)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-RERANK-1: mutation 성공 시 rerankIssue를 호출하고 backlog queryKey를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useRerankIssue('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        body: { previousIssueKey: 'ATLAS-2' },
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(rerankIssue).toHaveBeenCalledWith('ATLAS-1', { previousIssueKey: 'ATLAS-2' })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.detail('ATLAS') }),
    )
  })

  it('T-BL-RERANK-2: setQueryData를 호출하지 않는다 (invalidate-only)', async () => {
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useRerankIssue('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        body: { nextIssueKey: 'ATLAS-2' },
      })
    })

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useAssignToSprint
// ─────────────────────────────────────────────────────────────────────────────

describe('useAssignToSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(assignToSprint).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-ASSIGN-1: mutation 성공 시 assignToSprint를 호출하고 backlog queryKey를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useAssignToSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ sprintId: SPRINT_ID, issueKey: 'ATLAS-1' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(assignToSprint).toHaveBeenCalledWith(SPRINT_ID, 'ATLAS-1')
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.detail('ATLAS') }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUnassignFromSprint
// ─────────────────────────────────────────────────────────────────────────────

describe('useUnassignFromSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(unassignFromSprint).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-UNASSIGN-1: mutation 성공 시 unassignFromSprint를 호출하고 backlog queryKey를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUnassignFromSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ sprintId: SPRINT_ID, issueKey: 'ATLAS-1' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(unassignFromSprint).toHaveBeenCalledWith(SPRINT_ID, 'ATLAS-1')
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.detail('ATLAS') }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateSprint
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(createSprint).mockResolvedValue(MOCK_SPRINT_META)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-CREATE-SPRINT-1: mutation 성공 시 createSprint를 호출하고 backlog queryKey를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useCreateSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        projectKey: 'ATLAS',
        name: '스프린트 2',
        goal: '두 번째 목표',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(createSprint).toHaveBeenCalledWith({
      projectKey: 'ATLAS',
      name: '스프린트 2',
      goal: '두 번째 목표',
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.detail('ATLAS') }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useStartSprint
// ─────────────────────────────────────────────────────────────────────────────

describe('useStartSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(startSprint).mockResolvedValue({ ...MOCK_SPRINT_META, status: 'ACTIVE', version: 1 })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-START-SPRINT-1: mutation 성공 시 startSprint를 호출하고 backlog queryKey를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useStartSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(startSprint).toHaveBeenCalledWith(SPRINT_ID)
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.detail('ATLAS') }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCompleteSprint
// ─────────────────────────────────────────────────────────────────────────────

describe('useCompleteSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(completeSprint).mockResolvedValue({
      ...MOCK_SPRINT_META,
      status: 'COMPLETED',
      version: 2,
    })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-COMPLETE-SPRINT-1: mutation 성공 시 completeSprint를 호출하고 backlog queryKey를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useCompleteSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(completeSprint).toHaveBeenCalledWith(SPRINT_ID)
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.detail('ATLAS') }),
    )
  })
})
