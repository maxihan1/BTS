// 보드 스윔레인 기준 변경 mutation 훅
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateBoardSwimlane } from '@/api/boards'
import type { BoardMeta, SwimlaneField } from '@/api/boards'
import { boardKeys } from './use-boards'

/**
 * 보드의 스윔레인 기준 필드를 변경하는 mutation 훅.
 *
 * PATCH /api/v1/boards/{boardId} → BoardMeta
 *
 * onSuccess에서 boardKeys.detail(boardId) 쿼리를 invalidate해 보드 상세를 재조회한다.
 * setQueryData로 캐시를 통째 덮지 않는다 (mutation 부분응답 플리커 방지).
 *
 * @param boardId 스윔레인을 변경할 보드 UUID
 */
export function useUpdateSwimlane(boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<BoardMeta, unknown, SwimlaneField>({
    mutationFn: (swimlaneField: SwimlaneField) => updateBoardSwimlane(boardId, swimlaneField),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}
