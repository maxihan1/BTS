// 이슈 생성 폼의 제출 — 게이트·required 선검사·mutation·진행 신호 (부채 매핑 8)
import { useEffect } from 'react'
import { useMutation } from '@tanstack/react-query'
import { createIssue } from '@/api/issues'
import { isRequiredFieldEmpty } from '@/components/custom-fields/required-empty'
import { resolveCreateErrorMessage } from '@/components/issue/create/issue-create-validation'
import { buildCreateIssuePayload } from '@/components/issue/create/issue-create-schema'
import type { IssueCreateFormValues } from '@/components/issue/create/issue-create-schema'
import type { IssueCreateFormState } from '@/components/issue/create/use-issue-create-form-state'
import { issueCreateStrings } from '@/i18n/ko'

/** {@link useIssueCreateSubmit} 입력 — 상태 묶음 밖에서 오는 것들. */
export interface IssueCreateSubmitOptions {
  /**
   * 선택된 프로젝트의 CREATE 가 **명시적으로** 거부됐는가.
   *
   * ★미지(로딩·조회 실패)는 `false` 다 — 화면이 게이트 훅에서 그렇게 받아 넘긴다.
   *   미지를 거부로 읽으면 CREATE 를 실제로 가진 사용자를 영구 차단한다(훅 KDoc · 부채 매핑 15).
   */
  isCreateExplicitlyDenied: boolean
  /** 생성 성공 후 콜백 — 생성된 이슈 key 를 전달 */
  onSuccess?: (key: string) => void
  /** 제출 진행 상태 변경 콜백 (게이트 2 C-2) — 폼 밖 푸터 버튼이 이걸로 자기 상태를 안다 */
  onPendingChange?: (pending: boolean) => void
}

/** 화면이 쓰는 제출 표면. */
export interface IssueCreateSubmit {
  isPending: boolean
  submit: (values: IssueCreateFormValues) => void
}

/**
 * 제출 경로 하나. 게이트 → required 선검사 → mutation 순서가 이 훅의 전부다.
 *
 * ★게이트 판정을 **여기서 다시 계산하지 않는다.** 화면이 계산해 넘긴다 —
 *   로딩 프레임 계약(부채 매핑 15)의 주체는 화면이고, 판정이 두 곳에서 나면 두 값이 갈린다.
 */
export function useIssueCreateSubmit(
  state: IssueCreateFormState,
  options: IssueCreateSubmitOptions,
): IssueCreateSubmit {
  const { isCreateExplicitlyDenied, onSuccess, onPendingChange } = options
  const { setServerError, setCustomFieldRequiredError } = state

  const mutation = useMutation({
    mutationFn: createIssue,
    onSuccess: (data) => {
      setServerError(null)
      onSuccess?.(data.key)
    },
    onError: (err: unknown) => {
      setServerError(resolveCreateErrorMessage(err))
    },
  })

  // 제출 상태를 폼 밖(모달 푸터)으로 흘려보낸다 (게이트 2 C-2).
  useEffect(() => {
    onPendingChange?.(mutation.isPending)
  }, [mutation.isPending, onPendingChange])

  function submit(values: IssueCreateFormValues): void {
    // E7 — 제출 중 재클릭 차단. 푸터 버튼이 폼 밖에 있어 버튼 disabled 만으로는 부족하다.
    if (mutation.isPending) return
    // 선택된 프로젝트의 CREATE 게이트 (2026-08-09 Maxi 확정).
    // 이슈 생성 진입 경로 4개(상단바 · /issues/new 딥링크 · `c` 단축키 · 명령 팔레트)가
    // 전부 이 폼 하나를 지나므로 여기 한 곳이 무게이트 경로를 동시에 닫고,
    // 「게이트된 버튼으로 열어도 폼 안에서 무권한 프로젝트로 갈아타기」까지 덮는다.
    if (isCreateExplicitlyDenied) {
      setServerError(issueCreateStrings.errorCreateForbidden)
      return
    }
    // 스펙 E-3: required 커스텀 필드 빈값 1차 검사 — mutation 전 차단
    const hasRequiredEmpty = state.customFieldDefs.some(
      (field) =>
        field.required && isRequiredFieldEmpty(field.fieldType, state.customFieldValues[field.key]),
    )
    if (hasRequiredEmpty) {
      setCustomFieldRequiredError(true)
      return
    }
    setCustomFieldRequiredError(false)
    setServerError(null)
    mutation.mutate(
      buildCreateIssuePayload(values, {
        typeId: state.selectedTypeId,
        assigneeIntent: state.assignee.intent,
        priority: state.priority,
        labels: state.labels,
        componentIds: state.selectedComponentIds,
        securityLevelId: state.securityLevelId,
        customFieldValues: state.customFieldValues,
      }),
    )
  }

  return { isPending: mutation.isPending, submit }
}
