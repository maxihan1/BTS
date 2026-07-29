// ShellLayout(_shell) 컴포넌트 단위 테스트 — isAuthenticated 게이팅에 따른 크롬(TopBar+Sidebar) 렌더/억제 (FR-UX-06 PR11 Task 7)
import { render, screen, within, waitFor, act } from '@testing-library/react'
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

/**
 * @param seed 마운트 시점부터 `['projects', false]` 캐시를 선주입한다(기본값). `useProjects`의
 *   staleTime이 30초라 선주입하면 재조회가 안 돌아 네트워크 요청 여부를 관측하는 테스트에서는
 *   그 축을 가려 버린다(CR3 뮤테이션 실측 확인) — 요청 카운터를 단언하는 테스트는 반드시
 *   `seed:false`로 렌더해야 한다.
 */
function makeWrapper(seed = true) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  // FR-UX-07 CR3 — 기록기(useTrackActiveProject)와 사이드바(ProjectTree)가 같은 queryKey를
  // 구독한다. 선주입하면 인증 분기 테스트는 네트워크 왕복 없이 캐시에서 바로 목록을 받는다.
  if (seed) {
    qc.setQueryData(
      ['projects', false],
      [{ id: '11111111-1111-4111-8111-111111111111', key: 'INFRA', name: 'Infra' }],
    )
  }
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderShell(seed = true) {
  return render(<ShellLayout />, { wrapper: makeWrapper(seed) })
}

