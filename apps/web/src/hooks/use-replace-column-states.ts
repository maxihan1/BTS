// 컬럼 상태 매핑 교체 mutation 훅 — 드롭 계획을 순서대로 보낸다 (부채 177 R5 · E8 · G2)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import { replaceColumnStates } from '@/api/board-columns'
import type { DropPlan } from '@/components/board/settings/state-mapping-drop'
import { boardKeys } from './use-boards'

/** HTTP 409 — 그 상태를 이미 다른 컬럼이 쓰고 있다 (#444 X1). */
const CONFLICT = 409

/**
 * 상태 매핑 드롭 하나를 서버에 반영한다.
 *
 * ### 계획을 **순서대로** 보낸다 — 병렬로 보내면 409 다
 * 컬럼 사이 이동은 「빼기 → 넣기」 두 번의 교체다([DropPlan]). 한 상태는 한 컬럼에만 속할 수
 * 있으므로(X1) 두 요청을 동시에 던지면 넣기가 먼저 도착해 409 가 난다. `for await` 로 직렬화한다.
 *
 * ### 컬럼 사이 이동의 부분 실패 창
 * 빼기가 성공하고 넣기가 실패하면 그 상태는 **미매핑으로 남는다.** 이 PR 은 그것을 되돌리지
 * 않는다 — 되돌리기 자체가 또 실패할 수 있고, 미매핑은 **화면에 보이는 회복 가능한 상태**다
 * (패널에 나타나므로 사용자가 다시 끌어다 놓으면 된다). 조용한 손상이 아니다.
 *
 * ### 실패는 **전부** 호출자에게 되돌려 준다 (G2)
 * 409 만 특별 취급하고 나머지를 삼키면, 네트워크 단절이나 500 에서 화면이 낙관적 반영을
 * 그대로 유지해 **서버와 다른 것을 보여 준다.** 이 훅은 어떤 실패든 그대로 던지고,
 * [isStateConflict] 로 문구만 갈라 쓰게 한다.
 *
 * @param boardId 대상 보드 UUID — 성공 시 이 보드의 상세 쿼리를 무효화한다.
 */
export function useReplaceColumnStates(boardId: string) {
  const queryClient = useQueryClient()

  return useMutation<void, unknown, DropPlan>({
    mutationFn: async (plan) => {
      // ★순서가 계약이다. `Promise.all` 로 바꾸면 넣기가 먼저 도착해 409 가 난다.
      for (const change of plan.changes) {
        await replaceColumnStates(boardId, change.columnId, change.stateKeys)
      }
    },
    onSettled: async () => {
      // 성공이든 실패든 재조회한다 — 실패 시 서버 상태가 화면의 낙관적 반영과 다르므로,
      // 무효화가 곧 되돌리기다(G2). onSuccess 에만 두면 실패한 드롭이 화면에 남는다.
      await queryClient.invalidateQueries({ queryKey: boardKeys.detail(boardId) })
    },
  })
}

/**
 * 이 실패가 「그 상태는 이미 다른 컬럼에 있다」인가 (E3).
 *
 * 409 만 전용 문구를 쓰고 나머지는 공통 실패 문구를 쓴다. 판정을 여기 한 곳에 두는 이유는
 * 상태 코드 리터럴이 화면 여러 곳에 흩어지는 것을 막기 위해서다.
 */
export function isStateConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === CONFLICT
}
