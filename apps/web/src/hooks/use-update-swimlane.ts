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
 * ★**보드가 확정되기 전에는 발사하지 않는다.** 호출부(`projects.$projectKey.board.tsx`)는
 * 보드 목록을 비동기로 받으므로 첫 렌더에서 `boardId` 가 `undefined` 다. 종전처럼 `?? ''`
 * 로 메우면 `PATCH /api/v1/boards//swimlane` 이 나가고, 빈 세그먼트 때문에 Spring Security
 * 포괄 규칙(`/api/**` authenticated)으로 떨어져 **로그인 상태에서도 401** 이 된다
 * (같은 양식을 `useVersions` 에서 프로덕션 실측으로 확인, 2026-09-07).
 *
 * 쿼리와 달리 mutation 에는 `enabled` 가 없다 — 사용자가 실제로 조작했을 때만 실행되므로
 * **던져서** 알린다. 조용히 무시하면 「눌렀는데 아무 일도 안 일어난다」가 되고, 그것은
 * 잘못된 요청이 나가는 것만큼이나 디버깅이 어렵다.
 *
 * @param boardId 스윔레인을 변경할 보드 UUID. 아직 모르면 `undefined`
 */
export function useUpdateSwimlane(boardId: string | undefined) {
  const queryClient = useQueryClient()

  return useMutation<BoardMeta, unknown, SwimlaneField>({
    mutationFn: (swimlaneField) => {
      if (boardId === undefined || boardId === '') {
        return Promise.reject(new Error('보드가 확정되기 전에는 스윔레인을 바꿀 수 없다'))
      }
      return updateBoardSwimlane(boardId, swimlaneField)
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId ?? '') })
    },
  })
}
