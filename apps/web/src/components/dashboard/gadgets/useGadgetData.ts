// 가젯 데이터 fetch 훅 — TanStack Query 기반 가젯별 분기 (FR-DB-02 D6/D7 Task-4)
import { useQuery } from '@tanstack/react-query'
import { fetchIssues } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { searchAql } from '@/api/search'
import type { AqlSearchHit } from '@/api/search'
import { fetchFilter } from '@/api/saved-filters'
import { useAuthUser } from '@/auth/authStore'
import type { GadgetIssueRow, DataGadgetType, GadgetConfig, GadgetDataResult } from './gadget-types'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** maxItems 기본값 */
const DEFAULT_MAX_ITEMS = 10

/** maxItems 최솟값 */
const MIN_ITEMS = 1

/** maxItems 최댓값 */
const MAX_ITEMS = 50

/**
 * 가젯 데이터 staleTime(ms) — 30초.
 * 대시보드는 실시간 데이터보다 적당한 캐시 주기가 적합하다.
 */
const GADGET_STALE_TIME = 30_000

// ─────────────────────────────────────────────────────────────────────────────
// 정규화 순수 함수 (exported for test)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * IssueResponse를 GadgetIssueRow로 정규화한다.
 * key와 summary만 추출해 가젯이 최소 표현으로 렌더링하도록 한다 (Gap 2).
 *
 * @param response 백엔드 이슈 단건 응답
 * @returns GadgetIssueRow { key, summary }
 */
export function issueResponseToRow(response: IssueResponse): GadgetIssueRow {
  return { key: response.key, summary: response.summary }
}

/**
 * AqlSearchHit를 GadgetIssueRow로 정규화한다.
 * key와 summary만 추출해 가젯이 최소 표현으로 렌더링하도록 한다 (Gap 2).
 *
 * @param hit AQL 검색 결과 단건
 * @returns GadgetIssueRow { key, summary }
 */
export function aqlHitToRow(hit: AqlSearchHit): GadgetIssueRow {
  return { key: hit.key, summary: hit.summary }
}

/**
 * maxItems 값을 1~50 범위로 클램프한다.
 * undefined이면 기본값 10을 반환한다.
 *
 * @param value 클램프할 값
 * @returns 클램프된 값 (1~50 범위, undefined면 10)
 */
export function clampMaxItems(value: number | undefined): number {
  if (value === undefined) return DEFAULT_MAX_ITEMS
  return Math.min(MAX_ITEMS, Math.max(MIN_ITEMS, value))
}

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 데이터 queryKey 팩토리.
 * gadgetType + config JSON 직렬화로 캐시를 구분한다.
 * config가 변경되면 별도 캐시 엔트리를 생성해 가젯별 독립 캐시를 보장한다.
 */
