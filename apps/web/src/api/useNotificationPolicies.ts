// 알림 정책 목록/카탈로그 조회 + 생성/토글/삭제 React Query 훅
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { PolicyCatalog, NotificationPolicy, CreatePolicyRequest } from './notification-policies'
import {
  fetchCatalog,
  fetchPolicies,
  createPolicy,
  togglePolicy,
  deletePolicy,
} from './notification-policies'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 알림 정책 목록 TanStack Query 캐시 키 */
export const NOTIFICATION_POLICIES_QUERY_KEY = ['notification-policies'] as const

/** 알림 정책 카탈로그 TanStack Query 캐시 키 */
export const NOTIFICATION_CATALOG_QUERY_KEY = ['notification-catalog'] as const

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 카탈로그를 조회하는 훅.
 *
 * - `GET /api/v1/notification-policies/catalog` → `PolicyCatalog`
 * - 선택 가능한 eventType/recipientRole/channel 목록을 반환한다.
 * - staleTime 5분 — 카탈로그는 서버 enum 변경 시에만 갱신됨.
 * - 인증만 필요 (비-admin도 OK).
 *
 * @returns TanStack Query 훅 반환 객체. `data`는 `PolicyCatalog` 또는 undefined
 */
export function useCatalogQuery() {
  return useQuery<PolicyCatalog>({
    queryKey: NOTIFICATION_CATALOG_QUERY_KEY,
    queryFn: fetchCatalog,
    staleTime: 5 * 60_000,
  })
}

/**
 * 전역 알림 정책 목록을 조회하는 훅. SYSTEM_ADMIN 전용.
 *
 * - `GET /api/v1/notification-policies` → `NotificationPolicy[]`
 * - D1 = 전역만 → projectKey 파라미터 없이 호출.
 * - staleTime 30초.
 *
 * @returns TanStack Query 훅 반환 객체. `data`는 `NotificationPolicy[]` 또는 undefined
 */
export function usePoliciesQuery() {
  return useQuery<NotificationPolicy[]>({
    queryKey: NOTIFICATION_POLICIES_QUERY_KEY,
    queryFn: fetchPolicies,
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — 모든 onSuccess는 invalidate-only (setQueryData 금지)
// setQueryData로 캐시를 통째 덮으면 파생 필드 누락으로 화면 플리커 발생 (memory: mutation-setquerydata-partial-response-flicker)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 생성 mutation 훅. SYSTEM_ADMIN 전용.
 *
 * - `POST /api/v1/notification-policies` → 201 `NotificationPolicy`
 * - onSuccess: `notification-policies` 쿼리 invalidate (refetch 유도)
 * - 409 중복 → mutation isError=true, error.status===409
 *
 * @returns UseMutationResult — mutate(CreatePolicyRequest) 호출로 생성 실행
 */
export function useCreatePolicy() {
  const queryClient = useQueryClient()

  return useMutation<NotificationPolicy, Error, CreatePolicyRequest>({
    mutationFn: (body: CreatePolicyRequest) => createPolicy(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: NOTIFICATION_POLICIES_QUERY_KEY })
    },
  })
}

/** useTogglePolicy mutate 입력 타입 */
export interface TogglePolicyInput {
  /** 정책 UUID */
  id: string
  /** 변경할 활성 여부 */
  enabled: boolean
}

/**
 * 알림 정책 활성/비활성 토글 mutation 훅. SYSTEM_ADMIN 전용.
 *
 * - `PATCH /api/v1/notification-policies/{id}` → 204
 * - onSuccess: `notification-policies` 쿼리 invalidate
 * - ⚠️ enabled만 변경 가능. 조합 수정은 삭제 후 재생성.
 *
 * @returns UseMutationResult — mutate({ id, enabled }) 호출로 토글 실행
 */
export function useTogglePolicy() {
  const queryClient = useQueryClient()

  return useMutation<void, Error, TogglePolicyInput>({
    mutationFn: ({ id, enabled }: TogglePolicyInput) => togglePolicy(id, enabled),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: NOTIFICATION_POLICIES_QUERY_KEY })
    },
  })
}

/**
 * 알림 정책 삭제 mutation 훅. SYSTEM_ADMIN 전용.
 *
 * - `DELETE /api/v1/notification-policies/{id}` → 204
 * - onSuccess: `notification-policies` 쿼리 invalidate
 *
 * @returns UseMutationResult — mutate(id: string) 호출로 삭제 실행
 */
export function useDeletePolicy() {
  const queryClient = useQueryClient()

  return useMutation<void, Error, string>({
    mutationFn: (id: string) => deletePolicy(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: NOTIFICATION_POLICIES_QUERY_KEY })
    },
  })
}
