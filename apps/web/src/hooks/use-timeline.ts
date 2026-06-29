// 타임라인 조회 TanStack Query 훅 (FR-TL-01)
import { useQuery } from '@tanstack/react-query'
import { fetchTimeline, fetchTimelineDeps } from '@/api/timeline'

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

  /**
   * 프로젝트별 의존 라인(blocks) 엣지 queryKey.
   *
   * `['timeline', projectKey, 'deps']` 3요소 tuple.
   * `list` 키와 계층적으로 분리되어 deps만 독립 무효화 가능.
   *
   * @param projectKey 프로젝트 키. 예: `"BTS"`
   */
  deps: (projectKey: string) => ['timeline', projectKey, 'deps'] as const,
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

/**
 * 프로젝트 타임라인 의존 라인(blocks 관계) 엣지 목록을 조회한다.
 *
 * GET /api/v1/timeline/deps?project={projectKey} → TimelineDepsResponse (deps + truncated)
 * staleTime 30초 — 타임라인 목록과 동일 주기.
 *
 * best-effort: 이 훅이 에러를 반환해도 간트 렌더를 차단하지 않는다 (EC6).
 * deps 에러는 GanttChart/TimelinePage에서 오버레이만 조용히 미표시한다.
 *
 * @param projectKey 프로젝트 식별 키. 빈 문자열이면 쿼리가 비활성화된다.
 */
export function useTimelineDeps(projectKey: string) {
  return useQuery({
    queryKey: timelineKeys.deps(projectKey),
    queryFn: () => fetchTimelineDeps(projectKey),
    staleTime: 30_000,
    enabled: projectKey.length > 0,
  })
}
