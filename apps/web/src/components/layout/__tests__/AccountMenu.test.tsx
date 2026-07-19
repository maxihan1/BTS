// AccountMenu 컴포넌트 단위 테스트 — 계정 라벨 폴백, 아바타 cacheBust, 상태/부재중 배지, 로그아웃 (Header.test.tsx 계정 로직 이관, FR-UX-06 PR11 게이트2 C1)
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { oooLabels } from '@/i18n/ooo-labels'
import { AccountMenu } from '../AccountMenu'

// TanStack Router useNavigate + Link 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능 (Header.test.tsx 동일 패턴)
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  Link: ({ to, children }: { to: string; children: React.ReactNode }) => <a href={to}>{children}</a>,
}))

// Avatar 컴포넌트 mock — jsdom URL.createObjectURL 미구현 회피(Header.test.tsx 동일 패턴).
// blob fetch/objectURL 내부 동작은 avatar.test.tsx가 이미 검증하므로, AccountMenu 자체 로직(props
// 전달 + displayName/username 폴백)에 테스트를 집중한다.
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
      data-testid="account-menu-avatar-mock"
      data-avatar-url={avatarUrl ?? ''}
      data-display-name={displayName ?? ''}
      data-username={username ?? ''}
      data-cache-bust={cacheBust ?? ''}
    />
  ),
}))

// StatusModal/OooModal mock — AccountMenu가 open prop으로 모달을 여는지에 집중(모달 내부는 각자 테스트가 검증)
vi.mock('@/components/status/StatusModal', () => ({
  StatusModal: ({ open }: { open: boolean }) =>
    open ? <div data-testid="status-modal-mock" /> : null,
}))
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

function renderAccountMenu() {
  const Wrapper = createWrapper()
  return render(<AccountMenu />, { wrapper: Wrapper })
}

beforeEach(() => {
  mockNavigate.mockReset()
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
    },
  })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('AccountMenu', () => {
  it('displayName이 없으면 username으로 폴백해 트리거 aria-label에 표시된다', () => {
    renderAccountMenu()

    expect(screen.getByRole('button', { name: /alice 계정 메뉴/ })).toBeInTheDocument()
  })

  it('displayName이 있으면 username 대신 displayName이 트리거에 표시된다', () => {
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
      },
    })
    renderAccountMenu()

    expect(screen.getByRole('button', { name: '김앨리스 계정 메뉴' })).toBeInTheDocument()
  })

  it('Avatar에 authStore.avatarVersion을 cacheBust로 전달한다 (아바타 교체 재fetch 회귀 방지)', () => {
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
      },
      avatarVersion: 3,
    })
    renderAccountMenu()

    expect(screen.getByTestId('account-menu-avatar-mock')).toHaveAttribute('data-cache-bust', '3')
  })

  it('활성 상태가 있으면 아바타 옆에 상태 이모지 배지를 렌더한다 (FR-PR-02)', () => {
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
        statusEmoji: '🌴',
        statusText: '휴가 중',
      },
    })
    renderAccountMenu()

    expect(screen.getByText('🌴')).toBeInTheDocument()
  })

  it('활성 상태가 없으면 상태 배지를 렌더하지 않는다 (FR-PR-02)', () => {
    renderAccountMenu()

    expect(screen.queryByText('🌴')).toBeNull()
  })

  it('상태 배지와 부재중 배지가 동시에 표시된다 — 시각 공존 (FR-PR-03)', () => {
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
        statusEmoji: '🌴',
        statusText: '휴가 중',
        oooActive: true,
        oooUntil: new Date(2026, 6, 14, 12, 0, 0).toISOString(),
      },
    })
    renderAccountMenu()

    expect(screen.getByText('🌴')).toBeInTheDocument()
    expect(screen.getByText(oooLabels.headerBadge)).toBeInTheDocument()
  })

  it('계정 메뉴의 "상태 설정" 클릭 시 StatusModal이 열린다 (FR-PR-02)', async () => {
    const user = userEvent.setup()
    renderAccountMenu()

    await user.click(screen.getByRole('button', { name: /계정 메뉴/ }))
    await user.click(await screen.findByText('상태 설정'))

    expect(screen.getByTestId('status-modal-mock')).toBeInTheDocument()
  })

  it('계정 메뉴의 "부재중 설정" 클릭 시 OooModal이 열린다 (FR-PR-03)', async () => {
    const user = userEvent.setup()
    renderAccountMenu()

    await user.click(screen.getByRole('button', { name: /계정 메뉴/ }))
    await user.click(await screen.findByText(oooLabels.accountMenuItem))

    expect(screen.getByTestId('ooo-modal-mock')).toBeInTheDocument()
  })

  it('로그아웃 메뉴 항목 클릭 시 useLogoutMutation.mutate()가 호출되고 세션이 정리된다', async () => {
    const user = userEvent.setup()
    server.use(http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))
    renderAccountMenu()

    await user.click(screen.getByRole('button', { name: /alice/ }))
    const logoutItem = await screen.findByRole('menuitem', { name: '로그아웃' })
    await user.click(logoutItem)

    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBeNull()
    })
  })

  it('로그아웃 성공 후 /login으로 navigate가 호출된다', async () => {
    const user = userEvent.setup()
    server.use(http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))
    renderAccountMenu()

    await user.click(screen.getByRole('button', { name: /alice/ }))
    const logoutItem = await screen.findByRole('menuitem', { name: '로그아웃' })
    await user.click(logoutItem)

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/login' })
    })
  })

  it('서버 로그아웃 실패(500) 시에도 세션이 정리되고 /login으로 이동한다', async () => {
    const user = userEvent.setup()
    server.use(http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 500 })))
    renderAccountMenu()

    await user.click(screen.getByRole('button', { name: /alice/ }))
    const logoutItem = await screen.findByRole('menuitem', { name: '로그아웃' })
    await user.click(logoutItem)

    await waitFor(() => {
      expect(useAuthStore.getState().accessToken).toBeNull()
    })
    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/login' })
    })
  })
})
