// Sidebar 컴포넌트 단위 테스트 — 메인/관리 nav 렌더, isSystemAdmin 게이팅, h1/검색 부재, 접힘 시 접근가능 이름 보존 (FR-UX-06 PR11 Task 5)
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import type { WhoamiResponse } from '@/api/schemas'
import { favoriteLabels } from '@/i18n/favorite-labels'
import { navLabels } from '@/i18n/nav-labels'
import { Sidebar } from '../Sidebar'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 컴포넌트 isolation 렌더 (Header.test.tsx·
// FavoritesMenu.test.tsx 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const BASE_USER: WhoamiResponse = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'local',
  userId: 'u1',
  mustChangePassword: false,
  isSystemAdmin: false,
  mfaEnrollmentRequired: false,
}

/** 관리 nav 6링크 계약 — [라벨, href] */
const ADMIN_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['워크플로우 스킴', '/admin/workflow-schemes'],
  ['감사 로그', '/admin/audit-logs'],
  ['전역 권한', '/admin/global-permissions'],
  ['알림 정책', '/admin/notification-policies'],
  ['Webhook', '/admin/webhooks'],
  ['Slack 연결', '/admin/slack'],
]

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderSidebar() {
  return render(<Sidebar />, { wrapper: makeWrapper() })
}

beforeEach(() => {
  window.localStorage.clear()
  useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
  // useSidebarCollapsed는 모듈 전역 zustand 싱글톤 — 이전 테스트의 toggle()이 남긴 상태가
  // 누출되지 않도록 매 테스트 펼침(기본값)으로 리셋한다(테스트 간 격리)
  useSidebarCollapsed.setState({ collapsed: false })
  // FavoritesMenu(useFavorites)가 조회하는 엔드포인트 — 빈 목록으로 응답
  server.use(
    http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })),
  )
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('Sidebar', () => {
  it('메인 메뉴 nav가 존재하고 이슈·대시보드·캘린더 링크를 포함한다 (FR3)', () => {
    renderSidebar()

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(within(mainNav).getByRole('link', { name: navLabels.issues })).toHaveAttribute(
      'href',
      '/issues',
    )
    expect(within(mainNav).getByRole('link', { name: navLabels.dashboards })).toHaveAttribute(
      'href',
      '/dashboards',
    )
    expect(within(mainNav).getByRole('link', { name: navLabels.calendar })).toHaveAttribute(
      'href',
      '/calendar',
    )
  })

  it('메인 메뉴 nav 안에 즐겨찾기(FavoritesMenu) 트리거가 존재한다 (FR3)', () => {
    renderSidebar()

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(
      within(mainNav).getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }),
    ).toBeInTheDocument()
  })

  it('isSystemAdmin=true이면 관리 메뉴 nav가 기본 펼침 상태로 6링크를 노출한다 (FR4)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { ...BASE_USER, isSystemAdmin: true },
    })
    renderSidebar()

    const adminNav = screen.getByRole('navigation', { name: navLabels.adminNav })
    for (const [label, href] of ADMIN_LINK_CONTRACT) {
      const link = within(adminNav).getByRole('link', { name: label })
      expect(link).toBeVisible()
      expect(link).toHaveAttribute('href', href)
    }
  })

  it('isSystemAdmin=false이면 관리 메뉴 nav가 렌더되지 않는다 (FR4)', () => {
    renderSidebar()

    expect(screen.queryByRole('navigation', { name: navLabels.adminNav })).not.toBeInTheDocument()
    for (const [label] of ADMIN_LINK_CONTRACT) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument()
    }
  })

  it('사이드바에 <h1>이 없다', () => {
    renderSidebar()

    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it('검색 항목이 없다 — 상단바(Header) 단일 소유', () => {
    renderSidebar()

    expect(screen.queryByRole('button', { name: navLabels.search })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(navLabels.search)).not.toBeInTheDocument()
  })

  it('사이드바 접기 토글 클릭 시에도 메인 nav 링크의 접근가능 이름이 유지된다', async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.click(screen.getByRole('button', { name: navLabels.collapseSidebar }))

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(within(mainNav).getByRole('link', { name: navLabels.issues })).toBeInTheDocument()
    expect(within(mainNav).getByRole('link', { name: navLabels.dashboards })).toBeInTheDocument()
    expect(within(mainNav).getByRole('link', { name: navLabels.calendar })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: navLabels.expandSidebar })).toBeInTheDocument()
  })

  it('메인 nav 각 링크가 aria-hidden 아이콘(svg)을 렌더한다 (접힘=진짜 아이콘 레일, FR5)', () => {
    renderSidebar()

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(
      within(mainNav).getByRole('link', { name: navLabels.issues }).querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
    expect(
      within(mainNav)
        .getByRole('link', { name: navLabels.dashboards })
        .querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
    expect(
      within(mainNav).getByRole('link', { name: navLabels.calendar }).querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
  })

  it('사이드바 접힘 시 nav 링크의 텍스트 라벨이 시각적으로만 숨겨진다(sr-only) — 아이콘은 그대로 보인다', async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.click(screen.getByRole('button', { name: navLabels.collapseSidebar }))

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    const issuesLink = within(mainNav).getByRole('link', { name: navLabels.issues })
    expect(issuesLink.querySelector('svg[aria-hidden="true"]')).not.toBeNull()
    expect(issuesLink.querySelector('span')?.className).toContain('sr-only')
  })
})
