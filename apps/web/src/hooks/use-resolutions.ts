// 결의안(Resolution) 목록 TanStack Query 훅 (FR-IS-07)
import { useQuery } from '@tanstack/react-query'
import { fetchResolutions } from '@/api/resolutions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 결의안 BC queryKey 팩토리 */
export const RESOLUTION_KEYS = {
  /** 전체 결의안 목록 queryKey */
  all: () => ['resolutions'] as const,
} as const

// ─────────────────────────────────────────────────────────────────────────────
// useResolutions — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전체 결의안 목록을 조회한다.
 *
 * GET /api/v1/resolutions → Resolution[] (displayOrder asc)
 * staleTime 5분 — 결의안은 관리자가 변경하는 준정적(quasi-static) 데이터다.
 */
export function useResolutions() {
  return useQuery({
    queryKey: RESOLUTION_KEYS.all(),
    queryFn: fetchResolutions,
    staleTime: 5 * 60_000,
  })
}
