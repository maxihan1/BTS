// 이슈 상태 전이 가용목록 조회 + 전이 실행 TanStack Query 훅
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { fetchIssueTransitions, transitionIssue } from '@/api/issues'
import type { TransitionIssueInput } from '@/api/issues'

/** 이슈 전이 관련 queryKey 팩토리 */
export const issueTransitionKeys = {
  /** 특정 이슈의 가용 전이 목록 queryKey */
  list: (key: string) => ['issue-transitions', key] as const,
}

/**
 * 이슈의 현재 상태에서 가용한 전이 목록을 조회한다.
 * GET /api/v1/issues/{key}/transitions
 *
 * @param key 이슈 식별 키 (예: "ATLAS-1")
 */
export function useIssueTransitions(key: string) {
  return useQuery({
    queryKey: issueTransitionKeys.list(key),
    queryFn: () => fetchIssueTransitions(key),
  })
}

/**
 * 이슈 상태를 전이한다.
 * POST /api/v1/issues/{key}/transition
 *
 * onSuccess 시 해당 이슈 캐시('issue', key)와 가용 전이 목록 캐시를 모두 무효화해
 * 상태 배지 및 전이 버튼이 최신 상태로 갱신되도록 한다.
 *
 * @param key 전이할 이슈 식별 키
 */
export function useTransitionIssue(key: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (input: TransitionIssueInput) => transitionIssue(key, input),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['issue', key] }),
        queryClient.invalidateQueries({ queryKey: issueTransitionKeys.list(key) }),
      ])
    },
  })
}
