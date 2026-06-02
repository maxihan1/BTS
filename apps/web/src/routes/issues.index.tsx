// 이슈 목록 페이지 — IssueListPage(props 기반) + IssueCard + IssueListRouteAdapter(라우터 연결)
import type { JSX } from 'react'
import { useState, useCallback, useMemo } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchIssues } from '@/api/issues'
import type { IssueResponse, IssuePage } from '@/api/issues'
import { Button } from '@/components/ui/button'
import { useIssueSelection } from '@/hooks/use-issue-selection'
import { IssueBulkActionBar } from '@/components/issues/IssueBulkActionBar'
import { BulkEditDialog } from '@/components/issues/BulkEditDialog'
import { BulkTransitionDialog } from '@/components/issues/BulkTransitionDialog'
import { BulkOperationResultDialog } from '@/components/issues/BulkOperationResultDialog'

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
// IssueListRouteAdapter는 useSearch/useNavigate로 page 상태를 추출해
// IssueListPage에 전달한다. 라우터 등록은 Task8(router.ts) 담당.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_PROJECT_KEY = 'ATLAS'
const DEFAULT_PAGE_SIZE = 20

// ─────────────────────────────────────────────────────────────────────────────
// IssueCard — 이슈 목록 단일 항목 컴포넌트 (행 재구조화: 체크박스 + 링크 형제)
// ─────────────────────────────────────────────────────────────────────────────

interface IssueCardProps {
  /** 렌더할 이슈 단건 데이터 */
  issue: IssueResponse
  /** 항목 클릭 시 호출되는 콜백 (key 전달) */
  onNavigate: (key: string) => void
  /** 현재 이슈가 선택됐는지 여부 */
  checked: boolean
  /** 체크박스 클릭 시 선택 토글 콜백 */
  onToggle: (key: string) => void
}

/**
 * 이슈 목록의 단일 항목을 렌더하는 카드 컴포넌트.
 *
 * B1 BLOCKER 대응 — 체크박스를 `<a>` 내부에 중첩하지 않고 형제 요소로 구성한다.
 * - `<li>` 안에 체크박스(`<input type="checkbox">`)와 링크(`<a>`) 를 형제로 배치.
 * - 체크박스 aria-label에 이슈 키를 포함하지 않아 E2E `getByLabel(key)` strict mode를 지킨다.
 * - `data-testid={`select-${key}`}` 로 체크박스를 테스트에서 특정한다.
 *
 * @param issue 렌더할 이슈 데이터
 * @param onNavigate 항목 링크 클릭 시 호출되는 네비게이션 콜백
 * @param checked 체크박스 선택 여부
 * @param onToggle 체크박스 토글 콜백
 */
export function IssueCard({ issue, onNavigate, checked, onToggle }: IssueCardProps): JSX.Element {
  return (
    <div className="flex items-center gap-2 rounded-lg border border-border bg-card px-4 py-3 text-sm hover:bg-muted/50 transition-colors">
      {/* 체크박스 — <a> 외부 형제 요소. aria-label에 이슈 키 미포함 (E2E strict mode 보호) */}
      <input
        type="checkbox"
        aria-label="이슈 선택"
        data-testid={`select-${issue.key}`}
        checked={checked}
        onChange={() => onToggle(issue.key)}
        className="h-4 w-4 shrink-0 cursor-pointer accent-primary"
      />

      {/* 링크 — aria-label에 이슈 키만 사용해 getByLabel(key)가 링크 1개만 매칭되도록 보장 */}
      <a
        href={`/issues/${issue.key}`}
        aria-label={issue.key}
        onClick={(e) => {
          e.preventDefault()
          onNavigate(issue.key)
        }}
        className="flex flex-1 items-center gap-3 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded"
      >
        {/* 이슈 키 — 고정 너비로 정렬 */}
        <span className="shrink-0 font-mono text-xs font-medium text-muted-foreground w-20">
          {issue.key}
        </span>

        {/* 요약 — 긴 텍스트 말줄임 처리 (overflow: hidden + text-overflow: ellipsis) */}
        <span
          data-testid={`issue-summary-${issue.key}`}
          className="flex-1 truncate text-foreground"
        >
          {issue.summary}
        </span>

        {/* 현재 상태 배지 */}
        <span
          role="status"
          className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground"
        >
          {issue.currentStateKey}
        </span>
      </a>
    </div>
  )
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
// IssueListContent — 성공 상태 렌더 컴포넌트 (목록 + 페이지네이션)
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
}

