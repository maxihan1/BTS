// 팔레트 라이브 검색 훅 — 디바운스·이슈키 조회·AQL 검색·활성 프로젝트 게이트 (FR-UX-12 F4 ADR D-5)
import { useSearch } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { useDebounce } from '@/hooks/use-debounce'
import { useResolvedActiveProject } from '@/hooks/use-resolved-active-project'
import { fetchIssue } from '@/api/issues'
import { searchAql, type AqlSearchHit } from '@/api/search'
import { buildTextQuery } from '@/lib/aql-text-query'
import type { PaletteInput } from './palette-input'

/** 자유 텍스트 검색 디바운스(ms) — LabelAutocompleteInput 선례와 동일 (NFR1) */
const DEBOUNCE_DELAY_MS = 250

/** 팔레트가 인라인으로 보여줄 결과 상한 — 초과분은 「모든 결과 보기」로 유도 (NFR3) */
const PALETTE_RESULT_LIMIT = 7

/** 팔레트 결과 한 줄 — 키 + 요약 (Jira 대조 J3, 프로젝트명은 의도적 편차 X2 로 제외) */
export interface PaletteResult {
  readonly key: string
  readonly summary: string
}

/** usePaletteSearch 반환값 */
export interface PaletteSearchResult {
  /** 이슈키 즉시매칭 결과. 미일치·404·403 이면 null (S3·E6) */
  readonly issueHit: PaletteResult | null
  /** 자유 텍스트 검색 결과 (최대 PALETTE_RESULT_LIMIT 건) */
  readonly results: readonly PaletteResult[]
  /**
   * 서버가 보고한 **전체** 일치 건수 (`meta.page.totalElements`).
   *
   * ★`results.length` 와 다르다. 팔레트는 7건만 보여주므로 이 값 없이는 사용자가
   * "7건이 전부"라고 오독한다(design 리뷰 2-2). 「모든 결과 보기 (N건)」에 쓴다.
   */
  readonly totalCount: number
  /** 검색 진행 중 여부 */
  readonly isSearching: boolean
  /**
   * 접근 가능한 프로젝트가 **0개**라 자유 텍스트 검색이 불가한 상태 (FR7·S8).
   *
   * ★해소 중(`isResolvingProject`)·조회 실패(`projectError`)는 여기 들어오지 않는다.
   * 셋을 한 갈래로 묶으면 "고를 프로젝트가 없다"는 안내가 **사실이 아닌 상황**에도 떠서
   * 사용자를 빈 프로젝트 목록으로 보낸다.
   */
  readonly needsProject: boolean
  /**
   * 활성 프로젝트를 **아직 불러오는 중**이라 검색을 보내지 않은 상태 (E8).
   *
   * ★렌더 층이 이것을 모르면 요청을 한 건도 안 보낸 채 「결과가 없습니다.」라고
   * 단정한다 — `needsProject: false` 만으로는 "검색 가능"과 구분되지 않기 때문이다.
   */
  readonly isResolvingProject: boolean
  /**
   * 활성 프로젝트 목록 조회 **실패** (E9). 실패가 아니면 null.
   *
   * `retry` 를 멤버 **안**에 실어 소비처가 재시도 배선을 빠뜨릴 수 없게 한다
   * (`ResolvedActiveProject` 의 error 멤버와 같은 설계 — 별도 optional 필드면
   * 잊어도 tsc 가 못 잡는다). '0개'와 '못 불러왔다'는 서로 다른 사실이고,
   * 후자의 유일한 탈출구가 재시도다(`use-resolved-active-project.ts:69-70`).
   */
  readonly projectError: { readonly retry: () => void } | null
  /** 검색 실패 메시지. 없으면 null (FR13) */
  readonly errorMessage: string | null
  /** 「모든 결과 보기」가 넘길 AQL 질의. 자유 텍스트가 아니면 null */
  readonly fullSearchQuery: string | null
}

/**
 * 판별된 팔레트 입력으로 이슈키 조회 + 자유 텍스트 검색을 수행한다.
 *
 * **이슈키는 디바운스하지 않는다** — 키는 완성형으로 입력되고 조회가 단건이라
 * 지연이 체감 손해다. 자유 텍스트만 250ms 디바운스한다.
 *
 * **활성 프로젝트 게이트(ADR D-4).** 자유 텍스트 검색은 `projectKey` 가 필수라
 * `ready` 가 아니면 호출하지 않는다. 이슈키 조회는 프로젝트에 의존하지 않으므로
 * 그대로 진행한다.
 *
 * @param input palette-input 의 판별 결과
 * @returns 조회 결과 + 상태
 */
export function usePaletteSearch(input: PaletteInput): PaletteSearchResult {
  const urlProjectKey = (useSearch({ strict: false }) as { projectKey?: string }).projectKey
  const active = useResolvedActiveProject(urlProjectKey)
  const projectKey = active.status === 'ready' ? active.projectKey : null

  const isFreeText = input.kind === 'free-text'
  const issueKey = input.kind === 'issue-key' ? input.issueKey : null
  const rawQuery =
    input.kind === 'issue-key' ? input.issueKey : input.kind === 'free-text' ? input.query : ''
  const debouncedQuery = useDebounce(rawQuery, DEBOUNCE_DELAY_MS)
  const aqlQuery = buildTextQuery(debouncedQuery)

  // 이슈키 조회 — 프로젝트 무관(ADR D-4). 404/403 은 "없음"으로 흡수한다(S3·E6):
  // 존재 여부를 노출하지 않고 자유 텍스트 경로가 이어받는다.
  const issueQuery = useQuery({
    queryKey: ['palette-issue', issueKey],
    queryFn: () => fetchIssue(issueKey as string),
    enabled: issueKey !== null,
    retry: false,
    staleTime: 30_000,
  })

  const canSearch = projectKey !== null && aqlQuery !== null && input.kind !== 'empty'
  const searchQuery = useQuery({
    queryKey: ['palette-search', projectKey, aqlQuery],
    queryFn: () =>
      searchAql({
        projectKey: projectKey as string,
        query: aqlQuery as string,
        page: 0,
        size: PALETTE_RESULT_LIMIT,
      }),
    enabled: canSearch,
    retry: false,
    staleTime: 30_000,
  })

  const issueHit: PaletteResult | null =
    issueQuery.data !== undefined
      ? { key: issueQuery.data.key, summary: issueQuery.data.summary }
      : null

  const results: readonly PaletteResult[] = (searchQuery.data?.data ?? []).map(
    (hit: AqlSearchHit) => ({ key: hit.key, summary: hit.summary }),
  )

  return {
    issueHit,
    results,
    // ?? 0 은 조회 전/실패 시. results.length 로 대체하지 않는다 — 그러면 7건 상한이
    // 그대로 총계로 보고돼 design 리뷰 2-2 가 지적한 오독을 코드가 만들어낸다.
    totalCount: searchQuery.data?.meta.page.totalElements ?? 0,
    isSearching: searchQuery.isFetching,
    // 이슈키 경로는 프로젝트가 없어도 동작하므로 안내 3종 모두 자유 텍스트에만 세운다(ADR D-4)
    needsProject: isFreeText && active.status === 'empty',
    isResolvingProject: isFreeText && active.status === 'loading',
    projectError: isFreeText && active.status === 'error' ? { retry: active.retry } : null,
    errorMessage: searchQuery.error !== null ? '검색에 실패했습니다.' : null,
    fullSearchQuery: aqlQuery,
  }
}
