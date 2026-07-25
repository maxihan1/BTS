// 칸반 보드 스윔레인 간 카드 필드변경(담당자·우선순위·에픽) 낙관적 업데이트 훅 (FR-UX-06 PR21b Task-2·Task-3)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { changeAssignee, updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { connectEpicChild, disconnectEpicChild } from '@/api/epic-children'
import type { EpicChildSummary } from '@/api/epic-children'
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
  /** 소속 에픽 이슈 키. null이면 에픽 없음 */
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
 * field는 'assignee' | 'priority' | 'epic' 세 가지를 지원한다.
 * 에픽은 OCC(낙관적 잠금) 버전이 없는 링크 테이블이라 expectedVersion을 쓰지 않는다
 * (그래도 필드 자체는 required로 두어 board 카드의 항상 존재하는 version을 그대로 넘기게 한다).
 */
export interface ChangeCardFieldVars {
  /** 필드를 변경할 이슈 키 */
  issueKey: string
  /** 변경할 필드 종류 */
  field: 'assignee' | 'priority' | 'epic'
  /** field가 'assignee'일 때 목표 담당자 UUID. null이면 담당자 해제 */
  toAssigneeId?: string | null
  /** field가 'priority'일 때 목표 우선순위 1~5 */
  toPriority?: number
  /** field가 'epic'일 때 목표 에픽 이슈 키. null이면 에픽 해제(no-epic으로 이동) */
  toEpicKey?: string | null
  /** field가 'epic'일 때 현재 에픽 이슈 키. null이면 현재 에픽 없음 (2-step 판정에 사용) */
  fromEpicKey?: string | null
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 — 담당자·우선순위 변경에만 사용 */
  expectedVersion: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 helper — field별 mutationFn 분기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * requestFieldChange의 반환 타입.
 *
 * 담당자·우선순위는 IssueResponse(version 포함)를 반환하지만, 에픽 connect/disconnect는
 * EpicChildSummary 또는 void(undefined)를 반환한다 — 서로 다른 응답 모양을 `any`로 뭉개지
 * 않고 kind로 태깅한 판별 유니온으로 구분한다. onSuccess는 kind로 분기해 'issue'일 때만
 * version을 캐시에 반영한다(에픽은 OCC가 없어 version 갱신이 불필요).
 */
type FieldChangeResult =
  | { kind: 'issue'; data: IssueResponse }
  | { kind: 'epic'; data: EpicChildSummary | undefined }

/** IssueResponse를 FieldChangeResult로 태깅한다 — 담당자·우선순위 분기에서 반복 사용. */
function tagIssueResult(data: IssueResponse): FieldChangeResult {
  return { kind: 'issue', data }
}

/**
 * ChangeCardFieldVars.field에 따라 실제 API 호출을 분기한다.
 *
 * `!` non-null assertion 대신 명시적 undefined 가드로 필수 값 누락을 방어한다
 * (field='assignee'인데 toAssigneeId 누락 등은 호출부 버그이므로 즉시 실패시킨다).
 *
 * @param vars mutate 호출 변수
 * @returns 변경 결과를 kind로 태깅한 FieldChangeResult를 담은 Promise
 */
function requestFieldChange(vars: ChangeCardFieldVars): Promise<FieldChangeResult> {
  switch (vars.field) {
    case 'assignee': {
      if (vars.toAssigneeId === undefined) {
        throw new Error('toAssigneeId is required when field is "assignee"')
      }
      return changeAssignee(vars.issueKey, {
        assigneeId: vars.toAssigneeId,
        expectedVersion: vars.expectedVersion,
      }).then(tagIssueResult)
    }
    case 'priority': {
      if (vars.toPriority === undefined) {
        throw new Error('toPriority is required when field is "priority"')
      }
      return updateIssue(vars.issueKey, {
        priority: vars.toPriority,
        expectedVersion: vars.expectedVersion,
      }).then(tagIssueResult)
    }
    case 'epic': {
      return requestEpicFieldChange(vars).then((data) => ({ kind: 'epic' as const, data }))
    }
    default: {
      const exhaustiveCheck: never = vars.field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * 에픽 재배치 mutationFn — fromEpicKey/toEpicKey 조합으로 connect/disconnect를 분기한다.
 *
 * - `null → epic`: connectEpicChild 1-step.
 * - `epic → null`: disconnectEpicChild 1-step.
 * - `epicA → epicB`: replaceEpic으로 disconnect(A) → connect(B) 순차 2-step
 *   (백엔드 linkEpic이 `epic_id IS NULL` 조건부라 바로 교체 시 409 — ADR
 *   2026-06-22-fr-ep-01-epic-child-link.md).
 * - `동일값(from===to, null===null 포함)`: board-drop이 same-value를 걸러내지만
 *   훅 레벨에서도 방어 — API 호출 없이 즉시 실패시킨다.
 *
 * @param vars mutate 호출 변수 — field='epic'이어야 하며 fromEpicKey/toEpicKey 필수
 * @returns connect 성공 시 EpicChildSummary, disconnect만 수행했으면 undefined
 */
async function requestEpicFieldChange(
  vars: ChangeCardFieldVars,
): Promise<EpicChildSummary | undefined> {
  const { issueKey, fromEpicKey, toEpicKey } = vars
  if (fromEpicKey === undefined || toEpicKey === undefined) {
    throw new Error('fromEpicKey/toEpicKey are required when field is "epic"')
  }
  if (fromEpicKey === toEpicKey) {
    throw new Error('No epic change: fromEpicKey and toEpicKey are identical')
  }
  if (fromEpicKey === null && toEpicKey !== null) {
    return connectEpicChild(toEpicKey, issueKey)
  }
  if (fromEpicKey !== null && toEpicKey === null) {
    await disconnectEpicChild(fromEpicKey, issueKey)
    return undefined
  }
  if (fromEpicKey !== null && toEpicKey !== null) {
    return replaceEpic(issueKey, fromEpicKey, toEpicKey)
  }
  // fromEpicKey === toEpicKey === null은 위 same-value 가드에서 이미 걸러진다 — 도달 불가.
  throw new Error('Unreachable epic field-change branch')
}

/**
 * 에픽A→에픽B 2-step 재배치.
 *
 * disconnect(A) 성공 후 connect(B)가 실패하면(409 ISSUE_EPIC_CHILD_ALREADY_LINKED·422·403 등)
 * best-effort로 connect(A) 재연결을 시도해 "에픽 없음" 잔류를 최소화한다(D1 확정 —
 * 게이트 1 Maxi 승인 2026-07-25). 재연결 성공 여부와 무관하게 원래 connect(B) 에러를
 * 그대로 throw해 훅의 onError(낙관 캐시 롤백 + toast)가 처리하게 한다. 재연결 실패는
 * 조용히 삼키지 않고 console.error로 남긴다.
 *
 * @param issueKey 이동할 이슈 키
 * @param fromEpicKey 현재 연결된 에픽 키
 * @param toEpicKey 새로 연결할 에픽 키
 * @returns connect(B) 성공 시 EpicChildSummary
 */
async function replaceEpic(
  issueKey: string,
  fromEpicKey: string,
  toEpicKey: string,
): Promise<EpicChildSummary> {
  await disconnectEpicChild(fromEpicKey, issueKey)
  try {
    return await connectEpicChild(toEpicKey, issueKey)
  } catch (connectError) {
    try {
      await connectEpicChild(fromEpicKey, issueKey)
    } catch (rollbackError) {
      console.error('에픽 best-effort 재연결 실패 — 원래 에픽으로 복구하지 못했습니다', rollbackError)
    }
    throw connectError
  }
}

/**
 * ChangeCardFieldVars에서 onMutate 단계에 적용할 낙관적 patch를 계산한다.
 * version은 낙관 단계에선 변경하지 않는다 — 서버 응답을 받은 뒤 onSuccess에서 반영한다.
 *
 * 에픽은 2-step(disconnect→connect)이어도 이 함수가 **최종 목표값(toEpicKey) 1회만** 계산해
 * onMutate에서 한 번만 캐시에 반영한다 — 중간 epicKey=null 상태를 UI에 노출하지 않는다(FR-6a).
 *
 * @param vars mutate 호출 변수
 * @returns patchCardField에 전달할 CardFieldPatch
 */
function buildOptimisticPatch(vars: ChangeCardFieldVars): CardFieldPatch {
  switch (vars.field) {
    case 'assignee':
      return { assigneeId: vars.toAssigneeId }
    case 'priority':
      return { priority: vars.toPriority }
    case 'epic':
      return { epicKey: vars.toEpicKey }
    default: {
      const exhaustiveCheck: never = vars.field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * field별 실패 안내 toast 문구를 반환한다.
 *
 * @param field 실패한 변경 필드
 * @returns 한국어 안내 문구
 */
function buildErrorMessage(field: ChangeCardFieldVars['field']): string {
  switch (field) {
    case 'assignee':
      return '담당자 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    case 'priority':
      return '우선순위 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    case 'epic':
      return '에픽 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    default: {
      const exhaustiveCheck: never = field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeCardField
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드 스윔레인 간 드래그로 카드의 담당자·우선순위·에픽을 변경하는 mutation 훅.
 *
 * - onMutate: cancelQueries → 스냅샷 저장 → 낙관적 캐시 업데이트(patchCardField, 최종값 1회)
 * - onError: 스냅샷으로 롤백 + toast.error 안내(에픽 2-step 부분 실패도 동일 경로 — D1)
 * - onSuccess: field='assignee'|'priority'는 서버 응답(IssueResponse)의
 *   assigneeId·priority·version으로 카드 patch. field='epic'은 OCC version이 없고
 *   onMutate에서 이미 최종값을 반영했으므로 추가 patch를 하지 않는다(FR-6a).
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

  return useMutation<
    FieldChangeResult,
    unknown,
    ChangeCardFieldVars,
    { snapshot: BoardDetail | undefined }
  >({
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
      if (result.kind === 'epic') {
        // 에픽 connect/disconnect는 OCC(version)가 없다 — onMutate에서 이미 최종값을
        // 1회 반영했으므로 여기서는 추가 patch가 필요 없다(FR-6a).
        return
      }
      // 서버가 반환한 최신 담당자·우선순위·version으로 갱신
      queryClient.setQueryData<BoardDetail>(queryKey, (prev) =>
        prev
          ? patchCardField(prev, result.data.key, {
              assigneeId: result.data.assigneeId,
              priority: result.data.priority,
              version: result.data.version,
            })
          : prev,
      )
    },

    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey })
    },
  })
}
