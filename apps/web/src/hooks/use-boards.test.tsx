// 칸반 보드 조회·생성 TanStack Query 훅 단위 테스트 (FR-BD-01/02)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/boards 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/boards')

import {
  fetchBoards,
  fetchBoard,
  createBoard,
} from '@/api/boards'
import type { BoardSummary, BoardDetail, BoardCreated, BoardCardFilterParams } from '@/api/boards'
import { useBoards, useBoard, useCreateBoard, boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_BOARD_SUMMARY: BoardSummary = {
  boardId: 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  projectKey: 'ATLAS',
  name: '기본 보드',
}

const MOCK_BOARD_DETAIL: BoardDetail = {
  boardId: 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  projectKey: 'ATLAS',
  name: '기본 보드',
  columns: [],
  truncated: false,
  unplacedCount: 0,
}

const MOCK_BOARD_CREATED: BoardCreated = {
  boardId: 'c2b3d4e5-f6a7-4b8c-9d0e-f1a2b3c4d5e6',
  projectKey: 'ATLAS',
  name: '새 보드',
  columns: [],
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
// useBoards
// ─────────────────────────────────────────────────────────────────────────────

describe('useBoards', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(fetchBoards).mockResolvedValue([MOCK_BOARD_SUMMARY])
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-BOARDS-1: projectKey가 있으면 fetchBoards를 호출하고 데이터를 반환한다', async () => {
    const { result } = renderHook(() => useBoards('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchBoards).toHaveBeenCalledWith('ATLAS')
    expect(result.current.data).toEqual([MOCK_BOARD_SUMMARY])
  })

  it('T-BD-BOARDS-2: projectKey가 빈 문자열이면 enabled=false로 fetchBoards를 호출하지 않는다', async () => {
    const { result } = renderHook(() => useBoards(''), {
      wrapper: createWrapper(queryClient),
    })

    // idle 상태 유지 확인
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchBoards).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useBoard
// ─────────────────────────────────────────────────────────────────────────────

describe('useBoard', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(fetchBoard).mockResolvedValue(MOCK_BOARD_DETAIL)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-BOARD-1: boardId가 있으면 fetchBoard를 호출하고 상세 데이터를 반환한다', async () => {
    const boardId = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

    const { result } = renderHook(() => useBoard(boardId), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchBoard).toHaveBeenCalledWith(boardId, undefined)
    expect(result.current.data).toEqual(MOCK_BOARD_DETAIL)
  })

  it('T-BD-BOARD-2: boardId가 undefined이면 enabled=false로 fetchBoard를 호출하지 않는다', async () => {
    const { result } = renderHook(() => useBoard(undefined), {
      wrapper: createWrapper(queryClient),
    })

    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchBoard).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// boardKeys.detail — filter-aware (FR-BD-02)
// ─────────────────────────────────────────────────────────────────────────────

describe('boardKeys.detail (filter-aware)', () => {
  const FILTER: BoardCardFilterParams = {
    assigneeIds: ['a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'],
    includeUnassigned: false,
    labels: ['bug'],
    componentIds: [],
  }

  it('T-BD-KEY-1: filter 없으면 2요소 tuple을 반환한다', () => {
    const key = boardKeys.detail('b1')
    expect(key).toEqual(['board', 'b1'])
    expect(key).toHaveLength(2)
  })

  it('T-BD-KEY-2: filter 있으면 3요소 tuple을 반환하고 3번째 요소가 존재한다', () => {
    const key = boardKeys.detail('b1', FILTER)
    expect(key).toHaveLength(3)
    expect(key[0]).toBe('board')
    expect(key[1]).toBe('b1')
    // 3번째 요소는 undefined가 아닌 정규화 표현이어야 한다
    expect(key[2]).toBeDefined()
  })

  it('T-BD-KEY-3: assigneeIds 배열 순서가 달라도 동일한 3번째 요소를 생성한다 (안정 정규화)', () => {
    const filterAB: BoardCardFilterParams = {
      assigneeIds: ['aaa', 'bbb'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const filterBA: BoardCardFilterParams = {
      assigneeIds: ['bbb', 'aaa'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const keyAB = boardKeys.detail('b1', filterAB)
    const keyBA = boardKeys.detail('b1', filterBA)
    expect(keyAB[2]).toEqual(keyBA[2])
  })

  it('T-BD-KEY-4: labels 배열 순서가 달라도 동일한 3번째 요소를 생성한다', () => {
    const filterXY: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: ['bug', 'feature'],
      componentIds: [],
    }
    const filterYX: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: ['feature', 'bug'],
      componentIds: [],
    }
    expect(boardKeys.detail('b1', filterXY)[2]).toEqual(boardKeys.detail('b1', filterYX)[2])
  })

  it('T-BD-KEY-5: componentIds 배열 순서가 달라도 동일한 3번째 요소를 생성한다', () => {
    const filterPQ: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: ['c1', 'c2'],
    }
    const filterQP: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: ['c2', 'c1'],
    }
    expect(boardKeys.detail('b1', filterPQ)[2]).toEqual(boardKeys.detail('b1', filterQP)[2])
  })

  it('T-BD-KEY-6: 다른 filter 값이면 3번째 요소도 달라진다 (캐시 분리)', () => {
    const filterA: BoardCardFilterParams = {
      assigneeIds: ['user-a'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const filterB: BoardCardFilterParams = {
      assigneeIds: ['user-b'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    expect(boardKeys.detail('b1', filterA)[2]).not.toEqual(boardKeys.detail('b1', filterB)[2])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useBoard — filter-aware (FR-BD-02)
// ─────────────────────────────────────────────────────────────────────────────

describe('useBoard (filter-aware)', () => {
  let queryClient: QueryClient

  const FILTER: BoardCardFilterParams = {
    assigneeIds: ['a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'],
    includeUnassigned: true,
    labels: ['bug'],
    componentIds: ['c1c2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'],
  }

  const MOCK_BOARD_DETAIL_FILTERED: BoardDetail = {
    boardId: 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
    projectKey: 'ATLAS',
    name: '기본 보드',
    columns: [],
    truncated: false,
    unplacedCount: 0,
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(fetchBoard).mockResolvedValue(MOCK_BOARD_DETAIL_FILTERED)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-BOARD-FILTER-1: filter를 전달하면 fetchBoard(boardId, filter)를 호출한다', async () => {
    const boardId = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

    const { result } = renderHook(() => useBoard(boardId, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchBoard).toHaveBeenCalledWith(boardId, FILTER)
    expect(result.current.data).toEqual(MOCK_BOARD_DETAIL_FILTERED)
  })

  it('T-BD-BOARD-FILTER-2: filter 없이 호출하면 기존 동작(fetchBoard(boardId) 1인수)과 호환된다', async () => {
    const boardId = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

    const { result } = renderHook(() => useBoard(boardId), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // filter 미전달 시 fetchBoard를 boardId 하나로 호출한다 (undefined 제외)
    expect(fetchBoard).toHaveBeenCalledWith(boardId, undefined)
  })

  it('T-BD-BOARD-FILTER-3: filter가 바뀌면 queryKey가 달라져 재조회가 트리거된다', async () => {
    const boardId = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
    const filterA: BoardCardFilterParams = {
      assigneeIds: ['user-a'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const filterB: BoardCardFilterParams = {
      assigneeIds: ['user-b'],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }

    // filterA로 첫 조회
    const { result, rerender } = renderHook(
      ({ filter }: { filter: BoardCardFilterParams }) => useBoard(boardId, filter),
      { wrapper: createWrapper(queryClient), initialProps: { filter: filterA } },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(fetchBoard).toHaveBeenCalledTimes(1)

    // filterB로 변경 → 다른 queryKey로 재조회
    rerender({ filter: filterB })
    await waitFor(() => expect(fetchBoard).toHaveBeenCalledTimes(2))
    expect(fetchBoard).toHaveBeenNthCalledWith(2, boardId, filterB)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateBoard
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateBoard', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(createBoard).mockResolvedValue(MOCK_BOARD_CREATED)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-CREATE-1: mutate 성공 시 createBoard를 호출하고 보드 목록을 invalidate한다', async () => {
    const projectKey = 'ATLAS'
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useCreateBoard(projectKey), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ name: '새 보드' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(createBoard).toHaveBeenCalledWith(projectKey, '새 보드')
    expect(result.current.data).toEqual(MOCK_BOARD_CREATED)

    // invalidateQueries가 boards 목록 queryKey로 호출되어야 한다
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['boards', projectKey] }),
    )
  })
})
