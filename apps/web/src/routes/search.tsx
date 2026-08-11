// AQL 검색 페이지 — SearchPage(props 기반) + SearchRouteAdapter(URL 동기화 + filterId 딥링크) (FR-SR-02/03 D6 Task-5/6)
import type { JSX } from 'react'
import { useState, useCallback, useEffect } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { toast } from 'sonner'
import { searchAql, SEARCH_ERROR_CODES } from '@/api/search'
import type { AqlSearchPage } from '@/api/search'
import { ApiError } from '@/api/client'
import { fetchFilter } from '@/api/saved-filters'
import { AqlHighlighter } from '@/components/search/AqlHighlighter'
import { Button } from '@/components/ui/button'
import { SavedFilterMenu } from '@/components/search/SavedFilterMenu'
import { SaveFilterDialog } from '@/components/search/SaveFilterDialog'
import { ExportDialog } from '@/components/search/ExportDialog'
import { useResolvedActiveProject } from '@/hooks/use-resolved-active-project'
import { ActiveProjectGate } from '@/components/project/ActiveProjectGate'
import { searchLabels } from '@/i18n/search-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const PAGE_SIZE = 20

/**
 * filterId 로드 실패 시 표시할 toast 메시지.
 *
 * 에러 코드를 구분하지 않는다 — 404·400·500 등 **모든 에러**에 동일하게 적용한다 (N3).
 * 특정 코드(예: 404)만 처리하면 미구현 에러 코드에서 토스트 없이 무한 로딩이 발생한다.
 */
const FILTER_LOAD_ERROR_MSG = '필터를 불러오지 못했습니다. 일반 검색으로 진행합니다.'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → UI 메시지 매핑 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 검색 에러 코드와 detail을 사람이 읽을 수 있는 메시지로 변환한다.
 *
 * 문법오류/미지원필드는 입력창 하단 표시용이므로 detail을 우선한다.
 * 인증/권한 에러는 일반 alert에 표시한다.
 *
 * @param errorCode 백엔드 에러 코드
 * @param detail 백엔드 제공 detail 메시지 (선택)
 * @param position 문법 오류 발생 위치(0-base, 선택)
 * @returns 화면 표시용 메시지
 */
function resolveErrorMessage(
  errorCode: string | undefined,
  detail: string | undefined,
  position: number | undefined,
): string {
  if (errorCode === SEARCH_ERROR_CODES.SYNTAX_ERROR) {
    const baseMsg = detail ?? '쿼리 문법을 확인하세요.'
    if (position !== undefined) {
      return `${baseMsg} (${position + 1}번째 글자 근처)`
    }
    return baseMsg
  }

  if (errorCode === SEARCH_ERROR_CODES.UNKNOWN_FIELD) {
    return detail !== undefined ? `알 수 없는 필드: ${detail}` : '알 수 없는 필드입니다.'
  }

  if (errorCode === SEARCH_ERROR_CODES.FIELD_NOT_YET_SUPPORTED) {
    return detail !== undefined
      ? `아직 지원하지 않는 필드입니다. (${detail})`
      : '아직 지원하지 않는 필드입니다.'
  }

  if (errorCode === SEARCH_ERROR_CODES.VALIDATION_FAILED) {
    return detail ?? '입력값을 확인하세요.'
  }

  if (errorCode === SEARCH_ERROR_CODES.UNAUTHENTICATED) {
    return '로그인이 필요합니다. 다시 로그인해 주세요.'
  }

  if (errorCode === SEARCH_ERROR_CODES.ACCESS_DENIED) {
    return detail ?? '이 프로젝트를 검색할 권한이 없습니다.'
  }

  return detail ?? '검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
}

/**
 * 에러 코드가 입력창 하단 표시 대상인지 판별한다.
 *
 * 문법오류/미지원필드는 입력창 하단에, 인증/권한/서버 오류는 일반 alert에 표시한다.
 */
