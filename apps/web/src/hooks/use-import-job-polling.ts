// 비동기 Import 잡 진행률 폴링 query 훅 — use-export-job-polling.ts 폴링 패턴 미러
import { useQuery } from '@tanstack/react-query'
import { fetchImportJobStatus, IMPORT_POLL_INTERVAL_MS } from '@/api/imports'
import type { ImportJobStatus } from '@/api/imports'

/** 종단 상태 집합 — COMPLETED 또는 FAILED 도달 시 폴링 중단 */
const TERMINAL_STATUSES = new Set<ImportJobStatus['status']>(['COMPLETED', 'FAILED'])

/**
 * Import 잡 queryKey 팩토리.
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
