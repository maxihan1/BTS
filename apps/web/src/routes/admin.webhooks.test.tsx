// 관리자 Webhook 목록 페이지 라우트 단위 테스트 — 조립 렌더·생성·수정·삭제·이력이동·페이지네이션·409 (FR-API-03 PR4 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'
import {
  resetWebhookStore,
  seedWebhook,
  generateUuidV4,
  DEFAULT_WEBHOOK,
  DEFAULT_WEBHOOK_ID,
  SECOND_WEBHOOK,
} from '@/mocks/webhook-fixtures'
import type { WebhookResponse } from '@/api/webhooks'
import { AdminWebhooksPage } from './admin.webhooks'

// ─────────────────────────────────────────────────────────────────────────────
// useNavigate mock — 이력 보기 네비게이션 검증용 (TanStack Router 의존 없이 페이지 자체 테스트)
// ─────────────────────────────────────────────────────────────────────────────

const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock — 실제 DOM 없이 호출 여부로 검증
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 페이지네이션 full-page 시나리오용 최소 WebhookResponse 생성 헬퍼 */
function makeWebhook(overrides: Partial<WebhookResponse> = {}): WebhookResponse {
  return {
    id: generateUuidV4(),
    name: 'Webhook',
    url: 'https://example.com/hook',
    eventFilter: ['issue.created'],
    projectKey: null,
    enabled: true,
    hasSecret: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    version: 0,
    ...overrides,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage(): { user: ReturnType<typeof userEvent.setup> } & ReturnType<typeof render> {
  const user = userEvent.setup({ delay: null })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const result = render(
    <QueryClientProvider client={client}>
      <AdminWebhooksPage />
    </QueryClientProvider>,
  )
  return { user, ...result }
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 + store 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'valid-token',
    user: makeWhoami({ isSystemAdmin: true }),
  })
  resetWebhookStore()
  vi.clearAllMocks()
  seedWebhook(DEFAULT_WEBHOOK)
  seedWebhook(SECOND_WEBHOOK)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 + 목록 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 렌더', () => {
  it('페이지 제목과 시드 목록이 렌더된다', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '아웃바운드 Webhook' })).toBeInTheDocument()
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
      expect(screen.getByText(SECOND_WEBHOOK.name)).toBeInTheDocument()
    })
  })

  it('"새 구독" 버튼이 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '새 구독' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 생성
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 생성', () => {
  it('"새 구독" 클릭 → 폼 제출 성공 시 목록에 새 항목이 추가된다', async () => {
    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: '새 구독' }))

    await user.type(screen.getByLabelText('이름'), '신규 구독')
    await user.type(screen.getByLabelText('URL'), 'https://example.com/new-hook')
    await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))
    await user.click(screen.getByRole('button', { name: '생성' }))

    await waitFor(() => {
      expect(screen.getByText('신규 구독')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 수정
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 수정', () => {
  it('편집 → 저장 시 목록의 해당 행이 갱신된다', async () => {
    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: `${DEFAULT_WEBHOOK.name} 편집` }))

    const urlInput = screen.getByLabelText('URL')
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/updated-hook')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(screen.getByTitle('https://example.com/updated-hook')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 삭제
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 삭제', () => {
  it('삭제 확인 후 목록에서 제거된다', async () => {
    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: `${DEFAULT_WEBHOOK.name} 삭제` }))
    await user.click(screen.getByRole('button', { name: `${DEFAULT_WEBHOOK.name} 삭제 확인` }))

    await waitFor(() => {
      expect(screen.queryByText(DEFAULT_WEBHOOK.name)).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 발송 이력 이동
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 발송 이력 이동', () => {
  it('발송 이력 버튼 클릭 시 navigate가 호출된다', async () => {
    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: `${DEFAULT_WEBHOOK.name} 발송 이력` }))

    expect(mockNavigate).toHaveBeenCalledWith({
      to: `/admin/webhooks/${DEFAULT_WEBHOOK_ID}/deliveries`,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// size 기반 페이지네이션
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 페이지네이션', () => {
  it('첫 페이지·미만-사이즈 목록이면 이전/다음 모두 비활성이다', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
    expect(screen.getByText('페이지 1')).toBeInTheDocument()
  })

  it('꽉 찬 페이지(length===size)면 다음이 활성화되고, 다음 클릭 시 이전이 활성화된다', async () => {
    resetWebhookStore()
    for (let i = 0; i < 20; i += 1) {
      seedWebhook(makeWebhook({ name: `Full Webhook ${i}` }))
    }

    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByText('Full Webhook 0')).toBeInTheDocument()
    })

    expect(screen.getByRole('button', { name: '다음' })).not.toBeDisabled()

    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => {
      expect(screen.getByText('페이지 2')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '이전' })).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 409 OCC 충돌
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminWebhooksPage — 409 OCC 충돌', () => {
  it('stale version으로 저장 시 폼에 충돌 안내 메시지가 표시된다', async () => {
    const { user } = renderPage()

    await waitFor(() => {
      expect(screen.getByText(DEFAULT_WEBHOOK.name)).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: `${DEFAULT_WEBHOOK.name} 편집` }))

    // 폼이 열려 version=0을 캡처한 뒤, 다른 곳에서 먼저 변경된 상황을 흉내낸다.
    seedWebhook({ ...DEFAULT_WEBHOOK, version: 5 })

    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        '다른 곳에서 먼저 변경되었습니다. 목록을 다시 불러오세요.',
      )
    })
    expect(toast.error).not.toHaveBeenCalled()
  })
})
