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
  deleteSprint,
} from '@/api/backlog'
import type { BacklogView, IssueRankResult, SprintMeta } from '@/api/backlog'
import type { BoardCardFilterParams } from '@/api/boards'
import { DELETE_TIMEOUT_MS, DeleteTimeoutError } from '@/lib/delete-timeout'
import {
  useBacklog,
  useRerankIssue,
  useAssignToSprint,
  useUnassignFromSprint,
  useCreateSprint,
  useStartSprint,
  useCompleteSprint,
  useUpdateSprint,
  useDeleteSprint,
  backlogKeys,
} from './use-backlog'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

/** `?board=` 로 지정된 보드 (FR-BD-04) */
const BOARD_A = 'b0000000-0000-4000-8000-00000000000a'
/** 같은 프로젝트의 **다른** 보드 — 캐시가 갈리는지 재는 짝 */
const BOARD_B = 'b0000000-0000-4000-8000-00000000000b'

/**
 * 보드 화면이 실제로 쓰는 필터 — 상세 캐시를 3요소 키(`['board', id, filter]`)로 만든다.
 *
 * 무효화가 2요소 키만 정확히 짚으면 이 변종이 살아남는다. 화면은 필터를 걸고 보는 쪽이라
 * 「무효화했는데 화면은 그대로」가 되는 자리가 여기다.
 */
const BOARD_FILTER: BoardCardFilterParams = {
  assigneeIds: [],
  includeUnassigned: false,
  labels: ['bug'],
  componentIds: [],
}

/**
 * 보드 상세 캐시 자리를 채우는 표식.
 *
 * 내용은 판정에 안 쓴다 — 이 축이 재는 것은 `isInvalidated` 플래그뿐이라 BoardDetail 전체를
 * 짓는 것은 판정과 무관한 픽스처 유지비만 늘린다.
 */
const BOARD_CACHE_MARKER = { marker: 'board-detail' } as const

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

/**
 * 스프린트 전환이 덮어야 할 보드 상세 캐시를 미리 심는다.
 *
 * 세 자리를 심는 이유. 스프린트가 어느 보드에 속하는지 훅은 모르고(BOARD_B), 화면은 필터를 건
 * 3요소 키로 본다(BOARD_FILTER). 두 자리 중 하나라도 남으면 `useBoard` 의 staleTime 동안
 * 옛 보드가 그대로 보인다.
 *
 * @param queryClient 캐시를 심을 대상 클라이언트
 */
