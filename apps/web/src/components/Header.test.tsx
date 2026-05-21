// Header 컴포넌트 단위 테스트 — 사용자명 표시, 로그아웃 클릭, 리다이렉트 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { Header } from './Header'

// TanStack Router useNavigate 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
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
    user: { username: 'alice', email: 'alice@bts.local', authMethod: 'local', userId: 'u1' },
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
