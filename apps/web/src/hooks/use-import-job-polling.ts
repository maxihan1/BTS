// 비동기 Import 잡 진행률 폴링 query 훅 — use-export-job-polling.ts 폴링 패턴 미러
import { useQuery } from '@tanstack/react-query'
import { fetchImportJobStatus, IMPORT_POLL_INTERVAL_MS } from '@/api/imports'
import type { ImportJobStatus } from '@/api/imports'

/** 종단 상태 집합 — COMPLETED 또는 FAILED 도달 시 폴링 중단 */
const TERMINAL_STATUSES = new Set<ImportJobStatus['status']>(['COMPLETED', 'FAILED'])

/**
 * Import 잡 queryKey 팩토리.
 * id마다 별도 캐시 슬롯을 사용해 직전 잡의 stale 데이터가 새 잡에 표시되지 않도록 한다.
 *
 * @param id Import 잡 UUID (null이면 disabled 용도)
 */
export const importJobQueryKey = (id: string | null): ['import-job', string | null] => [
  'import-job',
  id,
]

/**
 * 비동기 Import 잡 진행률 폴링 query 훅.
 *
 * 상태머신 다이어그램.
 * ```
 * tracking → (폴링 COMPLETED) → done(errorLogReady/succeededRows 확정)
 * tracking → (폴링 FAILED)    → done(errorCode)
 * ```
 *
 * 폴링 계약.
 * - `GET /api/v1/imports/{id}` 를 `IMPORT_POLL_INTERVAL_MS`(1500ms) 간격으로 반복 조회
 * - status가 COMPLETED 또는 FAILED(종단 상태)에 도달하면 폴링을 자동으로 중단한다
 * - `enabled=false` 또는 `id=null`이면 쿼리를 실행하지 않는다
 * - queryKey에 id를 포함해 잡별로 캐시를 분리한다 (stale 데이터 표시 방지)
 *
 * ★ react-query v5 refetchInterval 콜백 인자는 `data`가 아니라 `query` 객체 (v4→v5 파괴적 변경).
 * `query.state.data?.status`로 종단 상태를 판별한다.
 * `data => data?.status` 형태로 쓰면 종단 판별 실패 → 폴링 무한 실행 (use-export-job-polling BLOCKER-1 동형).
 *
 * cleanup 근거.
 * - 다이얼로그 Content unmount 시 useQuery observer가 제거됨
 * - TanStack Query v5는 observer 없으면 refetchInterval 타이머를 즉시 정리함 (FR-7 NFR-3)
 *
 * @param id 조회할 Import 잡 UUID. null이면 disabled.
 * @param enabled false이면 쿼리 비활성화
 * @returns TanStack Query useQuery 반환 객체
 */
export function useImportJobPolling(id: string | null, enabled: boolean) {
  return useQuery<ImportJobStatus>({
    queryKey: importJobQueryKey(id),
    queryFn: () => fetchImportJobStatus(id as string),
    enabled: enabled && id !== null,
    refetchInterval: (query) => {
      // 에러 상태에서는 무한 재시도를 방지하기 위해 폴링을 중단한다.
      // (403 / 404 / 네트워크 에러 등)
      if (query.state.status === 'error') {
        return false
      }
      const status = query.state.data?.status
      if (status !== undefined && TERMINAL_STATUSES.has(status)) {
        return false
      }
      return IMPORT_POLL_INTERVAL_MS
    },
  })
}
