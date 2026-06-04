// 라벨 자동완성 TanStack Query 훅 (FR-IS-09)
import { useQuery } from '@tanstack/react-query'
import { fetchLabels } from '@/api/labels'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 라벨 자동완성 queryKey 팩토리 */
export const LABEL_KEYS = {
  /** prefix q에 대한 라벨 자동완성 queryKey */
  search: (q: string) => ['labels', q] as const,
} satisfies Record<string, (q: string) => readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// useLabels — 자동완성 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 라벨 자동완성 후보를 조회한다.
 *
 * GET /api/v1/labels?q=<prefix> → string[]
 * - debounce는 호출처 책임 (이 훅에서 처리하지 않음)
 * - staleTime 30초 — 자동완성 제안은 준실시간 데이터, 짧은 캐시로 UX 보호
 *
 * @param q 라벨 prefix 검색어 (빈 문자열이면 전체 상위 10개)
 */
export function useLabels(q: string) {
  return useQuery({
    queryKey: LABEL_KEYS.search(q),
    queryFn: () => fetchLabels(q),
    staleTime: 30_000,
  })
}
