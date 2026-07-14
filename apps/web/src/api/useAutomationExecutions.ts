// 자동화 룰 실행 이력 조회/재실행 TanStack Query 훅 — 커서 무한스크롤 목록 + 단건 trace + replay (FR-AT-05 D6/D7)
import { useInfiniteQuery, useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UseQueryResult, UseMutationResult, QueryClient, InfiniteData } from '@tanstack/react-query'
import { fetchRuleExecutions, fetchRuleExecution, replayRuleExecution } from './automation-executions'
import type { RuleExecutionSummary, RuleExecutionDetail } from './automation-executions.types'
import type { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 실행 이력 목록 페이지당 조회 건수 — 커서 판정(길이===limit)에도 동일 값을 재사용 */
const EXECUTIONS_PAGE_LIMIT = 50

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 실행 이력 목록 쿼리 키 튜플 타입 — issueKey 미지정 시 null로 정규화해 필터 없음 키를 고정한다 */
export type AutomationExecutionsQueryKey = readonly [string, string, string, string | null]

/**
 * 룰별 실행 이력 목록 쿼리 키 생성 헬퍼.
 *
 * projectKey·ruleId·issueKey를 모두 키에 포함하는 filter-aware 캐싱 — issueKey가 바뀌면
 * TanStack Query 입장에서 완전히 새 쿼리가 되어 페이지 누적이 자동으로 리셋된다(수동으로
 * `data.pages`를 비울 필요 없음). AUTOMATION_RULES_QUERY_KEY(automation-rules BC 선례)와
 * 동일하게 mutation onSuccess도 이 헬퍼로 키를 재구성해 문자열 리터럴 drift를 막는다.
 *
 * @param projectKey 프로젝트 키
 * @param ruleId 자동화 룰 UUID
 * @param issueKey 이슈별 필터 (미지정 시 null로 정규화)
 * @returns TanStack Query queryKey 튜플
 */
export const AUTOMATION_EXECUTIONS_QUERY_KEY = (
  projectKey: string,
  ruleId: string,
  issueKey?: string,
): AutomationExecutionsQueryKey => ['automation-executions', projectKey, ruleId, issueKey ?? null]

/** 실행 이력 단건 trace 쿼리 키 튜플 타입 */
export type AutomationExecutionDetailQueryKey = readonly [string, string | null]

/**
 * 실행 이력 단건(trace) 쿼리 키 생성 헬퍼.
 *
 * @param id 실행 이력 UUID (미확정 상태면 null)
 * @returns TanStack Query queryKey 튜플
 */
export const AUTOMATION_EXECUTION_DETAIL_QUERY_KEY = (id: string | null): AutomationExecutionDetailQueryKey => [
  'automation-execution-detail',
  id,
]

// ─────────────────────────────────────────────────────────────────────────────
// useRuleExecutions — 커서 기반 무한스크롤 목록
// ─────────────────────────────────────────────────────────────────────────────

/** `useRuleExecutions` 옵션 */
export interface UseRuleExecutionsOptions {
  /** 특정 이슈로 좁힌 실행 이력만 조회 */
  issueKey?: string
}

/** `useRuleExecutions` 반환 형태 — 소비 편의를 위해 누적 목록 + 무한쿼리 상태를 평탄화해 노출 */
export interface UseRuleExecutionsResult {
  /** 지금까지 로드된 모든 페이지를 평탄화한 실행 이력 목록 */
  executions: RuleExecutionSummary[]
  /** 다음 페이지를 요청한다 (더 없으면 호출해도 무동작) */
  fetchNextPage: () => Promise<void>
  /** 다음 페이지 존재 여부 — 마지막 페이지 길이가 limit과 같을 때만 true */
  hasNextPage: boolean
  /** 다음 페이지 로딩 중 여부 */
  isFetchingNextPage: boolean
  /** 최초 로딩 중 여부 */
  isLoading: boolean
  /** 조회 실패 시 에러, 없으면 null */
  error: ApiError | null
}

/**
 * 마지막 페이지 길이로 다음 페이지 존재 여부를 판정하고, 존재하면 커서(`before`)로 쓸
 * 마지막 항목의 `startedAt`을 반환한다. 길이가 limit 미만이면(서버가 덜 채워 반환) 더 없는
 * 것으로 간주해 undefined를 반환한다.
 */
function getNextExecutionsPageParam(lastPage: RuleExecutionSummary[]): string | undefined {
  if (lastPage.length < EXECUTIONS_PAGE_LIMIT) {
    return undefined
  }
  const lastItem = lastPage[lastPage.length - 1]
  return lastItem?.startedAt
}

/**
 * 자동화 룰의 실행 이력 목록을 커서 기반 무한스크롤로 조회하는 훅.
 *
 * 페이지네이션 모델로 `useInfiniteQuery`를 사용한다(수동 offset 누적 대신) — 커서(`before`)가
 * 서버 정렬 기준(startedAt desc)과 자연히 맞물리고, TanStack Query가 페이지 배열/pageParam
 * 이력을 관리해 "더 보기" 버튼의 로딩 상태·중복 요청 방지를 별도 구현할 필요가 없다.
 *
 * @param projectKey 프로젝트 키
 * @param ruleId 자동화 룰 UUID
 * @param opts issueKey(이슈별 필터)
 * @returns 평탄화된 목록 + 무한쿼리 상태 ({@link UseRuleExecutionsResult})
 */
export function useRuleExecutions(
  projectKey: string,
  ruleId: string,
  opts?: UseRuleExecutionsOptions,
): UseRuleExecutionsResult {
  const issueKey = opts?.issueKey

  const query = useInfiniteQuery<
    RuleExecutionSummary[],
    ApiError,
    InfiniteData<RuleExecutionSummary[], string | undefined>,
    AutomationExecutionsQueryKey,
    string | undefined
  >({
    queryKey: AUTOMATION_EXECUTIONS_QUERY_KEY(projectKey, ruleId, issueKey),
    queryFn: ({ pageParam }) =>
      fetchRuleExecutions(projectKey, ruleId, { issueKey, limit: EXECUTIONS_PAGE_LIMIT, before: pageParam }),
    initialPageParam: undefined,
    getNextPageParam: (lastPage) => getNextExecutionsPageParam(lastPage),
  })

  return {
    executions: query.data?.pages.flat() ?? [],
    fetchNextPage: async () => {
      await query.fetchNextPage()
    },
    hasNextPage: query.hasNextPage,
    isFetchingNextPage: query.isFetchingNextPage,
    isLoading: query.isLoading,
    error: query.error,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useRuleExecutionDetail — 단건 trace
// ─────────────────────────────────────────────────────────────────────────────

/** `useRuleExecutionDetail` 옵션 */
export interface UseRuleExecutionDetailOptions {
  /** true여야 조회한다 — 목록 행이 펼쳐졌을 때만 true로 넘겨 불필요한 조회를 막는다 */
  enabled?: boolean
}

/**
 * 실행 이력 단건의 전체 trace(replay 재료 `triggerEvent` + 액션별 결과)를 조회하는 훅.
 *
 * 목록 행을 펼칠 때만 호출되도록 `opts.enabled`(기본 false)와 `id !== null`을 모두 만족해야
 * 실제 요청이 나간다 — 접힌 행까지 미리 전부 조회하지 않기 위함이다.
 *
 * @param id 실행 이력 UUID (미확정이면 null)
 * @param opts enabled(펼침 여부, 기본 false)
 * @returns TanStack Query `useQuery` 결과
 */
export function useRuleExecutionDetail(
  id: string | null,
  opts?: UseRuleExecutionDetailOptions,
): UseQueryResult<RuleExecutionDetail, ApiError> {
  const enabled = (opts?.enabled ?? false) && id !== null

  return useQuery<RuleExecutionDetail, ApiError>({
    queryKey: AUTOMATION_EXECUTION_DETAIL_QUERY_KEY(id),
    queryFn: () => {
      // enabled 가드가 id !== null을 보장하지만, queryFn 스코프에서 타입을 좁히려면
      // 명시적 가드가 필요하다(`as string` 강제 캐스팅 대신).
      if (id === null) {
        return Promise.reject(new Error('useRuleExecutionDetail: id가 null인 상태에서는 조회할 수 없다'))
      }
      return fetchRuleExecution(id)
    },
    enabled,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// useReplayRuleExecution — 재실행 mutation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * replay 응답(RuleExecutionDetail)을 목록 summary 형태로 매핑한다.
 * 집계값(actionCount/successCount)은 detail에 없으므로 outcomes에서 직접 계산한다.
 */
function toReplayedSummary(detail: RuleExecutionDetail): RuleExecutionSummary {
  return {
    id: detail.id,
    ruleId: detail.ruleId,
    triggerType: detail.triggerType,
    issueKey: detail.issueKey,
    status: detail.status,
    actionCount: detail.outcomes.length,
    successCount: detail.outcomes.filter((outcome) => outcome.success).length,
    startedAt: detail.startedAt,
    finishedAt: detail.finishedAt,
    replayedFrom: detail.replayedFrom,
  }
}

/**
 * detail 캐시를 새 실행 결과로 시드한다 — 재실행 직후 자동 펼침 시 `useRuleExecutionDetail`이
 * 재fetch하지 않고 이 값을 즉시 사용하도록 한다.
 */
function seedReplayedExecutionDetail(queryClient: QueryClient, detail: RuleExecutionDetail): void {
  queryClient.setQueryData(AUTOMATION_EXECUTION_DETAIL_QUERY_KEY(detail.id), detail)
}

/**
 * issueKey 필터 없음 목록 쿼리의 캐시된 첫 페이지 맨 앞에 새 실행을 prepend한다.
 * 응답이 서버가 실제로 생성한 전체 RuleExecutionDetail이라 부분응답 플리커 우려 없이
 * summary로 매핑해 안전하게 얹을 수 있다. 캐시가 아직 없으면(목록을 연 적 없음) 손대지 않는다.
 */
function prependReplayedExecutionSummary(
  queryClient: QueryClient,
  projectKey: string,
  ruleId: string,
  detail: RuleExecutionDetail,
): void {
  const summary = toReplayedSummary(detail)
  const key = AUTOMATION_EXECUTIONS_QUERY_KEY(projectKey, ruleId)

  queryClient.setQueryData<InfiniteData<RuleExecutionSummary[], string | undefined>>(key, (old) => {
    if (old === undefined) {
      return old
    }
    const [firstPage, ...restPages] = old.pages
    if (firstPage === undefined) {
      return old
    }
    return { ...old, pages: [[summary, ...firstPage], ...restPages] }
  })
}

/**
 * 실행 이력 재실행(replay) mutation 훅 — 저장된 triggerEvent를 룰의 현재 정의로 다시 평가한다.
 *
 * onSuccess에서 두 캐시를 함께 갱신한다.
 * 1. detail 캐시 시드 — {@link seedReplayedExecutionDetail}
 * 2. 목록 첫 페이지 prepend — {@link prependReplayedExecutionSummary} (issueKey 필터 없음 키만;
 *    필터가 걸린 쿼리키는 이 mutation 시점엔 활성이 아닐 수 있어 최소 보장 범위로 좁힌다)
 *
 * @param projectKey 프로젝트 키
 * @param ruleId 자동화 룰 UUID
 * @returns UseMutationResult — mutate(id: string) 호출로 재실행 실행
 */
export function useReplayRuleExecution(
  projectKey: string,
  ruleId: string,
): UseMutationResult<RuleExecutionDetail, ApiError, string> {
  const queryClient = useQueryClient()

  return useMutation<RuleExecutionDetail, ApiError, string>({
    mutationFn: (id: string) => replayRuleExecution(id),
    onSuccess: (newDetail) => {
      seedReplayedExecutionDetail(queryClient, newDetail)
      prependReplayedExecutionSummary(queryClient, projectKey, ruleId, newDetail)
    },
  })
}
