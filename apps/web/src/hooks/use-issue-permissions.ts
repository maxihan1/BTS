// 이슈별 현재 사용자 권한을 조회하는 TanStack Query 훅 (FR-PM-02)
import { useQuery } from '@tanstack/react-query'
import { fetchIssuePermissions } from '@/api/issue-permissions'
import type { IssuePermissions } from '@/api/issue-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** queryKey 상수 — 매직 문자열 방지 */
export const ISSUE_PERMISSION_KEYS = {
  /** 이슈 권한 queryKey */
  detail: (issueKey: string) => ['issue-permissions', issueKey] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// useIssuePermissions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 로그인 사용자의 이슈별 권한을 조회한다.
 *
 * GET /api/v1/users/me/issue-permissions?issueKey={issueKey}
 * queryKey: ['issue-permissions', issueKey]
 * staleTime 30초 — 화면 내 중복 호출을 방지한다.
 * enabled: issueKey가 있을 때만 실행한다.
 *
 * @param issueKey 이슈 식별 키 (예: ATLAS-1). 빈 문자열이면 쿼리가 비활성화된다.
 * @returns TanStack Query 결과 — data(IssuePermissions), isLoading, isError 포함
 */
export function useIssuePermissions(issueKey: string) {
  return useQuery<IssuePermissions>({
    queryKey: ISSUE_PERMISSION_KEYS.detail(issueKey),
    queryFn: () => fetchIssuePermissions(issueKey),
    enabled: !!issueKey,
    staleTime: 30_000,
  })
}
