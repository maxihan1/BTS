// TopBar 컴포넌트 단위 테스트 — 토글/로고/전역 검색/만들기/알림/도움말/설정/계정 드롭다운 계약 (FR-UX-06 PR11 Task 6, FR-UX-12 F13)
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { navLabels } from '@/i18n/nav-labels'
import { issueCreateStrings } from '@/i18n/ko'
import { inboxLabels } from '@/i18n/inbox-labels'
import { TopBar } from '../TopBar'

// TanStack Router useNavigate + Link 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능 (Header.test.tsx 동일 패턴)
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  // TopBar 가 배선하는 ProjectSwitcher(FR-UX-08 F12)가 URL 의 projectKey 를 읽는다.
  // 이 파일의 계약(상단바 요소 구성·라벨)과 무관하므로 빈 값으로 모킹한다 —
  // §1-B 착지점 분기 자체는 ProjectSwitcher.test.tsx 가 실 memory router 로 검증한다.
  useParams: () => ({}),
  useSearch: () => ({}),
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

// 사이드바 접힘 상태 훅 모킹 — 테스트별로 collapsed/toggle을 자유롭게 제어
const mockToggle = vi.fn()
const mockUseSidebarCollapsed = vi.fn(() => ({ collapsed: false, toggle: mockToggle }))
vi.mock('@/hooks/use-sidebar-collapsed', () => ({
  useSidebarCollapsed: () => mockUseSidebarCollapsed(),
}))

// Avatar 컴포넌트 mock — jsdom URL.createObjectURL 미구현 회피(Header.test.tsx 동일 패턴)
vi.mock('@/components/ui/avatar', () => ({
  Avatar: () => <div data-testid="topbar-avatar-mock" />,
}))

// StatusModal/OooModal mock — TopBar가 open prop으로 여는지만 확인(모달 내부는 각자 테스트가 검증)
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

function renderTopBar(props: { onHelpClick?: () => void } = {}) {
  const Wrapper = createWrapper()
  return render(<TopBar {...props} />, { wrapper: Wrapper })
}