/**
 * 이슈 목록 성공 상태 렌더 컴포넌트.
 * 빈 목록이면 IssueEmptyState, 아니면 IssueCard 목록 + IssuePagination을 렌더한다.
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
}: IssueListContentProps): JSX.Element {
  if (data.empty) {
    return <IssueEmptyState />
  }

  return (
    <>
      {/* 전체 선택 행 */}
      <div className="flex items-center gap-2 px-4 py-2 border-b border-border">
        <input
          type="checkbox"
          aria-label="현재 페이지 전체 선택"
          data-testid="select-all-page"
          checked={isAllPageSelected}
          onChange={onSelectAllPage}
          className="h-4 w-4 cursor-pointer accent-primary"
        />
        <span className="text-xs text-muted-foreground">전체 선택</span>
      </div>

      <ul className="space-y-2" aria-label="이슈 목록">
        {data.content.map((issue) => (
          <li key={issue.key}>
            <IssueCard
              issue={issue}
              onNavigate={onNavigate}
              checked={isSelected(issue.key)}
              onToggle={onToggle}
            />
          </li>
        ))}
      </ul>

      <IssuePagination
        page={page}
        totalPages={data.totalPages}
        isFirst={data.first}
        isLast={data.last}
        onPageChange={onPageChange}
      />
    </>
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
}

/**
 * 이슈 목록 페이지 컴포넌트.
 *
 * - useQuery로 fetchIssues를 호출한다.
 * - 3 상태 분기: 로딩("로딩 중...") → 에러(role="alert") → 성공(IssueListContent).
 * - 에러 시 role="alert"로 스크린 리더 접근성 보장 (WCAG AA).
 * - "새 이슈" 링크(/issues/new)가 상단에 항상 노출된다.
 * - useIssueSelection으로 페이지 교차 누적 선택 상태를 관리한다.
 * - IssueBulkActionBar, BulkEditDialog, BulkTransitionDialog, BulkOperationResultDialog를 결선한다.
 *
 * 라우터 의존 없이 props로 동작해 단위 테스트가 가능하다.
 */
export function IssueListPage({ projectKey, page, onPageChange, onNavigate }: IssueListPageProps): JSX.Element {
  const queryClient = useQueryClient()
  const { data, isLoading, error } = useQuery({
    queryKey: ['issues', projectKey, page],
    queryFn: () => fetchIssues({ projectKey, page, size: DEFAULT_PAGE_SIZE }),
    retry: false,
  })

  // ── 선택 상태 ──────────────────────────────────────────────────────────────
  const { selectedKeys, count, isSelected, toggle, selectAllOnPage, clearPageSelection, clearAll } =
    useIssueSelection()

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

  if (error !== null && error !== undefined || data === undefined) {
    return (
      <div role="alert" className="p-8 text-destructive">
        이슈 목록을 불러올 수 없습니다.
      </div>
    )
  }

  return (
    <div className="p-4 sm:p-6 lg:p-8 space-y-4">
      {/* 페이지 헤더 — 타이틀 + 새 이슈 링크 */}
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">이슈 목록</h1>
        <a
          href="/issues/new"
          className="inline-flex items-center rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors"
        >
          새 이슈
        </a>
      </header>

      {/* 일괄 액션 바 — count > 0 일 때만 렌더 */}
      <IssueBulkActionBar
        count={count}
        onEdit={() => setEditOpen(true)}
        onTransition={() => setTransitionOpen(true)}
        onClear={clearAll}
      />

      <IssueListContent
        data={data}
        page={page}
        onPageChange={onPageChange}
        onNavigate={onNavigate}
        isSelected={isSelected}
        onToggle={toggle}
        onSelectAllPage={handleSelectAllPage}
        isAllPageSelected={isAllPageSelected}
      />

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
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * useSearch로 URL의 page 쿼리 파라미터를 추출하여 IssueListPage에 전달한다.
 * 라우터 등록은 Task8(router.ts) 담당.
 */
export function IssueListRouteAdapter(): JSX.Element {
  const search = useSearch({ strict: false }) as { page?: number }
  const navigate = useNavigate()
  const page = typeof search.page === 'number' ? search.page : 0

  function handlePageChange(nextPage: number): void {
    void navigate({ to: '/issues', search: (prev) => ({ ...prev, page: nextPage }) })
  }

  function handleNavigate(key: string): void {
    void navigate({ to: `/issues/${key}` })
  }

  return (
    <IssueListPage
      projectKey={DEFAULT_PROJECT_KEY}
      page={page}
      onPageChange={handlePageChange}
      onNavigate={handleNavigate}
    />
  )
}
