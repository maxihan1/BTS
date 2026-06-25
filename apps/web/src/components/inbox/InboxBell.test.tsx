// InboxBell 컴포넌트 단위 테스트 — 미읽음 뱃지 표시 + Link + 접근성 (FR-UX-03 D6/D7)
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { InboxBell } from './InboxBell'

// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 단위 테스트
vi.mock('@tanstack/react-router', () => ({
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

/** unread-count API를 주어진 count로 응답하도록 MSW 핸들러를 등록한다 */
function setupUnreadCount(count: number) {
  server.use(
    http.get('/api/v1/users/me/inbox/unread-count', () =>
      HttpResponse.json({ data: { count } }),
    ),
  )
}

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function renderInboxBell() {
  const Wrapper = createWrapper()
  return render(<InboxBell />, { wrapper: Wrapper })
}

describe('InboxBell', () => {
  it('/inbox 경로로 이동하는 Link를 렌더한다', async () => {
    setupUnreadCount(0)
    renderInboxBell()

    // Link(a 태그)가 /inbox 경로를 가진다
    const link = await screen.findByRole('link', { name: /알림 보관함 열기/i })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/inbox')
  })

  it('aria-label이 인박스 라벨 텍스트와 일치한다', async () => {
    setupUnreadCount(0)
    renderInboxBell()

    const link = await screen.findByRole('link', { name: '알림 보관함 열기' })
    expect(link).toBeInTheDocument()
  })

  it('미읽음 카운트가 0이면 뱃지가 DOM에 존재하지 않는다', async () => {
    setupUnreadCount(0)
    renderInboxBell()

    // 먼저 링크가 렌더될 때까지 대기
    await screen.findByRole('link', { name: /알림 보관함 열기/i })

    // 뱃지(숫자 텍스트)가 없어야 한다
    expect(screen.queryByTestId('inbox-unread-badge')).not.toBeInTheDocument()
  })

  it('미읽음 카운트가 1 이상이면 뱃지에 숫자가 표시된다', async () => {
    setupUnreadCount(5)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toBeInTheDocument()
    expect(badge).toHaveTextContent('5')
  })

  it('미읽음 카운트가 99이면 뱃지에 "99"가 표시된다', async () => {
    setupUnreadCount(99)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toHaveTextContent('99')
  })

  it('미읽음 카운트가 100이면 뱃지에 "99+"가 표시된다', async () => {
    setupUnreadCount(100)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toHaveTextContent('99+')
  })

  it('미읽음 카운트가 999이면 뱃지에 "99+"가 표시된다', async () => {
    setupUnreadCount(999)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toHaveTextContent('99+')
  })

  it('스크린리더용 텍스트(sr-only)가 뱃지 안에 존재한다', async () => {
    setupUnreadCount(3)
    renderInboxBell()

    await screen.findByTestId('inbox-unread-badge')

    // "읽지 않은 알림" 스크린리더 텍스트가 존재해야 한다
    const srText = screen.getByText('읽지 않은 알림')
    expect(srText).toBeInTheDocument()
  })
})
