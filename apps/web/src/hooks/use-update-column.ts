// 컬럼 이름·WIP 제한 갱신 + 순서 교체 mutation 훅 (부채 177 R8 · R9 · R10)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateColumn, reorderColumns } from '@/api/board-columns'
import type { ColumnMeta } from '@/api/board-columns'
import type { BoardMeta } from '@/api/boards'
import { boardKeys } from './use-boards'

/** [useUpdateColumn] 의 mutate 인자. */
export interface UpdateColumnVars {
  /** 대상 컬럼 UUID */
  columnId: string
  /**
   * 바꿀 것만 담는다.
   *
   * ★`wipLimit` 은 **키의 존재**가 의미를 갖는다 — 키가 없으면 무변경, `null` 이면 해제다
   * (백엔드 3-state). 그래서 `undefined` 를 넣지 말고 키 자체를 빼야 한다.
   */
  patch: { name?: string; wipLimit?: number | null }
}

/**
 * 컬럼의 이름·WIP 제한을 갱신한다 (R8 · R9 · J24 · J29).
 *
 * @param boardId 대상 보드 UUID.
 */
export function useUpdateColumn(boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<ColumnMeta, unknown, UpdateColumnVars>({
    mutationFn: ({ columnId, patch }) => updateColumn(boardId, columnId, patch),
    onSettled: async () => {
      // 실패해도 재조회한다 — 낙관적으로 보이던 입력값이 서버 값으로 되돌아온다.
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}

/**
 * 컬럼 순서를 통째로 교체한다 (R10 · J25).
 *
 * 전 컬럼을 순서대로 보낸다 — 부분 이동 명령이 아니다. 서버가 집합 일치를 판정하므로
 * 빠지거나 중복되면 400 이다.
 *
 * @param boardId 대상 보드 UUID.
 */
export function useReorderColumns(boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<BoardMeta, unknown, string[]>({
    mutationFn: (columnIds) => reorderColumns(boardId, columnIds),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}
