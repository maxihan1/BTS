// 사용자 부재중(Out of Office) TanStack Query 훅 — FR-PR-03, mutation은 invalidate-only
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { fetchOoo, updateOoo, clearOoo } from '@/api/ooo'
import type { OooPatchBody } from '@/api/ooo'
import type { OooResponse } from '@/api/schemas'
import { ApiError } from '@/api/client'

/**
 * 본인 부재중 설정 조회 쿼리 키 — `["ooo", "me"]`.
 * mutation onSuccess의 invalidateQueries가 이 키를 그대로 사용해 이 쿼리를 무효화한다.
 */
export const OOO_QUERY_KEY = ['ooo', 'me'] as const

/**
 * 본인 부재중(기간+대체담당자+메시지+활성여부) 조회 훅.
 *
 * `GET /api/v1/users/me/ooo` → {@link OooResponse}.
 *
 * @returns TanStack Query `useQuery` 결과
 */
export function useOooQuery(): UseQueryResult<OooResponse, ApiError> {
  return useQuery<OooResponse, ApiError>({
    queryKey: OOO_QUERY_KEY,
    queryFn: fetchOoo,
  })
}

/**
 * 부재중 원자적 교체(replace) mutation 훅.
 *
 * 성공 시 {@link OOO_QUERY_KEY} 쿼리를 invalidate해 재조회를 트리거한다
 * (mutation은 invalidate-only — 응답으로 캐시를 직접 덮어쓰지 않는다, memory:
 * mutation-setquerydata-partial-response-flicker).
 *
 * whoami 재조회 + authStore 갱신(헤더 부재중 표시 즉시 반영)은 이 훅의 책임이 아니다 — 호출측
 * (OooModal, Task 8)이 저장 성공 후 명시적으로 처리한다(useUpdateStatusMutation 선례와 동일한
 * 관심사 분리).
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate(body)`로 실행
 */
export function useUpdateOooMutation(): UseMutationResult<OooResponse, ApiError, OooPatchBody> {
  const queryClient = useQueryClient()

  return useMutation<OooResponse, ApiError, OooPatchBody>({
    mutationFn: updateOoo,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: OOO_QUERY_KEY }),
  })
}

/**
 * 부재중 해제 mutation 훅(멱등).
 *
 * 성공 시 {@link OOO_QUERY_KEY} 쿼리를 invalidate해 재조회를 트리거한다.
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate()`로 실행
 */
export function useClearOooMutation(): UseMutationResult<void, ApiError, void> {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, void>({
    mutationFn: clearOoo,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: OOO_QUERY_KEY }),
  })
}
