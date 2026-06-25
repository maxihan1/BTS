// AQL 검색 페이지 — SearchPage(props 기반) + SearchRouteAdapter(URL 동기화) (FR-SR-02 D6 Task-5)
import type { JSX } from 'react'
import { useState, useCallback } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { searchAql, SEARCH_ERROR_CODES } from '@/api/search'
import type { AqlSearchPage } from '@/api/search'
import { ApiError } from '@/api/client'
import { AqlHighlighter } from '@/components/search/AqlHighlighter'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_PROJECT_KEY = 'ATLAS'
const PAGE_SIZE = 20

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
          placeholder="AQL 쿼리를 입력하세요. 예: status = open AND priority IN (1, 2)"
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
        {/* 로딩 중 */}
        {isFetching && (
          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
            <span>검색 중...</span>
          </div>
        )}

        {/* 결과 렌더 (로딩 중에도 이전 결과 유지 — keepPreviousData) */}
        {!isFetching && data !== undefined && (
          <>
            {data.empty ? (
              /* 0건 빈 상태 — role=alert 아님(에러 아님), 회색 안내 */
              <div className="py-12 text-center text-muted-foreground">
                <p className="text-base">검색 결과가 없습니다.</p>
                <p className="mt-1 text-sm">다른 쿼리를 시도해 보세요.</p>
              </div>
            ) : (
              <>
                <ul className="space-y-2" aria-label="검색 결과">
                  {data.content.map((hit) => (
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

                {data.totalPages > 1 && (
                  <SearchPagination
                    page={page}
                    totalPages={data.totalPages}
                    isFirst={data.first}
                    isLast={data.last}
                    onPageChange={onPageChange}
                  />
                )}
              </>
            )}
          </>
        )}

        {/* 로딩 중에도 이전 결과 표시 (keepPreviousData — isFetching=true이지만 data 있음) */}
        {isFetching && data !== undefined && !data.empty && (
          <ul className="space-y-2 opacity-60" aria-label="검색 결과 (이전)">
            {data.content.map((hit) => (
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
        )}
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
 * useSearch로 URL의 q / projectKey / page를 추출해 SearchPage에 전달한다.
 * - q → SearchPage.q (AQL 쿼리)
 * - page → SearchPage.page (0-indexed)
 * - projectKey → SearchPage.projectKey (기본값: ATLAS)
 * - 쿼리/페이지 변경 시 navigate((prev) => ...) 머지 패턴으로 URL 갱신
 *
 * 라우터 등록은 Task 6(router.ts) 담당.
 */
export function SearchRouteAdapter(): JSX.Element {
  const search = useSearch({ strict: false }) as {
    q?: string
    page?: number
    projectKey?: string
  }
  const navigate = useNavigate()

  const q = typeof search.q === 'string' ? search.q : ''
  const page = typeof search.page === 'number' ? search.page : 0
  const projectKey =
    typeof search.projectKey === 'string' && search.projectKey.length > 0
      ? search.projectKey
      : DEFAULT_PROJECT_KEY

  // NOTE: '/search'는 Task 6(router.ts)에서 등록된다.
  // 등록 전까지 타입 시스템이 라우트를 모르므로 unknown cast로 우회한다.
  // Task 6 머지 후 cast 제거, 직접 navigate({ to: '/search', ... }) 사용 가능.
  type UntypedNavigate = (opts: { to: string; search: (prev: Record<string, unknown>) => Record<string, unknown> }) => void
  const nav = navigate as unknown as UntypedNavigate

  function handlePageChange(nextPage: number): void {
    nav({ to: '/search', search: (prev) => ({ ...prev, page: nextPage }) })
  }

  function handleNavigate(key: string): void {
    void navigate({ to: `/issues/${key}` })
  }

  function handleQueryChange(nextQ: string): void {
    nav({ to: '/search', search: (prev) => ({ ...prev, q: nextQ, page: 0 }) })
  }

  function handleSearch(submittedQ: string): void {
    nav({ to: '/search', search: (prev) => ({ ...prev, q: submittedQ, page: 0 }) })
  }

  return (
    <SearchPage
      projectKey={projectKey}
      q={q}
      page={page}
      onPageChange={handlePageChange}
      onNavigate={handleNavigate}
      onQueryChange={handleQueryChange}
      onSearch={handleSearch}
    />
  )
}
