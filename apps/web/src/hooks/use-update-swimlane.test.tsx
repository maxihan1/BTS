// 보드 스윔레인 기준 변경 mutation 훅 단위 테스트 (FR-BD-03 D6)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/boards 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/boards')

import { updateBoardSwimlane } from '@/api/boards'
import type { BoardMeta } from '@/api/boards'
import { boardKeys } from './use-boards'
import { useUpdateSwimlane } from './use-update-swimlane'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'

const MOCK_BOARD_META_ASSIGNEE: BoardMeta = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  swimlaneField: 'ASSIGNEE',
  boardType: 'KANBAN',
}

const MOCK_BOARD_META_NONE: BoardMeta = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  swimlaneField: 'NONE',
  boardType: 'KANBAN',
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
// useUpdateSwimlane
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateSwimlane', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(updateBoardSwimlane).mockResolvedValue(MOCK_BOARD_META_ASSIGNEE)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-BD-SW-1: mutate 성공 시 updateBoardSwimlane을 boardId와 field로 호출한다', async () => {
    const { result } = renderHook(() => useUpdateSwimlane(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('ASSIGNEE')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(vi.mocked(updateBoardSwimlane)).toHaveBeenCalledWith(BOARD_ID, 'ASSIGNEE')
  })

  it('T-BD-SW-2: mutate 성공 시 보드 단건 쿼리를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateSwimlane(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('ASSIGNEE')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: boardKeys.detail(BOARD_ID),
    })
  })

  it('T-BD-SW-3: mutate 성공 시 반환값이 BoardMeta이다', async () => {
    const { result } = renderHook(() => useUpdateSwimlane(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('ASSIGNEE')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(MOCK_BOARD_META_ASSIGNEE)
  })

  it('T-BD-SW-4: NONE으로 변경 시 updateBoardSwimlane을 NONE 인자로 호출한다', async () => {
    vi.mocked(updateBoardSwimlane).mockResolvedValue(MOCK_BOARD_META_NONE)

    const { result } = renderHook(() => useUpdateSwimlane(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('NONE')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(vi.mocked(updateBoardSwimlane)).toHaveBeenCalledWith(BOARD_ID, 'NONE')
  })

  it('T-BD-SW-5: API 오류 시 isError가 true가 된다', async () => {
    vi.mocked(updateBoardSwimlane).mockRejectedValue(new Error('서버 오류'))

    const { result } = renderHook(() => useUpdateSwimlane(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('ASSIGNEE')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
