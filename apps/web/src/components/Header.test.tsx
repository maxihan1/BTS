// Header 컴포넌트 단위 테스트 — 사용자명 표시, 로그아웃 클릭, 리다이렉트, 관리 nav isSystemAdmin 게이팅
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { Header } from './Header'

// TanStack Router useNavigate + Link 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
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
    user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false },
  })
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
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true },
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
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true },
    })
    renderHeader()

    const link = screen.getByRole('link', { name: '감사 로그' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin/audit-logs')
  })

  it('isSystemAdmin=true이면 관리 nav 안에 알림 정책 링크가 존재한다', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true },
    })
    renderHeader()

    const link = screen.getByRole('link', { name: '알림 정책' })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/admin/notification-policies')
  })

  it('isSystemAdmin=false이면 관리 메뉴 nav가 렌더되지 않는다', () => {
    // beforeEach에서 isSystemAdmin: false로 설정됨
    renderHeader()

    expect(screen.queryByRole('navigation', { name: '관리 메뉴' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '워크플로우 스킴' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '감사 로그' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '알림 정책' })).not.toBeInTheDocument()
  })

  it('isSystemAdmin=false이면 로그아웃 드롭다운은 정상 노출된다', () => {
    // beforeEach에서 isSystemAdmin: false로 설정됨
    renderHeader()

    // 로그아웃 버튼은 여전히 존재해야 함
    expect(screen.getByRole('button', { name: /alice/ })).toBeInTheDocument()
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
