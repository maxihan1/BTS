// 아웃바운드 webhook 구독/발송이력 React Query 훅 테스트 — queryKey filter-aware + mutation invalidate-only 검증
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import {
  WEBHOOKS_QUERY_KEY,
  WEBHOOK_DELIVERIES_QUERY_KEY,
  useWebhooksQuery,
  useWebhookDeliveriesQuery,
  useCreateWebhook,
  useUpdateWebhook,
  useDeleteWebhook,
} from './useWebhooks'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — RFC4122 v4 호환 UUID (zod-v4-uuid-fixture-strictness memory)
// ─────────────────────────────────────────────────────────────────────────────

const webhookFixtureA = {
  id: '11111111-1111-4111-a111-111111111111',
  name: '이슈 생성 알림',
  url: 'https://example.com/hook-a',
  eventFilter: ['issue.created'],
  enabled: true,
  hasSecret: false,
  version: 0,
}

const webhookFixtureB = {
  id: '22222222-2222-4222-a222-222222222222',
  name: '이슈 전이 알림',
  url: 'https://example.com/hook-b',
  eventFilter: ['issue.transitioned'],
  enabled: true,
  hasSecret: true,
  version: 0,
}

const deliveryFixture = {
  id: '33333333-3333-4333-a333-333333333333',
  eventType: 'issue.created',
  status: 'SUCCEEDED',
  responseCode: 200,
  attemptCount: 1,
  errorDetail: null,
  createdAt: '2026-06-01T00:00:00Z',
  deliveredAt: '2026-06-01T00:00:01Z',
}

