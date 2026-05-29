// SessionList 컴포넌트 테스트 — 세션 목록 렌더, current 배지/버튼 disabled, revoke 호출, null fallback, 안내 문구
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { toast } from 'sonner'
import { useAuthStore } from '@/auth/authStore'
import { SessionList } from './SessionList'

// sonner toast mock
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// RFC 4122 표준 UUID
const CURRENT_SID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
const OTHER_SID = 'f6e5d4c3-b2a1-4f8e-9d0c-b1a2f3e4d5c6'

const MOCK_SESSIONS = [
  {
    sid: CURRENT_SID,
    providerId: 'local',
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)',
    ipAddress: '192.168.1.1',
    lastSeenAt: '2026-05-29T10:00:00Z',
    createdAt: '2026-05-29T09:00:00Z',
    current: true,
  },
  {
    sid: OTHER_SID,
    providerId: 'local',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64)',
    ipAddress: '10.0.0.1',
    lastSeenAt: '2026-05-28T10:00:00Z',
    createdAt: '2026-05-28T09:00:00Z',
    current: false,
  },
]

const NULL_UA_SESSION = {
  sid: 'b2c3d4e5-f6a1-4b2c-8d9e-f0a1b2c3d4e5',
  providerId: 'local',
  userAgent: null,
  ipAddress: null,
  lastSeenAt: '2026-05-27T10:00:00Z',
  createdAt: '2026-05-27T09:00:00Z',
  current: false,
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'test-token', user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('SessionList', () => {
  it('세션 목록을 카드로 렌더한다 (기기/IP/날짜 표시)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: MOCK_SESSIONS }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    // 기기 정보(userAgent) 렌더 확인 — 데이터 로드 완료까지 대기
    await waitFor(() => {
      expect(
        screen.getByText(/Mozilla\/5\.0 \(Macintosh/),
      ).toBeInTheDocument()
    })
    // IP 주소 렌더 확인
    expect(screen.getByText('192.168.1.1')).toBeInTheDocument()
  })

  it('current 세션에 "현재 세션" 배지를 표시한다 (FR-7)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: MOCK_SESSIONS }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('현재 세션')).toBeInTheDocument()
    })
  })

  it('current 세션의 강제 로그아웃 버튼은 disabled이다 (FR-7)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: MOCK_SESSIONS }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('현재 세션')).toBeInTheDocument()
    })

    // 버튼은 2개 — current는 disabled, 나머지는 enabled
    const buttons = screen.getAllByRole('button', { name: /세션 종료/ })
    const disabledButton = buttons.find((btn) => btn.hasAttribute('disabled'))
    expect(disabledButton).toBeDefined()
  })

  it('다른 세션의 강제 로그아웃 버튼 클릭 시 revoke mutation을 호출한다', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: MOCK_SESSIONS }),
      ),
      http.delete(`/api/v1/auth/sessions/${OTHER_SID}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('현재 세션')).toBeInTheDocument()
    })

    const user = userEvent.setup()
    // current가 아닌 세션의 "세션 종료" 버튼 클릭
    const buttons = screen.getAllByRole('button', { name: /세션 종료/ })
    const enabledButton = buttons.find((btn) => !btn.hasAttribute('disabled'))
    expect(enabledButton).toBeDefined()
    if (enabledButton !== undefined) {
      await user.click(enabledButton)
    }

    // DELETE API가 호출되었음을 toast.success로 간접 검증
    await waitFor(() => {
      expect(toast.success).toHaveBeenCalled()
    })
  })

  it('userAgent null 시 "알 수 없는 기기" fallback을 표시한다 (EC-6)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: [NULL_UA_SESSION] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('알 수 없는 기기')).toBeInTheDocument()
    })
  })

  it('ipAddress null 시 "알 수 없는 위치" fallback을 표시한다 (EC-6)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: [NULL_UA_SESSION] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText('알 수 없는 위치')).toBeInTheDocument()
    })
  })

  it('세션 종료 지연 안내 문구를 렌더한다 (FR-8, EC-29 5초 캐시)', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: MOCK_SESSIONS }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(
        screen.getByText(/세션 종료는 최대.+초 내 완전히 적용/),
      ).toBeInTheDocument()
    })
  })

  it('세션이 없으면 빈 상태 안내를 표시한다', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: [] }),
      ),
    )

    const Wrapper = createWrapper()
    render(<SessionList />, { wrapper: Wrapper })

    await waitFor(() => {
      expect(screen.getByText(/활성 세션이 없습니다/)).toBeInTheDocument()
    })
  })
})
