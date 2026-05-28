// 이슈 타입 목록 TanStack Query hook — 5 표준 read-only, staleTime 1h
import { useQuery } from '@tanstack/react-query'
import { fetchIssueTypes } from '@/api/issue-types'

/** 이슈 타입 queryKey 상수 */
export const ISSUE_TYPE_KEYS = {
  /** 이슈 타입 목록 queryKey */
  list: ['issue-types'] as const,
} satisfies Record<string, readonly string[]>

/** 이슈 타입 캐시 유효 시간 — 시스템 고정값이므로 1시간 */
const ISSUE_TYPE_STALE_TIME = 60 * 60 * 1_000

/**
 * 이슈 타입 목록을 조회한다.
 * GET /api/v1/issue-types → IssueTypeResponse[]
 *
 * 5 표준 이슈 타입(bug, task, story, epic, subtask)은 시스템 고정값이므로
 * staleTime을 1시간으로 설정해 반복 요청을 최소화한다.
 */
export function useIssueTypes() {
  return useQuery({
    queryKey: ISSUE_TYPE_KEYS.list,
    queryFn: fetchIssueTypes,
    staleTime: ISSUE_TYPE_STALE_TIME,
  })
}
