// 계정 연결 목록을 조회하는 TanStack Query 훅
import { useQuery } from '@tanstack/react-query'
import { fetchAccountLinks } from '@/api/account-links'

/** 계정 연결 목록 TanStack Query 캐시 키 상수 — invalidateQueries 공유용 */
export const ACCOUNT_LINKS_QUERY_KEY = ['account-links'] as const

/**
 * 현재 인증 사용자의 계정 연결 목록을 조회하는 훅.
 *
 * - `GET /api/v1/auth/account/links` → `AccountLinksResponse`
 * - staleTime 30초 — 연결 목록은 짧은 주기로 갱신할 필요가 없음
 * - 에러(401/403)는 `isError`로 노출, 처리는 컴포넌트 책임
 *
 * @returns TanStack Query 훅 반환 객체. `data`는 `AccountLinksResponse` 또는 undefined
 */
export function useAccountLinksQuery() {
  return useQuery({
    queryKey: ACCOUNT_LINKS_QUERY_KEY,
    queryFn: fetchAccountLinks,
    staleTime: 30_000,
  })
}
