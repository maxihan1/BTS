// 알림 보관함(Inbox) 페이지 컴포넌트 + TanStack Router 어댑터 (FR-UX-03 D6/D7)
import type { JSX } from 'react'
import * as React from 'react'
import { useInbox, useMarkRead, useMarkArchive, useReadAll, INBOX_TABS } from '@/api/inbox'
import type { InboxFilters as InboxFiltersType, InboxItem, InboxTab } from '@/api/inbox'
import { useActorNames } from '@/api/inbox-actors'
import { InboxListItem } from '@/components/inbox/InboxListItem'
import { InboxFilters } from '@/components/inbox/InboxFilters'
import { inboxLabels } from '@/i18n/inbox-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 페이지 크기 */
const DEFAULT_PAGE_SIZE = 20

/** 스켈레톤 행 수 — 로딩 중 표시할 행 수 */
const SKELETON_ROW_COUNT = 5

// ─────────────────────────────────────────────────────────────────────────────
// 스켈레톤 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 목록 항목 로딩 스켈레톤.
 * 로딩 중일 때 SKELETON_ROW_COUNT 개수만큼 렌더한다.
 */
function InboxSkeleton(): JSX.Element {
  return (
    <div data-testid="inbox-skeleton" className="flex flex-col gap-2">
      {Array.from({ length: SKELETON_ROW_COUNT }, (_, i) => (
        <div
          key={`skeleton-${i}`}
          className="h-20 animate-pulse rounded-md border bg-muted"
          aria-hidden="true"
        />
      ))}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface InboxEmptyProps {
  /** 현재 활성 탭 */
  tab: InboxTab
}

/**
 * 목록이 비었을 때 탭별 안내 메시지를 표시하는 컴포넌트.
 */
function InboxEmpty({ tab }: InboxEmptyProps): JSX.Element {
  const messageMap: Record<InboxTab, string> = {
    [INBOX_TABS.ALL]: inboxLabels.empty.all,
    [INBOX_TABS.UNREAD]: inboxLabels.empty.unread,
    [INBOX_TABS.ARCHIVED]: inboxLabels.empty.archived,
  }

  return (
    <p className="py-12 text-center text-sm text-muted-foreground">
      {messageMap[tab]}
    </p>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface InboxPagerProps {
  /** 현재 페이지 (0-based) */
  page: number
  /** 전체 페이지 수 */
  totalPages: number
  /** 페이지 변경 콜백 */
  onPageChange: (nextPage: number) => void
}

/**
 * 이전/다음 페이지 이동 버튼 컴포넌트.
 * Spring Page 기반 — 0-based 페이지 번호를 받아 처리한다.
 */
function InboxPager({ page, totalPages, onPageChange }: InboxPagerProps): JSX.Element {
  const isFirst = page === 0
  const isLast = totalPages === 0 || page >= totalPages - 1

  return (
    <div className="flex items-center justify-center gap-4 py-4">
      <button
        type="button"
        disabled={isFirst}
        onClick={() => onPageChange(page - 1)}
        className="rounded px-3 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-40 hover:bg-accent"
      >
        {inboxLabels.pagination.previous}
      </button>

      <span className="text-sm text-muted-foreground">
        {page + 1} / {Math.max(totalPages, 1)}
      </span>

      <button
        type="button"
        disabled={isLast}
        onClick={() => onPageChange(page + 1)}
        className="rounded px-3 py-1.5 text-sm disabled:cursor-not-allowed disabled:opacity-40 hover:bg-accent"
      >
        {inboxLabels.pagination.next}
      </button>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// InboxList — 목록 렌더 분리 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface InboxListProps {
  /** 렌더할 Inbox 항목 배열 */
  items: InboxItem[]
  /** actorUserId → 표시 이름 Map */
  actorNameMap: Map<string, string> | undefined
  /** 읽음 토글 콜백 */
  onToggleRead: (id: string, read: boolean) => void
  /** 보관 토글 콜백 */
  onToggleArchive: (id: string, archived: boolean) => void
}

/**
 * Inbox 항목 목록을 InboxListItem으로 렌더하는 컴포넌트.
 * actorNameMap에서 발신자 이름을 조회해 각 항목에 전달한다.
 */
function InboxList({ items, actorNameMap, onToggleRead, onToggleArchive }: InboxListProps): JSX.Element {
  return (
    <ul className="flex flex-col gap-2" aria-label="알림 목록">
      {items.map((item) => (
        <li key={item.id}>
          <InboxListItem
            item={item}
            actorName={
              item.actorUserId !== null
                ? (actorNameMap?.get(item.actorUserId) ?? null)
                : null
            }
            onToggleRead={onToggleRead}
            onToggleArchive={onToggleArchive}
          />
        </li>
      ))}
    </ul>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// InboxPage — 메인 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 보관함 페이지 컴포넌트.
 *
 * - 탭(전체/안읽음/보관함) 상태를 로컬로 관리하고 useInbox 필터에 반영한다.
 * - 검색 필터(InboxFilters)를 조립해 목록 재조회를 트리거한다.
 * - useActorNames로 발신자 이름 Map을 구성해 각 InboxListItem에 전달한다.
 * - useMarkRead/useMarkArchive로 읽음·보관 토글을 처리한다.
 * - useReadAll로 일괄 읽음을 처리한다.
 * - Spring Page 기반 페이지네이션(이전/다음)을 지원한다.
 * - 로딩/에러/빈 상태를 각각 구분해 렌더한다 (design-review 보강).
 */
export function InboxPage(): JSX.Element {
  // ── 탭 상태 ────────────────────────────────────────────────────────────────
  const [activeTab, setActiveTab] = React.useState<InboxTab>(INBOX_TABS.ALL)

  // ── 검색 필터 상태 (탭 제외) ────────────────────────────────────────────────
  const [searchFilters, setSearchFilters] = React.useState<Omit<InboxFiltersType, 'tab' | 'page'>>({})

  // ── 페이지 상태 ────────────────────────────────────────────────────────────
  const [page, setPage] = React.useState(0)

  // ── 탭 변경 시 페이지 초기화 ────────────────────────────────────────────────
  function handleTabChange(tab: InboxTab) {
    setActiveTab(tab)
    setPage(0)
  }

  // ── 필터 변경 핸들러 ────────────────────────────────────────────────────────
  function handleFiltersChange(next: InboxFiltersType) {
    // tab과 page는 로컬 상태로 관리 — 검색 필터 컴포넌트가 변경해도 무시
    const { tab: omitTab, page: omitPage, ...rest } = next
    // omitTab/omitPage는 의도적으로 사용하지 않는다 (destructure to exclude)
    void omitTab
    void omitPage
    setSearchFilters(rest)
    setPage(0)
  }

  // ── 합성 필터 — 탭 + 검색 + 페이지 ─────────────────────────────────────────
  const filters: InboxFiltersType = React.useMemo(
    () => ({
      tab: activeTab,
      ...searchFilters,
      page,
      size: DEFAULT_PAGE_SIZE,
    }),
    [activeTab, searchFilters, page],
  )

  // ── 데이터 패칭 ────────────────────────────────────────────────────────────
  const { data, isLoading, error } = useInbox(filters)

  // ── 발신자 이름 조회 ────────────────────────────────────────────────────────
  const items = data?.content ?? []
  const { data: actorNameMap } = useActorNames(items)

  // ── Mutation 훅 ────────────────────────────────────────────────────────────
  const markReadMutation = useMarkRead()
  const markArchiveMutation = useMarkArchive()
  const readAllMutation = useReadAll()

  // ── 이벤트 핸들러 ──────────────────────────────────────────────────────────

  function handleToggleRead(id: string, read: boolean) {
    markReadMutation.mutate({ id, read })
  }

  function handleToggleArchive(id: string, archived: boolean) {
    markArchiveMutation.mutate({ id, archived })
  }

  function handleReadAll() {
    readAllMutation.mutate({})
  }

  // ── 페이지네이션 ────────────────────────────────────────────────────────────
  const totalPages = data?.totalPages ?? 0

  // ── 현재 필터(탭 포함)를 InboxFilters 컴포넌트에 전달하는 filters prop ────────
  const filtersForComponent: InboxFiltersType = React.useMemo(
    () => ({ ...searchFilters, tab: activeTab, page }),
    [searchFilters, activeTab, page],
  )

  // ─────────────────────────────────────────────────────────────────────────
  // 렌더
  // ─────────────────────────────────────────────────────────────────────────

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 sm:px-6 lg:px-8">
      {/* 페이지 헤더 */}
      <header className="mb-6">
        <h1 className="text-2xl font-semibold">{inboxLabels.page.title}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{inboxLabels.page.description}</p>
      </header>

      {/* 탭 */}
      <div role="tablist" className="mb-4 flex border-b">
        {[INBOX_TABS.ALL, INBOX_TABS.UNREAD, INBOX_TABS.ARCHIVED].map((tab) => {
          const labelMap: Record<InboxTab, string> = {
            [INBOX_TABS.ALL]: inboxLabels.tabs.all,
            [INBOX_TABS.UNREAD]: inboxLabels.tabs.unread,
            [INBOX_TABS.ARCHIVED]: inboxLabels.tabs.archived,
          }
          const isActive = activeTab === tab
          return (
            <button
              key={tab}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => handleTabChange(tab)}
              className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                isActive
                  ? 'border-primary text-primary'
                  : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              {labelMap[tab]}
            </button>
          )
        })}
      </div>

      {/* 상단 액션 바 — 검색 필터 + 전체 읽음 버튼 */}
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex-1">
          <InboxFilters
            filters={filtersForComponent}
            onFiltersChange={handleFiltersChange}
          />
        </div>
        <button
          type="button"
          onClick={handleReadAll}
          className="shrink-0 rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {inboxLabels.bulk.readAll}
        </button>
      </div>

      {/* 본문 영역 — 로딩·에러·빈 상태·목록 분기 */}
      {isLoading && <InboxSkeleton />}

      {!isLoading && error !== null && (
        <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">
          알림 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </div>
      )}

      {!isLoading && error === null && items.length === 0 && (
        <InboxEmpty tab={activeTab} />
      )}

      {!isLoading && error === null && items.length > 0 && (
        <>
          <InboxList
            items={items}
            actorNameMap={actorNameMap}
            onToggleRead={handleToggleRead}
            onToggleArchive={handleToggleArchive}
          />
          <InboxPager
            page={page}
            totalPages={totalPages}
            onPageChange={setPage}
          />
        </>
      )}

      {/* 페이지네이션은 데이터가 있을 때만이 아니라 항상 표시 (UX: 빈 상태에서도 탐색 가능) */}
      {!isLoading && error === null && items.length === 0 && (
        <InboxPager
          page={page}
          totalPages={totalPages}
          onPageChange={setPage}
        />
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// InboxRouteAdapter — router.ts 등록용 어댑터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * /inbox 경로에 URL 파라미터가 없으므로 props 없이 InboxPage를 직접 렌더한다.
 *
 * router.ts 등록 방법 (code-based 패턴).
 *
 *   import { InboxRouteAdapter } from './routes/inbox'
 *
 *   const inboxRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/inbox',
 *     component: InboxRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuthAndPasswordChanged,
 *   })
 */
export function InboxRouteAdapter(): JSX.Element {
  return <InboxPage />
}
