// InboxBell 컴포넌트 단위 테스트 — 미읽음 뱃지 + 그 자리 알림 팝오버 + 접근성 (FR-UX-03 D6/D7 · JN-1)
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { InboxBell } from './InboxBell'

// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 단위 테스트
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: { key: string }
    children: React.ReactNode
    className?: string
    onClick?: (e: React.MouseEvent) => void
  }) => (
    <a href={params ? to.replace('$key', params.key) : to} className={className} onClick={onClick}>
      {children}
    </a>
  ),
}))

const openIssueDetail = vi.fn()
vi.mock('@/components/issue/use-open-issue-detail', () => ({
  useOpenIssueDetail: () => openIssueDetail,
}))

/** unread-count API를 주어진 count로 응답하도록 MSW 핸들러를 등록한다 */
function setupUnreadCount(count: number) {
  server.use(
    http.get('/api/v1/users/me/inbox/unread-count', () =>
      HttpResponse.json({ data: { count } }),
    ),
  )
}

const UNREAD_ITEM = {
  id: '11111111-1111-4111-8111-111111111111',
  eventType: 'ISSUE_MENTIONED',
  issueKey: 'ATLAS-7',
  title: '홍길동님이 회원님을 멘션했습니다',
  body: null,
  actorUserId: null,
  commentId: null,
  readAt: null,
  archivedAt: null,
  createdAt: '2026-10-28T01:00:00Z',
}

const READ_ITEM = {
  ...UNREAD_ITEM,
  id: '22222222-2222-4222-8222-222222222222',
  issueKey: 'ATLAS-8',
  title: '이슈가 할당되었습니다',
  readAt: '2026-10-28T02:00:00Z',
}

/** 목록 API — tab 파라미터별로 응답하고, 받은 쿼리스트링을 기록한다 */
function setupInboxList() {
  const requests: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/users/me/inbox', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      const content = params.get('tab') === 'UNREAD' ? [UNREAD_ITEM] : [UNREAD_ITEM, READ_ITEM]
      return HttpResponse.json({
        content,
        totalElements: content.length,
        totalPages: 1,
        number: 0,
        size: 10,
      })
    }),
  )
  return requests
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
  beforeEach(() => {
    openIssueDetail.mockClear()
  })

  it('종은 페이지 이동 링크가 아니라 팝오버를 여는 버튼이다', async () => {
    setupUnreadCount(0)
    renderInboxBell()

    const bell = await screen.findByRole('button', { name: '알림' })
    expect(bell).toHaveAttribute('aria-haspopup', 'dialog')
    expect(screen.queryByRole('link', { name: '알림' })).not.toBeInTheDocument()
  })

  it('미읽음 카운트가 0이면 뱃지가 DOM에 존재하지 않는다', async () => {
    setupUnreadCount(0)
    renderInboxBell()

    await screen.findByRole('button', { name: '알림' })
    expect(screen.queryByTestId('inbox-unread-badge')).not.toBeInTheDocument()
  })

  it('미읽음 카운트가 1 이상이면 뱃지에 숫자가 표시된다', async () => {
    setupUnreadCount(5)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toHaveTextContent('5')
  })

  it('미읽음 카운트가 99이면 뱃지에 "99"가 표시된다', async () => {
    setupUnreadCount(99)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toHaveTextContent('99')
  })

  it('미읽음 카운트가 100 이상이면 뱃지에 "99+"가 표시된다', async () => {
    setupUnreadCount(100)
    renderInboxBell()

    const badge = await screen.findByTestId('inbox-unread-badge')
    expect(badge).toHaveTextContent('99+')
  })

  it('스크린리더용 텍스트(sr-only)가 뱃지 안에 존재한다', async () => {
    setupUnreadCount(3)
    renderInboxBell()

    await screen.findByTestId('inbox-unread-badge')
    expect(screen.getByText('읽지 않은 알림')).toBeInTheDocument()
  })

  it('종을 누르면 그 자리에 최근 알림 팝오버가 열린다 — 첫 화면은 전체', async () => {
    setupUnreadCount(1)
    const requests = setupInboxList()
    const user = userEvent.setup()
    renderInboxBell()

    await user.click(await screen.findByRole('button', { name: '알림' }))

    const panel = await screen.findByRole('dialog', { name: '알림' })
    expect(await within(panel).findByText(UNREAD_ITEM.title)).toBeInTheDocument()
    expect(within(panel).getByText(READ_ITEM.title)).toBeInTheDocument()
    expect(requests.at(-1)?.get('tab')).toBe('ALL')
    expect(requests.at(-1)?.get('size')).toBe('10')
  })

  it('「읽지 않은 항목만」을 켜면 안읽음 탭으로 다시 조회한다', async () => {
    setupUnreadCount(1)
    const requests = setupInboxList()
    const user = userEvent.setup()
    renderInboxBell()

    await user.click(await screen.findByRole('button', { name: '알림' }))
    const panel = await screen.findByRole('dialog', { name: '알림' })
    await within(panel).findByText(READ_ITEM.title)

    await user.click(within(panel).getByRole('switch', { name: '읽지 않은 항목만' }))

    await vi.waitFor(() => {
      expect(within(panel).queryByText(READ_ITEM.title)).not.toBeInTheDocument()
    })
    expect(within(panel).getByText(UNREAD_ITEM.title)).toBeInTheDocument()
    expect(requests.at(-1)?.get('tab')).toBe('UNREAD')
  })

  it('안읽음 항목을 누르면 읽음 처리하고 이슈를 열며 팝오버를 닫는다', async () => {
    setupUnreadCount(1)
    setupInboxList()
    const readCalls: unknown[] = []
    server.use(
      http.patch('/api/v1/users/me/inbox/:id/read', async ({ params, request }) => {
        readCalls.push({ id: params.id, body: await request.json() })
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderInboxBell()

    await user.click(await screen.findByRole('button', { name: '알림' }))
    const panel = await screen.findByRole('dialog', { name: '알림' })
    const row = await within(panel).findByRole('link', { name: new RegExp(UNREAD_ITEM.title) })
    expect(row).toHaveAttribute('href', '/issues/ATLAS-7')

    await user.click(row)

    expect(openIssueDetail).toHaveBeenCalledWith('ATLAS-7', expect.anything(), null)
    await vi.waitFor(() => {
      expect(readCalls).toEqual([{ id: UNREAD_ITEM.id, body: { read: true } }])
    })
    expect(screen.queryByRole('dialog', { name: '알림' })).not.toBeInTheDocument()
  })

  it('하단 「전체 보기」는 /inbox 로 간다', async () => {
    setupUnreadCount(0)
    setupInboxList()
    const user = userEvent.setup()
    renderInboxBell()

    await user.click(await screen.findByRole('button', { name: '알림' }))
    const panel = await screen.findByRole('dialog', { name: '알림' })

    expect(within(panel).getByRole('link', { name: '전체 보기' })).toHaveAttribute('href', '/inbox')
  })

  it('Esc 로 닫힌다', async () => {
    setupUnreadCount(0)
    setupInboxList()
    const user = userEvent.setup()
    renderInboxBell()

    await user.click(await screen.findByRole('button', { name: '알림' }))
    await screen.findByRole('dialog', { name: '알림' })

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog', { name: '알림' })).not.toBeInTheDocument()
  })
})
