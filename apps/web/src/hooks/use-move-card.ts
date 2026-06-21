// 칸반 보드 카드 이동 mutation 훅 — 낙관적 업데이트·롤백·409 회복 (FR-BD-01)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { moveCard } from '@/api/boards'
import type { BoardDetail } from '@/api/boards'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 helper — 불변 캐시 조작
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드의 특정 카드를 fromColumn에서 toColumn으로 이동한다.
 *
 * 원본 board는 변형하지 않는다(immutable — map/filter 사용).
 * columnId가 없는 컬럼이나 issueKey가 없는 카드는 조용히 무시한다.
 *
 * @param board 현재 BoardDetail 캐시 값
 * @param issueKey 이동할 이슈 키. 예: "ATLAS-1"
 * @param fromColumnId 출발 컬럼 UUID
 * @param toColumnId 도착 컬럼 UUID
 * @returns 카드가 이동된 새 BoardDetail (원본 불변)
 */
export function moveCardInBoard(
  board: BoardDetail,
  issueKey: string,
  fromColumnId: string,
  toColumnId: string,
): BoardDetail {
  // 이동할 카드를 fromColumn에서 찾는다
  const movingCard = board.columns
    .find((col) => col.columnId === fromColumnId)
    ?.cards.find((card) => card.issueKey === issueKey)

  return {
    ...board,
    columns: board.columns.map((col) => {
      if (col.columnId === fromColumnId) {
        return { ...col, cards: col.cards.filter((c) => c.issueKey !== issueKey) }
      }
      if (col.columnId === toColumnId && movingCard !== undefined) {
        return { ...col, cards: [...col.cards, movingCard] }
      }
      return col
    }),
  }
}

/**
 * 보드 내 특정 카드의 version 필드를 갱신한다.
 *
 * 낙관적 이동 후 서버 응답의 최신 버전을 캐시에 반영할 때 사용.
 * 원본 board는 변형하지 않는다(immutable).
 *
 * @param board 현재 BoardDetail 캐시 값
 * @param issueKey 버전을 갱신할 이슈 키
 * @param version 서버 응답의 최신 version 번호
 * @returns version이 갱신된 새 BoardDetail (원본 불변)
 */
export function patchCardVersion(
  board: BoardDetail,
  issueKey: string,
  version: number,
): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((col) => ({
      ...col,
      cards: col.cards.map((card) =>
        card.issueKey === issueKey ? { ...card, version } : card,
      ),
    })),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useMoveCard mutation 훅 입력 타입
// ─────────────────────────────────────────────────────────────────────────────

/** useMoveCard mutate 호출 변수 타입 */
export interface MoveCardVars {
  /** 이동할 이슈 키 */
  issueKey: string
  /** 출발 컬럼 UUID — 낙관적 이동과 롤백에 사용 */
  fromColumnId: string
  /** 도착 컬럼 UUID */
  toColumnId: string
  /** 낙관적 잠금(OCC) 버전 — 409 충돌 감지용 */
  expectedVersion: number
  /** 결의안 UUID — DONE 카테고리 이동 시 필요. 생략 가능 */
  resolutionId?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// useMoveCard
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 카드를 다른 컬럼으로 이동하는 mutation 훅.
 *
 * - onMutate: cancelQueries → 스냅샷 저장 → 낙관적 캐시 업데이트
 * - onError: 스냅샷으로 롤백 + invalidateQueries(서버 진실 회복)
 * - onSuccess: 서버 응답의 version으로 카드 version 패치
 *
 * @param boardId 보드 UUID
 */
export function useMoveCard(boardId: string) {
  const queryClient = useQueryClient()
  const queryKey = boardKeys.detail(boardId)

  return useMutation<
    Awaited<ReturnType<typeof moveCard>>,
    unknown,
    MoveCardVars,
    { snapshot: BoardDetail | undefined }
  >({
    mutationFn: (vars) =>
      moveCard(boardId, vars.issueKey, {
        toColumnId: vars.toColumnId,
        expectedVersion: vars.expectedVersion,
        resolutionId: vars.resolutionId,
      }),

    onMutate: async (vars) => {
      // 드래그 중 refetch가 낙관적 상태를 덮어쓰지 않도록 진행 중 쿼리를 취소한다
      await queryClient.cancelQueries({ queryKey })

      // 롤백을 위한 스냅샷 저장
      const snapshot = queryClient.getQueryData<BoardDetail>(queryKey)

      // 낙관적으로 카드를 목표 컬럼으로 이동 — 단일 카드만 이동(전체 교체 아님)
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev
          ? moveCardInBoard(prev, vars.issueKey, vars.fromColumnId, vars.toColumnId)
          : prev,
      )

      return { snapshot }
    },

    onError: (_err, _vars, ctx) => {
      // 스냅샷으로 원위치 복원
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(queryKey, ctx.snapshot)
      }
      // 409 충돌 등 — 서버 최신 상태로 강제 동기화
      void queryClient.invalidateQueries({ queryKey })
    },

    onSuccess: (result, vars) => {
      // 카드는 onMutate에서 이미 toColumn에 배치됨 — version만 최신 값으로 갱신
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev ? patchCardVersion(prev, vars.issueKey, result.version) : prev,
      )
    },
  })
}
