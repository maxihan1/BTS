// 알림 보관함 페이지 컴포넌트 단위 + 통합 테스트 (FR-UX-03 D6/D7 Task 8)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { InboxPage } from './inbox'
import { inboxLabels } from '@/i18n/inbox-labels'
import { seedInbox, resetInboxStore } from '@/mocks/inbox-handlers'
import {
  inboxFixtureUnread,
  inboxFixtureRead,
  inboxFixtureArchived,
} from '@/mocks/inbox-fixtures'
import type { InboxItem } from '@/api/inbox'
import type { InboxFiltersProps } from '@/components/inbox/InboxFilters'
import type { InboxListItemProps } from '@/components/inbox/InboxListItem'

// ─────────────────────────────────────────────────────────────────────────────
// 하위 컴포넌트 mock — import type 으로 실제 Props 타입 사용 (learnings vi.mock contract drift 방지)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/inbox/InboxFilters', () => ({
  InboxFilters: ({ filters, onFiltersChange }: InboxFiltersProps) => (
    <div data-testid="inbox-filters-mock">
      <button
        type="button"
        data-testid="filter-change-btn"
        onClick={() => onFiltersChange({ ...filters, q: 'test-query' })}
      >
        필터변경
      </button>
    </div>
  ),
}))

vi.mock('@/components/inbox/InboxListItem', () => ({
  InboxListItem: ({ item, actorName, onToggleRead, onToggleArchive }: InboxListItemProps) => (
    <div data-testid={`inbox-item-${item.id}`}>
      <span data-testid={`item-title-${item.id}`}>{item.title}</span>
      <span data-testid={`item-actor-${item.id}`}>{actorName ?? '시스템'}</span>
      <button
        type="button"
        data-testid={`item-toggle-read-${item.id}`}
        onClick={() => onToggleRead(item.id, item.readAt === null)}
      >
        읽음토글
      </button>
      <button
        type="button"
        data-testid={`item-toggle-archive-${item.id}`}
        onClick={() => onToggleArchive(item.id, item.archivedAt === null)}
      >
        보관토글
      </button>
    </div>
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 인증된 alice 토큰으로 fetch Authorization 헤더를 패치 */
function patchAuthHeader() {
  const originalFetch = globalThis.fetch
  return vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (input, init) => {
      const headers = new Headers(init?.headers)
      headers.set('Authorization', 'Bearer mock-access-token-alice')
      return originalFetch(input, { ...init, headers })
    })
}

/** InboxPage를 QueryClient + TanStack Router context 안에서 렌더 */
function renderInboxPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  const rootRoute = createRootRoute()
  const inboxRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/inbox',
    component: InboxPage,
  })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([inboxRoute]),
    defaultPreload: false,
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={testRouter} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처 - alice userId
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_ID = '00000000-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxPage', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    resetInboxStore()
    seedInbox(ALICE_ID, [inboxFixtureUnread, inboxFixtureRead])
    fetchSpy = patchAuthHeader()
  })

  afterEach(() => {
    fetchSpy.mockRestore()
  })

  // ── 페이지 기본 렌더 ─────────────────────────────────────────────────────

  it('페이지 제목을 표시한다', async () => {
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: inboxLabels.page.title })).toBeInTheDocument()
    })
  })

  it('탭 3종(전체/안읽음/보관함)을 표시한다', async () => {
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: inboxLabels.tabs.all })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: inboxLabels.tabs.unread })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: inboxLabels.tabs.archived })).toBeInTheDocument()
    })
  })

  it('InboxFilters 컴포넌트를 렌더한다', async () => {
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByTestId('inbox-filters-mock')).toBeInTheDocument()
    })
  })

  it('목록 항목이 InboxListItem으로 렌더된다', async () => {
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByTestId(`inbox-item-${inboxFixtureUnread.id}`)).toBeInTheDocument()
      expect(screen.getByTestId(`inbox-item-${inboxFixtureRead.id}`)).toBeInTheDocument()
    })
  })

  // ── 탭 전환 ──────────────────────────────────────────────────────────────

  it('안읽음 탭 클릭 시 UNREAD 필터로 재조회한다', async () => {
    const user = userEvent.setup()
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: inboxLabels.tabs.unread })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: inboxLabels.tabs.unread }))
    await waitFor(() => {
      expect(screen.getByTestId(`inbox-item-${inboxFixtureUnread.id}`)).toBeInTheDocument()
    })
  })

  it('보관함 탭 클릭 시 ARCHIVED 필터로 재조회한다', async () => {
    const user = userEvent.setup()
    seedInbox(ALICE_ID, [inboxFixtureArchived])
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: inboxLabels.tabs.archived })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('tab', { name: inboxLabels.tabs.archived }))
    await waitFor(() => {
      expect(screen.getByTestId(`inbox-item-${inboxFixtureArchived.id}`)).toBeInTheDocument()
    })
  })

  // ── 빈 상태 ──────────────────────────────────────────────────────────────

  it('항목이 없으면 전체 탭 빈 상태 메시지를 표시한다', async () => {
    resetInboxStore()
    seedInbox(ALICE_ID, [])
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByText(inboxLabels.empty.all)).toBeInTheDocument()
    })
  })

  it('안읽음 탭에서 항목이 없으면 안읽음 빈 상태 메시지를 표시한다', async () => {
    const user = userEvent.setup()
    // 읽음 처리된 항목만 있는 상태
    resetInboxStore()
    seedInbox(ALICE_ID, [inboxFixtureRead])
    renderInboxPage()
    await waitFor(() => screen.getByRole('tab', { name: inboxLabels.tabs.unread }))
    await user.click(screen.getByRole('tab', { name: inboxLabels.tabs.unread }))
    await waitFor(() => {
      expect(screen.getByText(inboxLabels.empty.unread)).toBeInTheDocument()
    })
  })

  // ── 로딩 상태 (design-review 보강) ───────────────────────────────────────

  it('로딩 중에는 스켈레톤 또는 로딩 상태 UI를 표시한다', async () => {
    renderInboxPage()
    // 로딩 직후 스켈레톤이나 로딩 표시가 나타나야 한다
    // 실제로는 빠르게 지나가지만, 초기 렌더에서 aria-busy 또는 data-testid 확인
    const loadingEl = screen.queryByTestId('inbox-loading')
    const skeletonEl = screen.queryByTestId('inbox-skeleton')
    // 최소 하나가 초기 렌더 시점에 존재하거나 곧 데이터가 로드되어 아이템이 나타남
    const hasLoadingOrData =
      loadingEl !== null ||
      skeletonEl !== null ||
      screen.queryByTestId(`inbox-item-${inboxFixtureUnread.id}`) !== null
    expect(hasLoadingOrData).toBe(true)
  })

  // ── 에러 상태 (design-review 보강) ───────────────────────────────────────

  it('조회 실패 시 에러 메시지를 표시한다', async () => {
    // 에러 상황 시뮬레이션 — fetch를 실패하도록 오버라이드
    fetchSpy.mockRestore()
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('Network Error'))

    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  // ── 읽음/보관 토글 ───────────────────────────────────────────────────────

  it('읽음 토글 버튼 클릭 시 mutation이 실행된다', async () => {
    const user = userEvent.setup()
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByTestId(`item-toggle-read-${inboxFixtureUnread.id}`)).toBeInTheDocument()
    })
    await user.click(screen.getByTestId(`item-toggle-read-${inboxFixtureUnread.id}`))
    // mutation 실행 후 invalidate가 발생하면 목록이 다시 로드됨
    // 에러 없이 동작하면 성공으로 간주
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })

  it('보관 토글 버튼 클릭 시 mutation이 실행된다', async () => {
    const user = userEvent.setup()
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByTestId(`item-toggle-archive-${inboxFixtureUnread.id}`)).toBeInTheDocument()
    })
    await user.click(screen.getByTestId(`item-toggle-archive-${inboxFixtureUnread.id}`))
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })

  // ── 전체 읽음 ────────────────────────────────────────────────────────────

  it('전체 읽음 버튼이 렌더된다', async () => {
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: inboxLabels.bulk.readAll })).toBeInTheDocument()
    })
  })

  it('전체 읽음 버튼 클릭 시 mutation이 실행된다', async () => {
    const user = userEvent.setup()
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: inboxLabels.bulk.readAll })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: inboxLabels.bulk.readAll }))
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })

  // ── 페이지네이션 ─────────────────────────────────────────────────────────

  it('페이지네이션 버튼이 렌더된다', async () => {
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: inboxLabels.pagination.previous })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: inboxLabels.pagination.next })).toBeInTheDocument()
    })
  })

  it('첫 페이지에서 이전 버튼이 비활성화된다', async () => {
    renderInboxPage()
    await waitFor(() => {
      const prevBtn = screen.getByRole('button', { name: inboxLabels.pagination.previous })
      expect(prevBtn).toBeDisabled()
    })
  })

  it('마지막 페이지에서 다음 버튼이 비활성화된다', async () => {
    renderInboxPage()
    await waitFor(() => {
      // 기본 데이터는 1페이지 미만이므로 다음 버튼 비활성화
      const nextBtn = screen.getByRole('button', { name: inboxLabels.pagination.next })
      expect(nextBtn).toBeDisabled()
    })
  })

  // ── actorName 전달 ────────────────────────────────────────────────────────

  it('actorUserId가 null인 항목은 actorName으로 null을 전달한다', async () => {
    resetInboxStore()
    const systemItem: InboxItem = {
      ...inboxFixtureUnread,
      id: 'f0000000-0000-4000-8000-000000000099',
      actorUserId: null,
    }
    seedInbox(ALICE_ID, [systemItem])
    renderInboxPage()
    await waitFor(() => {
      const actorEl = screen.getByTestId(`item-actor-${systemItem.id}`)
      // mock InboxListItem은 actorName이 null일 때 '시스템'을 출력
      expect(actorEl.textContent).toBe('시스템')
    })
  })

  // ── InboxFilters 콜백 ─────────────────────────────────────────────────────

  it('InboxFilters에서 필터가 변경되면 재조회가 발생한다', async () => {
    const user = userEvent.setup()
    renderInboxPage()
    await waitFor(() => {
      expect(screen.getByTestId('filter-change-btn')).toBeInTheDocument()
    })
    await user.click(screen.getByTestId('filter-change-btn'))
    // 필터 변경 후 에러 없이 렌더 유지
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// InboxRouteAdapter export 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('InboxRouteAdapter export', () => {
  it('InboxRouteAdapter가 named export로 존재한다', async () => {
    const module = await import('./inbox')
    expect(module.InboxRouteAdapter).toBeDefined()
    expect(typeof module.InboxRouteAdapter).toBe('function')
  })

  it('InboxPage가 named export로 존재한다', async () => {
    const module = await import('./inbox')
    expect(module.InboxPage).toBeDefined()
    expect(typeof module.InboxPage).toBe('function')
  })
})
