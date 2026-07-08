// Slack 연결 관리자 페이지 라우트 단위 테스트 — 조립 렌더 + 콜백 배너 + 쿼리 정리 + 재시도/닫기 배선 (FR-SL-01 D6/D7 Task 7/R8)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { getSlackInstallation, getSlackInstallUrl } from '@/api/slack'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock — api/slack(SlackConnectionCard.test.tsx 동일 패턴), TanStack Router
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/slack', () => ({
  getSlackInstallation: vi.fn(),
  getSlackInstallUrl: vi.fn(),
}))

const mockNavigate = vi.fn()
const mockUseSearch = vi.fn((): { installed?: string; error?: string } => ({}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useSearch: () => mockUseSearch(),
}))

// 대상 import — mock 이후 (vi.mock hoisting)
import {
  SlackConnectionSettingsPage,
  SlackConnectionSettingsRouteAdapter,
} from '@/routes/admin.slack'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function renderPage(): ReturnType<typeof render> {
  return render(<SlackConnectionSettingsPage />, { wrapper: createWrapper() })
}

beforeEach(() => {
  vi.clearAllMocks()
  mockUseSearch.mockReturnValue({})
  vi.mocked(getSlackInstallation).mockResolvedValue({
    connected: false,
    teamId: null,
    teamName: null,
    botUserId: null,
    installedAt: null,
    updatedAt: null,
    installerName: null,
  })
  vi.mocked(getSlackInstallUrl).mockResolvedValue({
    url: 'https://slack.com/oauth/v2/authorize?client_id=abc',
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 조립 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionSettingsPage — 조립 렌더', () => {
  it('T1: h1 "Slack 연결" 헤딩이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: 'Slack 연결', level: 1 })).toBeInTheDocument()
  })

  it('T2: SlackConnectionCard가 렌더된다 ("Slack에 연결" 버튼 등장)', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack에 연결' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 콜백 배너 — installed/error → SlackResultBanner
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionSettingsPage — 콜백 배너', () => {
  it('T3: installed 쿼리가 있으면 성공 배너가 렌더된다', () => {
    mockUseSearch.mockReturnValue({ installed: 'Acme' })
    renderPage()
    expect(screen.getByRole('alert')).toHaveTextContent('Acme 워크스페이스에 연결되었습니다')
  })

  it('T4: error 쿼리가 있으면 실패 배너가 렌더된다', () => {
    mockUseSearch.mockReturnValue({ error: 'access_denied' })
    renderPage()
    expect(screen.getByRole('alert')).toHaveTextContent('Slack 연결이 취소되었습니다.')
  })

  it('T5: installed/error 둘 다 없으면 배너가 렌더되지 않는다', () => {
    renderPage()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 정리 — 마운트 시 navigate로 installed/error 제거(새로고침 재표시 방지)
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionSettingsPage — 쿼리 정리', () => {
  it('T6: installed 쿼리가 있으면 마운트 시 navigate가 쿼리를 정리한다', () => {
    mockUseSearch.mockReturnValue({ installed: 'Acme' })
    renderPage()
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/admin/slack', search: {}, replace: true })
  })

  it('T7: error 쿼리만 있어도 마운트 시 navigate가 쿼리를 정리한다', () => {
    mockUseSearch.mockReturnValue({ error: 'oauth_failed' })
    renderPage()
    expect(mockNavigate).toHaveBeenCalledWith({ to: '/admin/slack', search: {}, replace: true })
  })

  it('T8: installed/error 둘 다 없으면 navigate가 호출되지 않는다', () => {
    renderPage()
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('T9: 쿼리 정리 후 useSearch가 빈 값을 반환해도 배너는 계속 표시된다', () => {
    mockUseSearch.mockReturnValue({ installed: 'Acme' })
    const { rerender } = renderPage()
    expect(screen.getByRole('alert')).toHaveTextContent('Acme 워크스페이스에 연결되었습니다')

    // navigate({ search: {} })로 URL이 정리된 이후를 시뮬레이션 — useSearch가 빈 객체를 반환해도
    // 배너는 최초 마운트 시 캡처한 값으로 계속 표시되어야 한다(EC7, 즉시 숨김 방지)
    mockUseSearch.mockReturnValue({})
    rerender(<SlackConnectionSettingsPage />)

    expect(screen.getByRole('alert')).toHaveTextContent('Acme 워크스페이스에 연결되었습니다')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 배너 재시도/닫기 배선 — 페이지가 SlackResultBanner에 onRetry/onDismiss를 주입
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionSettingsPage — 배너 재시도/닫기 배선', () => {
  it('T11: 실패 배너의 "다시 시도" 클릭 시 getSlackInstallUrl을 호출하고 반환된 url로 이동한다', async () => {
    mockUseSearch.mockReturnValue({ error: 'exchange_failed' })
    vi.mocked(getSlackInstallUrl).mockResolvedValue({
      url: 'https://slack.com/oauth/v2/authorize?client_id=retry',
    })
    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    await waitFor(() => {
      expect(getSlackInstallUrl).toHaveBeenCalled()
      expect(assignMock).toHaveBeenCalledWith(
        'https://slack.com/oauth/v2/authorize?client_id=retry',
      )
    })
  })

  it('T12: 배너의 "닫기" 클릭 시 배너가 화면에서 사라진다', async () => {
    mockUseSearch.mockReturnValue({ installed: 'Acme' })
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByRole('alert')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '닫기' }))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionSettingsRouteAdapter', () => {
  it('T10: RouteAdapter가 SlackConnectionSettingsPage를 렌더한다', () => {
    render(<SlackConnectionSettingsRouteAdapter />, { wrapper: createWrapper() })
    expect(screen.getByRole('heading', { name: 'Slack 연결', level: 1 })).toBeInTheDocument()
  })
})
