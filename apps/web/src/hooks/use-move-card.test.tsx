// 카드 이동 mutation 훅 — 낙관적 업데이트·롤백·helper 단위 테스트 (FR-BD-01 Task-4)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/boards 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/boards')

import { moveCard } from '@/api/boards'
import type { BoardDetail, MoveCardResult } from '@/api/boards'
import { useMoveCard, moveCardInBoard, patchCardVersion } from './use-move-card'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
const COL_A_ID = 'ca000000-0000-4000-a000-000000000001'
const COL_B_ID = 'cb000000-0000-4000-b000-000000000002'

/** 2컬럼 보드, col A에 카드 X / Y, col B는 빈 컬럼 */
const INITIAL_BOARD: BoardDetail = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: '기본 보드',
  truncated: false,
  unplacedCount: 0,
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: COL_A_ID,
      stateKey: 'TODO',
      name: 'To Do',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-1', summary: '카드 X', assigneeId: null, version: 1, priority: 1 },
        { issueKey: 'ATLAS-2', summary: '카드 Y', assigneeId: null, version: 1, priority: 1 },
      ],
    },
    {
      columnId: COL_B_ID,
      stateKey: 'IN_PROGRESS',
      name: '진행 중',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
}

const MOVE_RESULT: MoveCardResult = {
  issueKey: 'ATLAS-1',
  currentStateKey: 'IN_PROGRESS',
  version: 5,
  columnId: COL_B_ID,
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
// useMoveCard — mutation 훅 통합 테스트
// ─────────────────────────────────────────────────────────────────────────────

const FILTER: import('@/api/boards').BoardCardFilterParams = {
  assigneeIds: ['u1-uuid-0000-0000-000000000001'],
  includeUnassigned: false,
  labels: ['bug'],
  componentIds: [],
}

describe('useMoveCard', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    // 초기 보드 데이터를 캐시에 시드
    queryClient.setQueryData(boardKeys.detail(BOARD_ID), INITIAL_BOARD)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-MC-1: onMutate에서 cancelQueries를 호출한다', async () => {
    vi.mocked(moveCard).mockResolvedValue(MOVE_RESULT)
    const cancelSpy = vi.spyOn(queryClient, 'cancelQueries')

    const { result } = renderHook(() => useMoveCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
      // onMutate는 동기(microtask) — await 한 tick 대기
      await Promise.resolve()
    })

    expect(cancelSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-MC-2: 낙관적 이동 — mutate 직후(서버 응답 전) 카드 X가 col B에 있다', async () => {
    // moveCard를 절대 resolve하지 않는 pending Promise로 대체
    let resolveMove!: (v: MoveCardResult) => void
    vi.mocked(moveCard).mockReturnValue(
      new Promise<MoveCardResult>((res) => { resolveMove = res }),
    )

    const { result } = renderHook(() => useMoveCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
    })

    // onMutate가 처리될 때까지 대기
    await act(async () => {
      await Promise.resolve()
    })

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const colB = cached?.columns.find((c) => c.columnId === COL_B_ID)
    const colA = cached?.columns.find((c) => c.columnId === COL_A_ID)

    expect(colB?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(true)
    expect(colA?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(false)

    // 정리 — pending promise 해소
    resolveMove(MOVE_RESULT)
  })

  it('T-MC-3: 성공 version 갱신 — onSuccess 후 카드 X의 version이 5다', async () => {
    vi.mocked(moveCard).mockResolvedValue(MOVE_RESULT)

    const { result } = renderHook(() => useMoveCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const allCards = cached?.columns.flatMap((c) => c.cards) ?? []
    const card = allCards.find((c) => c.issueKey === 'ATLAS-1')

    expect(card?.version).toBe(5)
  })

  it('T-MC-4: 409 롤백 — 카드 X가 col A로 복원되고 invalidateQueries가 호출된다', async () => {
    const error = Object.assign(new Error('OCC 충돌'), { status: 409 })
    vi.mocked(moveCard).mockRejectedValue(error)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useMoveCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const colA = cached?.columns.find((c) => c.columnId === COL_A_ID)
    const colB = cached?.columns.find((c) => c.columnId === COL_B_ID)

    // 롤백 — 카드 X가 col A로 복원
    expect(colA?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(true)
    expect(colB?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(false)

    // 서버 진실 회복을 위한 invalidate
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-MC-5: resolutionId 전달 — moveCard 호출 인자에 resolutionId가 포함된다', async () => {
    vi.mocked(moveCard).mockResolvedValue(MOVE_RESULT)

    const { result } = renderHook(() => useMoveCard(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
        resolutionId: 'res-uuid-0000-0000-000000000001',
      })
    })

    expect(moveCard).toHaveBeenCalledWith(
      BOARD_ID,
      'ATLAS-1',
      expect.objectContaining({ resolutionId: 'res-uuid-0000-0000-000000000001' }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useMoveCard(boardId, filter) — filter-aware queryKey 회귀 가드 (Task-3)
// ─────────────────────────────────────────────────────────────────────────────

describe('useMoveCard — filter-aware queryKey', () => {
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
    // 필터된 queryKey에 보드를 시드
    queryClient.setQueryData(boardKeys.detail(BOARD_ID, FILTER), INITIAL_BOARD)
    // 필터 없는 키는 시드하지 않음 — 이 키를 건드려선 안 된다

    let resolveMove!: (v: MoveCardResult) => void
    vi.mocked(moveCard).mockReturnValue(
      new Promise<MoveCardResult>((res) => { resolveMove = res }),
    )

    const { result } = renderHook(() => useMoveCard(BOARD_ID, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    // filter-aware 캐시가 낙관적으로 갱신되어야 한다
    const filteredCache = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID, FILTER))
    const colB = filteredCache?.columns.find((c) => c.columnId === COL_B_ID)
    expect(colB?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(true)

    // filter 없는 캐시는 undefined 그대로여야 한다 (건드리지 않음)
    const unfilteredCache = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    expect(unfilteredCache).toBeUndefined()

    resolveMove(MOVE_RESULT)
  })

  it('T-FA-2: onError가 filter-aware 캐시를 롤백하고 invalidate한다', async () => {
    queryClient.setQueryData(boardKeys.detail(BOARD_ID, FILTER), INITIAL_BOARD)

    const error = Object.assign(new Error('OCC 충돌'), { status: 409 })
    vi.mocked(moveCard).mockRejectedValue(error)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useMoveCard(BOARD_ID, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 롤백 — filter-aware 캐시가 원래 상태로 복원
    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID, FILTER))
    const colA = cached?.columns.find((c) => c.columnId === COL_A_ID)
    expect(colA?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(true)

    // invalidate 대상도 filter-aware queryKey여야 한다
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID, FILTER) }),
    )
  })

  it('T-FA-3: onSuccess가 filter-aware 캐시의 version을 갱신한다', async () => {
    queryClient.setQueryData(boardKeys.detail(BOARD_ID, FILTER), INITIAL_BOARD)
    vi.mocked(moveCard).mockResolvedValue(MOVE_RESULT)

    const { result } = renderHook(() => useMoveCard(BOARD_ID, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        fromColumnId: COL_A_ID,
        toColumnId: COL_B_ID,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID, FILTER))
    const card = cached?.columns.flatMap((c) => c.cards).find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.version).toBe(5)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// moveCardInBoard — 순수 helper 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('moveCardInBoard', () => {
  it('T-MCB-1: fromColumn에서 카드를 제거하고 toColumn에 추가한다', () => {
    const result = moveCardInBoard(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, COL_B_ID)

    const colA = result.columns.find((c) => c.columnId === COL_A_ID)
    const colB = result.columns.find((c) => c.columnId === COL_B_ID)

    expect(colA?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(false)
    expect(colB?.cards.some((c) => c.issueKey === 'ATLAS-1')).toBe(true)
  })

  it('T-MCB-2: 원본 board가 변형되지 않는다 (immutable)', () => {
    const originalColACards = INITIAL_BOARD.columns[0]?.cards.length ?? 0

    moveCardInBoard(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, COL_B_ID)

    expect(INITIAL_BOARD.columns[0]?.cards.length).toBe(originalColACards)
  })

  it('T-MCB-3: ATLAS-2 카드는 col A에 그대로 남는다', () => {
    const result = moveCardInBoard(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, COL_B_ID)
    const colA = result.columns.find((c) => c.columnId === COL_A_ID)

    expect(colA?.cards.some((c) => c.issueKey === 'ATLAS-2')).toBe(true)
  })

  it('T-MCB-4: 존재하지 않는 issueKey는 board를 변형 없이 반환한다', () => {
    const result = moveCardInBoard(INITIAL_BOARD, 'ATLAS-999', COL_A_ID, COL_B_ID)

    expect(result.columns[0]?.cards.length).toBe(INITIAL_BOARD.columns[0]?.cards.length)
    expect(result.columns[1]?.cards.length).toBe(INITIAL_BOARD.columns[1]?.cards.length)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// patchCardVersion — 순수 helper 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('patchCardVersion', () => {
  it('T-PCV-1: 해당 카드의 version을 갱신한다', () => {
    const board = moveCardInBoard(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, COL_B_ID)
    const result = patchCardVersion(board, 'ATLAS-1', 5)

    const allCards = result.columns.flatMap((c) => c.cards)
    const card = allCards.find((c) => c.issueKey === 'ATLAS-1')

    expect(card?.version).toBe(5)
  })

  it('T-PCV-2: 원본 board가 변형되지 않는다 (immutable)', () => {
    const board = moveCardInBoard(INITIAL_BOARD, 'ATLAS-1', COL_A_ID, COL_B_ID)
    const originalVersion = board.columns
      .flatMap((c) => c.cards)
      .find((c) => c.issueKey === 'ATLAS-1')?.version

    patchCardVersion(board, 'ATLAS-1', 99)

    const afterVersion = board.columns
      .flatMap((c) => c.cards)
      .find((c) => c.issueKey === 'ATLAS-1')?.version

    expect(afterVersion).toBe(originalVersion)
  })

  it('T-PCV-3: 다른 카드의 version은 변경되지 않는다', () => {
    const result = patchCardVersion(INITIAL_BOARD, 'ATLAS-2', 10)
    const card1 = result.columns.flatMap((c) => c.cards).find((c) => c.issueKey === 'ATLAS-1')

    expect(card1?.version).toBe(1)
  })
})
