// 이슈 목록 페이지 — IssueListPage(props 기반) + IssueCard + IssueListRouteAdapter(라우터 연결)
import type { JSX } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { fetchIssues } from '@/api/issues'
import type { IssueResponse, IssuePage } from '@/api/issues'
import { Button } from '@/components/ui/button'

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
// IssueCard — 이슈 목록 단일 항목 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface IssueCardProps {
  /** 렌더할 이슈 단건 데이터 */
  issue: IssueResponse
  /** 항목 클릭 시 호출되는 콜백 (key 전달) */
  onNavigate: (key: string) => void
}

/**
 * 이슈 목록의 단일 항목을 렌더하는 카드 컴포넌트.
 *
 * - 이슈 키, 요약(truncate), 현재 상태 배지를 표시한다.
 * - 항목 전체가 클릭 가능한 링크(`<a>`)이며, onNavigate 콜백도 함께 호출한다.
 * - 키보드 포커스 시 ring 스타일로 WCAG AA 접근성을 보장한다.
 */
export function IssueCard({ issue, onNavigate }: IssueCardProps): JSX.Element {
  return (
    <a
      href={`/issues/${issue.key}`}
      aria-label={issue.key}
      onClick={(e) => {
        e.preventDefault()
        onNavigate(issue.key)
      }}
      className="flex items-center gap-3 rounded-lg border border-border bg-card px-4 py-3 text-sm hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-colors"
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
}

/**
 * 이슈 목록 성공 상태 렌더 컴포넌트.
 * 빈 목록이면 IssueEmptyState, 아니면 IssueCard 목록 + IssuePagination을 렌더한다.
 */
function IssueListContent({ data, page, onPageChange, onNavigate }: IssueListContentProps): JSX.Element {
  if (data.empty) {
    return <IssueEmptyState />
  }

  return (
    <>
      <ul className="space-y-2" aria-label="이슈 목록">
        {data.content.map((issue) => (
          <li key={issue.key}>
            <IssueCard issue={issue} onNavigate={onNavigate} />
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
 *
 * 라우터 의존 없이 props로 동작해 단위 테스트가 가능하다.
 */
export function IssueListPage({ projectKey, page, onPageChange, onNavigate }: IssueListPageProps): JSX.Element {
  const { data, isLoading, error } = useQuery({
    queryKey: ['issues', projectKey, page],
    queryFn: () => fetchIssues({ projectKey, page, size: DEFAULT_PAGE_SIZE }),
    retry: false,
  })

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

      <IssueListContent
        data={data}
        page={page}
        onPageChange={onPageChange}
        onNavigate={onNavigate}
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
    void navigate({ search: (prev: Record<string, unknown>) => ({ ...prev, page: nextPage }) })
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
