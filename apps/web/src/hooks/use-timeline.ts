// 타임라인 조회 TanStack Query 훅 (FR-TL-01)
import { useQuery } from '@tanstack/react-query'
import { fetchTimeline } from '@/api/timeline'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

export const timelineKeys = {
  list: (projectKey: string) => ['timeline', projectKey] as const,
}

// ─────────────────────────────────────────────────────────────────────────────
// useTimeline — 프로젝트 타임라인 아이템 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

export function useTimeline(projectKey: string) {
  return useQuery({
    queryKey: timelineKeys.list(projectKey),
    queryFn: () => fetchTimeline(projectKey),
    staleTime: 30_000,
    enabled: projectKey.length > 0,
  })
}
