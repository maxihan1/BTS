// 일괄 작업 접수 mutation + 진행률 폴링 query 훅
import { useMutation, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { submitBulkOperation, fetchBulkOperation } from '@/api/bulk-operations'
import type { BulkUpdateInput, BulkAccepted, BulkOperationResponse } from '@/api/bulk-operations'

/** 폴링 간격 (ms) — PENDING/RUNNING 상태에서 서버를 재조회하는 주기 */
const POLL_INTERVAL_MS = 1500

/** 종단 상태 집합 — COMPLETED 또는 FAILED 도달 시 폴링 중단 */
const TERMINAL_STATUSES = new Set<BulkOperationResponse['status']>(['COMPLETED', 'FAILED'])

/**
 * 일괄 작업 queryKey 팩토리.
 * id마다 별도 캐시 슬롯을 사용해 직전 작업의 stale 데이터가 새 작업에 표시되지 않도록 한다.
 *
 * @param id 일괄 작업 UUID (null이면 disabled 용도)
 */
export const bulkOperationQueryKey = (id: string | null): ['bulk-operation', string | null] =>
  ['bulk-operation', id]

/**
 * ApiError body에서 ProblemDetail.detail 필드를 추출한다.
 * 백엔드가 이미 한국어로 채워 보내므로 그대로 사용한다.
 *
 * @param error 발생한 에러
 * @returns 사용자에게 표시할 한국어 메시지
 */
function extractDetail(error: unknown): string {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | null
    const detail = body?.['detail']
    if (typeof detail === 'string' && detail.length > 0) {
      return detail
    }
    return '일괄 작업 접수에 실패했습니다.'
  }
  return '일괄 작업 접수에 실패했습니다.'
}

/**
 * 일괄 작업 접수 mutation 훅.
 *
 * - `POST /api/v1/issues/bulk-update` → 202 Accepted
 * - 성공 시: `toast.success`로 접수 완료 안내
 * - 실패 시: `toast.error`로 ProblemDetail.detail(한국어) 표시.
 *   detail이 없으면 기본 메시지. raw errorCode는 절대 노출하지 않는다.
 *
 * @returns TanStack Query useMutation 반환 객체. `mutateAsync(input)`으로 호출.
 */
export function useSubmitBulkOperation() {
  return useMutation<BulkAccepted, unknown, BulkUpdateInput>({
    mutationFn: (input: BulkUpdateInput) => submitBulkOperation(input),
    onSuccess: () => {
      toast.success('일괄 작업이 접수되었습니다.')
    },
    onError: (error: unknown) => {
      toast.error(extractDetail(error))
    },
  })
}

/**
 * 일괄 작업 진행률 폴링 query 훅.
 *
 * - `GET /api/v1/bulk-operations/{id}` 를 `POLL_INTERVAL_MS` 간격으로 반복 조회
 * - `status`가 COMPLETED 또는 FAILED(종단 상태)에 도달하면 폴링을 자동으로 중단한다
 * - `enabled=false` 또는 `id=null`이면 쿼리를 실행하지 않는다
 * - queryKey에 id를 포함해 작업별로 캐시를 분리한다
 *
 * @param id 조회할 일괄 작업 UUID. null이면 disabled.
 * @param enabled false이면 쿼리 비활성화
 * @returns TanStack Query useQuery 반환 객체
 */
export function useBulkOperationPolling(id: string | null, enabled: boolean) {
  return useQuery<BulkOperationResponse>({
    queryKey: bulkOperationQueryKey(id),
    queryFn: () => fetchBulkOperation(id as string),
    enabled: enabled && id !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (status !== undefined && TERMINAL_STATUSES.has(status)) {
        return false
      }
      return POLL_INTERVAL_MS
    },
  })
}
