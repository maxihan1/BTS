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
  updateSprint,
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
  useUpdateSprint,
  backlogKeys,
} from './use-backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

/** `?board=` 로 지정된 보드 (FR-BD-04) */
const BOARD_A = 'b0000000-0000-4000-8000-00000000000a'
/** 같은 프로젝트의 **다른** 보드 — 캐시가 갈리는지 재는 짝 */
const BOARD_B = 'b0000000-0000-4000-8000-00000000000b'

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
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
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
          typeKey: 'task',
          labels: [],
          originalEstimateSeconds: null,
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
    const { result } = renderHook(() => useBacklog('ATLAS', BOARD_A), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchBacklog).toHaveBeenCalledWith('ATLAS', BOARD_A)
    expect(result.current.data).toEqual(MOCK_BACKLOG_VIEW)
  })

  it('T-BL-QUERY-2: projectKey가 빈 문자열이면 enabled=false로 fetchBacklog를 호출하지 않는다', async () => {
    const { result } = renderHook(() => useBacklog('', undefined), {
      wrapper: createWrapper(queryClient),
    })

    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchBacklog).not.toHaveBeenCalled()
  })

  it('T-BL-QUERY-3: boardId 미지정(`?board=` 없는 진입)은 undefined 그대로 흘려 서버 폴백에 맡긴다', async () => {
    const { result } = renderHook(() => useBacklog('ATLAS', undefined), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 프론트가 기본 보드를 **골라주지 않는다** — 기본 보드 판단은 서버 한 곳뿐이어야 한다(E4)
    expect(fetchBacklog).toHaveBeenCalledWith('ATLAS', undefined)
  })

  it('T-BL-QUERY-4: 같은 프로젝트라도 보드가 다르면 캐시가 갈려 각각 조회한다 (FR-BD-04)', async () => {
    const { result: a } = renderHook(() => useBacklog('ATLAS', BOARD_A), {
      wrapper: createWrapper(queryClient),
    })
    await waitFor(() => expect(a.current.isSuccess).toBe(true))

    const { result: b } = renderHook(() => useBacklog('ATLAS', BOARD_B), {
      wrapper: createWrapper(queryClient),
    })
    await waitFor(() => expect(b.current.isSuccess).toBe(true))

    // 키가 갈리지 않으면 두 번째는 캐시 히트로 끝나 **다른 보드의 백로그를 그대로 보여준다**
    expect(fetchBacklog).toHaveBeenCalledTimes(2)
    expect(fetchBacklog).toHaveBeenNthCalledWith(1, 'ATLAS', BOARD_A)
    expect(fetchBacklog).toHaveBeenNthCalledWith(2, 'ATLAS', BOARD_B)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// backlogKeys 팩토리
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogKeys', () => {
  it('T-BL-KEYS-1: backlogKeys.detail이 projectKey와 boardId를 포함한 tuple을 반환한다', () => {
    const key = backlogKeys.detail('ATLAS', BOARD_A)
    expect(key).toEqual(['backlog', 'ATLAS', BOARD_A])
  })

  it('T-BL-KEYS-2: 다른 projectKey는 다른 tuple을 반환한다', () => {
    expect(backlogKeys.detail('ATLAS', BOARD_A)).not.toEqual(backlogKeys.detail('BETA', BOARD_A))
  })

  it('T-BL-KEYS-3: 같은 프로젝트라도 보드가 다르면 다른 tuple이다 (FR-BD-04)', () => {
    expect(backlogKeys.detail('ATLAS', BOARD_A)).not.toEqual(backlogKeys.detail('ATLAS', BOARD_B))
  })

  it('T-BL-KEYS-4: boardId 미지정(기본 보드)도 고유한 키를 갖는다 — 지정 보드와 섞이지 않는다', () => {
    expect(backlogKeys.detail('ATLAS', undefined)).not.toEqual(backlogKeys.detail('ATLAS', BOARD_A))
  })

  it('T-BL-KEYS-5: backlogKeys.project는 boardId 없는 접두 키다 — 무효화가 전 보드를 덮는다', () => {
    expect(backlogKeys.project('ATLAS')).toEqual(['backlog', 'ATLAS'])
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
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
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

  it('T-BL-RERANK-3: 무효화가 **보드 스코프 캐시까지** 덮는다 (접두 매칭)', async () => {
    // 한 보드의 변경이 다른 보드의 백로그 칸을 바꾼다 — 스프린트에서 뺀 이슈는 **모든** 보드의
    // 백로그 칸에 나타난다(E12). 그래서 무효화는 보드 단위가 아니라 프로젝트 단위다.
    queryClient.setQueryData(backlogKeys.detail('ATLAS', BOARD_A), MOCK_BACKLOG_VIEW)
    queryClient.setQueryData(backlogKeys.detail('ATLAS', BOARD_B), MOCK_BACKLOG_VIEW)

    const { result } = renderHook(() => useRerankIssue('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ issueKey: 'ATLAS-1', body: { nextIssueKey: 'ATLAS-2' } })
    })

    expect(queryClient.getQueryState(backlogKeys.detail('ATLAS', BOARD_A))?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(backlogKeys.detail('ATLAS', BOARD_B))?.isInvalidated).toBe(true)
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
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
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
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
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
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
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
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
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
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateSprint (FR-UX-13 F15 FR-4)
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(updateSprint).mockResolvedValue({
      ...MOCK_SPRINT_META,
      endDate: '2026-07-20',
      version: 1,
    })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-UPDATE-SPRINT-1: 변경분과 version 을 그대로 넘기고 backlog queryKey 를 invalidate 한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        sprintId: SPRINT_ID,
        body: { endDate: '2026-07-20', version: 0 },
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(updateSprint).toHaveBeenCalledWith(SPRINT_ID, { endDate: '2026-07-20', version: 0 })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
    )
  })

  it('T-BL-UPDATE-SPRINT-2: 성공 응답의 SprintMeta 를 그대로 돌려준다 (기준값·version 갱신 재료)', async () => {
    // FR-4 — 다이얼로그는 이 응답으로 내부 기준값과 version 을 갱신한다. 그래야 재시도가
    // 낡은 version 으로 409 를 받지 않는다.
    const { result } = renderHook(() => useUpdateSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    let returned: SprintMeta | undefined
    await act(async () => {
      returned = await result.current.mutateAsync({
        sprintId: SPRINT_ID,
        body: { endDate: '2026-07-20', version: 0 },
      })
    })

    expect(returned?.version).toBe(1)
    expect(returned?.endDate).toBe('2026-07-20')
  })
})
