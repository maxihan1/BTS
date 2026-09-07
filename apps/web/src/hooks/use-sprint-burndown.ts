// 스프린트 번다운 조회 훅 — 보드에서 활성 스프린트를 찾아 2단으로 잇는다
import { useQuery } from '@tanstack/react-query'
import { fetchSprintBurndown } from '@/api/burndown'
import type { BurndownResponse } from '@/api/burndown'
import { useBoard } from './use-boards'

/**
 * 번다운 queryKey 팩토리 — 매직 문자열 방지.
 *
 * ★번다운 상세 라우트와 대시보드 가젯이 **같은 키**를 써야 캐시를 공유한다. 문자열을 두 곳에
 * 각각 적으면 그것이 서로를 검사하지 않는 두 목록이 되고, 한쪽 오타는 「캐시가 안 먹는다」로만
 * 드러나 아무도 원인을 못 짚는다.
 */
export const sprintBurndownKeys = {
  /** 스프린트별 번다운 시계열 */
  detail: (sprintId: string | undefined) => ['sprint-burndown', sprintId] as const,
}

/** 번다운 가젯이 쓰는 조회 결과 — 세 상태를 호출측이 구분할 수 있게 편다. */
export interface SprintBurndownByBoard {
  /** 번다운 시계열. 활성 스프린트가 없거나 아직 안 받았으면 undefined */
  readonly data: BurndownResponse | undefined
  /** 보드 또는 번다운을 받는 중 */
  readonly isLoading: boolean
  /** 어느 단계든 실패 — 404 도 포함한다 */
  readonly isError: boolean
  /**
   * 보드는 읽혔는데 **활성 스프린트가 없다.**
   *
   * 오류가 아니다 — 칸반 보드이거나 스프린트를 아직 시작하지 않은 스크럼 보드다.
   * `isError` 와 반드시 구분해야 화면이 「불러오지 못했다」 대신 「활성 스프린트가 없다」를 낸다.
   */
  readonly hasNoActiveSprint: boolean
}

/**
 * 보드 ID 로 그 보드의 **활성 스프린트** 번다운을 가져온다.
 *
 * 2단이다 — `GET /boards/{id}` 의 `activeSprint` 에서 스프린트 UUID 를 얻고,
 * 그것으로 `GET /sprints/{id}/burndown` 을 부른다. 신규 엔드포인트는 없다.
 *
 * ★가젯이 `sprintId` 가 아니라 `boardId` 를 받는 이유(M-3) — 스프린트는 2주마다 넘어간다.
 * `sprintId` 를 박아두면 스프린트가 끝나는 순간 가젯이 **끝난 스프린트의 번다운**을 계속
 * 보여주고, 사용자가 매번 설정을 고쳐야 한다. 보드는 안 바뀐다.
 *
 * @param boardId 보드 UUID. undefined 면 두 쿼리 모두 비활성.
 */
export function useSprintBurndownByBoard(boardId: string | undefined): SprintBurndownByBoard {
  const board = useBoard(boardId)
  const sprintId = board.data?.activeSprint?.sprintId

  const burndown = useQuery({
    queryKey: sprintBurndownKeys.detail(sprintId),
    queryFn: () => {
      if (sprintId === undefined) {
        throw new Error('sprintId is required')
      }
      return fetchSprintBurndown(sprintId)
    },
    staleTime: 30_000,
    enabled: sprintId !== undefined,
  })

  // 보드를 성공적으로 읽었는데 activeSprint 가 null 인 경우에만 참이다.
  // 아직 보드를 못 읽은 동안(로딩·실패)에는 「스프린트가 없다」고 말하면 안 된다.
  const hasNoActiveSprint = board.data !== undefined && sprintId === undefined

  return {
    data: burndown.data,
    isLoading: board.isLoading || (sprintId !== undefined && burndown.isLoading),
    isError: board.isError || burndown.isError,
    hasNoActiveSprint,
  }
}
