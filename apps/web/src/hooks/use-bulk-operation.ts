// 일괄 작업 접수 mutation + 진행률 폴링 query 훅
import { useMutation, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ZodError } from 'zod'
import { ApiError } from '@/api/client'
import { submitBulkOperation, fetchBulkOperation } from '@/api/bulk-operations'
import type { Query } from '@tanstack/react-query'
import type { BulkUpdateInput, BulkAccepted, BulkOperationResponse } from '@/api/bulk-operations'

/**
 * 폴링 간격 (ms) — PENDING/RUNNING 상태에서 서버를 재조회하는 주기.
 *
 * **왜 2초인가.** 일괄 작업은 건수에 따라 수 초~수 분이라 이보다 길면 사용자가 「멈췄다」로
 * 오해하고, 짧으면 긴 작업에서 불필요한 요청이 쌓인다. FR-WF-07 D6b 리뷰(D3)가 정한 값이며,
 * 종전 1500 에서 올렸다 — 요청을 줄이는 방향이라 기존 소비처에도 안전하다.
 * **간격보다 정지 조건이 중요하다** — [computeRefetchInterval] 참조.
 */
export const POLL_INTERVAL_MS = 2000

/**
 * 5xx·네트워크 오류에 대한 재시도 상한(횟수).
 *
 * 4xx(403/404)는 재시도해도 서버 응답이 바뀌지 않으므로 이 값과 무관하게 즉시 정지한다.
 * 이 값은 일시 장애(배포 순단 등)를 흡수하되, 영구 장애를 무한 폴링으로 오인하지 않기 위한
 * 타협점이다.
 */
export const MAX_ERROR_RETRIES = 3

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
 * 다음 폴을 할지, 몇 ms 뒤에 할지 판정한다 — 이 훅의 정지 조건 전부가 여기 있다.
 *
 * 정지 조건.
 * - 종료 상태(`COMPLETED`·`FAILED`) 도달 — 성공만 멈추면 FAILED 에서 영구 폴링이 된다
 * - `ApiError` 4xx(403·404 등) — 재시도해도 같은 응답이 온다. 기다리게 하는 것이 거짓말이다
 * - `ZodError`(스키마 불일치) — 목이 서버보다 관대했을 때 여기서 처음 드러난다. 응답 구조가
 *   바뀌지 않는 한 재시도해도 다시 실패한다
 * - 5xx·네트워크 오류는 `MAX_ERROR_RETRIES` 회까지만 재시도하고 그 뒤 정지한다(일시 장애와
 *   영구 장애를 가른다)
 *
 * ★ **재시도 예산은 폴링 1회분이 아니라 쿼리 생애 전체다.** `errorUpdateCount` 는 error 진입마다
 * +1 하고 **`success` 로 리셋되지 않는** 누적 카운터다(`@tanstack/query-core`). 따라서 「3회까지」는
 * 연속 3회가 아니라 **총 3회**이고, 긴 이관에서 간헐적 5xx 가 상한을 채운 뒤에는 다음 에러 한 번에
 * 곧바로 멈춘다. 의도한 동작이다 — 폴링은 무한히 도는 쪽이 위험하고, 멈춘 뒤의 출구는 화면의
 * 「다시 시도」(`useMigrationWizard.retryPoll`)가 쥔다.
 *
 * ★ 4xx 즉시 정지에는 **429 도 포함**된다. 지금 이 엔드포인트에 rate limit 이 없어 실제로는 안
 * 나오지만, 도입한다면 429 만 5xx 쪽(재시도) 으로 옮겨야 한다 — 429 는 「기다리면 된다」가 참인
 * 유일한 4xx 다.
 *
 * @param query TanStack Query가 넘기는 현재 쿼리(상태만 사용)
 * @returns 다음 폴까지의 ms, 또는 정지할 경우 false
 */
export function computeRefetchInterval(
  query: Pick<Query<BulkOperationResponse>, 'state'>,
): number | false {
  if (query.state.status === 'error') {
    const error = query.state.error
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      return false
    }
    if (error instanceof ZodError) {
      return false
    }
    return query.state.errorUpdateCount >= MAX_ERROR_RETRIES ? false : POLL_INTERVAL_MS
  }
  const status = query.state.data?.status
  if (status !== undefined && TERMINAL_STATUSES.has(status)) {
    return false
  }
  return POLL_INTERVAL_MS
}

/**
 * 일괄 작업 응답으로부터 진행률(0~1)을 계산한다.
 * `totalCount`가 아직 없거나 0이면(0으로 나누기 방지) 계산할 수 없어 null.
 *
 * @param data 최신 조회 응답. 아직 없으면 undefined.
 * @returns 0~1 진행률, 계산 불가 시 null
 */
export function calculateProgressRatio(data: BulkOperationResponse | undefined): number | null {
  if (data === undefined || data.totalCount === 0) {
    return null
  }
  return data.processedCount / data.totalCount
}

/**
 * 일괄 작업 진행률 폴링 query 훅.
 *
 * - `GET /api/v1/bulk-operations/{id}` 를 `POLL_INTERVAL_MS` 간격으로 반복 조회
 * - `computeRefetchInterval` 이 정한 조건(종료 상태·4xx·ZodError·5xx/네트워크 재시도 상한)에서
 *   멈춘다
 * - `enabled=false` 또는 `id=null`이면 쿼리를 실행하지 않는다
 * - queryKey에 id를 포함해 작업별로 캐시를 분리한다
 * - `retry: false` — react-query 내장 재시도 대신 `computeRefetchInterval` 로 재시도를
 *   통일해, 호출 측 QueryClient 설정과 무관하게 훅 스스로 정지 조건을 보장한다
 *
 * @param id 조회할 일괄 작업 UUID. null이면 disabled.
 * @param enabled false이면 쿼리 비활성화
 * @returns TanStack Query useQuery 반환 객체 + 진행률 파생값 `progressRatio`(0~1, 계산 불가 시 null)
 */
export function useBulkOperationPolling(id: string | null, enabled: boolean) {
  const query = useQuery<BulkOperationResponse>({
    queryKey: bulkOperationQueryKey(id),
    queryFn: () => fetchBulkOperation(id as string),
    enabled: enabled && id !== null,
    retry: false,
    refetchInterval: computeRefetchInterval,
  })

  return {
    ...query,
    progressRatio: calculateProgressRatio(query.data),
  }
}
