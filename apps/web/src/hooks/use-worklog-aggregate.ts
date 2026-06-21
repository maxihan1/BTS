// 워크로그 집계 TanStack Query 훅 — projectKey + 파라미터 조합 캐시 분리 (FR-TT-02)
import { useQuery } from '@tanstack/react-query'
import {
  fetchWorklogAggregate,
  type AggregateDimension,
  type AggregateGranularity,
} from '@/api/worklog-aggregate'

// ─────────────────────────────────────────────────────────────────────────────
// 파라미터 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 집계 훅 파라미터 */
export interface WorklogAggregateParams {
  /** 집계 차원 — 이슈별/사용자별/기간별 */
  by: AggregateDimension
  /** 기간 단위 — by=period일 때 사용 */
  granularity?: AggregateGranularity
  /** 조회 시작일 (yyyy-MM-dd) */
  from?: string
  /** 조회 종료일 (yyyy-MM-dd) */
  to?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지, 파라미터 전체 포함으로 캐시 분리
// ─────────────────────────────────────────────────────────────────────────────

/** 워크로그 집계 queryKey 팩토리 */
export const WORKLOG_AGGREGATE_KEYS = {
  /**
   * 프로젝트 + 파라미터 조합 queryKey.
   * by / granularity / from / to 중 하나라도 다르면 별도 캐시 버킷이 된다.
   *
   * @param projectKey 프로젝트 키
   * @param params 집계 파라미터
   */
  all: (projectKey: string, params: WorklogAggregateParams) =>
    ['worklog-aggregate', projectKey, params] as const,
} satisfies Record<string, (projectKey: string, params: WorklogAggregateParams) => readonly unknown[]>

// ─────────────────────────────────────────────────────────────────────────────
// useWorklogAggregate — 집계 조회
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트의 워크로그 집계를 조회한다.
 *
 * GET /api/v1/worklogs/aggregate?project={projectKey}&by={by}[&granularity=...][&from=...][&to=...]
 *
 * - projectKey가 빈 문자열이면 `enabled: false`로 fetch를 지연한다.
 * - 파라미터(by/granularity/from/to) 전체가 queryKey에 포함되어,
 *   파라미터 변경 시 별도 캐시 버킷으로 분리된다.
 *
 * @param projectKey 프로젝트 키 (빈 문자열이면 fetch하지 않음)
 * @param params 집계 조회 파라미터 (by 필수, 나머지 선택)
 * @returns TanStack Query useQuery 결과 (data / isPending / isError / error)
 */
export function useWorklogAggregate(projectKey: string, params: WorklogAggregateParams) {
  return useQuery({
    queryKey: WORKLOG_AGGREGATE_KEYS.all(projectKey, params),
    queryFn: () => fetchWorklogAggregate(projectKey, params),
    enabled: projectKey.length > 0,
  })
}
