// 셀 내 카드 순서변경 mutation 훅 — 낙관적 업데이트·롤백·helper 단위 테스트 (FR-UX-06 PR21 Task-5)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/backlog 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/backlog')
// sonner toast mock — 실제 DOM 없이 호출 여부만 검증 (use-move-card.test.tsx 관례와 달리
// 이 훅은 onError 내부에서 직접 toast.error를 호출하므로 훅 테스트에서 mock 필요)
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { rerankIssue } from '@/api/backlog'
import type { IssueRankResult } from '@/api/backlog'
import { toast } from 'sonner'
import type { BoardDetail } from '@/api/boards'
import { useReorderCard, reorderCardInCell, patchCardRank } from './use-reorder-card'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
const COL_A_ID = 'ca000000-0000-4000-a000-000000000001'
const COL_B_ID = 'cb000000-0000-4000-b000-000000000002'

/** 1컬럼(카드 3개, rank 순) + 빈 컬럼 1개 */
const INITIAL_BOARD: BoardDetail = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: '기본 보드',
  boardType: 'KANBAN',
  activeSprint: null,
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'NONE',
  quickFilters: [],
  columns: [
    {
      columnId: COL_A_ID,
      states: [{ key: 'TODO', name: 'To Do', category: 'TODO' }],
      name: 'To Do',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-1', summary: '카드 1', assigneeId: null, version: 1, priority: 1, epicKey: null, rank: '0|100000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
        { issueKey: 'ATLAS-2', summary: '카드 2', assigneeId: null, version: 1, priority: 1, epicKey: null, rank: '0|200000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
        { issueKey: 'ATLAS-3', summary: '카드 3', assigneeId: null, version: 1, priority: 1, epicKey: null, rank: '0|300000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
      ],
    },
    {
      columnId: COL_B_ID,
      states: [{ key: 'IN_PROGRESS', name: '진행 중', category: 'IN_PROGRESS' }],
      name: '진행 중',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
}

const RANK_RESULT: IssueRankResult = {
  key: 'ATLAS-1',
  rank: '0|150000:',
  version: 5,
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
// useReorderCard — mutation 훅 통합 테스트
// ─────────────────────────────────────────────────────────────────────────────

const FILTER: import('@/api/boards').BoardCardFilterParams = {
  assigneeIds: ['u1-uuid-0000-0000-000000000001'],
  includeUnassigned: false,
  labels: ['bug'],
  componentIds: [],
}

describe('useReorderCard', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    queryClient.setQueryData(boardKeys.detail(BOARD_ID), INITIAL_BOARD)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-RC-1: onMutate에서 cancelQueries를 호출한다', async () => {
    vi.mocked(rerankIssue).mockResolvedValue(RANK_RESULT)
    const cancelSpy = vi.spyOn(queryClient, 'cancelQueries')

    const { result } = renderHook(() => useReorderCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
      await Promise.resolve()
    })

    expect(cancelSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-RC-2: 낙관적 순서변경 — mutate 직후(서버 응답 전) ATLAS-1이 ATLAS-2와 ATLAS-3 사이로 이동한다', async () => {
    let resolveRerank!: (v: IssueRankResult) => void
    vi.mocked(rerankIssue).mockReturnValue(
      new Promise<IssueRankResult>((res) => { resolveRerank = res }),
    )

    const { result } = renderHook(() => useReorderCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const colA = cached?.columns.find((c) => c.columnId === COL_A_ID)
    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-2', 'ATLAS-1', 'ATLAS-3'])

    resolveRerank(RANK_RESULT)
  })

  it('T-RC-3: 성공 rank/version 갱신 — onSuccess 후 카드 ATLAS-1의 rank/version이 서버 응답값이다', async () => {
    vi.mocked(rerankIssue).mockResolvedValue(RANK_RESULT)

    const { result } = renderHook(() => useReorderCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns.flatMap((c) => c.cards).find((c) => c.issueKey === 'ATLAS-1')

    expect(card?.rank).toBe('0|150000:')
    expect(card?.version).toBe(5)
  })

  it('T-RC-4: 성공 시에도 onSettled가 invalidateQueries를 호출한다', async () => {
    vi.mocked(rerankIssue).mockResolvedValue(RANK_RESULT)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useReorderCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-RC-5: 409 롤백 — ATLAS-1이 원래 위치로 복원되고 toast.error + invalidateQueries가 호출된다', async () => {
    const error = Object.assign(new Error('OCC 충돌'), { status: 409 })
    vi.mocked(rerankIssue).mockRejectedValue(error)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useReorderCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const colA = cached?.columns.find((c) => c.columnId === COL_A_ID)

    // 롤백 — 원래 순서(ATLAS-1, ATLAS-2, ATLAS-3)로 복원
    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-1', 'ATLAS-2', 'ATLAS-3'])

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('순서 변경'),
    )
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useReorderCard(boardId, filter) — filter-aware queryKey 회귀 가드
// ─────────────────────────────────────────────────────────────────────────────

describe('useReorderCard — filter-aware queryKey', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-FA-1: onMutate가 filter-aware 캐시를 낙관적으로 갱신한다', async () => {
    queryClient.setQueryData(boardKeys.detail(BOARD_ID, FILTER), INITIAL_BOARD)
    // 필터 없는 키는 시드하지 않음 — 이 키를 건드려선 안 된다

    let resolveRerank!: (v: IssueRankResult) => void
    vi.mocked(rerankIssue).mockReturnValue(
      new Promise<IssueRankResult>((res) => { resolveRerank = res }),
    )

    const { result } = renderHook(() => useReorderCard(BOARD_ID, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    const filteredCache = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID, FILTER))
    const colA = filteredCache?.columns.find((c) => c.columnId === COL_A_ID)
    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-2', 'ATLAS-1', 'ATLAS-3'])

    const unfilteredCache = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    expect(unfilteredCache).toBeUndefined()

    resolveRerank(RANK_RESULT)
  })

  it('T-FA-2: onError가 filter-aware 캐시를 롤백하고 invalidate한다', async () => {
    queryClient.setQueryData(boardKeys.detail(BOARD_ID, FILTER), INITIAL_BOARD)

    const error = Object.assign(new Error('OCC 충돌'), { status: 409 })
    vi.mocked(rerankIssue).mockRejectedValue(error)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useReorderCard(BOARD_ID, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        columnId: COL_A_ID,
        previousIssueKey: 'ATLAS-2',
        nextIssueKey: 'ATLAS-3',
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID, FILTER))
    const colA = cached?.columns.find((c) => c.columnId === COL_A_ID)
    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-1', 'ATLAS-2', 'ATLAS-3'])

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID, FILTER) }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// reorderCardInCell — 순수 helper 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('reorderCardInCell', () => {
  it('T-RCC-1: previous·next 이웃 사이로 카드를 이동한다', () => {
    const result = reorderCardInCell(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, 'ATLAS-2', 'ATLAS-3')
    const colA = result.columns.find((c) => c.columnId === COL_A_ID)

    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-2', 'ATLAS-1', 'ATLAS-3'])
  })

  it('T-RCC-2: previousIssueKey 없으면 셀 맨 앞으로 이동한다', () => {
    const result = reorderCardInCell(INITIAL_BOARD, 'ATLAS-3', COL_A_ID, undefined, 'ATLAS-1')
    const colA = result.columns.find((c) => c.columnId === COL_A_ID)

    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-3', 'ATLAS-1', 'ATLAS-2'])
  })

  it('T-RCC-3: nextIssueKey 없으면 셀 맨 뒤로 이동한다', () => {
    const result = reorderCardInCell(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, 'ATLAS-3', undefined)
    const colA = result.columns.find((c) => c.columnId === COL_A_ID)

    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-2', 'ATLAS-3', 'ATLAS-1'])
  })

  it('T-RCC-4: 원본 board가 변형되지 않는다 (immutable)', () => {
    const originalOrder = INITIAL_BOARD.columns[0]?.cards.map((c) => c.issueKey)

    reorderCardInCell(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, 'ATLAS-2', 'ATLAS-3')

    expect(INITIAL_BOARD.columns[0]?.cards.map((c) => c.issueKey)).toEqual(originalOrder)
  })

  it('T-RCC-5: 존재하지 않는 issueKey는 컬럼 카드 순서 변형 없이 반환한다', () => {
    const result = reorderCardInCell(INITIAL_BOARD, 'ATLAS-999', COL_A_ID, 'ATLAS-2', 'ATLAS-3')
    const colA = result.columns.find((c) => c.columnId === COL_A_ID)

    expect(colA?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-1', 'ATLAS-2', 'ATLAS-3'])
  })

  it('T-RCC-6: 존재하지 않는 columnId는 board를 변형 없이 반환한다', () => {
    const result = reorderCardInCell(INITIAL_BOARD, 'ATLAS-1', 'no-such-column', 'ATLAS-2', 'ATLAS-3')

    expect(result.columns[0]?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-1', 'ATLAS-2', 'ATLAS-3'])
    expect(result.columns[1]?.cards.length).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// patchCardRank — 순수 helper 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('patchCardRank', () => {
  it('T-PCR-1: 해당 카드의 rank·version을 갱신한다', () => {
    const result = patchCardRank(INITIAL_BOARD, 'ATLAS-1', '0|150000:', 5)
    const card = result.columns.flatMap((c) => c.cards).find((c) => c.issueKey === 'ATLAS-1')

    expect(card?.rank).toBe('0|150000:')
    expect(card?.version).toBe(5)
  })

  it('T-PCR-2: 원본 board가 변형되지 않는다 (immutable)', () => {
    const originalRank = INITIAL_BOARD.columns[0]?.cards[0]?.rank

    patchCardRank(INITIAL_BOARD, 'ATLAS-1', '0|999999:', 99)

    expect(INITIAL_BOARD.columns[0]?.cards[0]?.rank).toBe(originalRank)
  })

  it('T-PCR-3: 다른 카드의 rank·version은 변경되지 않는다', () => {
    const result = patchCardRank(INITIAL_BOARD, 'ATLAS-1', '0|150000:', 5)
    const card2 = result.columns.flatMap((c) => c.cards).find((c) => c.issueKey === 'ATLAS-2')

    expect(card2?.rank).toBe('0|200000:')
    expect(card2?.version).toBe(1)
  })
})
