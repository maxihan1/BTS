// 상태 이관 일괄작업의 진행률을 폴링하는 훅 — 종료 상태와 에러에서 멈춘다
import { useQuery } from '@tanstack/react-query'
import { ZodError } from 'zod'
import { fetchBulkOperation } from '@/api/bulk-operations'
import { ApiError } from '@/api/client'
import type { Query } from '@tanstack/react-query'
import type { BulkOperationResponse } from '@/api/bulk-operations'

/**
 * 폴링 간격(ms) — 2초 고정.
 *
 * 상태 이관은 이슈 수에 따라 수 초~수 분이 걸린다. 2초보다 짧으면 서버 요청이 과해지고,
 * 길면 사용자가 "멈췄다"고 오해한다. 점진적 백오프는 파생 판정이 늘어 구현을 무겁게 만들어
 * 기각했다(plan NFR — 리뷰 eng 렌즈 D3). 값을 상수로 고정해 두지 않으면 다음 구현자가
 * 임의로 고르고, 바꿀 때 근거가 없어 또 임의로 고른다.
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

/** 폴링을 멈추는 종료 상태 — COMPLETED 또는 FAILED */
const TERMINAL_STATUSES = new Set<BulkOperationResponse['status']>(['COMPLETED', 'FAILED'])

/**
 * 상태 이관 일괄작업 queryKey 팩토리.
 * id마다 별도 캐시 슬롯을 사용해 직전 작업의 stale 데이터가 새 작업에 표시되지 않도록 한다.
 *
 * @param id 일괄 작업 UUID (null이면 disabled 용도)
 */
export const migrationProgressQueryKey = (id: string | null): ['migration-progress', string | null] => [
  'migration-progress',
  id,
]

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
 * 상태 이관 일괄작업(`STATUS_MIGRATION`) 진행률 폴링 훅.
 *
 * `GET /api/v1/bulk-operations/{id}` 를 `POLL_INTERVAL_MS` 간격으로 반복 조회하고
 * `computeRefetchInterval` 이 정한 조건(종료 상태·4xx·ZodError·5xx/네트워크 재시도 상한)에서
 * 멈춘다. `id === null` 이면 애초에 시작하지 않고(`enabled: false`), 언마운트 시에는 TanStack
 * Query v5가 observer 소멸과 함께 refetchInterval 타이머를 즉시 정리한다.
 *
 * 캐시는 매 폴에서 `fetchBulkOperation` 이 반환한 **전체 응답**으로 교체된다 — 부분 응답을
 * `setQueryData` 로 병합하지 않으므로 필드가 placeholder 로 덮이는 플리커가 없다.
 *
 * @param id 조회할 일괄 작업 UUID. null이면 폴링을 시작하지 않는다.
 * @returns TanStack Query 결과 + 진행률 파생값 `progressRatio`(0~1, 계산 불가 시 null)
 */
export function useMigrationProgress(id: string | null) {
  const query = useQuery<BulkOperationResponse>({
    queryKey: migrationProgressQueryKey(id),
    queryFn: () => fetchBulkOperation(id as string),
    enabled: id !== null,
    retry: false,
    refetchInterval: computeRefetchInterval,
  })

  return {
    ...query,
    progressRatio: calculateProgressRatio(query.data),
  }
}