function seedBoardDetailCaches(queryClient: QueryClient): void {
  queryClient.setQueryData(boardKeys.detail(BOARD_A), BOARD_CACHE_MARKER)
  queryClient.setQueryData(boardKeys.detail(BOARD_A, BOARD_FILTER), BOARD_CACHE_MARKER)
  queryClient.setQueryData(boardKeys.detail(BOARD_B), BOARD_CACHE_MARKER)
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

  it('T-BL-START-SPRINT-2: 성공 시 **보드 접두 키**도 invalidate 한다 (J18 — 시작하면 보드가 바뀐다)', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    seedBoardDetailCaches(queryClient)

    const { result } = renderHook(() => useStartSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 어느 키로 불렸는지까지 본다 — 호출 횟수만 세면 백로그 무효화가 그 자리를 채워 통과한다
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.all }),
    )
    // 접두 매칭의 결과를 캐시 상태로 되잰다 — 팩토리가 바뀌어도 이 판정은 화면 쪽에 붙어 있다
    expect(queryClient.getQueryState(boardKeys.detail(BOARD_A))?.isInvalidated).toBe(true)
    expect(
      queryClient.getQueryState(boardKeys.detail(BOARD_A, BOARD_FILTER))?.isInvalidated,
    ).toBe(true)
    expect(queryClient.getQueryState(boardKeys.detail(BOARD_B))?.isInvalidated).toBe(true)
  })

  it('T-BL-START-SPRINT-3: 보드 **목록** 키는 건드리지 않는다 (`board` ↔ `boards` 한 글자 차)', async () => {
    // 시작은 보드의 이름도 종류도 안 바꾼다. 접두가 `boards` 로 미끄러지면 스위처 목록까지
    // 매번 다시 부르게 되는데, 그 낭비는 화면이 멀쩡해서 아무 테스트에도 안 걸린다.
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    queryClient.setQueryData(boardKeys.list('ATLAS'), BOARD_CACHE_MARKER)

    const { result } = renderHook(() => useStartSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    expect(invalidateSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.list('ATLAS') }),
    )
    expect(queryClient.getQueryState(boardKeys.list('ATLAS'))?.isInvalidated).toBe(false)
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

  it('T-BL-COMPLETE-SPRINT-2: 성공 시 **보드 접두 키**도 invalidate 한다 (시작의 대칭 — 보드가 빈다)', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    seedBoardDetailCaches(queryClient)

    const { result } = renderHook(() => useCompleteSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.all }),
    )
    expect(queryClient.getQueryState(boardKeys.detail(BOARD_A))?.isInvalidated).toBe(true)
    expect(
      queryClient.getQueryState(boardKeys.detail(BOARD_A, BOARD_FILTER))?.isInvalidated,
    ).toBe(true)
    expect(queryClient.getQueryState(boardKeys.detail(BOARD_B))?.isInvalidated).toBe(true)
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

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteSprint (FR-3)
//
// 삭제는 시작·완료와 **같은 무효화 규약**을 쓴다. 규약이 갈리면 「가끔 안 바뀌는 화면」으로만
// 남아 어떤 실패로도 드러나지 않으므로, 여기서도 보드 축을 함께 잰다.
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteSprint', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(deleteSprint).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BL-DELETE-SPRINT-1: 성공 시 취소 신호를 함께 넘기고 backlog 접두 키를 invalidate 한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 두 번째 인자는 상한 헬퍼가 만든 취소 신호다 — 이게 빠지면 요청을 끊을 방법이 없다.
    expect(deleteSprint).toHaveBeenCalledWith(SPRINT_ID, expect.any(AbortSignal))
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: backlogKeys.project('ATLAS') }),
    )
  })

  it('T-BL-DELETE-SPRINT-2: 지금 보고 있지 않은 보드의 백로그·보드 캐시까지 무효화한다', async () => {
    // ★이 판정이 이 훅의 존재 이유다. 무효화를 `backlogKeys.detail` 완전 일치로 좁히면
    //   BOARD_B 의 캐시가 조용히 낡은 채로 남는다(#424 BLOCKER-1 과 같은 양식). 지운 스프린트의
    //   이슈는 **모든 보드의** 백로그 칸에 나타나므로 무효화는 프로젝트 접두여야 한다(E12).
    //   보드 축이 함께 필요한 이유는 `useBoard` 의 staleTime 30초다 — 안 덮으면 최대 30초간
    //   없는 스프린트가 보드에 남는다.
    queryClient.setQueryData(backlogKeys.detail('ATLAS', BOARD_A), MOCK_BACKLOG_VIEW)
    queryClient.setQueryData(backlogKeys.detail('ATLAS', BOARD_B), MOCK_BACKLOG_VIEW)
    seedBoardDetailCaches(queryClient)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteSprint('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(SPRINT_ID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(queryClient.getQueryState(backlogKeys.detail('ATLAS', BOARD_A))?.isInvalidated).toBe(true)
    expect(queryClient.getQueryState(backlogKeys.detail('ATLAS', BOARD_B))?.isInvalidated).toBe(true)
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.all }),
    )
    expect(queryClient.getQueryState(boardKeys.detail(BOARD_A))?.isInvalidated).toBe(true)
    expect(
      queryClient.getQueryState(boardKeys.detail(BOARD_A, BOARD_FILTER))?.isInvalidated,
    ).toBe(true)
    expect(queryClient.getQueryState(boardKeys.detail(BOARD_B))?.isInvalidated).toBe(true)
  })

  it('T-BL-DELETE-SPRINT-3: 상한이 지나면 요청이 취소되고 isPending 이 풀린다', async () => {
    // 확인 창은 `confirming` 동안 취소·Esc·오버레이를 전부 잠근다. 응답이 오지 않으면
    // 사용자가 창에 갇히므로 상한이 요청을 끊고 실패를 창 안으로 돌려줘야 한다(E-4 · 장부 145).
    // mock 은 실제 요청처럼 **signal 을 존중한다** — 취소를 무시하는 mock 을 쓰면
    // 「요청이 살아 있다」는 결함이 테스트에서 보이지 않는다.
    const signals: (AbortSignal | undefined)[] = []
    vi.mocked(deleteSprint).mockImplementation((_sprintId, signal) => {
      signals.push(signal)
      return new Promise<void>((_resolve, reject) => {
        signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'))
        })
      })
    })
    vi.useFakeTimers()

    try {
      const { result } = renderHook(() => useDeleteSprint('ATLAS'), {
        wrapper: createWrapper(queryClient),
      })

      act(() => {
        result.current.mutate(SPRINT_ID)
      })

      // 상한 직전까지는 계속 기다린다 — 판정이 「항상 참」이 아님을 여기서 본다.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(DELETE_TIMEOUT_MS - 1)
      })
      expect(result.current.isPending).toBe(true)
      expect(signals[0]?.aborted).toBe(false)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2)
      })

      expect(signals[0]?.aborted).toBe(true)
      expect(result.current.isPending).toBe(false)
      expect(result.current.error).toBeInstanceOf(DeleteTimeoutError)
    } finally {
      vi.useRealTimers()
    }
  })
})
