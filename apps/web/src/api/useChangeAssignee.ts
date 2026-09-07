// 이슈 담당자 변경 mutation 훅 — invalidate-only (setQueryData 금지) + 409/422 toast
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { changeAssignee } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
import { issueWatchersKey } from '@/api/issue-watchers'
import { invalidateIssueViews } from './issue-view-invalidation'

/** useChangeAssignee mutate 입력 타입 */
export interface ChangeAssigneeInput {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  key: string
  /** 담당자 UUID. null이면 해제. */
  assigneeId: string | null
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

/**
 * 이슈 담당자 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /api/v1/issues/{key}/assignee 호출
 * 2. onError: 409 → 버전 충돌 toast.error / 422 → 사용자 미존재 toast.error
 * 3. onSettled: invalidateQueries(['issue', key])로 서버 최신 상태 재조회 (setQueryData 금지 — 교훈 1)
 *
 * ⚠️ setQueryData 사용 금지.
 * PATCH 응답의 descriptionHtml은 항상 null이라 단건 GET 이후에만 채워짐.
 * setQueryData(전체 응답)로 덮으면 descriptionHtml이 null로 덮여 화면 플리커 발생.
 *
 * @returns UseMutationResult — mutate({ key, assigneeId, expectedVersion }) 호출로 담당자 변경 실행
 */
export function useChangeAssignee() {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, ApiError, ChangeAssigneeInput>({
    mutationFn: ({ key, assigneeId, expectedVersion }: ChangeAssigneeInput) =>
      changeAssignee(key, { assigneeId, expectedVersion }),

    onError: (error) => {
      if (error instanceof ApiError) {
        if (error.status === 409) {
          toast.error(issueDetailStrings.versionConflictError)
        } else if (error.status === 422) {
          toast.error(issueDetailStrings.assigneeNotFoundError)
        } else {
          toast.error(issueDetailStrings.assigneeChangeError)
        }
      }
    },

    onSettled: (_data, _error, { key }: ChangeAssigneeInput) => {
      // 성공/실패 무관하게 서버 상태와 동기화 — setQueryData 금지 (descriptionHtml 플리커 방지)
      void invalidateIssueViews(queryClient, key)
      // FR-WT-01 FR-7: 백엔드가 담당자 변경 시 해당 인물을 자동 watcher로 등록하므로
      // watcher 목록도 함께 갱신해야 한다.
      void queryClient.invalidateQueries({ queryKey: issueWatchersKey(key) })
    },
  })
}
