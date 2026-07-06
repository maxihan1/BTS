// 사용자 상태 메시지 TanStack Query 훅 — FR-PR-02, mutation 성공 시 status invalidate만 수행
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { fetchStatus, updateStatus } from '@/api/status'
import type { StatusPatchBody } from '@/api/status'
import type { StatusResponse } from '@/api/schemas'
import { ApiError } from '@/api/client'

/**
 * 본인 상태 조회 쿼리 키 — `["status", "me"]`.
 * mutation onSuccess의 invalidateQueries가 이 키를 그대로 사용해 이 쿼리를 무효화한다.
 */
export const STATUS_QUERY_KEY = ['status', 'me'] as const

/**
 * 본인 상태(이모지+텍스트+만료) 조회 훅.
 *
 * `GET /api/v1/users/me/status` → {@link StatusResponse}.
 *
 * @returns TanStack Query `useQuery` 결과
 */
export function useStatusQuery(): UseQueryResult<StatusResponse, ApiError> {
  return useQuery<StatusResponse, ApiError>({
    queryKey: STATUS_QUERY_KEY,
    queryFn: fetchStatus,
  })
}

/**
 * 상태 원자적 교체(replace) mutation 훅.
 *
 * 성공 시 {@link STATUS_QUERY_KEY} 쿼리를 invalidate해 재조회를 트리거한다
 * (mutation은 invalidate-only — 응답으로 캐시를 직접 덮어쓰지 않는다, memory:
 * mutation-setquerydata-partial-response-flicker).
 *
 * whoami 재조회 + authStore 갱신(헤더 배지 즉시 반영)은 이 훅의 책임이 아니다 — 호출측
 * (StatusModal, Task 8)이 저장 성공 후 명시적으로 처리한다. 이 훅을 재사용하는 다른 화면이
 * 항상 헤더 갱신을 원하는 것은 아니기 때문에 관심사를 분리했다.
 *
 * @returns TanStack Query `useMutation` 결과 — `mutate(body)`로 실행
 */
export function useUpdateStatusMutation(): UseMutationResult<
  StatusResponse,
  ApiError,
  StatusPatchBody
> {
  const queryClient = useQueryClient()

  return useMutation<StatusResponse, ApiError, StatusPatchBody>({
    mutationFn: updateStatus,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: STATUS_QUERY_KEY }),
  })
}
