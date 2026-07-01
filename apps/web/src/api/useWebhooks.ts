// 아웃바운드 webhook 구독/발송이력 목록 조회 + 생성/수정/삭제 React Query 훅
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { WebhookResponse, WebhookDeliveryResponse, CreateWebhookRequest, UpdateWebhookRequest } from './webhooks'
import { fetchWebhooks, fetchDeliveries, createWebhook, updateWebhook, deleteWebhook } from './webhooks'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수 — 캐시 키 문자열을 한 곳에서 관리해 오타·drift 방지
// mutation onSuccess에서 invalidateQueries를 호출할 때 이 상수를 직접 참조한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * webhook 구독 목록 TanStack Query 캐시 키 prefix.
 * mutation(create/update/delete) onSuccess → invalidateQueries 대상.
 */
export const WEBHOOKS_QUERY_KEY = ['webhooks'] as const

/**
 * webhook 발송 이력 TanStack Query 캐시 키 prefix.
 * update mutation onSuccess → invalidateQueries 대상 (재발송/재시도로 이력이 바뀔 수 있음).
 */
export const WEBHOOK_DELIVERIES_QUERY_KEY = ['webhook-deliveries'] as const

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * webhook 구독 목록을 조회하는 훅. SYSTEM_ADMIN 전용.
 *
 * - `GET /api/v1/webhooks?page=&size=` → `WebhookResponse[]`
 * - queryKey에 page/size를 포함해 filter-aware — 페이지가 바뀌면 새로 조회한다.
 *
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @returns TanStack Query 훅 반환 객체. `data`는 `WebhookResponse[]` 또는 undefined
 */
export function useWebhooksQuery(page: number, size: number) {
  return useQuery<WebhookResponse[]>({
    queryKey: [...WEBHOOKS_QUERY_KEY, page, size],
    queryFn: () => fetchWebhooks(page, size),
  })
}

/**
 * webhook 구독의 발송 이력을 조회하는 훅. SYSTEM_ADMIN 전용.
 *
 * - `GET /api/v1/webhooks/{id}/deliveries?page=&size=` → `WebhookDeliveryResponse[]`
 * - queryKey에 id/page/size를 포함해 filter-aware.
 *
 * @param id 구독 UUID
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @returns TanStack Query 훅 반환 객체. `data`는 `WebhookDeliveryResponse[]` 또는 undefined
 */
export function useWebhookDeliveriesQuery(id: string, page: number, size: number) {
  return useQuery<WebhookDeliveryResponse[]>({
    queryKey: [...WEBHOOK_DELIVERIES_QUERY_KEY, id, page, size],
    queryFn: () => fetchDeliveries(id, page, size),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — 모든 onSuccess는 invalidate-only (setQueryData 금지)
// setQueryData로 캐시를 통째 덮으면 파생 필드 누락으로 화면 플리커 발생 (memory: mutation-setquerydata-partial-response-flicker)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * webhook 구독 생성 mutation 훅. SYSTEM_ADMIN 전용.
 *
 * - `POST /api/v1/webhooks` → 201 `WebhookResponse`
 * - onSuccess: `webhooks` 쿼리 invalidate (refetch 유도)
 *
 * @returns UseMutationResult — mutate(CreateWebhookRequest) 호출로 생성 실행
 */
export function useCreateWebhook() {
  const queryClient = useQueryClient()

  return useMutation<WebhookResponse, Error, CreateWebhookRequest>({
    mutationFn: (body: CreateWebhookRequest) => createWebhook(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WEBHOOKS_QUERY_KEY })
    },
  })
}

/** useUpdateWebhook mutate 입력 타입 */
export interface UpdateWebhookInput {
  /** 구독 UUID */
  id: string
  /** 수정 요청 바디 (version 포함, OCC) */
  body: UpdateWebhookRequest
}

/**
 * webhook 구독 수정(전체 교체) mutation 훅. SYSTEM_ADMIN 전용.
 *
 * - `PUT /api/v1/webhooks/{id}` → 200 `WebhookResponse`
 * - onSuccess: `webhooks` + `webhook-deliveries` 쿼리 모두 invalidate
 *
 * @returns UseMutationResult — mutate({ id, body }) 호출로 수정 실행
 */
export function useUpdateWebhook() {
  const queryClient = useQueryClient()

  return useMutation<WebhookResponse, Error, UpdateWebhookInput>({
    mutationFn: ({ id, body }: UpdateWebhookInput) => updateWebhook(id, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WEBHOOKS_QUERY_KEY })
      void queryClient.invalidateQueries({ queryKey: WEBHOOK_DELIVERIES_QUERY_KEY })
    },
  })
}

/**
 * webhook 구독 삭제 mutation 훅. SYSTEM_ADMIN 전용.
 *
 * - `DELETE /api/v1/webhooks/{id}` → 204
 * - onSuccess: `webhooks` 쿼리 invalidate
 *
 * @returns UseMutationResult — mutate(id: string) 호출로 삭제 실행
 */
export function useDeleteWebhook() {
  const queryClient = useQueryClient()

  return useMutation<void, Error, string>({
    mutationFn: (id: string) => deleteWebhook(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: WEBHOOKS_QUERY_KEY })
    },
  })
}
