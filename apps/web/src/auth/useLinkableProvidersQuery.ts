// 연결 가능한 공급자 목록을 조회하는 TanStack Query 훅 — 다이얼로그 열릴 때만 fetch하도록 enabled 옵션 지원
import { useQuery } from '@tanstack/react-query'
import { fetchLinkableProviders } from '@/api/account-links'

/** 연결 가능한 공급자 목록 TanStack Query 캐시 키 상수 */
export const LINKABLE_PROVIDERS_QUERY_KEY = ['linkable-providers'] as const

/**
 * 현재 계정에 연결 가능한 공급자 목록을 조회하는 훅.
 *
 * - `GET /api/v1/auth/account/linkable-providers` → `LinkableProvider[]`
 * - `enabled` 파라미터로 fetch 타이밍을 제어한다 — 다이얼로그가 열릴 때만 호출하려면 `false`로 초기화한 후 다이얼로그 open 시 `true`로 전환.
 * - staleTime 60초 — 공급자 목록은 자주 바뀌지 않음
 *
 * @param enabled fetch 활성화 여부 (기본값: true). false이면 쿼리를 실행하지 않는다.
 * @returns TanStack Query 훅 반환 객체. `data`는 `LinkableProvider[]` 또는 undefined
 */
export function useLinkableProvidersQuery(enabled = true) {
  return useQuery({
    queryKey: LINKABLE_PROVIDERS_QUERY_KEY,
    queryFn: fetchLinkableProviders,
    staleTime: 60_000,
    enabled,
  })
}
