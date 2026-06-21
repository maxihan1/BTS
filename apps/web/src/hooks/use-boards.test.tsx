// 칸반 보드 조회·생성 TanStack Query 훅 단위 테스트 (FR-BD-01)
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
import type { BoardSummary, BoardDetail, BoardCreated } from '@/api/boards'
import { useBoards, useBoard, useCreateBoard } from './use-boards'

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

    expect(fetchBoard).toHaveBeenCalledWith(boardId)
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