beforeEach(() => {
  window.localStorage.clear()
  mockParams = {}
  // zustand 스토어는 모듈 전역 싱글턴 — 테스트 간 활성 프로젝트가 새지 않게 리셋
  useActiveProject.setState({ activeProjectKey: null })
  // Sidebar(FavoritesMenu)·TopBar(InboxBell)가 마운트 시 조회하는 엔드포인트.
  // `/api/v1/projects` 핸들러는 여기 두지 않는다 — makeWrapper가 캐시를 선주입하므로
  // 공용으로 둘 필요가 없고, 미인증 테스트까지 덮으면 CR3 회귀 가드가 무력화된다.
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

  it('인증 상태에서 $projectKey 경로 파라미터를 활성 프로젝트로 기록한다 (S4)', async () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    mockParams = { projectKey: 'INFRA' }
    // FR-UX-07 CR3 — 기록기가 접근 가능 목록과 대조한다. makeWrapper가 캐시를 이미
    // 선주입해 이 핸들러가 실제로 호출되진 않지만, 이 테스트가 프로젝트 목록에
    // 의존한다는 사실을 코드로 남겨 둔다(S4 스코프로 한정).
    server.use(
      http.get('/api/v1/projects', () =>
        HttpResponse.json({
          data: [{ id: '11111111-1111-4111-8111-111111111111', key: 'INFRA', name: 'Infra' }],
        }),
      ),
    )

    renderShell()

    // 목록 도착 후 기록된다 — CR3 가드가 접근 가능 목록과 대조하기 때문
    await waitFor(() => expect(useActiveProject.getState().activeProjectKey).toBe('INFRA'))
  })

  it('경로 파라미터가 없으면 활성 프로젝트를 건드리지 않는다 (/issues 등)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    useActiveProject.setState({ activeProjectKey: 'ATLAS' })
    mockParams = {}

    renderShell()

    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('미인증이면 목록이 이미 있어도 기록하지 않는다 (공개 공유 라우트, effect 가드)', () => {
    useAuthStore.setState({ accessToken: null, user: null })
    mockParams = { projectKey: 'INFRA' }

    // ★ 선주입(기본 seed:true) — makeWrapper 가 캐시에 INFRA 를 이미 채워도(isKnownProject
    // 우회로 봉쇄) 미인증 분기에서는 기록되지 않아야 한다. `useTrackActiveProject` 의
    // `if (!enabled) return` 가드를 직접 검증한다.
    renderShell()

    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('인증 상태에서는 /api/v1/projects 요청이 실제로 나간다 (양성 대조군)', async () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    let calls = 0
    server.use(
      http.get('/api/v1/projects', () => {
        calls += 1
        return HttpResponse.json({ data: [] })
      }),
    )

    // seed:false — 캐시가 비어 있어야 실제 네트워크 요청이 발생한다. 이 카운터가 0보다 큰
    // 값을 낼 수 있음을 먼저 확인해야, 아래 미인증 테스트의 "0"이 판별식 고장이 아니라
    // 진짜 요청 부재라고 믿을 수 있다.
    render(<ShellLayout />, { wrapper: makeWrapper(false) })

    await waitFor(() => expect(calls).toBeGreaterThan(0))
  })

  it('미인증이면 인증 API 요청 자체가 나가지 않는다 (공개 공유 라우트, EC-11 — enabled 관통 가드)', async () => {
    useAuthStore.setState({ accessToken: null, user: null })
    mockParams = { projectKey: 'INFRA' }

    // CR3 회귀 가드 — useTrackActiveProject 가 enabled=false 를 useProjects 까지
    // 관통시키지 못하면 미인증 셸에서도 /api/v1/projects 가 나간다. 실제 백엔드라면
    // 401 → apiFetch 자동 refresh → clearSession 으로 이어져 공개 공유 라우트의
    // 세션이 지워진다(EC-11 — apiFetch/인증 훅/인증 store 를 쓰지 않는다는 계약과 충돌).
    let calls = 0
    server.use(
      http.get('/api/v1/projects', () => {
        calls += 1
        return HttpResponse.json({ data: [] })
      }),
    )

    // ★ 미주입(seed:false) — 캐시가 신선하면 enabled 값과 무관하게 재조회가 안 돌아
    // 이 축을 가린다(CR3 뮤테이션 실측 확인, use-track-active-project.test.tsx 동일 사유).
    render(<ShellLayout />, { wrapper: makeWrapper(false) })
    await screen.findByTestId('outlet-content')
    // 마운트 effect 이후 마이크로태스크까지 흘려보낸다 — 동기 단언이나 waitFor(=>toBe(0))는
    // 결함을 되주입해도 통과한다(요청은 마운트 이후 마이크로태스크에 나가고, waitFor는 첫
    // 체크에서 즉시 성공한다).
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0))
    })

    expect(calls).toBe(0)
    expect(useActiveProject.getState().activeProjectKey).toBeNull()
  })

  it('미인증 + refresh 성공 세계에서도 크롬이 새지 않는다 (EC-11 — refresh 성공이 더 나쁜 결과다)', async () => {
    useAuthStore.setState({ accessToken: null, user: null })
    mockParams = { projectKey: 'INFRA' }

    // CR3 게이팅이 풀리면 미인증 요청이 401을 받고(실제 백엔드 동작을 흉내낸다), apiFetch가
    // 자동으로 /refresh를 호출한다. 여기서는 그 refresh가 "성공"하는(더 나쁜) 세계를
    // 흉내낸다 — 성공하면 accessToken이 채워져 isAuthenticated가 true로 뒤집히고
    // 셸 크롬(TopBar/Sidebar)이 새어나온다. 현재 구현(CR3 게이팅)에서는 요청 자체가
    // 나가지 않으므로 이 핸들러들은 호출되지 않고 크롬도 나타나지 않는다.
    // seed:false — 위와 같은 이유로 캐시를 비워야 이 시나리오가 실제로 exercise 된다.
    server.use(
      http.get('/api/v1/projects', () => new HttpResponse(null, { status: 401 })),
      http.post('/api/v1/auth/refresh', () => HttpResponse.json({ access_token: 'leaked-token' })),
    )

    render(<ShellLayout />, { wrapper: makeWrapper(false) })
    await screen.findByTestId('outlet-content')
    await act(async () => {
      await new Promise((r) => setTimeout(r, 50))
    })

    expect(screen.queryByRole('banner')).not.toBeInTheDocument()
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
  })
})
