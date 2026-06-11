// 이슈 변경 이력 TanStack Query 조회 훅 — useQuery 래퍼 (FR-HS-02)
import { useQuery } from '@tanstack/react-query'
import { fetchIssueChangelog } from '@/api/changelog'
import type { ChangelogPage } from '@/api/changelog'

/** 기본 페이지 크기 — 20건 */
const DEFAULT_PAGE_SIZE = 20

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 변경 이력 queryKey 팩토리 */
export const CHANGELOG_KEYS = {
  /**
   * 이슈 변경 이력 목록 queryKey.
   * page가 달라지면 별도 캐시 항목으로 관리된다.
   */
  list: (issueKey: string, page: number) =>
    ['issue-changelog', issueKey, page] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// useIssueChangelog — 이슈 변경 이력 페이지 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 변경 이력을 페이징 조회한다.
 *
 * GET /api/v1/issues/{key}/changelog?page={page}&size={DEFAULT_PAGE_SIZE}
 *
 * "더 보기" 패턴에서 page를 증가시켜 호출하면 별도 캐시로 관리된다.
 * staleTime 0 — 이력은 항상 최신 데이터를 반영해야 하므로 캐시를 유지하지 않는다.
 *
 * @param issueKey 이슈 식별 키 (예: "ATLAS-1")
 * @param page 0-based 페이지 번호 (기본값: 0)
 */
export function useIssueChangelog(issueKey: string, page: number = 0) {
  return useQuery<ChangelogPage, unknown>({
    queryKey: CHANGELOG_KEYS.list(issueKey, page),
    queryFn: () => fetchIssueChangelog(issueKey, page, DEFAULT_PAGE_SIZE),
    staleTime: 0,
    retry: false,
  })
}