beforeEach(() => {
  mockNavigate.mockReset()
  mockToggle.mockReset()
  mockUseSidebarCollapsed.mockReset()
  mockUseSidebarCollapsed.mockReturnValue({ collapsed: false, toggle: mockToggle })
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

describe('TopBar', () => {
  it('로고가 /dashboards(홈)로 연결된다', () => {
    renderTopBar()

    expect(screen.getByRole('link', { name: /Atlas/ })).toHaveAttribute('href', '/dashboards')
  })

  it('F13 — 전역 검색 입력창이 정확히 1개고 검색 버튼은 없다', () => {
    renderTopBar()

    expect(screen.getAllByRole('searchbox', { name: navLabels.globalSearch })).toHaveLength(1)
    // 계약 §2 「`검색` 이름 분리」 — 상단바는 `전역 검색`, `검색` 은 AQL 페이지 제출 버튼 전용
    expect(screen.queryByRole('button', { name: navLabels.search })).toBeNull()
  })

  it('F13 S1 — 자유 텍스트 Enter 는 text ~ 로 감싸 /search 로 보낸다 (projectKey 미첨부)', async () => {
    const user = userEvent.setup()
    renderTopBar()

    const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
    await user.type(box, '로그인 버그{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/search', search: { q: 'text ~ "로그인 버그"' } })
  })

  it('F13 S2 — 이슈키 Enter 는 이슈 상세로 보낸다 (대소문자 무관)', async () => {
    const user = userEvent.setup()
    renderTopBar()

    await user.type(screen.getByRole('searchbox', { name: navLabels.globalSearch }), 'atlas-42{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/$key', params: { key: 'ATLAS-42' } })
  })

  it('F13 S3 — AQL 문법은 원문 그대로 보낸다', async () => {
    const user = userEvent.setup()
    renderTopBar()

    await user.type(screen.getByRole('searchbox', { name: navLabels.globalSearch }), 'status = "열림"{Enter}')

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/search', search: { q: 'status = "열림"' } })
  })

  it('F13 S4 — 공백만 Enter 는 아무 데도 가지 않는다', async () => {
    const user = userEvent.setup()
    renderTopBar()

    await user.type(screen.getByRole('searchbox', { name: navLabels.globalSearch }), '   {Enter}')

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('F13 S6 — IME 조합 중 Enter 는 제출하지 않는다', () => {
    renderTopBar()

    // 한글 조합 중의 Enter 는 조합 확정이지 제출이 아니다 — 「로그인」을 치는 도중 검색이 나가면 안 된다
    const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
    fireEvent.change(box, { target: { value: '로그인' } })
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true })

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('F13 S6 — isComposing 없이 keyCode 229 만 오는 IME 도 제출하지 않는다', () => {
    renderTopBar()

    // 일부 브라우저/IME 조합은 조합 중 keydown 에 isComposing 을 세팅하지 않고 keyCode 229 만 보낸다.
    // 위 S6 은 isComposing 을 직접 심으므로 이 경로를 원리적으로 못 잡는다 — 이중 방어의 짝 테스트다
    // (선례: routes/issues.$key.tsx:742 · components/issue/IssueDescription.tsx:392).
    const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
    fireEvent.change(box, { target: { value: '로그인' } })
    fireEvent.keyDown(box, { key: 'Enter', keyCode: 229 })

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('F13 FR11 — 제출 후 입력값을 지우지 않는다', async () => {
    const user = userEvent.setup()
    renderTopBar()

    const box = screen.getByRole('searchbox', { name: navLabels.globalSearch })
    await user.type(box, '로그인{Enter}')

    expect(box).toHaveValue('로그인')
  })

  it('만들기 버튼 클릭 시 URL 을 바꾸지 않고 제자리에서 생성 모달이 열린다 (FR-12)', async () => {
    const user = userEvent.setup()
    renderTopBar()

    await user.click(screen.getByRole('button', { name: navLabels.create }))

    // 보드를 보던 사용자가 이슈 하나 만들려다 화면을 떠나면 안 된다
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(
      await screen.findByRole('dialog', { name: issueCreateStrings.dialogTitle }),
    ).toBeInTheDocument()
  })

  it('사이드바가 펼쳐진 상태(collapsed=false)면 토글 버튼 aria-label이 "사이드바 접기"다', async () => {
    const user = userEvent.setup()
    mockUseSidebarCollapsed.mockReturnValue({ collapsed: false, toggle: mockToggle })
    renderTopBar()

    const toggleButton = screen.getByRole('button', { name: navLabels.collapseSidebar })
    expect(toggleButton).toBeInTheDocument()

    await user.click(toggleButton)
    expect(mockToggle).toHaveBeenCalledTimes(1)
  })

  it('사이드바가 접힌 상태(collapsed=true)면 토글 버튼 aria-label이 "사이드바 펼치기"다', () => {
    mockUseSidebarCollapsed.mockReturnValue({ collapsed: true, toggle: mockToggle })
    renderTopBar()

    expect(screen.getByRole('button', { name: navLabels.expandSidebar })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: navLabels.collapseSidebar })).not.toBeInTheDocument()
  })

  it('InboxBell(알림)이 렌더된다', () => {
    renderTopBar()

    expect(screen.getByRole('link', { name: inboxLabels.bell.ariaLabel })).toBeInTheDocument()
  })

  it('도움말 버튼 클릭 시 onHelpClick 콜백을 호출한다', async () => {
    const user = userEvent.setup()
    const onHelpClick = vi.fn()
    renderTopBar({ onHelpClick })

    await user.click(screen.getByRole('button', { name: '도움말' }))
    expect(onHelpClick).toHaveBeenCalledTimes(1)
  })

  it('설정 링크가 /settings 인덱스 라우트로 연결된다', () => {
    renderTopBar()

    expect(screen.getByRole('link', { name: '설정' })).toHaveAttribute('href', '/settings')
  })

  it('계정 드롭다운 트리거가 존재한다', () => {
    renderTopBar()

    expect(screen.getByRole('button', { name: /alice.*계정 메뉴/ })).toBeInTheDocument()
  })
})
