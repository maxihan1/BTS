// 칸반 보드 셀 내 카드 순서변경 mutation 훅 — 낙관적 업데이트·롤백 (FR-UX-06 PR21 Task-5)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { rerankIssue } from '@/api/backlog'
import type { IssueRankResult } from '@/api/backlog'
import type { BoardDetail, BoardCard, BoardCardFilterParams } from '@/api/boards'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 helper — 불변 캐시 조작
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드 배열에서 previous/next 이웃 사이에 삽입할 위치(index)를 계산한다.
 *
 * - previousIssueKey가 있으면 그 카드 바로 뒤 (없으면 배열 끝)
 * - previousIssueKey가 없고 nextIssueKey가 있으면 그 카드 바로 앞 (없으면 배열 맨 앞)
 * - 둘 다 없으면 배열 맨 앞 (rerankIssue 계약상 실제로는 발생하지 않는 방어적 기본값)
 *
 * @param cards 이동할 카드가 제거된 상태의 카드 배열
 * @param previousIssueKey 앞에 위치할 이슈 키
 * @param nextIssueKey 뒤에 위치할 이슈 키
 * @returns 삽입할 index
 */
function resolveInsertIndex(
  cards: BoardCard[],
  previousIssueKey: string | undefined,
  nextIssueKey: string | undefined,
): number {
  if (previousIssueKey !== undefined) {
    const idx = cards.findIndex((c) => c.issueKey === previousIssueKey)
    return idx === -1 ? cards.length : idx + 1
  }
  if (nextIssueKey !== undefined) {
    const idx = cards.findIndex((c) => c.issueKey === nextIssueKey)
    return idx === -1 ? 0 : idx
  }
  return 0
}

/**
 * 보드의 특정 컬럼 카드 배열 안에서 issueKey 카드를 previous/next 이웃 사이로 이동한다.
 *
 * 원본 board는 변형하지 않는다(immutable — map/filter 사용).
 * columnId가 없는 컬럼이나 issueKey가 없는 카드는 조용히 무시한다(원본 그대로 반환).
 *
 * 스윔레인 그룹(셀)은 previous/next 이웃 issueKey의 상대 위치로 이미 반영돼 있으므로
 * 이 helper는 컬럼의 flat 카드 배열만 다루면 된다 — 그룹 경계를 별도로 계산하지 않는다.
 *
 * @param board 현재 BoardDetail 캐시 값
 * @param issueKey 순서를 변경할 이슈 키. 예: "ATLAS-1"
 * @param columnId 카드가 속한 컬럼 UUID
 * @param previousIssueKey 앞에 위치할 이슈 키. 없으면 셀 맨 앞으로 이동
 * @param nextIssueKey 뒤에 위치할 이슈 키. 없으면 셀 맨 뒤로 이동
 * @returns 카드 순서가 변경된 새 BoardDetail (원본 불변)
 */
export function reorderCardInCell(
  board: BoardDetail,
  issueKey: string,
  columnId: string,
  previousIssueKey: string | undefined,
  nextIssueKey: string | undefined,
): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((col) => {
      if (col.columnId !== columnId) return col

      const movingCard = col.cards.find((c) => c.issueKey === issueKey)
      if (movingCard === undefined) return col

      const remaining = col.cards.filter((c) => c.issueKey !== issueKey)
      const insertIndex = resolveInsertIndex(remaining, previousIssueKey, nextIssueKey)

      return {
        ...col,
        cards: [...remaining.slice(0, insertIndex), movingCard, ...remaining.slice(insertIndex)],
      }
    }),
  }
}

/**
 * 보드 내 특정 카드의 rank·version 필드를 갱신한다.
 *
 * 낙관적 순서변경 후 서버 응답(IssueRankResult)의 최신 rank·version을 캐시에 반영할 때 사용.
 * use-move-card.ts의 patchCardVersion과 동일한 목적이나, rank 필드까지 함께 patch한다
 * (rerankIssue 응답은 version뿐 아니라 새 LexoRank 문자열도 포함하므로).
 * 원본 board는 변형하지 않는다(immutable).
 *
 * @param board 현재 BoardDetail 캐시 값
 * @param issueKey rank·version을 갱신할 이슈 키
 * @param rank 서버 응답의 새 LexoRank 문자열
 * @param version 서버 응답의 최신 version 번호
 * @returns rank·version이 갱신된 새 BoardDetail (원본 불변)
 */
