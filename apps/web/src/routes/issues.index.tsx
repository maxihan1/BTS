// 이슈 목록 페이지 — IssueListPage(props 기반) + IssueListRouteAdapter(라우터 연결). 테이블·정렬·컬럼 선택 + split view 결선(FR-UX-06 PR20)
import type { JSX } from 'react'
import { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { fetchIssues, ISSUE_SORT_FIELDS } from '@/api/issues'
import type { IssuePage, IssueFilterParams, IssueSortField } from '@/api/issues'
import { Button } from '@/components/ui/button'
import { useIssueSelection } from '@/hooks/use-issue-selection'
import { useColumnVisibility } from '@/hooks/use-column-visibility'
import { useUsersByIds } from '@/hooks/use-users'
import { useMediaQuery } from '@/hooks/use-media-query'
import { IssueBulkActionBar } from '@/components/issues/IssueBulkActionBar'
import { BulkEditDialog } from '@/components/issues/BulkEditDialog'
import { BulkTransitionDialog } from '@/components/issues/BulkTransitionDialog'
import { BulkOperationResultDialog } from '@/components/issues/BulkOperationResultDialog'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { IssueFilterBar } from '@/components/issues/IssueFilterBar'
import { IssueTable } from '@/components/issues/IssueTable'
import type { IssueTableSortState } from '@/components/issues/IssueTable'
import { ColumnSelector } from '@/components/issues/ColumnSelector'
import { ISSUE_COLUMNS } from '@/components/issues/issue-columns'
import { normalizeIssueFilter, isEmptyIssueFilter, searchToIssueFilter, issueFilterToSearch } from '@/lib/issue-filter'
import type { IssueFilterSearch } from '@/lib/issue-filter'
import { IssueDetailPage } from './issues.$key'
import { FilteredEmptyState } from '@/components/filters/FilteredEmptyState'

// ─────────────────────────────────────────────────────────────────────────────
// router.ts 등록 방법 (code-based 패턴 — PR #11 컨벤션).
//
//   import { IssueListRouteAdapter } from './routes/issues.index'
//
//   const issuesIndexRoute = createRoute({
//     getParentRoute: () => rootRoute,
//     path: '/issues',
//     component: IssueListRouteAdapter,
//   })
//
// IssueListRouteAdapter는 useSearch/useNavigate로 page + sort 상태를 추출해
// IssueListPage에 전달한다. 라우터 등록은 Task8(router.ts) 담당.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_PROJECT_KEY = 'ATLAS'
const DEFAULT_PAGE_SIZE = 20

/** 빈 필터 상수 — 매 렌더마다 새 객체 생성 방지 */
const EMPTY_FILTER: IssueFilterParams = {
  statusKeys: [],
  assigneeIds: [],
  includeUnassigned: false,
  labels: [],
  componentIds: [],
}

/** useColumnVisibility localStorage 키 — 기기별 컬럼 표시 상태 persist (S4) */
const ISSUE_TABLE_COLUMNS_STORAGE_KEY = 'issue-table-columns'

/** 컬럼 선택기에 노출할 전체 컬럼 키 목록 (issue-columns.ts ISSUE_COLUMNS 기준) */
const ISSUE_COLUMN_KEYS = ISSUE_COLUMNS.map((column) => column.key)

/** 항상 표시되는 필수 컬럼 키 목록(키·요약) — issue-columns.ts required 플래그 기준 */
const ISSUE_COLUMN_REQUIRED_KEYS = ISSUE_COLUMNS.filter((column) => column.required).map((column) => column.key)

/** 컬럼 표시 기본값 — 최초 방문 시 전체 컬럼을 표시한다 */
const ISSUE_COLUMN_DEFAULT_VISIBLE = ISSUE_COLUMN_KEYS

// ─────────────────────────────────────────────────────────────────────────────
// 정렬 URL 직렬화 헬퍼 (T1↔T2↔T4↔T5 공유 "정렬 필드 계약" 5종 기준)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 문자열이 "정렬 필드 계약"(ISSUE_SORT_FIELDS) 5종에 속하는지 좁히는 타입 가드.
 * URL에서 읽은 임의 문자열을 안전하게 {@link IssueSortField}로 좁힐 때 사용한다
 * (issue-columns.ts의 동형 가드와 별개 — 이쪽은 IssueColumnKey가 아닌 순수 문자열 대상).
 */
function isIssueSortField(field: string): field is IssueSortField {
  return (ISSUE_SORT_FIELDS as readonly string[]).includes(field)
}

/**
 * URL 쿼리 파라미터 `sort=<field>,<dir>` 문자열을 정렬 상태 객체로 파싱한다.
 * 구분자가 없거나 필드/방향이 허용 값이 아니면 안전하게 null(정렬 미적용)로 폴백한다.
 *
 * @param raw URL search의 sort 원문 문자열. undefined면 정렬 미적용.
 * @returns 파싱된 정렬 상태. 형식이 잘못됐으면 null.
 */
function parseSortParam(raw: string | undefined): IssueTableSortState | null {
  if (raw === undefined) return null
  const [field, dir] = raw.split(',')
  if (field === undefined || dir === undefined) return null
  if (!isIssueSortField(field)) return null
  if (dir !== 'asc' && dir !== 'desc') return null
  return { field, dir }
}

/**
 * 정렬 상태를 URL 쿼리 파라미터 문자열로 직렬화한다.
 * null(정렬 해제)이면 undefined를 반환해 상위(어댑터)가 sort 키 자체를 URL에서 생략하게 한다.
 *
 * @param sort 직렬화할 정렬 상태
 * @returns `<field>,<dir>` 형식 문자열. sort가 null이면 undefined.
 */
function serializeSortParam(sort: IssueTableSortState | null): string | undefined {
  return sort === null ? undefined : `${sort.field},${sort.dir}`
}

/**
 * 정렬 헤더 클릭 시 다음 정렬 상태를 계산한다 — asc → desc → 해제(null) 3-state 순환(F2, S2).
 * 현재 정렬 중인 필드와 다른 필드를 클릭하면 그 필드의 asc로 즉시 초기화한다.
 *
 * @param current 현재 정렬 상태
 * @param field 클릭된 정렬 가능 컬럼의 필드
 * @returns 다음 정렬 상태. 3번째 클릭(현재 desc)이면 null(해제).
 */
function nextSortState(current: IssueTableSortState | null, field: IssueSortField): IssueTableSortState | null {
  if (current === null || current.field !== field) {
    return { field, dir: 'asc' }
  }
  return current.dir === 'asc' ? { field, dir: 'desc' } : null
}

/**
 * GAP-5 — 재조회(isFetching) 중 표 영역에 적용할 dim 처리 className을 계산한다.
 * keepPreviousData로 유지된 이전 데이터 위에 겹쳐 전환 중임을 알리되,
 * pointer-events-none으로 전환 중 이중 클릭 등 의도치 않은 조작을 막는다.
 *
 * @param isFetching 재조회 진행 여부
 * @returns dim 처리 className. 재조회 중이 아니면 undefined(클래스 없음).
 */
function issueTableRegionClassName(isFetching: boolean): string | undefined {
  return isFetching ? 'opacity-60 pointer-events-none' : undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueEmptyState — 빈 이슈 목록 안내 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈가 없을 때 표시하는 빈 상태 안내 컴포넌트. */
function IssueEmptyState(): JSX.Element {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
      <p className="text-base">이슈가 없습니다.</p>
      <p className="mt-1 text-sm">새 이슈를 만들어 프로젝트를 시작해 보세요.</p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssuePagination — 이슈 목록 페이지네이션 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface IssuePaginationProps {
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 전체 페이지 수 */
  totalPages: number
  /** 첫 번째 페이지 여부 */
  isFirst: boolean
  /** 마지막 페이지 여부 */
  isLast: boolean
  /** 페이지 변경 콜백 */
  onPageChange: (page: number) => void
}

/**
 * 이슈 목록 페이지네이션 컴포넌트.
 *
 * - 이전/다음 버튼으로 페이지를 탐색한다.
 * - 첫 페이지일 때 이전 버튼, 마지막 페이지일 때 다음 버튼이 비활성화된다.
 */
function IssuePagination({ page, totalPages, isFirst, isLast, onPageChange }: IssuePaginationProps): JSX.Element {
  return (
    <nav
      className="flex items-center justify-between pt-2"
      aria-label="페이지 탐색"
    >
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
// IssueListContent — 성공 상태 렌더 컴포넌트 (툴바 + 테이블 + 페이지네이션)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueListContentProps {
  /** Spring Page 형태의 이슈 목록 데이터 */
  data: IssuePage
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 페이지 변경 콜백 */
  onPageChange: (page: number) => void
  /** 항목 클릭 콜백 */
  onNavigate: (key: string) => void
  /** 선택 상태 체크 함수 */
  isSelected: (key: string) => boolean
  /** 개별 토글 콜백 */
  onToggle: (key: string) => void
  /** 전체 선택 토글 콜백 (현재 페이지 기준) */
  onSelectAllPage: () => void
  /** 현재 페이지 전체 선택 여부 */
  isAllPageSelected: boolean
  /** 현재 정렬 상태 — IssueTable 헤더의 aria-sort/방향 아이콘에 반영 */
  sort: IssueTableSortState | null
  /** 정렬 헤더 클릭 콜백 */
  onSort: (field: IssueSortField) => void
  /** 재조회 진행 여부(GAP-5) — true면 표 영역을 dim 처리해 전환 중임을 알린다 */
  isFetching: boolean
  /**
   * split view(FR-UX-06 PR20 Task 5) 우측 상세 페인에 현재 열린 이슈 키.
   * IssueTable.selectedKey로 그대로 전달돼 해당 행을 aria-current로 강조한다.
   * undefined/null이면 어떤 행도 강조하지 않는다(bulk 체크박스 선택과는 완전히 별개).
   */
  selectedKey?: string | null
}

/**
 * 이슈 목록 성공 상태 렌더 컴포넌트.
 *
 * 빈 목록이면 IssueEmptyState, 아니면 컬럼 선택 툴바(GAP-2) + IssueTable(F1) +
 * IssuePagination을 렌더한다.
 *
 * - 담당자 이름 해석(F5) — 현재 페이지에 등장하는 assigneeId만 useUsersByIds로 조회한다
 *   (FilterBar.tsx assigneeNameMap 관례 미러).
 * - 컬럼 표시 상태(F4)는 useColumnVisibility로 localStorage에 영속한다.
 * - GAP-5 — isFetching(재조회 중)이면 표 영역에 opacity-60 pointer-events-none을 적용해
 *   레이아웃 시프트 없이 전환 중임을 알린다. IssueListPage의 keepPreviousData 덕분에
 *   이전 데이터가 유지된 채 dim된다.
 */
function IssueListContent({
  data,
  page,
  onPageChange,
  onNavigate,
  isSelected,
  onToggle,
  onSelectAllPage,
  isAllPageSelected,
  sort,
  onSort,
  isFetching,
  selectedKey,
}: IssueListContentProps): JSX.Element {
  // ── 담당자 이름 해석 — 현재 페이지 assigneeId만 조회 ───────────────────────
  const assigneeIds = useMemo(
    () =>
      Array.from(
        new Set(data.content.map((issue) => issue.assigneeId).filter((id): id is string => id !== null)),
      ),
    [data.content],
  )
  const { data: assignees = [] } = useUsersByIds(assigneeIds)
  const assigneeNameMap = useMemo<Map<string, string>>(() => {
    const map = new Map<string, string>()
    for (const user of assignees) {
      map.set(user.id, user.displayName ?? user.username)
    }
    return map
  }, [assignees])

  // ── 컬럼 표시 상태 — localStorage persist(F4) ──────────────────────────────
  const { visible, isVisible, toggle } = useColumnVisibility(
    ISSUE_TABLE_COLUMNS_STORAGE_KEY,
    ISSUE_COLUMN_KEYS,
    ISSUE_COLUMN_REQUIRED_KEYS,
    ISSUE_COLUMN_DEFAULT_VISIBLE,
  )

  if (data.empty) {
    return <IssueEmptyState />
  }

  return (
    <div
      data-testid="issue-table-region"
      className={issueTableRegionClassName(isFetching)}
    >
      {/* GAP-2 — 컬럼 선택 툴바(테이블 상단 우측) */}
      <div className="flex justify-end pb-2">
        <ColumnSelector allColumns={ISSUE_COLUMNS} isVisible={isVisible} onToggle={toggle} />
      </div>

      <IssueTable
        issues={data.content}
        visibleColumnKeys={visible}
        sort={sort}
        onSort={onSort}
        selection={{ isSelected, onToggle, onSelectAllPage, isAllPageSelected }}
        onNavigate={onNavigate}
        assigneeNameMap={assigneeNameMap}
        selectedKey={selectedKey}
      />

      <IssuePagination
        page={page}
        totalPages={data.totalPages}
        isFirst={data.first}
        isLast={data.last}
        onPageChange={onPageChange}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListPage — 이슈 목록 페이지 (props 기반, 라우터 비의존)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueListPageProps {
  /** 조회할 프로젝트 키 */
  projectKey: string
  /** 현재 페이지 번호 (0-indexed) */
  page: number
  /** 페이지 변경 시 호출되는 콜백 */
  onPageChange: (page: number) => void
  /** 이슈 항목 클릭 시 상세 페이지로 이동하는 콜백 */
  onNavigate: (key: string) => void
  /**
   * 현재 적용된 필터 파라미터 (FR-SR-01 D6).
   * 미전달 시 빈 필터(전체 조회)로 동작한다.
   */
  filter?: IssueFilterParams
  /**
   * 필터 변경 콜백 (FR-SR-01 D6).
   * IssueFilterBar onChange → 새 필터 전달 + page=0 리셋.
   */
  onFilterChange?: (filter: IssueFilterParams) => void
  /**
   * 현재 정렬 상태(Task 5, F2). undefined/null이면 정렬 미적용(서버 기본 정렬 created_at desc 유지).
   */
  sort?: IssueTableSortState | null
  /**
   * 정렬 변경 콜백(Task 5). IssueListRouteAdapter가 URL sort 쿼리를 갱신한다.
   */
  onSortChange?: (sort: IssueTableSortState | null) => void
  /**
   * split view(FR-UX-06 PR20 Task 5) 우측 상세 페인에 현재 열린 이슈 키.
   * IssueListContent → IssueTable로 그대로 전달된다. undefined/null이면 강조 없음.
   */
  selectedKey?: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// NewIssueButton — "새 이슈" 진입점. CREATE 권한 게이트(fail-closed).
// ─────────────────────────────────────────────────────────────────────────────

/** "새 이슈" 버튼/링크의 공통 시각 스타일 */
const NEW_ISSUE_CLASS =
  'inline-flex items-center rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors'

interface NewIssueButtonProps {
  /** CREATE 권한 보유 여부. false(로딩/에러/권한없음) → disabled 버튼. */
  canCreate: boolean
}

/**
 * "새 이슈" 진입점 컴포넌트.
 *
 * - canCreate=true  → `<a href="/issues/new">` (role=link, 기존 스타일 동일).
 * - canCreate=false → `<button type="button" disabled>` (동일 시각 스타일, fail-closed).
 * - 양쪽 모두 `data-testid="new-issue-button"` 부여.
 */
function NewIssueButton({ canCreate }: NewIssueButtonProps): JSX.Element {
  if (canCreate) {
    return (
      <a
        href="/issues/new"
        data-testid="new-issue-button"
        className={NEW_ISSUE_CLASS}
      >
        새 이슈
      </a>
    )
  }

  return (
    // PR22 — variant 미지정(default)이 의도다. NEW_ISSUE_CLASS 는 위 canCreate=true 분기의
    // <a> 와 공유하는 상수이므로, 중복돼 보이는 bg-primary 를 정리하면 링크 쪽 시각이 함께 깨진다.
    // 상수는 건드리지 않고 그대로 넘긴다. 프리미티브 기본과 겹치는 disabled:opacity-50 만 뺐다.
    <Button
      type="button"
      disabled
      data-testid="new-issue-button"
      className={`${NEW_ISSUE_CLASS} disabled:cursor-not-allowed`}
      aria-label="새 이슈 (권한 없음)"
    >
      새 이슈
    </Button>
  )
}

/**
 * 이슈 목록 페이지 컴포넌트.
 *
 * - useQuery로 fetchIssues를 호출한다. queryKey에 sort를 포함해 정렬별 캐시를 분기한다.
 * - placeholderData: keepPreviousData로 page/sort/filter 전환 중에도 이전 데이터를 유지한다
 *   (GAP-5 — IssueListContent가 isFetching으로 dim 처리).
 * - 3 상태 분기: 로딩("로딩 중...") → 에러(role="alert") → 성공(IssueListContent).
 * - 에러 시 role="alert"로 스크린 리더 접근성 보장 (WCAG AA).
 * - "새 이슈" 진입점: CREATE 권한 기반 게이트. fail-closed(로딩/에러/undefined → 비활성).
 * - useIssueSelection으로 페이지 교차 누적 선택 상태를 관리한다.
 * - IssueBulkActionBar, BulkEditDialog, BulkTransitionDialog, BulkOperationResultDialog를 결선한다.
 * - IssueFilterBar 결선 (FR-SR-01 D6): filter prop → queryKey filter-aware + filter 실변경 시 clearAll.
 * - 정렬 헤더 클릭(Task 5, F2) → nextSortState로 3-state 계산 → onSortChange + page=0 리셋(EC3).
 *
 * 라우터 의존 없이 props로 동작해 단위 테스트가 가능하다.
 */
export function IssueListPage({
  projectKey,
  page,
  onPageChange,
  onNavigate,
  filter = EMPTY_FILTER,
  onFilterChange,
  sort = null,
  onSortChange,
  selectedKey = null,
}: IssueListPageProps): JSX.Element {
  const queryClient = useQueryClient()

  // ── B1 queryKey filter-aware ─────────────────────────────────────────────────
  // normalizeIssueFilter로 배열 순서를 정규화해 동일 필터 → 동일 queryKey 보장.
  // (use-boards.ts normalizeFilter 미러 — PR #168 선례)
  const normalizedFilter = useMemo(() => normalizeIssueFilter(filter), [filter])

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['issues', projectKey, page, normalizedFilter, sort],
    queryFn: () =>
      fetchIssues({
        projectKey,
        page,
        size: DEFAULT_PAGE_SIZE,
        filter,
        sort: sort ?? undefined,
      }),
    // GAP-5 — page/sort/filter 전환 중에도 이전 데이터를 유지해 전체 화면 로딩 플리커를 방지한다.
    // 첫 로딩(데이터 없음)은 이 옵션과 무관하게 아래 isLoading 분기가 기존 "로딩 중..." 안내를 담당한다.
    placeholderData: keepPreviousData,
    retry: false,
  })

  // ── CREATE 권한 게이트 (PR #57) ────────────────────────────────────────────
  const { data: permData, isLoading: isPermLoading } = useProjectPermissions(projectKey)

  // fail-closed: 권한 로딩 중이거나 응답이 없으면 false
  const canCreate = !isPermLoading && permData?.permissions.CREATE === true

  // ── 선택 상태 ──────────────────────────────────────────────────────────────
  const { selectedKeys, count, isSelected, toggle, selectAllOnPage, clearPageSelection, clearAll } =
    useIssueSelection()

  // ── C3 FR10 — filter 실변경 시 clearAll ────────────────────────────────────
  // 이전 normalizedFilter를 ref로 추적해 실제 변경이 발생했을 때만 clearAll 호출.
  // page 이동(filter 동일) 시에는 선택 누적을 유지한다.
  const prevNormalizedFilterRef = useRef<string>(JSON.stringify(normalizedFilter))
  useEffect(() => {
    const current = JSON.stringify(normalizedFilter)
    if (current !== prevNormalizedFilterRef.current) {
      prevNormalizedFilterRef.current = current
      clearAll()
    }
  }, [normalizedFilter, clearAll])

  // ── IssueFilterBar onChange ─────────────────────────────────────────────────
  /**
   * IssueFilterBar 필터 변경 핸들러.
   * 새 필터를 onFilterChange로 상위에 전달하고 page=0으로 리셋한다.
   * 상위(IssueListRouteAdapter)가 URL search params를 갱신한다.
   */
  const handleFilterChange = useCallback(
    (nextFilter: IssueFilterParams) => {
      onFilterChange?.(nextFilter)
      onPageChange(0)
    },
    [onFilterChange, onPageChange],
  )

  // ── 정렬 헤더 클릭 핸들러(Task 5, F2·EC3) ───────────────────────────────────
  /**
   * IssueTable 정렬 헤더 클릭 핸들러.
   * nextSortState로 3-state(asc→desc→해제)를 계산해 onSortChange로 상위에 전달하고,
   * 정렬 기준이 바뀌면 현재 page 번호는 의미가 없어지므로 page=0으로 리셋한다(EC3).
   */
  const handleSort = useCallback(
    (field: IssueSortField) => {
      onSortChange?.(nextSortState(sort, field))
      onPageChange(0)
    },
    [sort, onSortChange, onPageChange],
  )

  // ── Dialog 열림 상태 ───────────────────────────────────────────────────────
  const [editOpen, setEditOpen] = useState(false)
  const [transitionOpen, setTransitionOpen] = useState(false)
  const [resultOpen, setResultOpen] = useState(false)
  const [bulkOperationId, setBulkOperationId] = useState<string | null>(null)

  // ── 전체 선택 토글 ─────────────────────────────────────────────────────────
  const pageKeys = useMemo(
    () => data?.content.map((i) => i.key) ?? [],
    [data],
  )
  const isAllPageSelected =
    pageKeys.length > 0 && pageKeys.every((k) => isSelected(k))

  const handleSelectAllPage = useCallback(() => {
    if (isAllPageSelected) {
      clearPageSelection(pageKeys)
    } else {
      selectAllOnPage(pageKeys)
    }
  }, [isAllPageSelected, pageKeys, clearPageSelection, selectAllOnPage])

  // ── Dialog onSubmitted 결선 ────────────────────────────────────────────────
  /**
   * BulkEditDialog / BulkTransitionDialog 에서 접수 성공 시 호출.
   * - 결과 Dialog를 열고 이슈 목록을 invalidate(refetch)하며 선택을 해제한다.
   */
  const handleBulkSubmitted = useCallback(
    (id: string) => {
      setBulkOperationId(id)
      setResultOpen(true)
      void queryClient.invalidateQueries({ queryKey: ['issues', projectKey] })
      clearAll()
    },
    [queryClient, projectKey, clearAll],
  )

  // ── 로딩 / 에러 분기 ───────────────────────────────────────────────────────
  if (isLoading) {
    return (
      <div className="flex items-center justify-center p-8 text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  if ((error !== null && error !== undefined) || data === undefined) {
    return (
      <div role="alert" className="p-8 text-destructive">
        이슈 목록을 불러올 수 없습니다.
      </div>
    )
  }

  return (
    <div className="p-4 sm:p-6 lg:p-8 space-y-4">
      {/* 페이지 헤더 — 타이틀 + 새 이슈 진입점 (CREATE 권한 게이트) */}
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">이슈 목록</h1>
        <NewIssueButton canCreate={canCreate} />
      </header>

      {/* G4 — 필터 바: 페이지 헤더 아래, 일괄 액션 바 위 (FR-SR-01 D6) */}
      <IssueFilterBar
        projectKey={projectKey}
        value={filter}
        onChange={handleFilterChange}
      />

      {/* 일괄 액션 바 — count > 0 일 때만 렌더 */}
      <IssueBulkActionBar
        count={count}
        onEdit={() => setEditOpen(true)}
        onTransition={() => setTransitionOpen(true)}
        onClear={clearAll}
      />

      {/* EC1 — 0건 + 필터 있음: 필터 초기화 CTA. 0건 + 필터 없음: 기존 IssueEmptyState.
          문구는 흡수 전 로컬 정의의 값을 verbatim 유지한다(NFR-N1). 보드 쪽은 i18n 상수를 쓰지만
          이슈 쪽 3개 문자열의 i18n 이관은 본 PR 범위 밖이다 — 문구 통일은 별도 판단이 필요하다. */}
      {data.empty && !isEmptyIssueFilter(filter) ? (
        <FilteredEmptyState
          title="필터 조건에 맞는 이슈가 없습니다."
          description="다른 조건을 시도하거나 필터를 초기화하세요."
          resetLabel="필터 초기화"
          className="py-16"
          onReset={() => {
            onFilterChange?.(EMPTY_FILTER)
            onPageChange(0)
          }}
        />
      ) : (
        <IssueListContent
          data={data}
          page={page}
          onPageChange={onPageChange}
          onNavigate={onNavigate}
          isSelected={isSelected}
          onToggle={toggle}
          onSelectAllPage={handleSelectAllPage}
          isAllPageSelected={isAllPageSelected}
          sort={sort}
          onSort={handleSort}
          isFetching={isFetching}
          selectedKey={selectedKey}
        />
      )}

      {/* 일괄 편집 Dialog */}
      <BulkEditDialog
        issueKeys={selectedKeys}
        open={editOpen}
        onOpenChange={setEditOpen}
        onSubmitted={handleBulkSubmitted}
      />

      {/* 일괄 전이 Dialog */}
      <BulkTransitionDialog
        issueKeys={selectedKeys}
        open={transitionOpen}
        onOpenChange={setTransitionOpen}
        onSubmitted={handleBulkSubmitted}
      />

      {/* 일괄 작업 결과 Dialog */}
      <BulkOperationResultDialog
        bulkOperationId={bulkOperationId}
        open={resultOpen}
        onOpenChange={(next) => {
          setResultOpen(next)
          // 닫힐 때 id를 null로 리셋해 재오픈 시 이전 작업 데이터 잔상을 방지한다.
          if (!next) setBulkOperationId(null)
        }}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListRouteAdapter — router.ts에 등록되는 라우트 어댑터 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * URL의 selected 파라미터를 정규화한다 — 빈 문자열/공백만 있는 값은 "미선택"으로 취급한다(EC-1).
 * `/issues?selected=`처럼 값이 빈 문자열로 남아있으면 상세 페인이 `issueKey=''`로 열려
 * 404 렌더가 뜨는 것을 방지한다.
 *
 * @param raw URL search의 selected 원문 문자열
 * @returns 정규화된 이슈 키. 미지정/빈값/공백이면 undefined.
 */
function normalizeSelectedKey(raw: string | undefined): string | undefined {
  if (raw === undefined) return undefined
  const trimmed = raw.trim()
  return trimmed === '' ? undefined : trimmed
}

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * useSearch로 URL의 page + status/assignee/label/component 필터 + sort + selected 파라미터를
 * 추출해 IssueListPage(+ split view 우측 페인)에 전달한다.
 *
 * - page → IssueListPage.page (0-indexed)
 * - status/assignee/label/component → searchToIssueFilter → IssueListPage.filter
 * - sort → parseSortParam → IssueListPage.sort (Task 5, S2 — URL에 정렬 상태 반영)
 * - IssueFilterBar onChange → issueFilterToSearch → navigate(URL 갱신, page=0 리셋)
 * - IssueTable 정렬 헤더 → onSortChange → serializeSortParam → navigate(URL sort 갱신)
 * - 빈 필터/정렬 해제 시 해당 키 자체를 URL에서 제거 (issueFilterToSearch/serializeSortParam이 처리)
 * - selected → split view(FR-UX-06 PR20 Task 5) 우측 상세 페인에 열린 이슈 키(D1).
 *   와이드(`useMediaQuery('(min-width: 1024px)')`)이고 selected가 있을 때만 페인이 등장한다(D2).
 *   좁은폭(D3)은 selected를 무시하고 목록만 렌더하며, 행 클릭은 `/issues/$key` 전체화면으로 이동한다.
 *
 * 라우터 등록은 router.ts 담당.
 */
export function IssueListRouteAdapter(): JSX.Element {
  const search = useSearch({ strict: false }) as {
    page?: number
    sort?: string
    selected?: string
  } & IssueFilterSearch
  const navigate = useNavigate()
  const isWide = useMediaQuery('(min-width: 1024px)')
  const page = typeof search.page === 'number' ? search.page : 0
  const sort = useMemo(() => parseSortParam(search.sort), [search.sort])
  const selected = normalizeSelectedKey(search.selected)

  // searchToIssueFilter는 매 렌더마다 새 객체를 반환하므로
  // 실제 search 값이 바뀔 때만 재계산한다 (BoardRouteAdapter 패턴 미러).
  const searchStatus = search.status
  const searchAssignee = search.assignee
  const searchLabel = search.label
  const searchComponent = search.component
  const filter = useMemo(
    () => searchToIssueFilter({
      status: searchStatus,
      assignee: searchAssignee,
      label: searchLabel,
      component: searchComponent,
    }),
    [searchStatus, searchAssignee, searchLabel, searchComponent],
  )

  function handlePageChange(nextPage: number): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, page: nextPage }) })
  }

  /**
   * 행 클릭 핸들러 — 폭에 따라 분기한다(D3).
   * - 와이드: `selected` 검색 파라미터를 토글한다. 다른 검색 파라미터(status/sort/page 등)는
   *   `prev` 스프레드로 보존한다. 같은 키를 다시 클릭하면 `selected`를 해제한다(페인 닫힘).
   * - 좁은폭: split view를 건너뛰고 `/issues/$key` 전체화면 상세로 이동한다.
   */
  function handleNavigate(key: string): void {
    if (isWide) {
      void navigate({
        to: '/issues',
        search: (prev) => ({ ...prev, selected: prev.selected === key ? undefined : key }),
      })
      return
    }
    void navigate({ to: '/issues/$key', params: { key } })
  }

  /**
   * split view 우측 페인 닫기(D2) — `selected`를 URL에서 제거해 페인을 닫고 목록을 전체폭으로 복귀시킨다.
   * IssueDetailPage(variant='pane')의 onClose(닫기 버튼·Escape)·onIssueClosed(삭제 성공) 양쪽에서 쓰인다.
   */
  function clearSelected(): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, selected: undefined }) })
  }

  /**
   * split view 우측 페인에 열린 이슈 키를 교체한다.
   * IssueDetailPage(variant='pane')가 옛 키 → 새 키 308 redirect를 감지했을 때(onIssueRedirect) 쓰인다.
   */
  function setSelectedKey(nextKey: string): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, selected: nextKey }) })
  }

  /**
   * 필터 변경 핸들러 — issueFilterToSearch로 URL search params 갱신.
   * page=0 리셋은 IssueListPage.handleFilterChange가 onPageChange(0)로도 처리한다.
   * 빈 필터 필드는 issueFilterToSearch가 키 자체를 생략해 URL을 깔끔하게 유지한다.
   *
   * CONCERNS-1(NFR-3) — 상세 페인은 selected 키 기준 독립 fetch로, 목록 필터와 무관하게
   * 유지돼야 한다. search를 통째 nextSearch로 교체하지 않고 prev.selected를 보존한다.
   */
  function handleFilterChange(nextFilter: IssueFilterParams): void {
    const nextSearch = issueFilterToSearch(nextFilter)
    void navigate({
      to: '/issues',
      search: (prev) => ({ ...nextSearch, page: 0, selected: prev.selected }),
    })
  }

  /**
   * 정렬 변경 핸들러(Task 5) — serializeSortParam으로 URL search params 갱신.
   * page=0 리셋은 IssueListPage.handleSort가 onPageChange(0)로도 처리하지만(이중 안전),
   * handleFilterChange와 동일하게 이 navigate 호출 자체에도 page=0을 포함한다.
   * nextSort가 null(해제)이면 serializeSortParam이 undefined를 반환해 sort 키가 URL에서 제거된다.
   */
  function handleSortChange(nextSort: IssueTableSortState | null): void {
    void navigate({
      to: '/issues',
      search: (prev) => ({ ...prev, sort: serializeSortParam(nextSort), page: 0 }),
    })
  }

  const listPage = (
    <IssueListPage
      projectKey={DEFAULT_PROJECT_KEY}
      page={page}
      onPageChange={handlePageChange}
      onNavigate={handleNavigate}
      filter={filter}
      onFilterChange={handleFilterChange}
      sort={sort}
      onSortChange={handleSortChange}
      selectedKey={isWide ? selected : null}
    />
  )

  // D2 — 와이드 + selected일 때만 split view(2컬럼)로 전환한다. 그 외(미선택/좁은폭)는 목록 전체폭.
  if (isWide && selected !== undefined) {
    return (
      <IssueListSplitView
        list={listPage}
        detail={
          // CONCERNS-3 — key={selected}로 이슈 전환 시 인스턴스를 강제 재마운트한다.
          // key 없이 인스턴스가 재사용되면 confirmDelete/isEditingTitle 등 잔여 state가
          // 다음 이슈로 이월돼 잘못된 이슈가 삭제되는 위험이 있다([[react-usestate-stale-key-prop]]).
          <IssueDetailPage
            key={selected}
            issueKey={selected}
            variant="pane"
            onClose={clearSelected}
            onIssueClosed={clearSelected}
            onIssueRedirect={setSelectedKey}
          />
        }
      />
    )
  }

  return listPage
}

