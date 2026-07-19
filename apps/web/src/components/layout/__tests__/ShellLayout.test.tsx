// ShellLayout(_shell) 컴포넌트 단위 테스트 — isAuthenticated 게이팅에 따른 크롬(TopBar+Sidebar) 렌더/억제 (FR-UX-06 PR11 Task 7)
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import type { WhoamiResponse } from '@/api/schemas'
import { navLabels } from '@/i18n/nav-labels'
import { ShellLayout } from '../ShellLayout'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router 모킹 — Outlet은 콘텐츠 마커로 대체, Link/useNavigate는 TopBar·Sidebar가 내부에서
// 소비한다(Sidebar.test.tsx·TopBar.test.tsx 동일 패턴, 라우터 컨텍스트 없이 isolation 렌더).
// ─────────────────────────────────────────────────────────────────────────────

const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  Outlet: () => <div data-testid="outlet-content">content</div>,
  useNavigate: () => mockNavigate,
  Link: ({
    to,
    children,
    className,
    'aria-label': ariaLabel,
  }: {
    to: string
    children: React.ReactNode
    className?: string
    'aria-label'?: string
  }) => (
    <a href={to} className={className} aria-label={ariaLabel}>
      {children}
    </a>
  ),
}))

// Avatar mock — jsdom URL.createObjectURL 미구현 회피 (TopBar.test.tsx·Header.test.tsx 동일 패턴)
vi.mock('@/components/ui/avatar', () => ({
  Avatar: () => <div data-testid="shell-avatar-mock" />,
}))

// StatusModal/OooModal mock — AccountMenu(TopBar 내부) 협력자, 이 테스트 범위 밖
vi.mock('@/components/status/StatusModal', () => ({ StatusModal: () => null }))
vi.mock('@/components/ooo/OooModal', () => ({ OooModal: () => null }))

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

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderShell() {
  return render(<ShellLayout />, { wrapper: makeWrapper() })
}

beforeEach(() => {
  window.localStorage.clear()
  // Sidebar(FavoritesMenu)·TopBar(InboxBell)가 마운트 시 조회하는 엔드포인트
  server.use(
    http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })),
    http.get('/api/v1/users/me/inbox/unread-count', () =>
      HttpResponse.json({ data: { count: 0 } }),
    ),
  )
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('ShellLayout', () => {
  it('isAuthenticated=true — TopBar+Sidebar+콘텐츠(Outlet)를 렌더한다 (FR1)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    // TopBar — 로고(→ /dashboards)
    expect(screen.getByRole('link', { name: /Atlas/ })).toHaveAttribute('href', '/dashboards')
    // Sidebar — 메인 메뉴 nav
    expect(screen.getByRole('navigation', { name: navLabels.mainNav })).toBeInTheDocument()
    // 콘텐츠 — Outlet
    expect(screen.getByTestId('outlet-content')).toBeInTheDocument()
  })

  it('isAuthenticated=false — bare Outlet만 렌더한다(사이드바·상단바 부재, E1 공개공유)', () => {
    useAuthStore.setState({ accessToken: null, user: null })
    renderShell()

    expect(screen.queryByRole('navigation', { name: navLabels.mainNav })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Atlas/ })).not.toBeInTheDocument()
    expect(screen.getByTestId('outlet-content')).toBeInTheDocument()
  })

  it('검색 버튼(aria-label="검색")이 정확히 1개다 — TopBar 단일 소유(Header 삭제로 중복 해소)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    expect(screen.getAllByRole('button', { name: navLabels.search })).toHaveLength(1)
  })

  it('도움말 버튼을 렌더하지 않는다 — ShortcutsHelpDialog는 RootLayout 소유라 onHelpClick 미배선(PR11서 버튼 이연, "?" 단축키는 RootLayout이 계속 처리)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    expect(screen.queryByRole('button', { name: '도움말' })).not.toBeInTheDocument()
  })

  it('isSystemAdmin=true — 관리 메뉴 nav가 기본 펼침으로 렌더된다 (FR4)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { ...BASE_USER, isSystemAdmin: true },
    })
    renderShell()

    expect(screen.getByRole('navigation', { name: navLabels.adminNav })).toBeInTheDocument()
  })
})