const updateBody = {
  name: '이슈 생성 알림 (수정)',
  url: 'https://example.com/hook-a2',
  eventFilter: ['issue.created', 'issue.transitioned'],
  version: 0,
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

describe('useWebhooks 훅 묶음', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useWebhooksQuery
  // ─────────────────────────────────────────────────────────────────────────

  describe('useWebhooksQuery', () => {
    it('구독 목록을 로드한다', async () => {
      server.use(http.get('/api/v1/webhooks', () => HttpResponse.json([webhookFixtureA])))

      const { result } = renderHook(() => useWebhooksQuery(0, 20), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data).toHaveLength(1)
      expect(result.current.data?.[0]?.id).toBe(webhookFixtureA.id)
    })

    it('page/size가 다르면 서로 다른 캐시 키를 사용한다 (filter-aware)', async () => {
      server.use(
        http.get('/api/v1/webhooks', ({ request }) => {
          const url = new URL(request.url)
          const page = url.searchParams.get('page')
          if (page === '1') return HttpResponse.json([webhookFixtureB])
          return HttpResponse.json([webhookFixtureA])
        }),
      )

      const { result: resultPage0 } = renderHook(() => useWebhooksQuery(0, 20), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(resultPage0.current.isSuccess).toBe(true))

      const { result: resultPage1 } = renderHook(() => useWebhooksQuery(1, 20), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(resultPage1.current.isSuccess).toBe(true))

      // 두 페이지가 서로 다른 캐시 항목을 유지한다 — page 0 데이터가 page 1 조회로 덮이지 않음
      expect(resultPage0.current.data?.[0]?.id).toBe(webhookFixtureA.id)
      expect(resultPage1.current.data?.[0]?.id).toBe(webhookFixtureB.id)

      const cache0 = queryClient.getQueryData([...WEBHOOKS_QUERY_KEY, 0, 20])
      const cache1 = queryClient.getQueryData([...WEBHOOKS_QUERY_KEY, 1, 20])
      expect(cache0).toBeDefined()
      expect(cache1).toBeDefined()
      expect(cache0).not.toEqual(cache1)
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useWebhookDeliveriesQuery
  // ─────────────────────────────────────────────────────────────────────────

  describe('useWebhookDeliveriesQuery', () => {
    it('발송 이력을 로드한다', async () => {
      server.use(
        http.get(`/api/v1/webhooks/${webhookFixtureA.id}/deliveries`, () =>
          HttpResponse.json([deliveryFixture]),
        ),
      )

      const { result } = renderHook(() => useWebhookDeliveriesQuery(webhookFixtureA.id, 0, 20), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data).toHaveLength(1)
      expect(result.current.data?.[0]?.id).toBe(deliveryFixture.id)
    })

    it('id/page/size를 캐시 키에 포함한다', async () => {
      server.use(
        http.get(`/api/v1/webhooks/${webhookFixtureA.id}/deliveries`, () =>
          HttpResponse.json([deliveryFixture]),
        ),
      )

      const { result } = renderHook(() => useWebhookDeliveriesQuery(webhookFixtureA.id, 0, 20), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const cached = queryClient.getQueryData([
        ...WEBHOOK_DELIVERIES_QUERY_KEY,
        webhookFixtureA.id,
        0,
        20,
      ])
      expect(cached).toBeDefined()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useCreateWebhook
  // ─────────────────────────────────────────────────────────────────────────

  describe('useCreateWebhook', () => {
    it('성공(201) 시 webhooks 쿼리가 invalidate된다', async () => {
      server.use(
        http.post('/api/v1/webhooks', () => HttpResponse.json(webhookFixtureA, { status: 201 })),
      )
      queryClient.setQueryData([...WEBHOOKS_QUERY_KEY, 0, 20], [])

      const { result } = renderHook(() => useCreateWebhook(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate({
        name: webhookFixtureA.name,
        url: webhookFixtureA.url,
        eventFilter: webhookFixtureA.eventFilter,
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      // WEBHOOKS_QUERY_KEY로 invalidate하면 접두사가 일치하는 [...WEBHOOKS_QUERY_KEY, page, size] 캐시도 invalidate된다
      const queryState = queryClient.getQueryState([...WEBHOOKS_QUERY_KEY, 0, 20])
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
      server.use(
        http.post('/api/v1/webhooks', () => HttpResponse.json(webhookFixtureA, { status: 201 })),
      )
      queryClient.setQueryData([...WEBHOOKS_QUERY_KEY, 0, 20], [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useCreateWebhook(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate({
        name: webhookFixtureA.name,
        url: webhookFixtureA.url,
        eventFilter: webhookFixtureA.eventFilter,
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(spy).not.toHaveBeenCalledWith(WEBHOOKS_QUERY_KEY, expect.anything())
      spy.mockRestore()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useUpdateWebhook
  // ─────────────────────────────────────────────────────────────────────────

  describe('useUpdateWebhook', () => {
    it('성공(200) 시 webhooks + webhook-deliveries 쿼리가 모두 invalidate된다', async () => {
      server.use(
        http.put(`/api/v1/webhooks/${webhookFixtureA.id}`, () => HttpResponse.json(webhookFixtureA)),
      )
      queryClient.setQueryData([...WEBHOOKS_QUERY_KEY, 0, 20], [])
      queryClient.setQueryData([...WEBHOOK_DELIVERIES_QUERY_KEY, webhookFixtureA.id, 0, 20], [])

      const { result } = renderHook(() => useUpdateWebhook(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate({ id: webhookFixtureA.id, body: updateBody })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const webhooksState = queryClient.getQueryState([...WEBHOOKS_QUERY_KEY, 0, 20])
      const deliveriesState = queryClient.getQueryState([
        ...WEBHOOK_DELIVERIES_QUERY_KEY,
        webhookFixtureA.id,
        0,
        20,
      ])
      expect(webhooksState?.isInvalidated).toBe(true)
      expect(deliveriesState?.isInvalidated).toBe(true)
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useDeleteWebhook
  // ─────────────────────────────────────────────────────────────────────────

  describe('useDeleteWebhook', () => {
    it('성공(204) 시 webhooks 쿼리가 invalidate된다', async () => {
      server.use(
        http.delete(`/api/v1/webhooks/${webhookFixtureA.id}`, () => new HttpResponse(null, { status: 204 })),
      )
      queryClient.setQueryData([...WEBHOOKS_QUERY_KEY, 0, 20], [])

      const { result } = renderHook(() => useDeleteWebhook(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate(webhookFixtureA.id)

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState([...WEBHOOKS_QUERY_KEY, 0, 20])
      expect(queryState?.isInvalidated).toBe(true)
    })
  })
})