function gadgetDataKey(
  gadgetType: DataGadgetType,
  config: GadgetConfig,
): readonly [string, DataGadgetType, string] {
  return ['gadget-data', gadgetType, JSON.stringify(config)] as const
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 fetch 함수 — 가젯 타입별 분기
// ─────────────────────────────────────────────────────────────────────────────

/**
 * assigned_to_me 가젯 — fetchIssues(assignee 필터)로 이슈 목록을 가져온다.
 * projectKey 지정형 가젯: config.projectKey 필수.
 */
async function fetchAssignedToMe(
  config: GadgetConfig,
  userId: string,
): Promise<GadgetIssueRow[]> {
  const page = await fetchIssues({
    projectKey: config.projectKey ?? '',
    page: 0,
    size: clampMaxItems(config.maxItems),
    filter: {
      assigneeIds: [userId],
      statusKeys: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    },
  })
  return page.content.map(issueResponseToRow)
}

/**
 * recently_created 가젯 — fetchIssues(필터 없음, 기본 created_at DESC)로 최신 이슈를 가져온다.
 * projectKey 지정형 가젯: config.projectKey 필수.
 */
async function fetchRecentlyCreated(config: GadgetConfig): Promise<GadgetIssueRow[]> {
  const page = await fetchIssues({
    projectKey: config.projectKey ?? '',
    page: 0,
    size: clampMaxItems(config.maxItems),
  })
  return page.content.map(issueResponseToRow)
}

/**
 * filter_result 가젯 — fetchFilter → searchAql 순으로 호출해 이슈 목록을 가져온다.
 * filterId 경로(MVP): fetchFilter로 projectKey + aqlQuery를 얻어 searchAql에 전달한다.
 */
async function fetchFilterResult(config: GadgetConfig): Promise<GadgetIssueRow[]> {
  const filter = await fetchFilter(config.filterId ?? '')
  const searchPage = await searchAql({
    projectKey: filter.projectKey,
    query: filter.aqlQuery,
    size: clampMaxItems(config.maxItems),
  })
  return searchPage.data.map(aqlHitToRow)
}

/**
 * issue_count 가젯 — fetchFilter → searchAql(size:1) 순으로 호출해 totalElements만 반환한다.
 * size: 1로 최소화해 데이터 전송량을 줄인다.
 */
async function fetchIssueCount(config: GadgetConfig): Promise<number> {
  const filter = await fetchFilter(config.filterId ?? '')
  const searchPage = await searchAql({
    projectKey: filter.projectKey,
    query: filter.aqlQuery,
    size: 1,
  })
  return searchPage.meta.page.totalElements
}

// ─────────────────────────────────────────────────────────────────────────────
// useGadgetData 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 데이터를 fetch하는 TanStack Query 훅.
 *
 * 가젯 타입에 따라 기존 BC API(fetchIssues / searchAql / fetchFilter)를 재사용한다.
 * 새 API 신설 없음 — BC 격리 준수.
 *
 * ★ React Rules of Hooks 준수.
 * useAuthUser()는 항상 컴포넌트 최상위에서 호출한다.
 * userId는 assigned_to_me 가젯에서만 실제로 사용하지만,
 * 조건부 훅 호출 금지 규칙에 따라 항상 호출 후 조건부 사용한다 (plan 노트 B).
 *
 * @param gadgetType 데이터를 fetch할 가젯 타입
 * @param config 가젯 설정 (projectKey / filterId / maxItems)
 * @returns 로딩/에러 상태 + rows(목록 가젯) | totalElements(건수 가젯)
 */
export function useGadgetData(gadgetType: DataGadgetType, config: GadgetConfig): GadgetDataResult {
  // ★ 항상 최상위에서 호출 — assigned_to_me에서만 사용하지만 조건부 훅 호출 금지
  const user = useAuthUser()
  const userId = user?.userId

  // 가젯 타입별 enabled 조건
  const enabled = resolveEnabled(gadgetType, config, userId)

  const query = useQuery<GadgetIssueRow[] | number>({
    queryKey: gadgetDataKey(gadgetType, config),
    staleTime: GADGET_STALE_TIME,
    enabled,
    queryFn: () => dispatchFetch(gadgetType, config, userId ?? ''),
  })

  if (gadgetType === 'issue_count') {
    return {
      isLoading: query.isLoading,
      isError: query.isError,
      rows: undefined,
      totalElements: typeof query.data === 'number' ? query.data : undefined,
    }
  }

  return {
    isLoading: query.isLoading,
    isError: query.isError,
    rows: Array.isArray(query.data) ? (query.data as GadgetIssueRow[]) : undefined,
    totalElements: undefined,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 가젯 타입별 쿼리 활성화 조건을 결정한다.
 *
 * - assigned_to_me: projectKey 존재 + userId 존재(로그인 필수)
 * - recently_created: projectKey 존재
 * - filter_result / issue_count: filterId 존재
 */
function resolveEnabled(
  gadgetType: DataGadgetType,
  config: GadgetConfig,
  userId: string | undefined,
): boolean {
  switch (gadgetType) {
    case 'assigned_to_me':
      return Boolean(config.projectKey) && userId !== undefined
    case 'recently_created':
      return Boolean(config.projectKey)
    case 'filter_result':
    case 'issue_count':
      return Boolean(config.filterId)
  }
}

/**
 * 가젯 타입에 따라 적절한 fetch 함수를 호출한다.
 * queryFn 내부에서 사용되는 dispatcher.
 */
function dispatchFetch(
  gadgetType: DataGadgetType,
  config: GadgetConfig,
  userId: string,
): Promise<GadgetIssueRow[] | number> {
  switch (gadgetType) {
    case 'assigned_to_me':
      return fetchAssignedToMe(config, userId)
    case 'recently_created':
      return fetchRecentlyCreated(config)
    case 'filter_result':
      return fetchFilterResult(config)
    case 'issue_count':
      return fetchIssueCount(config)
  }
}
