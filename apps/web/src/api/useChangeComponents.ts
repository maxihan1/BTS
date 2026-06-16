// 이슈의 컴포넌트 다중 할당을 변경하는 mutation 훅
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { changeComponents } from '@/api/issues'
import type { IssueResponse, ChangeComponentsInput as ApiChangeComponentsInput } from '@/api/issues'
import { issueQueryKey } from './useUpdateIssueSummary'
import { issueDetailStrings } from '@/i18n/ko'
import { issueWatchersKey } from '@/api/issue-watchers'

/** useChangeComponents mutate 입력 타입 — 훅 레벨에서 이슈 key를 포함한 확장 형태 */
export interface ChangeComponentsInput extends ApiChangeComponentsInput {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  key: string
}

/**
 * 이슈 컴포넌트 다중 할당 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /api/v1/issues/{key}/components 호출
 * 2. onError: 409 → 버전 충돌 toast.error / 422 → 컴포넌트 미존재 toast.error
 * 3. onSettled: invalidateQueries(['issue', key])로 서버 최신 상태 재조회 (setQueryData 금지 — 교훈 1)
 *
 * setQueryData 사용 금지.
 * PATCH 응답의 descriptionHtml은 항상 null이라 단건 GET 이후에만 채워짐.
 * setQueryData(전체 응답)로 덮으면 descriptionHtml이 null로 덮여 화면 플리커 발생.
 *
 * @returns UseMutationResult — mutate({ key, componentIds, expectedVersion }) 호출로 컴포넌트 변경 실행
 */
export function useChangeComponents() {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, ApiError, ChangeComponentsInput>({
    mutationFn: ({ key, componentIds, expectedVersion }: ChangeComponentsInput) =>
      changeComponents(key, { componentIds, expectedVersion }),

    onError: (error) => {
      if (error instanceof ApiError) {
        if (error.status === 409) {
          toast.error(issueDetailStrings.versionConflictError)
        } else if (error.status === 422) {
          toast.error(issueDetailStrings.componentNotFoundError)
        } else {
          toast.error(issueDetailStrings.componentChangeError)
        }
      }
    },

    onSettled: (_data, _error, { key }: ChangeComponentsInput) => {
      // 성공/실패 무관하게 서버 상태와 동기화 — setQueryData 금지 (descriptionHtml 플리커 방지)
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(key) })
      // FR-WT-01 FR-7: 백엔드가 컴포넌트 변경 시 해당 컴포넌트 책임자를 자동 watcher로 등록하므로
      // watcher 목록도 함께 갱신해야 한다.
      void queryClient.invalidateQueries({ queryKey: issueWatchersKey(key) })
    },
  })
}
