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
  updateBoardName,
  deleteBoard,
} from '@/api/boards'
import type {
  BoardSummary,
  BoardDetail,
  BoardCreated,
  BoardCardFilterParams,
  BoardMeta,
} from '@/api/boards'
import {
  useBoards,
  useBoard,
  useCreateBoard,
  useUpdateBoardName,
  useDeleteBoard,
  boardKeys,
  BoardDeleteTimeoutError,
  DELETE_BOARD_TIMEOUT_MS,
} from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_BOARD_SUMMARY: BoardSummary = {
  boardId: 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  projectKey: 'ATLAS',
  name: '기본 보드',
  boardType: 'KANBAN',
}

const MOCK_BOARD_DETAIL: BoardDetail = {
  boardId: 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  projectKey: 'ATLAS',
  name: '기본 보드',
  boardType: 'KANBAN',
  activeSprint: null,
  columns: [],
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'NONE',
  quickFilters: [],
}

// 응답 픽스처의 종류는 KANBAN 으로 고정한다 — 아래 SCRUM 단언이 응답을 되읽어 통과하는
// 가짜 그린을 막는다. SCRUM 이 관찰되는 경로는 mutate 입력뿐이어야 한다.
const MOCK_BOARD_CREATED: BoardCreated = {
  boardId: 'c2b3d4e5-f6a7-4b8c-9d0e-f1a2b3c4d5e6',
  projectKey: 'ATLAS',
  name: '새 보드',
  boardType: 'KANBAN',
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

  it('T-BD-KEY-7: boardKeys.all 은 상세 키 두 변종의 공통 접두다 — 무효화 한 번이 필터 변종까지 덮는다', () => {
    // 스프린트 시작·완료는 어느 보드가 바뀌는지 모른 채 무효화한다(use-backlog.ts). 그 접두가
    // 상세 키와 어긋나면 무효화는 조용히 아무것도 안 덮는다 — 실패가 아니라 옛 화면이다.
    expect(boardKeys.all).toEqual(['board'])
    expect(boardKeys.detail('b1').slice(0, 1)).toEqual([...boardKeys.all])
    expect(boardKeys.detail('b1', FILTER).slice(0, 1)).toEqual([...boardKeys.all])
  })

  it('T-BD-KEY-8: boardKeys.all 은 목록 키를 덮지 않는다 — `board` 와 `boards` 는 다른 축이다', () => {
    expect(boardKeys.list('ATLAS')[0]).not.toBe(boardKeys.all[0])
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
    boardType: 'KANBAN',
    activeSprint: null,
    columns: [],
    truncated: false,
    unplacedCount: 0,
    unmappedStates: [],
    swimlaneField: 'NONE',
    quickFilters: [],
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
      await result.current.mutateAsync({ name: '새 보드', boardType: 'KANBAN' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(createBoard).toHaveBeenCalledWith(projectKey, '새 보드', 'KANBAN')
    expect(result.current.data).toEqual(MOCK_BOARD_CREATED)

    // invalidateQueries가 boards 목록 queryKey로 호출되어야 한다
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['boards', projectKey] }),
    )
  })

  it('T-BD-CREATE-2: 입력의 boardType 을 createBoard 세 번째 인자로 그대로 넘긴다 (FR-BD-04)', async () => {
    const projectKey = 'ATLAS'

    const { result } = renderHook(() => useCreateBoard(projectKey), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ name: '새 보드', boardType: 'SCRUM' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // ★훅이 종류를 흘리면 화면에서 고른 스크럼이 KANBAN 으로 조용히 생성된다 —
    //   백엔드가 미전송 시 KANBAN 을 채우므로(#421) 요청은 성공하고 아무도 모른다.
    expect(createBoard).toHaveBeenCalledWith(projectKey, '새 보드', 'SCRUM')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateBoardName — 보드 이름 변경 (FR-BD-01-2a)
//
// ★이 축은 Task 4 가 훅을 만들고도 단언을 남기지 못한 자리다 — invalidate 를 통째로 지워도
//   초록이었다. 아래 두 describe 가 그 공백을 닫는다.
// ─────────────────────────────────────────────────────────────────────────────

/** 이름 변경 대상 보드 UUID — 상세 키 단언에 그대로 쓰인다 */
const RENAME_BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

const MOCK_BOARD_META: BoardMeta = {
  boardId: RENAME_BOARD_ID,
  projectKey: 'ATLAS',
  name: '바뀐 보드',
  swimlaneField: 'NONE',
  boardType: 'KANBAN',
}

describe('useUpdateBoardName', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(updateBoardName).mockResolvedValue(MOCK_BOARD_META)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-RENAME-1: 성공 시 목록 키와 상세 키를 모두 invalidate 한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateBoardName('ATLAS', RENAME_BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ name: '바뀐 보드' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(updateBoardName).toHaveBeenCalledWith(RENAME_BOARD_ID, '바뀐 보드')
    // 목록 — 스위처의 이름 표기가 갱신되는 근거
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['boards', 'ATLAS'] }),
    )
    // 상세 — 헤더의 이름 표기가 갱신되는 근거. 2요소 키는 필터가 걸린 3요소 변종의 접두다
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['board', RENAME_BOARD_ID] }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteBoard — 보드 소프트 삭제 (FR-BD-01-2b)
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteBoard', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(deleteBoard).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-DELETE-1: 성공 시 목록 키만 invalidate 한다 — 상세 키는 건드리지 않는다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteBoard('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ boardId: RENAME_BOARD_ID })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 두 번째 인자는 상한 헬퍼가 만든 취소 신호다 — 이게 빠지면 요청을 끊을 방법이 없다.
    expect(deleteBoard).toHaveBeenCalledWith(RENAME_BOARD_ID, expect.any(AbortSignal))
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['boards', 'ATLAS'] }),
    )
    // ★상세 키를 무효화하면 아직 마운트된 useBoard(boardId) 관찰자가 곧바로 재조회를 걸어
    //   방금 지운 보드에 404 를 받는다. 「지웠는데 화면이 에러로 바뀌는」 경로가 그것이다.
    expect(invalidateSpy).not.toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: ['board', RENAME_BOARD_ID] }),
    )
  })

  it('T-BD-DELETE-2: 타임아웃이 지나면 요청이 취소되고 isPending 이 풀린다', async () => {
    // 응답이 오지 않는 요청 — 네트워크가 끊긴 채 pending 에 머무는 상태를 재현한다.
    // 이 상태에서 isPending 이 계속 참이면 ConfirmDialog 의 confirming 이 닫힘 경로를 전부
    // 잠근 채 풀리지 않아 사용자가 창에 갇힌다(게이트 1 지시).
    //
    // mock 은 실제 fetch 처럼 **signal 을 존중한다** — 취소를 무시하는 mock 을 쓰면
    // 「요청이 살아 있다」는 결함(장부 146)이 테스트에서 보이지 않는다.
    const signals: (AbortSignal | undefined)[] = []
    vi.mocked(deleteBoard).mockImplementation((_boardId, signal) => {
      signals.push(signal)
      return new Promise<void>((_resolve, reject) => {
        signal?.addEventListener('abort', () => {
          reject(new DOMException('The operation was aborted.', 'AbortError'))
        })
      })
    })
    vi.useFakeTimers()

    try {
      const { result } = renderHook(() => useDeleteBoard('ATLAS'), {
        wrapper: createWrapper(queryClient),
      })

      act(() => {
        result.current.mutate({ boardId: RENAME_BOARD_ID })
      })

      // 타임아웃 직전까지는 계속 기다린다 — 판정이 「항상 참」이 아님을 여기서 본다.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(DELETE_BOARD_TIMEOUT_MS - 1)
      })
      expect(result.current.isPending).toBe(true)
      expect(signals[0]?.aborted).toBe(false)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2)
      })

      // ★상한이 지난 뒤 요청이 실제로 끊겼다는 유일한 증거다 (장부 146).
      expect(signals[0]?.aborted).toBe(true)
      expect(result.current.isPending).toBe(false)
      expect(result.current.error).toBeInstanceOf(BoardDeleteTimeoutError)
    } finally {
      vi.useRealTimers()
    }
  })
})