// ─────────────────────────────────────────────────────────────────────────────
// IssueListSplitView — split view 2컬럼 레이아웃 (FR-UX-06 PR20 Task 5)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueListSplitViewProps {
  /** 좌측 목록(축소) — IssueListPage 렌더 결과 */
  list: JSX.Element
  /** 우측 상세 페인 — IssueDetailPage(variant='pane') 렌더 결과 */
  detail: JSX.Element
}

/**
 * split view 2컬럼 레이아웃(D2, PR20-F3).
 *
 * - 좌측 목록과 우측 상세 페인은 각자 독립 스크롤 컨테이너(`overflow-y-auto`)다 — 긴 상세를
 *   스크롤해도 목록 스크롤에 영향을 주지 않는다.
 * - 좌측 목록은 폭이 줄어도 컬럼을 축약하지 않고 유지한 채 가로 스크롤한다(taste A —
 *   반응형 컬럼 축약 도입 금지, `overflow-x-auto`).
 * - `h-full`/`min-h-0` 조합은 ShellLayout의 `<main className="min-w-0 flex-1 overflow-y-auto">`
 *   높이를 그대로 물려받아, 바깥 main이 아닌 이 컴포넌트의 두 자식이 각자 스크롤하게 한다.
 */
function IssueListSplitView({ list, detail }: IssueListSplitViewProps): JSX.Element {
  return (
    <div className="flex h-full min-h-0">
      <div className="min-w-0 shrink-0 basis-[420px] overflow-x-auto overflow-y-auto border-r border-border">
        {list}
      </div>
      <div className="min-w-0 flex-1 overflow-y-auto">
        {detail}
      </div>
    </div>
  )
}
