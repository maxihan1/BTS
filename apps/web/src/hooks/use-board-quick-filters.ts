// 보드 퀵필터 CRUD TanStack Query 훅 — cross-mutation invalidate-only (FR-UX-01 Task 9)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createQuickFilter,
  updateQuickFilter,
  deleteQuickFilter,
} from '@/api/board-quick-filters'
import type {
  QuickFilter,
  CreateQuickFilterRequest,
  UpdateQuickFilterRequest,
} from '@/api/board-quick-filters'
import { boardKeys } from '@/hooks/use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// useCreateQuickFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드에 퀵필터를 생성한다.
 *
 * POST /api/v1/boards/{boardId}/quick-filters.
 * 성공 시 board 상세 쿼리(`quickFilters` 필드 포함)를 invalidate-only로 갱신한다.
 * `setQueryData`로 캐시를 직접 덮지 않는다 — 응답에 없는 파생 필드가 null로 덮여
 * 화면이 플리커하는 사고를 방지한다 (memory: mutation-setquerydata-partial-response-flicker).
 * `boardKeys.detail(boardId)`는 2요소 키(`['board', boardId]`)라 필터가 적용된
 * 3요소 변형 쿼리들도 prefix 매칭으로 함께 invalidate된다.
 *
 * @param boardId 퀵필터가 속한 보드 UUID
 */
export function useCreateQuickFilter(boardId: string) {
  const queryClient = useQueryClient()
  return useMutation<QuickFilter, unknown, CreateQuickFilterRequest>({
    mutationFn: (request) => createQuickFilter(boardId, request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateQuickFilter
// ─────────────────────────────────────────────────────────────────────────────

/** useUpdateQuickFilter mutation 입력 */
export interface UpdateQuickFilterInput {
  /** 수정할 퀵필터 UUID */
  filterId: string
  /** 수정 요청 바디. name·query 전체를 다시 받는다 (부분 patch 아님) */
  request: UpdateQuickFilterRequest
}

/**
 * 보드 퀵필터를 수정한다(name, query).
 *
 * PATCH /api/v1/boards/{boardId}/quick-filters/{filterId}.
 * invalidate-only 원칙은 useCreateQuickFilter와 동일하다.
 *
 * @param boardId 퀵필터가 속한 보드 UUID
 */
export function useUpdateQuickFilter(boardId: string) {
  const queryClient = useQueryClient()
  return useMutation<QuickFilter, unknown, UpdateQuickFilterInput>({
    mutationFn: ({ filterId, request }) => updateQuickFilter(boardId, filterId, request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteQuickFilter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 퀵필터를 삭제한다.
 *
 * DELETE /api/v1/boards/{boardId}/quick-filters/{filterId}.
 * invalidate-only 원칙은 useCreateQuickFilter와 동일하다.
 *
 * @param boardId 퀵필터가 속한 보드 UUID
 */
export function useDeleteQuickFilter(boardId: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, string>({
    mutationFn: (filterId) => deleteQuickFilter(boardId, filterId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}
