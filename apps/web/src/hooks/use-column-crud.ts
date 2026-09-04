// 컬럼 생성·삭제 mutation 훅 — 보드 상세 무효화까지 (부채 177 R6 · R7)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createColumn, deleteColumn } from '@/api/board-columns'
import type { ColumnMeta, DeleteColumnResult } from '@/api/board-columns'
import { boardKeys } from './use-boards'

/**
 * 컬럼을 만든다 (R6 · J23).
 *
 * 이름만 받는다 — `category` 는 보내지 않고(편차 X1) `stateKeys` 는 빈 배열이다. 만들어진 컬럼은
 * **상태 0개**이고 사용자가 이어서 상태를 끌어다 놓는다(J23→J27). 그 중간 상태를 표현할 수
 * 없으면 지라의 흐름 자체가 성립하지 않는다.
 *
 * @param boardId 대상 보드 UUID.
 */
export function useCreateColumn(boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<ColumnMeta, unknown, string>({
    mutationFn: (name) => createColumn(boardId, name),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}

/**
 * 컬럼을 지운다 (R7 · J26 · J28).
 *
 * 담겨 있던 상태는 미매핑 패널로 돌아간다 — 서버의 복합 FK `ON DELETE CASCADE` 가 그렇게 한다.
 * **이슈는 지워지지 않는다**: `board_columns` 와 `issues` 사이에 FK 가 없고 카드 배치는 조회
 * 시점 계산이라, 컬럼이 사라져도 이슈의 상태는 그대로다.
 *
 * ### 왜 `onSettled` 인가 (리뷰 CONCERNS C9)
 * 실패해도 재조회한다. 이 화면에서 가장 흔한 실패가 **동시 편집**(E6)이고 — 다른 사람이 이미
 * 지운 컬럼을 지우면 404 다 — 그때야말로 화면을 서버에 맞춰야 한다. `onSuccess` 면 404 를 받고도
 * 재조회가 안 돌아 **이미 없는 컬럼이 화면에 남는다.** 컬럼 조작 mutation 훅 4개
 * (생성·삭제·갱신·상태 교체)가 이 이유로 모두 `onSettled` 다.
 *
 * @param boardId 대상 보드 UUID.
 */
export function useDeleteColumn(boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<DeleteColumnResult, unknown, string>({
    mutationFn: (columnId) => deleteColumn(boardId, columnId),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}
