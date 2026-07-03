// 보드 퀵필터 CRUD TanStack Query 훅 단위 테스트 (FR-UX-01 Task 9)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/board-quick-filters 전체 mock — 실제 HTTP 요청 없이 단위 테스트 (use-boards.test.tsx 동일 패턴)
vi.mock('@/api/board-quick-filters')

import {
  createQuickFilter,
  updateQuickFilter,
  deleteQuickFilter,
} from '@/api/board-quick-filters'
import type { QuickFilter } from '@/api/board-quick-filters'
import {
  useCreateQuickFilter,
  useUpdateQuickFilter,
  useDeleteQuickFilter,
} from './use-board-quick-filters'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

const QUICK_FILTER: QuickFilter = {
  filterId: 'f1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  name: '내 버그',
  query: 'assignee=abc&label=bug',
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
// useCreateQuickFilter
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateQuickFilter', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(createQuickFilter).mockResolvedValue(QUICK_FILTER)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-UX01-CQF-1: mutate 성공 시 createQuickFilter(boardId, request)를 호출하고 board 상세를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useCreateQuickFilter(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({ name: '내 버그', query: 'assignee=abc&label=bug' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(createQuickFilter).toHaveBeenCalledWith(BOARD_ID, {
      name: '내 버그',
      query: 'assignee=abc&label=bug',
    })
    // invalidate-only — 캐시를 setQueryData로 직접 덮지 않는다 (mutation-setquerydata-partial-response-flicker)
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateQuickFilter
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateQuickFilter', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(updateQuickFilter).mockResolvedValue({ ...QUICK_FILTER, name: '긴급 버그' })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-UX01-UQF-1: mutate 성공 시 updateQuickFilter(boardId, filterId, request)를 호출하고 board 상세를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateQuickFilter(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        filterId: QUICK_FILTER.filterId,
        request: { name: '긴급 버그', query: QUICK_FILTER.query },
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(updateQuickFilter).toHaveBeenCalledWith(BOARD_ID, QUICK_FILTER.filterId, {
      name: '긴급 버그',
      query: QUICK_FILTER.query,
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteQuickFilter
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteQuickFilter', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(deleteQuickFilter).mockResolvedValue(undefined)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-UX01-DQF-1: mutate 성공 시 deleteQuickFilter(boardId, filterId)를 호출하고 board 상세를 invalidate한다', async () => {
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useDeleteQuickFilter(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync(QUICK_FILTER.filterId)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(deleteQuickFilter).toHaveBeenCalledWith(BOARD_ID, QUICK_FILTER.filterId)
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })
})
