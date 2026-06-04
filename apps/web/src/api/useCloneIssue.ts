// 이슈 클론 mutation 훅 — 성공 시 새 이슈 navigate + 성공 toast + issues 캐시 무효화
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { cloneIssue } from './issues'
import type { CloneIssueInput } from './issues'
import { ApiError } from './client'
import { issuesListQueryKey } from './useDeleteIssue'
import { issueDetailStrings } from '@/i18n/ko'

/** useCloneIssue mutate 입력 타입 */
export interface CloneIssueMutateInput {
  /** 클론할 원본 이슈 식별 키 (예: "ATLAS-1") */
  key: string
  /** 클론 옵션 — 모두 선택 사항 */
  input?: CloneIssueInput
}

/**
 * 이슈 클론 mutation 훅.
 *
 * - `mutate({ key, input? })` 호출로 이슈를 클론한다.
 * - 성공 시 새 이슈 상세 페이지로 navigate하고 성공 toast를 표시한다.
 * - 성공 시 `['issues']` 캐시를 무효화해 목록이 최신화되도록 한다.
 * - 실패 시 HTTP 상태 코드별 toast.error를 표시한다.
 *   - 404: 이슈 없음
 *   - 403: 권한 없음
 *   - 400: 유효성 오류
 *   - 그 외: 기본 에러
 *
 * Dialog가 mutation을 직접 소유하므로 submitError prop 패턴을 사용하지 않는다.
 * 에러 표시는 onError toast로 처리한다.
 */
export function useCloneIssue() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  return useMutation({
    mutationFn: ({ key, input }: CloneIssueMutateInput) => cloneIssue(key, input),

    onSuccess: async (newIssue) => {
      toast.success(issueDetailStrings.cloneSuccessToast)
      await queryClient.invalidateQueries({ queryKey: issuesListQueryKey })
      void navigate({ to: `/issues/${newIssue.key}` as string })
    },

    onError: (error: unknown) => {
      if (error instanceof ApiError) {
        if (error.status === 404) {
          toast.error(issueDetailStrings.cloneErrorNotFound)
        } else if (error.status === 403) {
          toast.error(issueDetailStrings.cloneErrorForbidden)
        } else if (error.status === 400) {
          toast.error(issueDetailStrings.cloneErrorValidation)
        } else {
          toast.error(issueDetailStrings.cloneErrorDefault)
        }
      } else {
        toast.error(issueDetailStrings.cloneErrorDefault)
      }
    },
  })
}
