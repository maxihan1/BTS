// useAuditLogsQuery 훅 단위 테스트 — queryKey params 포함·data 형태·필터 분리 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import type { JSX } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from './authStore'
import { useAuditLogsQuery } from './useAuditLogsQuery'

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }): JSX.Element {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

const MOCK_PAGE = {
  items: [
    {
      id: 1,
      userId: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
      username: 'alice',
      displayName: '김앨리스',
      eventType: 'LOGIN_SUCCESS',
      providerId: 'local',
      ipAddress: '127.0.0.1',
      userAgent: 'Mozilla/5.0',
      metadata: { sid: 'sid-1' },
      createdAt: '2026-06-10T10:00:00Z',
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
}

const LOGIN_FAILURE_PAGE = {
  items: [
    {
      id: 2,
      userId: null,
      username: null,
      displayName: null,
      eventType: 'LOGIN_FAILURE',
      providerId: 'local',
      ipAddress: '203.0.113.5',
      userAgent: 'curl/7.68',
      metadata: {},
      createdAt: '2026-06-10T09:00:00Z',
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
}

describe('useAuditLogsQuery', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    useAuthStore.setState({ accessToken: 'test-token', user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('params 없이 호출 시 전체 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () => HttpResponse.json(MOCK_PAGE)),
    )

    const { result } = renderHook(() => useAuditLogsQuery({}), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.totalElements).toBe(1)
    expect(result.current.data?.items).toHaveLength(1)
  })

  it('eventType 필터 파라미터가 다르면 별도 쿼리로 처리한다', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', ({ request }) => {
        const url = new URL(request.url)
        const eventType = url.searchParams.get('eventType')
        if (eventType === 'LOGIN_SUCCESS') return HttpResponse.json(MOCK_PAGE)
        if (eventType === 'LOGIN_FAILURE') return HttpResponse.json(LOGIN_FAILURE_PAGE)
        return HttpResponse.json({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
      }),
    )

    const { result: r1 } = renderHook(
      () => useAuditLogsQuery({ eventType: 'LOGIN_SUCCESS' }),
      { wrapper: createWrapper(queryClient) },
    )
    const { result: r2 } = renderHook(
      () => useAuditLogsQuery({ eventType: 'LOGIN_FAILURE' }),
      { wrapper: createWrapper(queryClient) },
    )

    await waitFor(() => expect(r1.current.isSuccess).toBe(true))
    await waitFor(() => expect(r2.current.isSuccess).toBe(true))

    expect(r1.current.data?.items[0]?.eventType).toBe('LOGIN_SUCCESS')
    expect(r2.current.data?.items[0]?.eventType).toBe('LOGIN_FAILURE')
  })

  it('data shape — items/page/size/totalElements/totalPages 존재', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () => HttpResponse.json(MOCK_PAGE)),
    )

    const { result } = renderHook(() => useAuditLogsQuery({}), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveProperty('items')
    expect(result.current.data).toHaveProperty('page')
    expect(result.current.data).toHaveProperty('size')
    expect(result.current.data).toHaveProperty('totalElements')
    expect(result.current.data).toHaveProperty('totalPages')
  })

  it('401 응답 시 isError가 true가 된다', async () => {
    server.use(
      http.get('/api/v1/admin/auth-audit-logs', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    const { result } = renderHook(() => useAuditLogsQuery({}), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