export function patchCardRank(
  board: BoardDetail,
  issueKey: string,
  rank: string | null,
  version: number,
): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((col) => ({
      ...col,
      cards: col.cards.map((card) =>
        card.issueKey === issueKey ? { ...card, rank, version } : card,
      ),
    })),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useReorderCard mutation 훅 입력 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useReorderCard mutate 호출 변수 타입 */
export interface ReorderCardVars {
  /** 순서를 변경할 이슈 키 */
  issueKey: string
  /** 카드가 속한 컬럼 UUID — 낙관적 순서변경과 롤백에 사용 */
  columnId: string
  /** 앞에 위치할 이슈 키. 생략 시 셀 맨 앞으로 이동 */
  previousIssueKey?: string
  /** 뒤에 위치할 이슈 키. 생략 시 셀 맨 뒤로 이동 */
  nextIssueKey?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// useReorderCard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 셀(컬럼 × 스윔레인 그룹) 내 카드 순서를 변경하는 mutation 훅.
 *
 * - onMutate: cancelQueries → 스냅샷 저장 → 낙관적 캐시 업데이트(reorderCardInCell)
 * - onError: 스냅샷으로 롤백 + toast.error 안내
 * - onSuccess: 서버 응답의 rank·version으로 카드 patch
 * - onSettled: invalidateQueries — 성공/실패 관계없이 서버 진실과 최종 동기화
 *
 * filter-aware queryKey를 useBoard·useMoveCard와 공유 — 필터된 보드에서도 드래그 낙관적
 * 순서변경이 화면에 정합된다. filter 없이 호출하는 기존 코드와 호환된다.
 *
 * rerankIssue(backlog.ts)를 그대로 재사용한다 — issue-tracking BC의 이슈 rank 변경 API가
 * 이슈 rank의 유일한 쓰기 창구이므로 별도 API를 새로 만들지 않는다.
 *
 * @param boardId 보드 UUID
 * @param filter 선택적 카드 필터 파라미터 — useBoard에 전달한 것과 동일한 값이어야 한다
 */
export function useReorderCard(boardId: string, filter?: BoardCardFilterParams) {
  const queryClient = useQueryClient()
  const queryKey = boardKeys.detail(boardId, filter)

  return useMutation<IssueRankResult, unknown, ReorderCardVars, { snapshot: BoardDetail | undefined }>({
    mutationFn: (vars) =>
      rerankIssue(vars.issueKey, {
        previousIssueKey: vars.previousIssueKey,
        nextIssueKey: vars.nextIssueKey,
      }),

    onMutate: async (vars) => {
      // 드래그 중 refetch가 낙관적 상태를 덮어쓰지 않도록 진행 중 쿼리를 취소한다
      await queryClient.cancelQueries({ queryKey })

      // 롤백을 위한 스냅샷 저장
      const snapshot = queryClient.getQueryData<BoardDetail>(queryKey)

      // 낙관적으로 셀 내 카드 순서를 변경한다
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev
          ? reorderCardInCell(prev, vars.issueKey, vars.columnId, vars.previousIssueKey, vars.nextIssueKey)
          : prev,
      )

      return { snapshot }
    },

    onError: (_err, _vars, ctx) => {
      // 스냅샷으로 원위치 복원
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(queryKey, ctx.snapshot)
      }
      toast.error('순서 변경 중 충돌이 발생했습니다. 다시 시도해 주세요.')
    },

    onSuccess: (result, vars) => {
      // 카드는 onMutate에서 이미 새 위치에 배치됨 — rank·version만 최신 값으로 갱신
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev ? patchCardRank(prev, vars.issueKey, result.rank, result.version) : prev,
      )
    },

    onSettled: () => {
      // 409 충돌 등 서버 최신 상태로 강제 동기화 — 성공/실패 관계없이 최종 정합을 보장한다
      void queryClient.invalidateQueries({ queryKey })
    },
  })
}
