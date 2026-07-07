// Header 컴포넌트 단위 테스트 — 사용자명 표시, 로그아웃 클릭, 리다이렉트, 관리 nav isSystemAdmin 게이팅, InboxBell 렌더, 검색 진입점
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { oooLabels } from '@/i18n/ooo-labels'
import { Header } from './Header'

// TanStack Router useNavigate + Link 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
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

// Avatar 컴포넌트 mock — jsdom URL.createObjectURL 미구현 회피(ProfileForm.test.tsx 동일 패턴).
// blob fetch/objectURL 내부 동작은 avatar.test.tsx가 이미 검증하므로, Header 자체 로직(props 전달 +
// displayName/username 폴백)에 테스트를 집중한다.
vi.mock('@/components/ui/avatar', () => ({
  Avatar: ({
    avatarUrl,
    displayName,
    username,
    cacheBust,
  }: {
    avatarUrl?: string | null
    displayName?: string | null
    username?: string | null
    cacheBust?: number
  }) => (
    <div
      data-testid="header-avatar-mock"
      data-avatar-url={avatarUrl ?? ''}
      data-display-name={displayName ?? ''}
      data-username={username ?? ''}
      data-cache-bust={cacheBust ?? ''}
    />
  ),
}))

// StatusModal mock — Header가 open prop으로 모달을 여는지에 집중(모달 내부는 StatusModal.test가 검증).
vi.mock('@/components/status/StatusModal', () => ({
  StatusModal: ({ open }: { open: boolean }) =>
    open ? <div data-testid="status-modal-mock" /> : null,
}))

// OooModal mock — Header가 open prop으로 모달을 여는지에 집중(모달 내부는 OooModal.test가 검증).
vi.mock('@/components/ooo/OooModal', () => ({
  OooModal: ({ open }: { open: boolean }) =>
    open ? <div data-testid="ooo-modal-mock" /> : null,
}))

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function renderHeader() {
  const Wrapper = createWrapper()
  return render(<Header />, { wrapper: Wrapper })
}

