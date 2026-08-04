// 이슈 목록 셀에서 담당자·우선순위·상태를 바꾸는 mutation 훅 — 목록 캐시 전용 (FR-UX-11 F9)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryKey } from '@tanstack/react-query'
import { toast } from 'sonner'
import { changeAssignee, updateIssue, transitionIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { extractErrorCode } from '@/lib/extract-error-code'
import { issueDetailStrings } from '@/i18n/ko'

/** 409 응답 중 "허용되지 않는 전이" 를 가리키는 백엔드 에러코드 */
const TRANSITION_NOT_ALLOWED = 'TRANSITION_NOT_ALLOWED'

/**
 * 목록 한 페이지 — 이 훅이 건드리는 최소 형태만 요구한다.
 *
 * 실제 캐시 값은 `IssuePage`(totalElements 등 포함)지만, patch 는 `content` 만 다루고
 * 나머지는 스프레드로 그대로 보존하므로 최소 계약으로 충분하다.
 */
export interface IssueListPage {
  content: IssueResponse[]
}

/** 셀 편집이 적용하는 필드 patch. undefined 인 필드는 미변경. */
export interface IssueCellPatch {
  /** 담당자 UUID. null 이면 미할당 */
  assigneeId?: string | null
  /** 우선순위 1(Highest)~5(Lowest) */
  priority?: number
  /** 현재 상태 키 */
  currentStateKey?: string
  /** 낙관적 잠금(OCC) 버전 */
  version?: number
}

/**
 * 목록 한 페이지에서 대상 이슈에만 patch 를 병합한다. 원본은 변형하지 않는다.
 *
 * 무관한 행은 **참조까지 그대로** 반환해 불필요한 리렌더를 막는다.
 *
 * @param page 현재 목록 페이지 캐시 값
 * @param issueKey patch 할 이슈 키. 예: "ATLAS-1"
 * @param patch 적용할 필드 변경분 — undefined 필드는 미변경
 * @returns patch 가 반영된 새 페이지 (원본 불변)
 */
export function patchIssueInList(
  page: IssueListPage,
  issueKey: string,
  patch: IssueCellPatch,
): IssueListPage {
  return {
    ...page,
    content: page.content.map((issue) => (issue.key === issueKey ? { ...issue, ...patch } : issue)),
  }
}

/** mutate 호출 변수 */
export interface IssueCellFieldVars {
  /** 필드를 변경할 이슈 키 */
  issueKey: string
  /** 변경할 필드 종류 */
  field: 'assignee' | 'priority' | 'status'
  /** field 가 'assignee' 일 때 목표 담당자 UUID. null 이면 담당자 해제 */
  toAssigneeId?: string | null
  /** field 가 'priority' 일 때 목표 우선순위 1~5 */
  toPriority?: number
  /** field 가 'status' 일 때 목표 상태 키 */
  toStatusKey?: string
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

/**
 * field 별 실제 API 호출을 분기한다.
 *
 * `!` non-null assertion 대신 명시적 undefined 가드를 쓴다 — field 와 값이 어긋난 호출은
 * 호출부 버그이므로 즉시 실패시킨다 (`useChangeCardField` 선례).
 *
 * @param vars mutate 호출 변수
 * @returns 변경된 이슈 응답
 */
function requestCellChange(vars: IssueCellFieldVars): Promise<IssueResponse> {
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
    case 'status': {
      if (vars.toStatusKey === undefined) {
        throw new Error('toStatusKey is required when field is "status"')
      }
      return transitionIssue(vars.issueKey, {
        toStatusKey: vars.toStatusKey,
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
 * onMutate 단계에 적용할 낙관적 patch 를 계산한다.
 * version 은 낙관 단계에서 건드리지 않는다 — 서버 응답을 받은 뒤 onSuccess 에서 반영한다.
 *
 * @param vars mutate 호출 변수
 * @returns patchIssueInList 에 전달할 IssueCellPatch
 */
function buildOptimisticPatch(vars: IssueCellFieldVars): IssueCellPatch {
  switch (vars.field) {
    case 'assignee':
      return { assigneeId: vars.toAssigneeId }
    case 'priority':
      return { priority: vars.toPriority }
    case 'status':
      return { currentStateKey: vars.toStatusKey }
    default: {
      const exhaustiveCheck: never = vars.field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * field 별 일반 실패 안내 문구를 반환한다.
 *
 * @param field 실패한 변경 필드
 * @returns 한국어 안내 문구
 */
function buildErrorMessage(field: IssueCellFieldVars['field']): string {
  switch (field) {
    case 'assignee':
      return '담당자 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    case 'priority':
      return '우선순위 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    case 'status':
      return '상태 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    default: {
      const exhaustiveCheck: never = field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * 실패 사유별 안내 문구를 고른다 (FR15).
 *
 * 상세 화면 `issues.$key.tsx` 의 전이 onError 가 이미 이 3분기를 갖고 있다 — 목록도 같은
 * 어휘를 쓴다. `409` 를 뭉개면 "내가 못 하는 전이" 와 "남이 먼저 바꿔서 낡은 버전" 이 같은
 * 문구로 나와 사용자가 다음 행동을 고를 수 없다.
 *
 * 코드 추출은 인라인으로 다시 짜지 않고 공유 util `extractErrorCode` 를 경유한다 —
 * 화면마다 추출 규칙이 갈리면 raw 코드가 그대로 노출되는 drift 가 난다 (PR #106).
 *
 * @param err mutationFn 이 throw 한 값
 * @param field 실패한 변경 필드
 * @returns 한국어 안내 문구
 */
function resolveCellErrorMessage(err: unknown, field: IssueCellFieldVars['field']): string {
  if (!(err instanceof ApiError)) return buildErrorMessage(field)

  if (err.status === 422) return issueDetailStrings.transitionWorkflowNotConfiguredError

  if (err.status === 409) {
    if (extractErrorCode(err.body) === TRANSITION_NOT_ALLOWED) {
      return issueDetailStrings.transitionNotAllowedError
    }
    return field === 'status'
      ? issueDetailStrings.transitionVersionConflictError
      : issueDetailStrings.versionConflictError
  }

  return buildErrorMessage(field)
}

/**
 * 목록 셀 인라인 편집 mutation 훅.
 *
 * `useChangeCardField`(보드)와 같은 4단계(onMutate 스냅샷 → onError 롤백 → onSuccess 반영 →
 * onSettled invalidate)를 쓰되 캐시 대상이 이슈 목록 페이지다.
 *
 * `setQueryData` 로 **응답 전체를 교체하지 않고 변경 필드만 patch** 한다 — PATCH 응답은
 * 부분 뷰(`descriptionHtml` 항상 null)라 통째 교체하면 파생 필드가 사라진다
 * (learnings 2026-05-30 C2).
 *
 * @param listQueryKey 이 목록의 queryKey — `['issues', projectKey, page, filter, sort]`
 * @returns 담당자·우선순위·상태 변경 mutation
 */
export function useIssueListCellField(listQueryKey: QueryKey) {
  const queryClient = useQueryClient()

  return useMutation<
    IssueResponse,
    unknown,
    IssueCellFieldVars,
    { snapshot: IssueListPage | undefined }
  >({
    mutationFn: requestCellChange,

    onMutate: async (vars) => {
      // 편집 중 refetch 가 낙관적 상태를 덮어쓰지 않도록 진행 중 쿼리를 취소한다
      await queryClient.cancelQueries({ queryKey: listQueryKey })

      // 롤백용 스냅샷
      const snapshot = queryClient.getQueryData<IssueListPage>(listQueryKey)

      queryClient.setQueryData<IssueListPage>(listQueryKey, (prev) =>
        prev ? patchIssueInList(prev, vars.issueKey, buildOptimisticPatch(vars)) : prev,
      )

      return { snapshot }
    },

    onError: (err, vars, ctx) => {
      // 스냅샷으로 원위치 복원 — 실패한 값이 화면에 남으면 사용자가 저장된 줄 안다
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(listQueryKey, ctx.snapshot)
      }
      toast.error(resolveCellErrorMessage(err, vars.field))
    },

    onSuccess: (data) => {
      // 서버가 반환한 최신 담당자·우선순위·상태·version 으로 갱신
      queryClient.setQueryData<IssueListPage>(listQueryKey, (prev) =>
        prev
          ? patchIssueInList(prev, data.key, {
              assigneeId: data.assigneeId,
              priority: data.priority,
              currentStateKey: data.currentStateKey,
              version: data.version,
            })
          : prev,
      )
    },

    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: listQueryKey })
    },
  })
}
