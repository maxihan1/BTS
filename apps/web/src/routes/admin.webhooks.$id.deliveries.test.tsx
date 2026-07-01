// admin.webhooks.$id.deliveries 라우트 테스트 — 이력 렌더 + RouteAdapter id 전달 + size 기반 페이지네이션 + 목록 네비
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { webhookHandlers } from '@/mocks/webhook-handlers'
import {
  resetWebhookStore,
  seedWebhook,
  seedDeliveries,
  DEFAULT_WEBHOOK,
  DEFAULT_WEBHOOK_ID,
  DEFAULT_WEBHOOK_DELIVERIES,
  SECOND_WEBHOOK,
  SECOND_WEBHOOK_ID,
} from '@/mocks/webhook-fixtures'
import type { WebhookDeliveryResponse } from '@/api/webhooks'
import { WebhookDeliveriesPage, WebhookDeliveriesRouteAdapter } from '@/routes/admin.webhooks.$id.deliveries'

// ─────────────────────────────────────────────────────────────────────────────
// @tanstack/react-router mock — useParams만 스텁 (workflows.$key.test.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ id: SECOND_WEBHOOK_ID }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function renderPage(webhookId: string = DEFAULT_WEBHOOK_ID): ReturnType<typeof render> {
  server.use(...webhookHandlers)
  return render(
    <QueryClientProvider client={makeClient()}>
      <WebhookDeliveriesPage webhookId={webhookId} />
    </QueryClientProvider>,
  )
}

/** count건의 SUCCEEDED 이력을 생성한다 (size 기반 next-enabled 검증용). */
function makeSucceededDeliveries(count: number): WebhookDeliveryResponse[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `30000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    eventType: 'issue.created',
    status: 'SUCCEEDED',
    responseCode: 200,
    attemptCount: 1,
    errorDetail: null,
    createdAt: '2026-06-01T00:00:00Z',
    deliveredAt: '2026-06-01T00:00:01Z',
  }))
}

afterEach(() => {
  resetWebhookStore()
})

// ─────────────────────────────────────────────────────────────────────────────
// 1. 이력 렌더 — MSW 시드 데이터가 WebhookDeliveryTable에 표시된다
// ─────────────────────────────────────────────────────────────────────────────

describe('WebhookDeliveriesPage — 이력 렌더', () => {
  it('MSW 시드 이력이 표시된다 (이벤트/상태 라벨)', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)

    renderPage(DEFAULT_WEBHOOK_ID)

    await waitFor(() => {
      expect(screen.getByText('이슈 생성')).toBeInTheDocument()
    })
    expect(screen.getByText('성공')).toBeInTheDocument()
    expect(screen.getAllByText('실패').length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. RouteAdapter — useParams id가 Page에 전달된다
// ─────────────────────────────────────────────────────────────────────────────

describe('WebhookDeliveriesRouteAdapter — useParams id 전달', () => {
  it('useParams가 반환한 id로 해당 구독의 이력만 조회한다', async () => {
    // DEFAULT_WEBHOOK과 SECOND_WEBHOOK을 모두 시드하되 서로 다른 이벤트 종류를 부여해
    // RouteAdapter가 useParams()의 id(SECOND_WEBHOOK_ID)를 실제로 사용하는지 구분한다.
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES) // issue.created/issue.transitioned 혼합

    seedWebhook(SECOND_WEBHOOK)
    seedDeliveries(SECOND_WEBHOOK_ID, [
      {
        id: '40000000-0000-4000-8000-000000000001',
        eventType: 'issue.transitioned',
        status: 'FAILED',
        responseCode: null,
        attemptCount: 5,
        errorDetail: 'SECOND_WEBHOOK 전용 오류',
        createdAt: '2026-06-05T00:00:00Z',
        deliveredAt: null,
      },
    ])
    server.use(...webhookHandlers)

    render(
      <QueryClientProvider client={makeClient()}>
        <WebhookDeliveriesRouteAdapter />
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(screen.getByText('SECOND_WEBHOOK 전용 오류')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 3. size 기반 prev/next disabled 규칙 (EC-2, raw List)
// ─────────────────────────────────────────────────────────────────────────────

describe('WebhookDeliveriesPage — size 기반 페이지네이션', () => {
  it('첫 페이지(page=0)에서는 이전 버튼이 disabled다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)

    renderPage(DEFAULT_WEBHOOK_ID)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이전' })).toBeDisabled()
    })
    expect(screen.getByText('페이지 1')).toBeInTheDocument()
  })

  it('받은 이력 개수가 size(20)보다 적으면 다음 버튼이 disabled다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES) // 3건 < 20

    renderPage(DEFAULT_WEBHOOK_ID)

    await waitFor(() => {
      expect(screen.getByText('이슈 생성')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
  })

  it('받은 이력 개수가 size(20)와 같으면 다음 버튼이 활성화되고, 클릭 시 이전 버튼도 활성화된다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, makeSucceededDeliveries(20))

    renderPage(DEFAULT_WEBHOOK_ID)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '다음' })).not.toBeDisabled()
    })

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() => {
      expect(screen.getByText('페이지 2')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '이전' })).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 4. "목록으로" 네비
// ─────────────────────────────────────────────────────────────────────────────

describe('WebhookDeliveriesPage — 목록으로 네비', () => {
  it('"목록으로" 링크가 /admin/webhooks를 가리킨다', () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)

    renderPage(DEFAULT_WEBHOOK_ID)

    const link = screen.getByRole('link', { name: /목록으로/ })
    expect(link).toHaveAttribute('href', '/admin/webhooks')
  })
})
