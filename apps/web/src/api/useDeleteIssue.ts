// 이슈 삭제 mutation 훅 — 성공 시 목록 캐시 무효화 + onSuccess 콜백, 실패 시 toast.error
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { deleteIssue } from './issues'
import { ApiError } from './client'

/** 이슈 목록 TanStack Query 캐시 키 — invalidateQueries 공유용 상수 */
export const issuesListQueryKey = ['issues'] as const

/** useDeleteIssue 옵션 인터페이스 */
export interface UseDeleteIssueOptions {
  /** 삭제 성공 후 호출되는 콜백 — 주로 상세 페이지 → 목록으로 navigate할 때 사용 */
  onSuccess?: () => void
}

/**
 * 이슈 삭제 mutation 훅.
 *
 * - `mutate(key)` 호출로 이슈를 삭제한다.
 * - 성공 시 `['issues']` 캐시를 무효화하고 `onSuccess` 콜백을 실행한다.
 * - 실패 시 `toast.error`로 오류 메시지를 표시한다.
 *
 * @param options.onSuccess 삭제 성공 후 실행할 콜백 (상세→목록 navigate 등)
 */
export function useDeleteIssue({ onSuccess }: UseDeleteIssueOptions) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (key: string) => deleteIssue(key),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: issuesListQueryKey })
      onSuccess?.()
    },
    onError: (error: unknown) => {
      const message =
        error instanceof ApiError
          ? `이슈 삭제에 실패했습니다. (${error.status})`
          : '이슈 삭제 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
      toast.error(message)
    },
  })
}