beforeEach(() => {
  mockNavigate.mockReset()
  useAuthStore.setState({
    accessToken: 'test-token',
    user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
  })
  // InboxBell이 사용하는 unread-count API 기본 응답 등록
  server.use(
    http.get('/api/v1/users/me/inbox/unread-count', () =>
      HttpResponse.json({ data: { count: 0 } }),
    ),
  )
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('Header', () => {
  it('authStore의 user.username을 트리거 버튼에 표시한다', () => {
    renderHeader()

    // 사용자명이 헤더에 표시되어야 함
    expect(screen.getByRole('button', { name: /alice/ })).toBeInTheDocument()
  })

  it('계정 트리거 안에 Avatar 컴포넌트가 렌더된다 (FR-PR-01 D6 Task 8)', () => {
    renderHeader()

    expect(screen.getByTestId('header-avatar-mock')).toBeInTheDocument()
  })

  it('Avatar에 authStore.avatarVersion을 cacheBust로 전달한다 (아바타 교체 재fetch 회귀 방지)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
      avatarVersion: 3,
    })
    renderHeader()

    expect(screen.getByTestId('header-avatar-mock')).toHaveAttribute('data-cache-bust', '3')
  })

  it('displayName이 있으면 계정 트리거에 username 대신 displayName이 표시된다 (FR-PR-01 D6 Task 8)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice',
        email: 'alice@bts.local',
        authMethod: 'local',
        userId: 'u1',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
        displayName: '김앨리스',
        avatarUrl: null,
      },
    })
    renderHeader()

    expect(screen.getByRole('button', { name: '김앨리스 계정 메뉴' })).toBeInTheDocument()
  })

  it('활성 상태가 있으면 아바타 옆에 상태 이모지 배지를 렌더한다 (FR-PR-02)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1',
        mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false,
        statusEmoji: '🌴', statusText: '휴가 중',
      },
    })
    renderHeader()

    expect(screen.getByText('🌴')).toBeInTheDocument()
  })

  it('활성 상태가 없으면 상태 배지를 렌더하지 않는다 (FR-PR-02)', () => {
    renderHeader()

    expect(screen.queryByText('🌴')).toBeNull()
  })

  it('계정 메뉴의 "상태 설정" 클릭 시 StatusModal이 열린다 (FR-PR-02)', async () => {
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: /계정 메뉴/ }))
    await user.click(await screen.findByText('상태 설정'))

    expect(screen.getByTestId('status-modal-mock')).toBeInTheDocument()
  })

  it('계정 메뉴의 "부재중 설정" 클릭 시 OooModal이 열린다 (FR-PR-03)', async () => {
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: /계정 메뉴/ }))
    await user.click(await screen.findByText(oooLabels.accountMenuItem))

    expect(screen.getByTestId('ooo-modal-mock')).toBeInTheDocument()
  })

  it('whoami oooActive:true이면 계정 메뉴 영역에 부재중 배지를 표시한다 (FR-PR-03)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1',
        mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false,
        oooActive: true, oooUntil: new Date(2026, 6, 14, 12, 0, 0).toISOString(),
      },
    })
    renderHeader()

    expect(screen.getByText(oooLabels.headerBadge)).toBeInTheDocument()
  })

  it('whoami oooActive:false(미설정)이면 부재중 배지를 표시하지 않는다 (FR-PR-03)', () => {
    // beforeEach 기본 상태 — oooActive 미설정
    renderHeader()

    expect(screen.queryByText(oooLabels.headerBadge)).toBeNull()
  })

  it('부재중 배지에 복귀 예정일이 tooltip(title)으로 표시된다 (FR-PR-03)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1',
        mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false,
        oooActive: true, oooUntil: new Date(2026, 6, 14, 12, 0, 0).toISOString(),
      },
    })
    renderHeader()

    expect(screen.getByText(oooLabels.headerBadge)).toHaveAttribute(
      'title',
      expect.stringContaining('2026-07-14'),
    )
  })

  it('상태 배지와 부재중 배지가 동시에 표시된다 — 시각 공존 (C2, FR-PR-03)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1',
        mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false,
        statusEmoji: '🌴', statusText: '휴가 중',
        oooActive: true, oooUntil: new Date(2026, 6, 14, 12, 0, 0).toISOString(),
      },
    })
    renderHeader()

    expect(screen.getByText('🌴')).toBeInTheDocument()
    expect(screen.getByText(oooLabels.headerBadge)).toBeInTheDocument()
  })

  it('displayName이 없으면 username으로 폴백해 계정 트리거에 표시된다 (FR-PR-01 D6 Task 8)', () => {
    // beforeEach 기본 상태 — displayName/avatarUrl 미설정
    renderHeader()

    expect(screen.getByRole('button', { name: 'alice 계정 메뉴' })).toBeInTheDocument()
  })

  it('드롭다운 메뉴 안에 프로필 링크가 존재한다 (FR-PR-01 D6 Task 8)', async () => {
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: /alice/ }))

    const link = await screen.findByRole('link', { name: '프로필' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/settings/profile')
  })

  it('로그아웃 메뉴 항목 클릭 시 useLogoutMutation.mutate()가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
    )

    renderHeader()

    // 드롭다운 열기
    await user.click(screen.getByRole('button', { name: /alice/ }))

    // 로그아웃 항목 클릭
    const logoutItem = await screen.findByRole('menuitem', { name: '로그아웃' })
    await user.click(logoutItem)

    // mutate 호출 결과로 clearSession이 실행되어 accessToken이 null이어야 함
    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBeNull()
    })
  })

  it('로그아웃 성공 후 /login으로 navigate가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })),
    )

    renderHeader()

    await user.click(screen.getByRole('button', { name: /alice/ }))

    const logoutItem = await screen.findByRole('menuitem', { name: '로그아웃' })
    await user.click(logoutItem)

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/login' })
    })
  })

  it('isSystemAdmin=true이면 관리 nav 안에 워크플로우 스킴 링크가 존재한다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true, mfaEnrollmentRequired: false },
    })
    renderHeader()

    // 「워크플로우 스킴」 링크가 존재해야 함
    const link = screen.getByRole('link', { name: '워크플로우 스킴' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin/workflow-schemes')
  })

  it('isSystemAdmin=true이면 관리 nav 안에 감사 로그 링크가 존재한다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true, mfaEnrollmentRequired: false },
    })
    renderHeader()

    const link = screen.getByRole('link', { name: '감사 로그' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin/audit-logs')
  })

  it('isSystemAdmin=true이면 관리 nav 안에 알림 정책 링크가 존재한다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true, mfaEnrollmentRequired: false },
    })
    renderHeader()

    const link = screen.getByRole('link', { name: '알림 정책' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin/notification-policies')
  })

  it('isSystemAdmin=true이면 관리 nav 안에 Webhook 링크가 존재한다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true, mfaEnrollmentRequired: false },
    })
    renderHeader()

    const link = screen.getByRole('link', { name: 'Webhook' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin/webhooks')
  })

  it('isSystemAdmin=false이면 관리 메뉴 nav가 렌더되지 않는다', () => {
    // beforeEach에서 isSystemAdmin: false로 설정됨
    renderHeader()

    expect(screen.queryByRole('navigation', { name: '관리 메뉴' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '워크플로우 스킴' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '감사 로그' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '알림 정책' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Webhook' })).not.toBeInTheDocument()
  })

  it('드롭다운 메뉴 안에 Personal Access Token 링크가 존재한다 (FR-API-04 Task 8)', async () => {
    const user = userEvent.setup()
    renderHeader()

    await user.click(screen.getByRole('button', { name: /alice/ }))

    const link = await screen.findByRole('link', { name: 'Personal Access Token' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/settings/pats')
  })

  it('isSystemAdmin=false이면 로그아웃 드롭다운은 정상 노출된다', () => {
    // beforeEach에서 isSystemAdmin: false로 설정됨
    renderHeader()

    // 로그아웃 버튼은 여전히 존재해야 함
    expect(screen.getByRole('button', { name: /alice/ })).toBeInTheDocument()
  })

  it('메인 네비게이션에 대시보드 링크(to="/dashboards")가 노출된다', () => {
    // beforeEach에서 isSystemAdmin: false로 설정됨 — 모든 사용자에게 노출
    renderHeader()

    const link = screen.getByRole('link', { name: '대시보드' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/dashboards')
  })

  it('검색 버튼 — aria-label="검색"인 버튼이 Header 안에 렌더된다 (FR-SR-02 Task-6)', () => {
    renderHeader()

    const searchBtn = screen.getByRole('button', { name: '검색' })
    expect(searchBtn).toBeInTheDocument()
  })

  it('InboxBell — 알림 보관함 열기 링크(href=/inbox)가 Header 안에 렌더된다', async () => {
    renderHeader()

    // InboxBell이 /inbox 링크로 렌더되어야 한다
    const bellLink = await screen.findByRole('link', { name: '알림 보관함 열기' })
    expect(bellLink).toBeInTheDocument()
    expect(bellLink).toHaveAttribute('href', '/inbox')
  })

  it('서버 로그아웃 실패(500) 시에도 세션이 정리되고 /login으로 이동한다', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 500 })),
    )

    renderHeader()

    await user.click(screen.getByRole('button', { name: /alice/ }))

    const logoutItem = await screen.findByRole('menuitem', { name: '로그아웃' })
    await user.click(logoutItem)

    // onSettled에서 clearSession 보장 — 서버 실패여도 세션 정리
    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBeNull()
    })

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/login' })
    })
  })
})
