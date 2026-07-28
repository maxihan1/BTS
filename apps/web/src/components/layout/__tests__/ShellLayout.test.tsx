// ShellLayout(_shell) 컴포넌트 단위 테스트 — isAuthenticated 게이팅에 따른 크롬(TopBar+Sidebar) 렌더/억제 (FR-UX-06 PR11 Task 7)
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { useActiveProject } from '@/hooks/use-active-project'
import type { WhoamiResponse } from '@/api/schemas'
import { navLabels } from '@/i18n/nav-labels'
import { ShellLayout } from '../ShellLayout'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router 모킹 — Outlet은 콘텐츠 마커로 대체, Link/useNavigate는 TopBar·Sidebar가 내부에서
// 소비한다(Sidebar.test.tsx·TopBar.test.tsx 동일 패턴, 라우터 컨텍스트 없이 isolation 렌더).
// ─────────────────────────────────────────────────────────────────────────────

const mockNavigate = vi.fn()

/**
 * useParams 반환값 — 테스트마다 갈아끼운다.
 * FR-UX-07 `useTrackActiveProject`가 `/projects/$projectKey/*` 경로 키를 여기서 읽는다.
 */
let mockParams: Record<string, string | undefined> = {}
vi.mock('@tanstack/react-router', () => ({
  Outlet: () => <div data-testid="outlet-content">content</div>,
  useNavigate: () => mockNavigate,
  // Sidebar가 배선하는 ProjectTree(FR-UX-06 PR12)가 useParams({strict:false})를 호출하므로
  // 라우터 컨텍스트 없는 isolation 렌더에서도 크래시하지 않도록 빈 파라미터로 모킹한다
  // (Sidebar.test.tsx 동일 패턴, 셸 랜드마크 계약과 무관).
  useParams: () => mockParams,
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
  mockParams = {}
  // zustand 스토어는 모듈 전역 싱글턴 — 테스트 간 활성 프로젝트가 새지 않게 리셋
  useActiveProject.setState({ activeProjectKey: null })
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

  it('isAuthenticated=true — banner(header)·main·complementary(aside) 랜드마크가 각 1개다 (C3)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    expect(screen.getAllByRole('banner')).toHaveLength(1)
    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(screen.getAllByRole('complementary')).toHaveLength(1)
  })

  it('isAuthenticated=true — header/aside가 main 밖의 형제다(main 안에 중첩되지 않는다) (C3)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    const main = screen.getByRole('main')
    expect(within(main).queryByRole('banner')).not.toBeInTheDocument()
    expect(within(main).queryByRole('complementary')).not.toBeInTheDocument()
    // 콘텐츠(Outlet)는 main 안에 있어야 한다
    expect(within(main).getByTestId('outlet-content')).toBeInTheDocument()
  })

  it('isAuthenticated=false — main 랜드마크가 1개다(bare Outlet 아님, D-D) (C3)', () => {
    useAuthStore.setState({ accessToken: null, user: null })
    renderShell()

    expect(screen.getAllByRole('main')).toHaveLength(1)
    expect(within(screen.getByRole('main')).getByTestId('outlet-content')).toBeInTheDocument()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-07 — 활성 프로젝트 기록기 배선 (S4 / 리뷰 BLOCKER B1)
  // ───────────────────────────────────────────────────────────────────────────

  it('인증 상태에서 $projectKey 경로 파라미터를 활성 프로젝트로 기록한다 (S4)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    mockParams = { projectKey: 'INFRA' }

    renderShell()

    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
  })

  it('경로 파라미터가 없으면 활성 프로젝트를 건드리지 않는다 (/issues 등)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = {}

    renderShell()

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('미인증이면 경로 파라미터가 있어도 기록하지 않는다 (공개 공유 라우트)', () => {
    useAuthStore.setState({ accessToken: null, user: null })
    mockParams = { projectKey: 'INFRA' }

    renderShell()

    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })
})
