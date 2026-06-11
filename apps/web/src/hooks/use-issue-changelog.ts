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
 *
 * staleTime 30_000 — 이력은 append-only이므로 30초 캐시 유지. 창 포커스 복귀 시
 * offset 기반 다중 페이지가 동시에 재조회되어 경계 그룹 중복/누락이 발생하는
 * 것을 방지하기 위해 refetchOnWindowFocus를 false로 고정한다.
 *
 * @param issueKey 이슈 식별 키 (예: "ATLAS-1")
 * @param page 0-based 페이지 번호 (기본값: 0)
 */
export function useIssueChangelog(issueKey: string, page: number = 0) {
  return useQuery<ChangelogPage, unknown>({
    queryKey: CHANGELOG_KEYS.list(issueKey, page),
    queryFn: () => fetchIssueChangelog(issueKey, page, DEFAULT_PAGE_SIZE),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    retry: false,
  })
}
