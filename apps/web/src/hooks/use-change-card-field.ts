// 칸반 보드 스윔레인 간 카드 필드변경(담당자·우선순위) 낙관적 업데이트 훅 (FR-UX-06 PR21b Task-2)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { changeAssignee, updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import type { BoardDetail, BoardCard, BoardCardFilterParams } from '@/api/boards'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 helper — 불변 캐시 조작
// ─────────────────────────────────────────────────────────────────────────────

/** patchCardField에 전달할 필드 patch. undefined인 필드는 미변경. */
export interface CardFieldPatch {
  /** 담당자 UUID. null이면 미배정 */
  assigneeId?: string | null
  /** 우선순위 1(Highest)~5(Lowest) */
  priority?: number
  /** 소속 에픽 이슈 키. null이면 에픽 없음 (Task 3에서 사용) */
  epicKey?: string | null
  /** 낙관적 잠금(OCC) 버전 */
  version?: number
}

/**
 * 카드 하나에 patch를 병합한다. undefined인 필드는 기존 값을 유지한다.
 *
 * @param card patch 대상 카드
 * @param patch 적용할 필드 변경분
 * @returns patch가 반영된 새 BoardCard (원본 불변)
 */
function applyCardFieldPatch(card: BoardCard, patch: CardFieldPatch): BoardCard {
  return {
    ...card,
    assigneeId: patch.assigneeId !== undefined ? patch.assigneeId : card.assigneeId,
    priority: patch.priority !== undefined ? patch.priority : card.priority,
    epicKey: patch.epicKey !== undefined ? patch.epicKey : card.epicKey,
    version: patch.version !== undefined ? patch.version : card.version,
  }
}

/**
 * 보드 내 특정 카드의 담당자·우선순위·에픽·version 필드를 patch한다.
 *
 * 모든 컬럼을 순회해 issueKey가 일치하는 카드에만 patch를 적용한다
 * (스윔레인 간 드래그는 카드가 어느 컬럼에 있는지 미리 알 필요가 없다 — patchCardRank와 달리
 * columnId를 받지 않는다). 원본 board는 변형하지 않는다(immutable — map 사용).
 *
 * @param board 현재 BoardDetail 캐시 값
 * @param issueKey patch할 이슈 키. 예: "ATLAS-1"
 * @param patch 적용할 필드 변경분 — undefined 필드는 미변경
 * @returns patch가 반영된 새 BoardDetail (원본 불변)
 */
export function patchCardField(
  board: BoardDetail,
  issueKey: string,
  patch: CardFieldPatch,
): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((col) => ({
      ...col,
      cards: col.cards.map((card) =>
        card.issueKey === issueKey ? applyCardFieldPatch(card, patch) : card,
      ),
    })),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeCardField mutation 훅 입력 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * useChangeCardField mutate 호출 변수 타입.
 *
 * field는 'assignee' | 'priority' 두 가지만 지원한다 (에픽 재배치는 Task 3에서
 * 'epic'을 추가할 예정 — 확장을 고려해 필드 유니온 형태로 둔다).
 */
