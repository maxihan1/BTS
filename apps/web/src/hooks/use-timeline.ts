// 타임라인 조회 TanStack Query 훅 (FR-TL-01)
import { useQuery } from '@tanstack/react-query'
import { fetchTimeline } from '@/api/timeline'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 타임라인 BC queryKey 팩토리 */
export const timelineKeys = {
  /**
   * 프로젝트별 타임라인 목록 queryKey.
   *
   * `['timeline', projectKey]` 2요소 tuple.
   * projectKey가 달라지면 별개 캐시 엔트리로 분리된다.
   *
   * @param projectKey 프로젝트 키. 예: `"ATLAS"`
   */
  list: (projectKey: string) => ['timeline', projectKey] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// useTimeline — 프로젝트 타임라인 아이템 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트에 속한 타임라인 아이템 목록을 조회한다.
 *
 * GET /api/v1/timeline?project={projectKey} → TimelineResponse (items + truncated)
 * staleTime 30초 — 빈번한 재조회를 방지한다.
 *
 * @param projectKey 프로젝트 식별 키. 빈 문자열이면 쿼리가 비활성화된다.
 */
export function useTimeline(projectKey: string) {
  return useQuery({
    queryKey: timelineKeys.list(projectKey),
    queryFn: () => fetchTimeline(projectKey),
    staleTime: 30_000,
    enabled: projectKey.length > 0,
  })
}
