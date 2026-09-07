// 이슈 보안등급 변경 mutation 훅 — invalidate-only (setQueryData 금지) + 409/403 toast
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
import { invalidateIssueViews } from './issue-view-invalidation'

/** useChangeSecurityLevel mutate 입력 타입 */
export interface ChangeSecurityLevelInput {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  key: string
  /**
   * 지정할 보안등급 UUID.
   * null이면 해제(공개 복귀).
   * 백엔드 PATCH body의 securityLevelId: JsonNullable 3-state:
   * - null = 해제, UUID = 지정 (필드 부재는 이 훅에서 직접 전달해 처리)
   */
  securityLevelId: string | null
  /** 낙관적 잠금(OCC)을 위한 현재 버전 번호 */
  expectedVersion: number
}

/**
 * 이슈 보안등급 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /api/v1/issues/{key} — body.securityLevelId 포함
 * 2. onError: 409 → 버전 충돌 toast.error / 403 → 권한 없음 toast.error / 그 외 → 일반 에러 toast
 * 3. onSettled: invalidateQueries(['issue', key])로 서버 최신 상태 재조회 (setQueryData 금지)
 *
 * setQueryData 사용 금지.
 * PATCH 응답의 descriptionHtml은 항상 null이라 단건 GET 이후에만 채워진다.
 * setQueryData(전체 응답)로 덮으면 descriptionHtml이 null로 덮여 화면 플리커 발생.
 * (mutation-setquerydata-partial-response-flicker 교훈)
 *
 * @returns UseMutationResult — mutate({ key, securityLevelId, expectedVersion }) 호출로 보안등급 변경 실행
 */
export function useChangeSecurityLevel() {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, ApiError, ChangeSecurityLevelInput>({
    mutationFn: ({ key, securityLevelId, expectedVersion }: ChangeSecurityLevelInput) =>
      updateIssue(key, { securityLevelId, expectedVersion }),

    onError: (error) => {
      if (error instanceof ApiError) {
        if (error.status === 409) {
          toast.error(issueDetailStrings.securityLevelVersionConflictError)
        } else if (error.status === 403) {
          toast.error(issueDetailStrings.securityLevelForbiddenError)
        } else {
          toast.error(issueDetailStrings.securityLevelChangeError)
        }
      }
    },

    onSettled: (_data, _error, { key }: ChangeSecurityLevelInput) => {
      // 성공/실패 무관하게 서버 상태와 동기화 — setQueryData 금지 (descriptionHtml 플리커 방지)
      void invalidateIssueViews(queryClient, key)
    },
  })
}