export interface ChangeCardFieldVars {
  /** 필드를 변경할 이슈 키 */
  issueKey: string
  /** 변경할 필드 종류 */
  field: 'assignee' | 'priority'
  /** field가 'assignee'일 때 목표 담당자 UUID. null이면 담당자 해제 */
  toAssigneeId?: string | null
  /** field가 'priority'일 때 목표 우선순위 1~5 */
  toPriority?: number
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 helper — field별 mutationFn 분기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ChangeCardFieldVars.field에 따라 실제 API 호출을 분기한다.
 *
 * `!` non-null assertion 대신 명시적 undefined 가드로 필수 값 누락을 방어한다
 * (field='assignee'인데 toAssigneeId 누락 등은 호출부 버그이므로 즉시 실패시킨다).
 *
 * @param vars mutate 호출 변수
 * @returns 변경된 IssueResponse를 담은 Promise
 */
function requestFieldChange(vars: ChangeCardFieldVars): Promise<IssueResponse> {
  switch (vars.field) {
    case 'assignee': {
      if (vars.toAssigneeId === undefined) {
        throw new Error('toAssigneeId is required when field is "assignee"')
      }
      return changeAssignee(vars.issueKey, {
        assigneeId: vars.toAssigneeId,
        expectedVersion: vars.expectedVersion,
      })
    }
    case 'priority': {
      if (vars.toPriority === undefined) {
        throw new Error('toPriority is required when field is "priority"')
      }
      return updateIssue(vars.issueKey, {
        priority: vars.toPriority,
        expectedVersion: vars.expectedVersion,
      })
    }
    default: {
      const exhaustiveCheck: never = vars.field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * ChangeCardFieldVars에서 onMutate 단계에 적용할 낙관적 patch를 계산한다.
 * version은 낙관 단계에선 변경하지 않는다 — 서버 응답을 받은 뒤 onSuccess에서 반영한다.
 *
 * @param vars mutate 호출 변수
 * @returns patchCardField에 전달할 CardFieldPatch
 */
function buildOptimisticPatch(vars: ChangeCardFieldVars): CardFieldPatch {
  return vars.field === 'assignee'
    ? { assigneeId: vars.toAssigneeId }
    : { priority: vars.toPriority }
}

/**
 * field별 실패 안내 toast 문구를 반환한다.
 *
 * @param field 실패한 변경 필드
 * @returns 한국어 안내 문구
 */
function buildErrorMessage(field: ChangeCardFieldVars['field']): string {
  return field === 'assignee'
    ? '담당자 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    : '우선순위 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeCardField
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 스윔레인 간 드래그로 카드의 담당자·우선순위를 변경하는 mutation 훅.
 *
 * - onMutate: cancelQueries → 스냅샷 저장 → 낙관적 캐시 업데이트(patchCardField)
 * - onError: 스냅샷으로 롤백 + toast.error 안내 + invalidateQueries(서버 진실 회복)
 * - onSuccess: 서버 응답(IssueResponse)의 assigneeId·priority·version으로 카드 patch
 * - onSettled: invalidateQueries — 성공/실패 관계없이 서버 진실과 최종 동기화
 *
 * FR-6b — 필드값만 변경하고 대상 그룹 내 rank/순서는 건드리지 않는다.
 * filter-aware queryKey를 useReorderCard·useMoveCard와 공유한다.
 *
 * @param boardId 보드 UUID
 * @param filter 선택적 카드 필터 파라미터 — useBoard에 전달한 것과 동일한 값이어야 한다
 */
export function useChangeCardField(boardId: string, filter?: BoardCardFilterParams) {
  const queryClient = useQueryClient()
  const queryKey = boardKeys.detail(boardId, filter)

  return useMutation<IssueResponse, unknown, ChangeCardFieldVars, { snapshot: BoardDetail | undefined }>({
    mutationFn: requestFieldChange,

    onMutate: async (vars) => {
      // 드래그 중 refetch가 낙관적 상태를 덮어쓰지 않도록 진행 중 쿼리를 취소한다
      await queryClient.cancelQueries({ queryKey })

      // 롤백을 위한 스냅샷 저장
      const snapshot = queryClient.getQueryData<BoardDetail>(queryKey)

      // 낙관적으로 변경 대상 필드만 patch — rank/순서는 건드리지 않는다(FR-6b)
      const optimisticPatch = buildOptimisticPatch(vars)
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev ? patchCardField(prev, vars.issueKey, optimisticPatch) : prev,
      )

      return { snapshot }
    },

    onError: (_err, vars, ctx) => {
      // 스냅샷으로 원위치 복원
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(queryKey, ctx.snapshot)
      }
      toast.error(buildErrorMessage(vars.field))
    },

    onSuccess: (result) => {
      // 서버가 반환한 최신 담당자·우선순위·version으로 갱신
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev
          ? patchCardField(prev, result.key, {
              assigneeId: result.assigneeId,
              priority: result.priority,
              version: result.version,
            })
          : prev,
      )
    },

    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey })
    },
  })
}
