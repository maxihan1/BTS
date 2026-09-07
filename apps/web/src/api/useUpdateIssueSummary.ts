// 이슈 요약 수정 훅 — 낙관적 업데이트 + 409 VERSION_CONFLICT 롤백 + sonner toast
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
import { invalidateIssueViews } from './issue-view-invalidation'

/** 이슈 쿼리키 팩토리 — ['issue', key] 형태로 일관성 있게 생성 */
export const issueQueryKey = (key: string): [string, string] => ['issue', key]

/** useUpdateIssueSummary mutate 입력 타입 */
export interface UpdateIssueSummaryInput {
  /** 수정할 이슈 키 (예: "ATLAS-1") */
  key: string
  /** 변경할 요약 텍스트 */
  summary: string
  /** OCC(낙관적 동시성 제어)를 위한 현재 버전 번호 */
  expectedVersion: number
}

/**
 * 이슈 요약 수정 mutation 훅.
 *
 * 동작 순서.
 * 1. onMutate: 진행 중인 쿼리 취소 → 이전 캐시 snapshot → 낙관적으로 캐시 선반영
 * 2. onSuccess: 서버 응답(IssueResponse)으로 캐시 최종 반영 (version 포함)
 * 3. onError: snapshot으로 캐시 롤백 + 409 VERSION_CONFLICT 시 toast.error 표시
 * 4. onSettled: invalidateQueries로 서버 최신 상태 재조회 트리거
 *
 * @returns UseMutationResult — mutate(input) 호출로 수정 실행
 */
export function useUpdateIssueSummary() {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, ApiError, UpdateIssueSummaryInput, { previousIssue: IssueResponse | undefined }>({
    mutationFn: ({ key, summary, expectedVersion }: UpdateIssueSummaryInput) =>
      updateIssue(key, { summary, expectedVersion }),

    onMutate: async ({ key, summary }: UpdateIssueSummaryInput) => {
      const qKey = issueQueryKey(key)

      // 진행 중인 쿼리를 취소해 낙관적 업데이트가 덮어씌워지는 것을 방지
      await queryClient.cancelQueries({ queryKey: qKey })

      // 롤백에 쓸 이전 캐시 snapshot
      const previousIssue = queryClient.getQueryData<IssueResponse>(qKey)

      // 낙관적으로 캐시에 새 summary 선반영
      if (previousIssue !== undefined) {
        queryClient.setQueryData<IssueResponse>(qKey, { ...previousIssue, summary })
      }

      return { previousIssue }
    },

    onSuccess: (updatedIssue, { key }: UpdateIssueSummaryInput) => {
      // 서버 응답으로 캐시 갱신 — version 포함 전체 필드 반영
      queryClient.setQueryData<IssueResponse>(issueQueryKey(key), updatedIssue)
    },

    onError: (
      error,
      { key }: UpdateIssueSummaryInput,
      context,
    ) => {
      // 이전 캐시로 롤백
      if (context?.previousIssue !== undefined) {
        queryClient.setQueryData<IssueResponse>(issueQueryKey(key), context.previousIssue)
      }

      // 409 VERSION_CONFLICT: 다른 사람이 먼저 수정한 경우 안내
      if (error instanceof ApiError && error.status === 409) {
        toast.error(issueDetailStrings.versionConflictError)
      }
    },

    onSettled: (_data, _error, { key }: UpdateIssueSummaryInput) => {
      // 성공/실패 무관하게 서버 상태와 동기화
      void invalidateIssueViews(queryClient, key)
    },
  })
}