function isInputError(errorCode: string | undefined): boolean {
  return (
    errorCode === SEARCH_ERROR_CODES.SYNTAX_ERROR ||
    errorCode === SEARCH_ERROR_CODES.UNKNOWN_FIELD ||
    errorCode === SEARCH_ERROR_CODES.FIELD_NOT_YET_SUPPORTED ||
    errorCode === SEARCH_ERROR_CODES.VALIDATION_FAILED
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 파싱 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface ParsedApiError {
  errorCode: string | undefined
  detail: string | undefined
  position: number | undefined
}

/**
 * unknown 에러에서 AQL 검색 관련 필드를 안전하게 추출한다.
 */
function parseApiError(err: unknown): ParsedApiError {
  if (!(err instanceof ApiError)) {
    return { errorCode: undefined, detail: undefined, position: undefined }
  }

  const body = err.body
  if (body === null || typeof body !== 'object') {
    return { errorCode: undefined, detail: undefined, position: undefined }
  }

  const bodyObj = body as Record<string, unknown>
  const errorCode = typeof bodyObj['errorCode'] === 'string' ? bodyObj['errorCode'] : undefined
  const detail = typeof bodyObj['detail'] === 'string' ? bodyObj['detail'] : undefined
  const position = typeof bodyObj['position'] === 'number' ? bodyObj['position'] : undefined

  return { errorCode, detail, position }
}

// ─────────────────────────────────────────────────────────────────────────────
// 검색 결과 카드 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface SearchResultCardProps {
  /** 이슈 키 (ATLAS-1 형태) */
  issueKey: string
  /** 이슈 요약 */
  summary: string
  /** 현재 상태 키 */
  currentStateKey: string
  /** 우선순위 이름 (백엔드 priorityName 필드) */
  priorityName: string
  /** 카드 클릭 콜백 */
  onNavigate: (key: string) => void
}

/**
 * AQL 검색 결과 단건 카드 컴포넌트.
 *
 * - IssueCard와 동일한 시각 클래스 사용 (rounded-lg border bg-card px-4 py-3)
 * - 상태 배지: rounded-full bg-muted
 * - 결과 클릭 시 onNavigate(key) 호출
 */
function SearchResultCard({
  issueKey,
  summary,
  currentStateKey,
  priorityName,
  onNavigate,
}: SearchResultCardProps): JSX.Element {
  return (
    <a
      href={`/issues/${issueKey}`}
      aria-label={issueKey}
      onClick={(e) => {
        e.preventDefault()
        onNavigate(issueKey)
      }}
      className="flex items-center gap-3 rounded-lg border bg-card px-4 py-3 text-sm hover:bg-muted/50 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <span className="shrink-0 font-mono text-xs font-medium text-muted-foreground w-20">
        {issueKey}
      </span>

      <span className="flex-1 truncate text-foreground">{summary}</span>

      <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
        {currentStateKey}
      </span>

      <span className="shrink-0 text-xs text-muted-foreground">{priorityName}</span>
    </a>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 컴포넌트 (IssuePagination과 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

interface SearchPaginationProps {
  page: number
  totalPages: number
  isFirst: boolean
  isLast: boolean
  onPageChange: (page: number) => void
}

/** AQL 검색 결과 페이지네이션 컴포넌트. IssuePagination과 동일 패턴. */
function SearchPagination({
  page,
  totalPages,
  isFirst,
  isLast,
  onPageChange,
}: SearchPaginationProps): JSX.Element {
  return (
    <nav className="flex items-center justify-between pt-2" aria-label="페이지 탐색">
      <Button
        variant="outline"
        size="sm"
        onClick={() => onPageChange(page - 1)}
        disabled={isFirst}
        aria-label="이전 페이지"
      >
        이전
      </Button>

      <span className="text-sm text-muted-foreground">
        {page + 1} / {totalPages}
      </span>

      <Button
        variant="outline"
        size="sm"
        onClick={() => onPageChange(page + 1)}
        disabled={isLast}
        aria-label="다음 페이지"
      >
        다음
      </Button>
    </nav>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SearchPage props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** SearchPage 컴포넌트 props */
export interface SearchPageProps {
  /** 검색 대상 프로젝트 키 */
  projectKey: string
  /** 현재 AQL 쿼리 문자열 */
  q: string
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 페이지 변경 콜백 */
  onPageChange: (page: number) => void
  /** 결과 카드 클릭 시 이슈 상세로 이동하는 콜백 */
  onNavigate: (key: string) => void
  /** 쿼리 텍스트 변경 콜백 (입력창 제어 위임) */
  onQueryChange: (q: string) => void
  /** 검색 실행 콜백 (버튼 클릭 또는 Cmd/Ctrl+Enter) */
  onSearch: (q: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// SearchPage — 검색 UI 핵심 컴포넌트 (props 기반, 라우터 비의존)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 검색 페이지 컴포넌트.
 *
 * - AqlHighlighter 입력창 + 검색 버튼 + Cmd/Ctrl+Enter 힌트
 * - 빈/공백 쿼리 시 검색 버튼 disable
 * - useQuery(['search-aql', projectKey, q, page]) — placeholderData keepPrevious로 깜빡임 방지
 * - 에러 위치 분리: 문법오류/미지원필드는 입력창 하단 alert, 401/403은 일반 alert
 * - 0건은 결과 영역 회색 안내(alert 아님)
 * - 로딩 "검색 중..." + 입력창/버튼 유지
 *
 * 라우터 의존 없이 props로 동작해 단위 테스트가 가능하다.
 */
export function SearchPage({
  projectKey,
  q,
  page,
  onPageChange,
  onNavigate,
  onQueryChange,
  onSearch,
}: SearchPageProps): JSX.Element {
  // ── 로컬 입력 상태 (q prop 기반 초기값, 검색 실행 전까지 로컬에서 관리) ───
  const [inputValue, setInputValue] = useState(q)
  // 마지막으로 실행된 쿼리 — useQuery queryKey에 사용
  const [submittedQuery, setSubmittedQuery] = useState(q)

  const trimmedInput = inputValue.trim()
  const isQueryEmpty = trimmedInput.length === 0

  // ── 쿼리 변경 핸들러 ──────────────────────────────────────────────────────
  const handleQueryChange = useCallback(
    (value: string) => {
      setInputValue(value)
      onQueryChange(value)
    },
    [onQueryChange],
  )

  // ── 검색 실행 ─────────────────────────────────────────────────────────────
  const handleSearch = useCallback(() => {
    if (isQueryEmpty) return
    setSubmittedQuery(trimmedInput)
    onSearch(trimmedInput)
  }, [isQueryEmpty, trimmedInput, onSearch])

  // ── useQuery — filter-aware queryKey ─────────────────────────────────────
  // submittedQuery가 비어 있으면 쿼리를 비활성화 (enabled=false)
  const { data, isFetching, error } = useQuery<AqlSearchPage, Error>({
    queryKey: ['search-aql', projectKey, submittedQuery, page],
    queryFn: () =>
      searchAql({ projectKey, query: submittedQuery, page, size: PAGE_SIZE }),
    enabled: submittedQuery.trim().length > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })

  // ── 에러 분류 ─────────────────────────────────────────────────────────────
  const { errorCode, detail, position } = parseApiError(error)
  const showInputError = error !== null && error !== undefined && isInputError(errorCode)
  const showGeneralError = error !== null && error !== undefined && !isInputError(errorCode)
  const inputErrorMessage = showInputError
    ? resolveErrorMessage(errorCode, detail, position)
    : null
  const generalErrorMessage = showGeneralError
    ? resolveErrorMessage(errorCode, detail, position)
    : null

  // ── 렌더 ─────────────────────────────────────────────────────────────────
  return (
    <div className="p-4 sm:p-6 lg:p-8 space-y-4">
      {/* 페이지 헤더 */}
      <header>
        <h1 className="text-2xl font-semibold">AQL 검색</h1>
      </header>

      {/* 일반 에러 alert (401/403/500) */}
      {generalErrorMessage !== null && (
        <div
          role="alert"
          className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          {generalErrorMessage}
        </div>
      )}

      {/* 입력 영역 */}
      <div className="space-y-2">
        <AqlHighlighter
          value={inputValue}
          onChange={handleQueryChange}
          onSubmit={handleSearch}
          placeholder={searchLabels.aqlPlaceholder}
        />

        {/* 입력창 하단 에러 (문법오류/미지원필드) */}
        {inputErrorMessage !== null && (
          <div
            role="alert"
            className="rounded-md border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {inputErrorMessage}
          </div>
        )}

        {/* 검색 버튼 + Cmd/Ctrl+Enter 힌트 */}
        <div className="flex items-center gap-3">
          <Button
            type="button"
            onClick={handleSearch}
            disabled={isQueryEmpty}
            aria-label="검색"
          >
            검색
          </Button>
          <span className="text-xs text-muted-foreground">
            Cmd/Ctrl+Enter로 검색
          </span>
        </div>
      </div>

      {/* 결과 영역 */}
      <div>
        {/* 로딩 인디케이터 — 입력창/버튼은 항상 활성 상태 유지 */}
        {isFetching && (
          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
            <span>검색 중...</span>
          </div>
        )}

        {/* 결과 렌더 — data가 있으면 isFetching 여부와 무관하게 표시.
            keepPreviousData 덕분에 새 쿼리 로딩 중에도 이전 결과가 유지된다.
            isFetching=true 시 opacity-60으로 전환 중임을 시각화한다. */}
        {data !== undefined && (() => {
          // envelope meta.page 로컬 변수 — data.meta.page 중첩 반복 제거
          const pg = data.meta.page
          const hits = data.data
          const isEmpty = pg.totalElements === 0
          const isFirst = pg.number === 0
          const isLast = pg.number >= pg.totalPages - 1

          return (
            <div className={isFetching ? 'opacity-60' : undefined}>
              {isEmpty ? (
                /* 0건 빈 상태 — role=alert 없음(에러 아님), 결과 영역 회색 안내 */
                <div className="py-12 text-center text-muted-foreground">
                  <p className="text-base">검색 결과가 없습니다.</p>
                  <p className="mt-1 text-sm">다른 쿼리를 시도해 보세요.</p>
                </div>
              ) : (
                <>
                  <ul className="space-y-2" aria-label="검색 결과">
                    {hits.map((hit) => (
                      <li key={hit.key}>
                        <SearchResultCard
                          issueKey={hit.key}
                          summary={hit.summary}
                          currentStateKey={hit.currentStateKey}
                          priorityName={hit.priorityName}
                          onNavigate={onNavigate}
                        />
                      </li>
                    ))}
                  </ul>

                  {pg.totalPages > 1 && (
                    <SearchPagination
                      page={page}
                      totalPages={pg.totalPages}
                      isFirst={isFirst}
                      isLast={isLast}
                      onPageChange={onPageChange}
                    />
                  )}
                </>
              )}
            </div>
          )
        })()}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SearchRouteAdapter — router.ts에 등록되는 라우트 어댑터 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * URL의 `q` / `projectKey` / `page` / `filterId`를 읽어 SearchPage에 전달한다.
 * SearchPage는 라우터 비의존(props 기반)이므로 단위 테스트가 가능하다.
 *
 * **filterId 딥링크 흐름 (C1)**
 * 1. `filterId`가 URL에 있으면 로딩 상태를 표시하며 `fetchFilter(id)` 호출
 * 2. 성공 시: filterId를 URL에서 제거하고 q+projectKey로 교체(navigate replace)
 *    → 다음 렌더에서 filterId=undefined, 새 q+projectKey로 SearchPage가 마운트되어
 *      submittedQuery가 stale 없이 정확히 초기화되고 검색이 자동 실행된다.
 * 3. 실패 시: 모든 에러(404, 400 등)에 대해 toast.error + 일반 검색 화면 표시 (N3)
 *
 * **저장 버튼 + SavedFilterMenu (C2)**
 * adapter 레벨에서 마운트해 SearchPage의 props-only 설계를 보존한다.
 */
export function SearchRouteAdapter(): JSX.Element {
  const search = useSearch({ strict: false }) as {
    q?: string
    page?: number
    projectKey?: string
    filterId?: string
  }
  const navigate = useNavigate()

  const q = typeof search.q === 'string' ? search.q : ''
  const page = typeof search.page === 'number' ? search.page : 0
  const filterId = typeof search.filterId === 'string' ? search.filterId : undefined

  // FR-UX-07 — URL > 저장값 > 첫 프로젝트 순으로 해소한다. 우선순위 구조는 그대로이고
  // 폴백만 DEFAULT_PROJECT_KEY 하드코딩에서 해소 결과로 바뀐다(스펙 E8).
  const activeProject = useResolvedActiveProject(search.projectKey)

  // filterId 로딩 중 여부 — true이면 SearchPage 대신 로딩 안내를 표시한다
  const [filterIdLoading, setFilterIdLoading] = useState(filterId !== undefined)
  const [saveDialogOpen, setSaveDialogOpen] = useState(false)
  const [exportDialogOpen, setExportDialogOpen] = useState(false)

  /**
   * filterId 딥링크 해소 effect.
   *
   * **cleanup 보장** — `cancelled` 플래그로 언마운트 또는 연속 진입(filterId가 연속 변경)
   * 시 이전 비동기 콜백의 setState/navigate 호출을 차단한다. strict mode 이중 실행에서도
   * 두 번째 effect가 시작될 때 첫 번째 cleanup이 실행되어 stale 업데이트가 발생하지 않는다.
   *
   * **에러 일반화(N3)** — 모든 에러(404·400·500 등)를 catch에서 동일하게 처리한다.
   * 에러 코드별 분기를 추가하면 미처리 코드에서 무한 로딩이 발생하므로 금지.
   */
  useEffect(() => {
    if (filterId === undefined) {
      setFilterIdLoading(false)
      return
    }

    let cancelled = false
    setFilterIdLoading(true)

    void fetchFilter(filterId)
      .then((filter) => {
        if (cancelled) return
        void navigate({
          to: '/search',
          search: () => ({ q: filter.aqlQuery, projectKey: filter.projectKey, page: 0 }),
          replace: true,
        })
        // filterIdLoading은 다음 effect 실행(filterId=undefined)에서 false로 전환된다.
        // 성공 후 navigate → 라우터 URL 갱신 → 재렌더 → filterId=undefined → effect 재실행
        // → setFilterIdLoading(false) 순서로 자동 해소.
      })
      .catch(() => {
        // N3: 에러 코드 무관, 모든 에러에 동일 처리
        if (cancelled) return
        toast.error(FILTER_LOAD_ERROR_MSG)
        setFilterIdLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [filterId, navigate])

  const handlePageChange = useCallback(
    (nextPage: number) => {
      void navigate({ to: '/search', search: (prev) => ({ ...prev, page: nextPage }) })
    },
    [navigate],
  )

  const handleNavigate = useCallback(
    (key: string) => {
      void navigate({ to: `/issues/${key}` })
    },
    [navigate],
  )

  /** 입력 도중 타이핑 변경 — page=0 리셋 포함 */
  const handleQueryChange = useCallback(
    (nextQ: string) => {
      void navigate({ to: '/search', search: (prev) => ({ ...prev, q: nextQ, page: 0 }) })
    },
    [navigate],
  )

  /** 검색 실행(버튼/Cmd+Enter) — page=0 리셋 포함 */
  const handleSearch = useCallback(
    (submittedQ: string) => {
      void navigate({ to: '/search', search: (prev) => ({ ...prev, q: submittedQ, page: 0 }) })
    },
    [navigate],
  )

  // 활성 프로젝트가 정해지기 전(로딩/에러/0개)에는 검색 화면 전체를 렌더하지 않는다.
  // SearchPage·SaveFilterDialog·ExportDialog 3소비처가 모두 `projectKey: string`
  // non-nullable 계약이라 어댑터가 흡수해야 한다(스펙 §8 G3, plan 리뷰 B4).
  // `/issues`와 달리 여기엔 프로젝트 해소와 무관하게 살아야 할 영역(상세 페인)이 없으므로
  // 최상단 반환이 맞다 — 툴바의 저장·내보내기도 projectKey를 요구한다.
  if (activeProject.status !== 'ready') {
    return <ActiveProjectGate state={activeProject} />
  }
  const projectKey = activeProject.projectKey

  return (
    <>
      {/* 툴바 — SavedFilterMenu + 저장 버튼 + 내보내기 버튼 (C2: adapter 레벨 마운트, SearchPage 비의존 설계 보존) */}
      <div className="flex items-center justify-end gap-2 px-4 pt-4 sm:px-6 lg:px-8">
        <SavedFilterMenu />
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={q.trim().length === 0}
          onClick={() => { setSaveDialogOpen(true) }}
          aria-label="현재 검색 저장"
        >
          저장
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={q.trim().length === 0}
          onClick={() => { setExportDialogOpen(true) }}
          aria-label="검색 결과 내보내기"
        >
          내보내기
        </Button>
      </div>

      {/* filterId 로딩 중 안내 — SearchPage는 unmount 상태, 다음 렌더에서 fresh mount된다 */}
      {filterIdLoading ? (
        <div className="p-4 sm:p-6 lg:p-8 text-sm text-muted-foreground">
          필터를 불러오는 중...
        </div>
      ) : (
        <SearchPage
          projectKey={projectKey}
          q={q}
          page={page}
          onPageChange={handlePageChange}
          onNavigate={handleNavigate}
          onQueryChange={handleQueryChange}
          onSearch={handleSearch}
        />
      )}

      {/* 저장 다이얼로그 — adapter 레벨 포탈, SearchPage 비의존 (C2) */}
      <SaveFilterDialog
        open={saveDialogOpen}
        onOpenChange={setSaveDialogOpen}
        mode="create"
        aqlQuery={q}
        projectKey={projectKey}
      />

      {/* 내보내기 다이얼로그 — adapter 레벨 포탈 (FR-EX-01 D6) */}
      <ExportDialog
        open={exportDialogOpen}
        onOpenChange={setExportDialogOpen}
        projectKey={projectKey}
        query={q}
      />
    </>
  )
}
