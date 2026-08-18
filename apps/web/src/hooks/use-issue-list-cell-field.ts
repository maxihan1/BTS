// 이슈 목록 셀에서 담당자·우선순위·상태를 바꾸는 mutation 훅 — 목록 캐시 전용 (FR-UX-11 F9)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryKey } from '@tanstack/react-query'
import { toast } from 'sonner'
import { changeAssignee, updateIssue, transitionIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { issueTransitionKeys } from '@/hooks/use-issue-transitions'
import { extractErrorCode } from '@/lib/extract-error-code'
import { issueDetailStrings } from '@/i18n/ko'

/** 409 응답 중 "허용되지 않는 전환" 를 가리키는 백엔드 에러코드 */
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
  /**
   * field 가 'status' 이고 **종료(DONE) 전환**일 때 선택된 결의안 UUID (FR14).
   *
   * 해결 결과는 종료 전환의 필수 입력이라 `ResolutionModal` 이 확정한 값을 여기 싣는다.
   * `TransitionIssueInput` 이 이미 받는 선택 필드이므로 그대로 통과시킨다.
   */
  resolutionId?: string
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
        // 종료 전환에만 실린다 — 비종료 전환은 undefined 라 body 에서 빠진다 (FR14)
        resolutionId: vars.resolutionId,
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
 * 상태 변경의 일반 실패 안내 문구.
 *
 * ★`i18n/ko.ts` 에 **대응 정본이 없다.** 있는 것은 사유별 3종
 * (`transitionNotAllowedError` · `transitionVersionConflictError` ·
 * `transitionWorkflowNotConfiguredError`)뿐이고, 상세 화면(`issues.$key.tsx:354`)은 일반
 * 실패(네트워크 등)에도 `transitionNotAllowedError` 를 재사용한다 — 타임아웃에
 * *"현재 상태에서 허용되지 않는 전환입니다"* 라고 말하는 셈이라 그대로 베끼지 않는다.
 *
 * 키 신설은 이 PR 범위 밖이므로(§제약) 인라인으로 두고 후속 과제로 남긴다.
 */
const STATUS_CHANGE_ERROR = '상태 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'

/**
 * field 별 일반 실패 안내 문구를 반환한다.
 *
 * ★문구를 새로 짓지 않는다 — 담당자·우선순위는 `issueDetailStrings` 정본을 쓴다.
 * 상세 화면(`useChangeAssignee.ts:49` · `issues.$key.tsx:420`)이 소비하는 바로 그 값이라,
 * 같은 실패가 상세와 목록에서 **다른 말**로 안내되지 않는다.
 *
 * @param field 실패한 변경 필드
 * @returns 한국어 안내 문구
 */
function buildErrorMessage(field: IssueCellFieldVars['field']): string {
  switch (field) {
    case 'assignee':
      return issueDetailStrings.assigneeChangeError
    case 'priority':
      return issueDetailStrings.priorityChangeError
    case 'status':
      return STATUS_CHANGE_ERROR
    default: {
      const exhaustiveCheck: never = field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * 실패 사유별 안내 문구를 고른다 (FR15).
 *
 * 상세 화면 `issues.$key.tsx` 의 전환 onError 가 이미 이 3분기를 갖고 있다 — 목록도 같은
 * 어휘를 쓴다. `409` 를 뭉개면 "내가 못 하는 전환" 와 "남이 먼저 바꿔서 낡은 버전" 이 같은
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

    onSettled: (_data, _error, vars) => {
      // ★목록만 무효화하면 **상세 페인이 낡은 채로 남는다** (리뷰 C1).
      //
      // 와이드 폭 `/issues` 는 목록과 상세 페인을 **동시에** 마운트한다
      // (`issues.index.tsx` split view). 상세는 `['issue', key]`·`['issue-transitions', key]`
      // 를 자기 캐시로 갖는데, 목록에서 값을 바꾸면 서버 version 이 올라간 뒤에도 페인은 옛
      // version 을 들고 있다. 그 상태에서 페인으로 뭔가 바꾸면 **409 VERSION_CONFLICT** 가
      // 나고 "다른 사용자가 이미 수정했습니다" 가 뜬다 — 다른 사용자는 없다.
      // 상태 전환은 옛 전환 목록까지 남아 409 TRANSITION_NOT_ALLOWED 로도 이어진다.
      //
      // 처방은 저장소 선례를 그대로 따른다 — `useTransitionIssue`(use-issue-transitions.ts)
      // 가 이미 두 캐시를 함께 무효화한다.
      void queryClient.invalidateQueries({ queryKey: listQueryKey })
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(vars.issueKey) })
      void queryClient.invalidateQueries({ queryKey: issueTransitionKeys.list(vars.issueKey) })
    },
  })
}
