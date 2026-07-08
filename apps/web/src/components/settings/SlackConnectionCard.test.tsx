// SlackConnectionCard 컴포넌트 테스트 — 연결/미연결/로딩/연결버튼/오류 상태 렌더 검증 (FR-SL-01 D6/D7 Task 5)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { getSlackInstallation, getSlackInstallUrl } from '@/api/slack'
import { SlackConnectionCard } from './SlackConnectionCard'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/slack', () => ({
  getSlackInstallation: vi.fn(),
  getSlackInstallUrl: vi.fn(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 연결됨
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionCard — 연결됨', () => {
  it('teamName·설치일을 표시하고 "다시 연결" 버튼을 렌더한다', async () => {
    vi.mocked(getSlackInstallation).mockResolvedValue({
      connected: true,
      teamId: 'T123',
      teamName: 'Acme',
      botUserId: null,
      installedAt: '2026-07-08T00:00:00Z',
      updatedAt: null,
      installerName: null,
    })

    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })
    // installedAt 원문 ISO를 그대로 노출하지 않고 날짜 형식으로 포맷해 표시한다
    expect(screen.getByText(/2026-07-08/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 연결' })).toBeInTheDocument()
  })

  it('installerName·botUserId·설치일·최근 갱신을 모두 표시한다', async () => {
    vi.mocked(getSlackInstallation).mockResolvedValue({
      connected: true,
      teamId: 'T123',
      teamName: 'Acme',
      botUserId: 'U0BOT',
      installedAt: '2026-07-08T00:00:00Z',
      updatedAt: '2026-07-09T12:00:00Z',
      installerName: '홍길동',
    })

    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })

    // 설치자 이름
    expect(screen.getByText('설치자')).toBeInTheDocument()
    expect(screen.getByText('홍길동')).toBeInTheDocument()
    // 봇 사용자 ID
    expect(screen.getByText('봇 사용자 ID')).toBeInTheDocument()
    expect(screen.getByText('U0BOT')).toBeInTheDocument()
    // 설치일(installedAt) — ISO 원문이 아닌 포맷된 날짜
    expect(screen.getByText('설치일')).toBeInTheDocument()
    expect(screen.getByText(/2026-07-08/)).toBeInTheDocument()
    // 최근 갱신(updatedAt) — ISO 원문이 아닌 포맷된 날짜
    expect(screen.getByText('최근 갱신')).toBeInTheDocument()
    expect(screen.getByText(/2026-07-09/)).toBeInTheDocument()
  })

  it('installerName이 null이면 설치자 행을 생략하고 나머지 필드는 그대로 표시한다', async () => {
    vi.mocked(getSlackInstallation).mockResolvedValue({
      connected: true,
      teamId: 'T123',
      teamName: 'Acme',
      botUserId: 'U0BOT',
      installedAt: '2026-07-08T00:00:00Z',
      updatedAt: '2026-07-09T12:00:00Z',
      installerName: null,
    })

    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Acme')).toBeInTheDocument()
    })

    expect(screen.queryByText('설치자')).not.toBeInTheDocument()
    // 나머지 필드는 에러 없이 정상 렌더
    expect(screen.getByText('봇 사용자 ID')).toBeInTheDocument()
    expect(screen.getByText('U0BOT')).toBeInTheDocument()
    expect(screen.getByText('최근 갱신')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 연결' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 미연결
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionCard — 미연결', () => {
  it('"Slack에 연결" 버튼과 미연결 안내를 표시한다', async () => {
    vi.mocked(getSlackInstallation).mockResolvedValue({
      connected: false,
      teamId: null,
      teamName: null,
      botUserId: null,
      installedAt: null,
      updatedAt: null,
      installerName: null,
    })

    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack에 연결' })).toBeInTheDocument()
    })
    expect(screen.getByText(/연결되어 있지 않습니다/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionCard — 로딩', () => {
  it('조회 중에는 로딩 표시를 렌더한다', () => {
    vi.mocked(getSlackInstallation).mockImplementation(() => new Promise(() => undefined))

    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    expect(screen.getByText(/불러오는 중/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 연결 버튼 클릭 → install URL 조회 → window.location.assign
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionCard — 연결 버튼 클릭', () => {
  it('클릭 시 getSlackInstallUrl을 호출하고 반환된 url로 window.location.assign을 실행한다', async () => {
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

    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    const user = userEvent.setup()
    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack에 연결' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Slack에 연결' }))

    await waitFor(() => {
      expect(getSlackInstallUrl).toHaveBeenCalled()
      expect(assignMock).toHaveBeenCalledWith('https://slack.com/oauth/v2/authorize?client_id=abc')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// install URL 조회 실패 — 인라인 오류 + 카드 유지
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackConnectionCard — 연결 URL 발급 실패', () => {
  it('getSlackInstallUrl이 reject되면 인라인 오류 메시지를 표시하고 카드는 유지된다', async () => {
    vi.mocked(getSlackInstallation).mockResolvedValue({
      connected: false,
      teamId: null,
      teamName: null,
      botUserId: null,
      installedAt: null,
      updatedAt: null,
      installerName: null,
    })
    vi.mocked(getSlackInstallUrl).mockRejectedValue(new Error('network error'))

    const user = userEvent.setup()
    render(<SlackConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack에 연결' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Slack에 연결' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    // 카드(버튼)가 사라지지 않고 페이지에 그대로 유지된다
    expect(screen.getByRole('button', { name: 'Slack에 연결' })).toBeInTheDocument()
  })
})
