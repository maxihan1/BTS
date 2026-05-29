// 활성 세션 목록을 조회하는 TanStack Query 훅
import { useQuery } from '@tanstack/react-query'
import { listSessions } from '@/api/sessions'
import type { Session } from '@/api/sessions'

/** 세션 목록 TanStack Query 캐시 키 상수 — invalidateQueries 공유용 */
export const SESSIONS_QUERY_KEY = ['sessions'] as const

/**
 * 현재 인증 사용자의 활성 세션 목록을 조회하는 훅.
 *
 * - `GET /api/v1/auth/sessions` → `Session[]`
 * - staleTime 30초 — 세션 목록은 짧은 주기로 갱신할 필요가 없음
 * - PAT 인증 시 백엔드가 403을 반환, isError가 true가 됨 (spec §FR-6b)
 *
 * @returns TanStack Query 훅 반환 객체. `data`는 `Session[]` 또는 undefined
 */
export function useSessionsQuery() {
  return useQuery<Session[]>({
    queryKey: SESSIONS_QUERY_KEY,
    queryFn: listSessions,
    staleTime: 30_000,
  })
}
