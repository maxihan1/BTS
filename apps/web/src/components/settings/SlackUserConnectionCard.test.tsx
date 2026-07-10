// SlackUserConnectionCard 컴포넌트 테스트 — 본인 계정 연결/미연결/연결·해제 mutation/오류 배너 검증 (FR-SL-02 D6 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { getMyConnection, connectSlack, disconnectSlack } from '@/api/slack'
import { ApiError } from '@/api/client'
import { SlackUserConnectionCard } from './SlackUserConnectionCard'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/slack', () => ({
  getMyConnection: vi.fn(),
  connectSlack: vi.fn(),
  disconnectSlack: vi.fn(),
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

const DISCONNECTED = { connected: false, workspaceName: null, linkedAt: null }
const CONNECTED = { connected: true, workspaceName: 'Acme Corp', linkedAt: '2026-07-10T00:00:00Z' }

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 미연결
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackUserConnectionCard — 미연결', () => {
  it('"Slack 연결" 버튼을 표시한다', async () => {
    vi.mocked(getMyConnection).mockResolvedValue(DISCONNECTED)

    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 연결됨
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackUserConnectionCard — 연결됨', () => {
  it('워크스페이스명·연결 시각을 표시하고 "연결 해제" 버튼을 렌더한다', async () => {
    vi.mocked(getMyConnection).mockResolvedValue(CONNECTED)

    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument()
    })
    // linkedAt 원문 ISO를 그대로 노출하지 않고 날짜 형식으로 포맷해 표시한다
    expect(screen.getByText(/2026-07-10/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '연결 해제' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// "Slack 연결" 클릭 → connectSlack → 성공 시 연결됨 UI로 갱신
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackUserConnectionCard — 연결 버튼 클릭', () => {
  it('connectSlack을 호출하고 성공하면 연결됨 UI로 갱신된다', async () => {
    vi.mocked(getMyConnection).mockResolvedValueOnce(DISCONNECTED).mockResolvedValueOnce(CONNECTED)
    vi.mocked(connectSlack).mockResolvedValue(CONNECTED)

    const user = userEvent.setup()
    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Slack 연결' }))

    await waitFor(() => {
      expect(connectSlack).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '연결 해제' })).toBeInTheDocument()
    })
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// "연결 해제" 클릭 → disconnectSlack → 성공 시 미연결 UI로 갱신
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackUserConnectionCard — 연결 해제 버튼 클릭', () => {
  it('disconnectSlack을 호출하고 성공하면 미연결 UI로 갱신된다', async () => {
    vi.mocked(getMyConnection).mockResolvedValueOnce(CONNECTED).mockResolvedValueOnce(DISCONNECTED)
    vi.mocked(disconnectSlack).mockResolvedValue(DISCONNECTED)

    const user = userEvent.setup()
    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '연결 해제' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '연결 해제' }))

    await waitFor(() => {
      expect(disconnectSlack).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// connectSlack 오류 — role=alert 배너 + 코드별 한국어 메시지 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackUserConnectionCard — 연결 오류', () => {
  it('409 WORKSPACE_NOT_INSTALLED → 워크스페이스 미설치 안내를 표시한다', async () => {
    vi.mocked(getMyConnection).mockResolvedValue(DISCONNECTED)
    vi.mocked(connectSlack).mockRejectedValue(new ApiError(409, { code: 'WORKSPACE_NOT_INSTALLED' }))

    const user = userEvent.setup()
    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: 'Slack 연결' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        '먼저 관리자가 워크스페이스에 Slack을 연결해야 합니다',
      )
    })
    // 카드(버튼)가 사라지지 않고 페이지에 그대로 유지된다
    expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
  })

  it('409 SLACK_SCOPE_MISSING → 재연결 필요 안내를 표시한다', async () => {
    vi.mocked(getMyConnection).mockResolvedValue(DISCONNECTED)
    vi.mocked(connectSlack).mockRejectedValue(new ApiError(409, { code: 'SLACK_SCOPE_MISSING' }))

    const user = userEvent.setup()
    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: 'Slack 연결' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('관리자가 Slack 앱을 다시 연결해야 합니다')
    })
  })

  it('404 SLACK_USER_NOT_FOUND → 이메일 미발견 안내를 표시한다', async () => {
    vi.mocked(getMyConnection).mockResolvedValue(DISCONNECTED)
    vi.mocked(connectSlack).mockRejectedValue(new ApiError(404, { code: 'SLACK_USER_NOT_FOUND' }))

    const user = userEvent.setup()
    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: 'Slack 연결' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Slack에서 회원님 이메일로 계정을 찾을 수 없습니다',
      )
    })
  })

  it('알 수 없는 코드/네트워크 오류 → 일반 오류 안내로 폴백한다', async () => {
    vi.mocked(getMyConnection).mockResolvedValue(DISCONNECTED)
    vi.mocked(connectSlack).mockRejectedValue(new ApiError(503, { code: 'SLACK_TEMPORARILY_UNAVAILABLE' }))

    const user = userEvent.setup()
    render(<SlackUserConnectionCard />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: 'Slack 연결' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Slack 연결 중 문제가 발생했습니다')
    })
  })
})
