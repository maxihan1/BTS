// ShellLayout(_shell) 컴포넌트 단위 테스트 — isAuthenticated 게이팅에 따른 크롬(TopBar+Sidebar) 렌더/억제 (FR-UX-06 PR11 Task 7)
import { render, screen, within, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { useActiveProject } from '@/hooks/use-active-project'
import type { WhoamiResponse } from '@/api/schemas'
import { navLabels } from '@/i18n/nav-labels'
import {
  dispatchContextAction,
  getRegisteredContexts,
} from '@/components/keyboard-shortcuts/useContextShortcuts'
import { useCommandPaletteStore } from '@/components/command-palette/useCommandPalette'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { useSidebarDrawer, MOBILE_MEDIA_QUERY } from '@/hooks/use-sidebar-drawer'
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
  // 같은 이유로 ProjectTree가 `useSearch({strict:false})`도 호출한다(FR-UX-08 FR7 —
  // `/issues?projectKey=` 검색 파라미터까지 활성 프로젝트 근거로 읽는다).
  useSearch: () => ({}),
  // ShellLayout 이 라우트 이동 시 모바일 드로어를 닫으려고 pathname 을 구독한다(F24).
  // selector 를 그대로 실행해 실제 훅과 같은 모양(select 콜백 적용 결과)을 돌려준다.
  useRouterState: <T,>({ select }: { select: (state: { location: { pathname: string } }) => T }): T =>
    select({ location: { pathname: '/' } }),
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

  it('전역 검색 입력창(aria-label="전역 검색")이 정확히 1개다 — TopBar 단일 소유', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    // F13 — 상단바 컨트롤이 버튼→입력창(`role="searchbox"`)으로 바뀌었다.
    // `검색`(navLabels.search)은 AQL 검색 페이지 제출 버튼 전용이라 셸에는 없어야 한다(계약 §2 이름 분리).
    expect(screen.getAllByRole('searchbox', { name: navLabels.globalSearch })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: navLabels.search })).toBeNull()
  })

  it('도움말 버튼을 렌더하지 않는다 — ShortcutsHelpDialog는 RootLayout 소유라 onHelpClick 미배선(PR11서 버튼 이연, "?" 단축키는 RootLayout이 계속 처리)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    expect(screen.queryByRole('button', { name: '도움말' })).not.toBeInTheDocument()
  })

  it('isSystemAdmin=true — 셸이 관리 진입점을 상단바 링크로 조립한다 (J9)', () => {
    // 셸 조립 관점의 단언이다 — 진입점이 사이드바에서 상단바로 옮겨갔어도 **조립된 화면에는
    // 여전히 있다**를 재는 것이 이 파일의 몫이다. 링크의 목적지·게이팅은 TopBar.test 가 본다.
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { ...BASE_USER, isSystemAdmin: true },
    })
    renderShell()

    expect(screen.getByRole('link', { name: navLabels.adminNav })).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: navLabels.adminNav })).not.toBeInTheDocument()
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
    // ★ 저장값은 **접근 가능 목록에 실재하는** 키여야 한다 (시드는 INFRA 하나).
    // 예전에는 여기가 'ATLAS'(목록에 없는 키)였는데, FR-UX-08 이 상단바에 ProjectSwitcher 를
    // 배선하면서 `useResolvedActiveProject` 가 전 페이지에서 돌게 됐다. 그 훅은 낡은 저장값을
    // 폴백 ③(첫 프로젝트)으로 **교정**하므로(FR-UX-07 스펙 E3) 'ATLAS'는 'INFRA'로 바뀐다 —
    // 스펙대로 맞는 동작이고, 이 테스트가 검증하려던 `useTrackActiveProject` 가드와는 무관한
    // 교란 요인이었다. 유효한 키를 쓰면 교정이 일어나지 않아 가드만 단독으로 검증된다.
    useActiveProject.setState({ activeProjectKey: 'INFRA' })
    mockParams = {}

    renderShell()

    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
  })

  it('FR-UX-08 FR11-b: 상단바 스위처가 낡은 저장값을 전 페이지에서 교정한다 (의도된 확장)', () => {
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    // 접근 불가한 저장값 — 삭제·아카이브·권한 회수된 프로젝트를 흉내낸다
    useActiveProject.setState({ activeProjectKey: 'GONE' })
    mockParams = {}

    renderShell()

    // ★ 조용한 행동 변화로 두지 않고 단언으로 못박는다. FR-UX-07 이전에는 이 교정이
    // `/issues`·`/search` 라우트에서만 일어났으나, 스위처가 상단바에 있어 이제 전 인증
    // 페이지에서 일어난다. 첫 방문자가 어느 페이지로 들어와도 활성 프로젝트가 앵커된다.
    expect(useActiveProject.getState().activeProjectKey).toBe('INFRA')
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

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-10 F11 — `.` (명령 팔레트 열기) 배선
  //
  // 셸이 `app-shell` 레이어의 등록 지점이므로 `.` 의 콜백도 여기 있어야 한다.
  // 등록만 하고 콜백을 빠뜨리면 키를 삼키고도(preventDefault) 팔레트가 안 열리는
  // ADR D-5-a 의 그 형태가 된다 — 아래 두 테스트가 그 구멍을 막는다.
  // ───────────────────────────────────────────────────────────────────────────

  it('인증 상태에서 `.` 액션이 명령 팔레트를 연다 (F11)', () => {
    // 팔레트 스토어는 모듈 전역 zustand — 실행 순서에 무관하게 닫힌 상태에서 출발시킨다
    useCommandPaletteStore.setState({ open: false })
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    renderShell()

    act(() => {
      dispatchContextAction({ layer: 'app-shell', action: { kind: 'open-command-palette' } })
    })

    expect(useCommandPaletteStore.getState().open).toBe(true)
  })

  it('미인증이면 `.` 액션이 무동작이다 — app-shell 미등록 (E5)', () => {
    useCommandPaletteStore.setState({ open: false })
    useAuthStore.setState({ accessToken: null, user: null })
    renderShell()

    act(() => {
      dispatchContextAction({ layer: 'app-shell', action: { kind: 'open-command-palette' } })
    })

    expect(useCommandPaletteStore.getState().open).toBe(false)
  })

  it('모바일에서 `[` 는 드로어를 여닫는다 — collapsed 는 건드리지 않는다 (F24)', () => {
    // 🛑 회귀 고정용이다. 토글 지점은 셋(상단바 버튼 · `[` 단축키 · 사이드바 하단 버튼)이고
    //    폭에 따른 대상 선택은 `useSidebarToggle` 한 곳이 소유해야 한다. 여기(=`[` 경로)만
    //    그 훅을 안 쓰고 판정을 인라인 복제하면 오늘은 동작이 같아 아무 테스트도 red 가
    //    되지 않고, 훗날 훅만 고쳐질 때 이 경로가 조용히 썩는다.
    //    구조 자체는 `hooks/__tests__/sidebar-collapsed-consumer-allowlist.test.ts` 가 지고,
    //    이 테스트는 그 구조가 만들어내는 **행동**을 고정한다. 둘은 짝이다.
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query: string) => ({
        matches: query === MOBILE_MEDIA_QUERY,
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    )
    try {
      // ★인증을 여기서 명시적으로 세운다 — `beforeEach` 가 `useAuthStore` 를 리셋하지 않아
      //   직전 테스트의 `accessToken: null` 이 새고, 그러면 `app-shell` 레이어가 아예
      //   등록되지 않아 dispatch 가 무동작이 된다(실측 — 이 단언이 그 이유로 한 번 red 였다).
      useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
      useSidebarDrawer.setState({ open: false })
      useSidebarCollapsed.setState({ collapsed: false })
      renderShell()

      act(() => {
        dispatchContextAction({ layer: 'app-shell', action: { kind: 'toggle-sidebar' } })
      })

      expect(useSidebarDrawer.getState().open).toBe(true)
      expect(useSidebarCollapsed.getState().collapsed).toBe(false)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('Escape 는 열린 드로어를 닫는다 (F24 · D7)', () => {
    // 백드롭이 본문을 시각적으로 덮는 순간 이 UI 는 모달로 읽힌다 — 포인터로 빠져나갈 수
    // 있으면 키보드로도 빠져나갈 수 있어야 한다. 새 전역 리스너를 달지 않고
    // `CONTEXT_SHORTCUTS` 항목으로 등록하는 것이 ADR D-2 의 정식 경로다. 레이어는
    // `sidebar-drawer` 다 — `app-shell` 에 두면 인증된 모든 화면에서 판별이 hit 이 되고
    // 파이프라인의 선제 `preventDefault()` 가 split view pane 의 Esc 닫기를 죽인다(리뷰 B1).
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    useSidebarDrawer.setState({ open: true })
    renderShell()

    act(() => {
      dispatchContextAction({ layer: 'sidebar-drawer', action: { kind: 'close-sidebar-drawer' } })
    })

    expect(useSidebarDrawer.getState().open).toBe(false)
  })

  it('★드로어가 닫혀 있으면 sidebar-drawer 레이어를 등록조차 하지 않는다 (Escape 를 빼앗지 않는다)', () => {
    // 🛑 「핸들러가 무동작이다」로는 부족하다. 레이어가 등록돼 있으면 판별이 hit 이 되고,
    //    파이프라인이 dispatch 전에 preventDefault 를 걸어 `usePaneEscapeClose` 가 죽는다.
    //    등록 자체가 없어야 안전하다 — 그래서 무동작이 아니라 **미등록**을 잰다.
    useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
    useSidebarDrawer.setState({ open: false })
    renderShell()

    expect(getRegisteredContexts().has('sidebar-drawer')).toBe(false)
    expect(getRegisteredContexts().has('app-shell')).toBe(true)
  })
})
