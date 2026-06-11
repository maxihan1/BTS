// 알림 정책 React Query 훅 테스트 — query 데이터 로드 + mutation invalidate-only 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { useAuthStore } from '@/auth/authStore'
import {
  resetNotificationPolicyStore,
} from '@/mocks/notification-policy-handlers'
import { SEED_POLICY_IDS } from '@/mocks/notification-policy-fixtures'
import {
  NOTIFICATION_POLICIES_QUERY_KEY,
  useCatalogQuery,
  usePoliciesQuery,
  useCreatePolicy,
  useTogglePolicy,
  useDeletePolicy,
} from './useNotificationPolicies'

// sonner toast mock — 실제 DOM 없이 호출 여부만 검증
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 공통 준비
// ─────────────────────────────────────────────────────────────────────────────

describe('useNotificationPolicies 훅 묶음', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    // MSW store를 시드 상태로 초기화하여 테스트 간 격리
    resetNotificationPolicyStore()
    useAuthStore.setState({ accessToken: 'test-token', user: null })
    vi.clearAllMocks()
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useCatalogQuery
  // ─────────────────────────────────────────────────────────────────────────

  describe('useCatalogQuery', () => {
    it('카탈로그를 로드하면 eventTypes 9종, recipientRoles 9종, channels 5종을 반환한다', async () => {
      const { result } = renderHook(() => useCatalogQuery(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const data = result.current.data
      expect(data?.eventTypes).toHaveLength(9)
      expect(data?.recipientRoles).toHaveLength(9)
      expect(data?.channels).toHaveLength(5)
    })

    it('카탈로그 응답에 publishable 필드가 포함된 eventTypes 항목이 있다', async () => {
      const { result } = renderHook(() => useCatalogQuery(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const firstEventType = result.current.data?.eventTypes[0]
      expect(firstEventType).toHaveProperty('value')
      expect(firstEventType).toHaveProperty('publishable')
    })

    it('GET /catalog 실패(401) 시 isError가 true가 된다', async () => {
      server.use(
        http.get('/api/v1/notification-policies/catalog', () =>
          HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
        ),
      )

      const { result } = renderHook(() => useCatalogQuery(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // usePoliciesQuery
  // ─────────────────────────────────────────────────────────────────────────

  describe('usePoliciesQuery', () => {
    it('시드 정책 4건을 로드한다', async () => {
      const { result } = renderHook(() => usePoliciesQuery(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(result.current.data).toHaveLength(4)
    })

    it('각 정책에 id/eventType/recipientRole/channel/enabled 필드가 있다', async () => {
      const { result } = renderHook(() => usePoliciesQuery(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const first = result.current.data?.[0]
      expect(first).toHaveProperty('id')
      expect(first).toHaveProperty('eventType')
      expect(first).toHaveProperty('recipientRole')
      expect(first).toHaveProperty('channel')
      expect(first).toHaveProperty('enabled')
    })

    it('GET /notification-policies 실패(403) 시 isError가 true가 된다', async () => {
      server.use(
        http.get('/api/v1/notification-policies', () =>
          HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
        ),
      )

      const { result } = renderHook(() => usePoliciesQuery(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useCreatePolicy
  // ─────────────────────────────────────────────────────────────────────────

  describe('useCreatePolicy', () => {
    it('성공(201) 시 notification-policies 쿼리가 invalidate된다', async () => {
      // 목록 쿼리 캐시를 미리 채워 invalidate 여부를 확인한다
      queryClient.setQueryData(NOTIFICATION_POLICIES_QUERY_KEY, [])

      const { result } = renderHook(() => useCreatePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({
          eventType: 'sprint.started',
          recipientRole: 'PROJECT_MEMBER',
          channel: 'WEBHOOK',
          enabled: true,
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(NOTIFICATION_POLICIES_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('성공 후 목록을 재조회하면 새 정책이 포함된다', async () => {
      const { result: listResult } = renderHook(() => usePoliciesQuery(), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(listResult.current.isSuccess).toBe(true))

      const beforeCount = listResult.current.data?.length ?? 0

      const { result: createResult } = renderHook(() => useCreatePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await createResult.current.mutateAsync({
          eventType: 'sprint.ended',
          recipientRole: 'RULE_OWNER',
          channel: 'TEAMS',
          enabled: true,
        })
      })

      await waitFor(() => expect(createResult.current.isSuccess).toBe(true))

      // invalidate 후 refetch를 기다린다
      await waitFor(() => {
        expect(listResult.current.data?.length).toBe(beforeCount + 1)
      })
    })

    it('중복(409) 시 mutation이 에러 상태가 된다', async () => {
      const { result } = renderHook(() => useCreatePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        // 시드에 이미 존재하는 조합 — 409 NOTIF_POLICY_DUPLICATE
        result.current.mutate({
          eventType: 'issue.created',
          recipientRole: 'REPORTER',
          channel: 'EMAIL',
          enabled: true,
        })
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
      queryClient.setQueryData(NOTIFICATION_POLICIES_QUERY_KEY, [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useCreatePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({
          eventType: 'automation.failed',
          recipientRole: 'WATCHER',
          channel: 'IN_APP',
          enabled: true,
        })
      })

      expect(spy).not.toHaveBeenCalledWith(NOTIFICATION_POLICIES_QUERY_KEY, expect.anything())
      spy.mockRestore()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useTogglePolicy
  // ─────────────────────────────────────────────────────────────────────────

  describe('useTogglePolicy', () => {
    it('성공(204) 시 notification-policies 쿼리가 invalidate된다', async () => {
      queryClient.setQueryData(NOTIFICATION_POLICIES_QUERY_KEY, [])

      const { result } = renderHook(() => useTogglePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({ id: SEED_POLICY_IDS.p1, enabled: false })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(NOTIFICATION_POLICIES_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('토글 후 목록을 재조회하면 enabled 값이 반영된다', async () => {
      const { result: listResult } = renderHook(() => usePoliciesQuery(), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(listResult.current.isSuccess).toBe(true))

      // p3은 시드에서 enabled=false
      const { result: toggleResult } = renderHook(() => useTogglePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await toggleResult.current.mutateAsync({ id: SEED_POLICY_IDS.p3, enabled: true })
      })

      await waitFor(() => expect(toggleResult.current.isSuccess).toBe(true))

      await waitFor(() => {
        const p3 = listResult.current.data?.find((p) => p.id === SEED_POLICY_IDS.p3)
        expect(p3?.enabled).toBe(true)
      })
    })

    it('존재하지 않는 id(404) 시 mutation이 에러 상태가 된다', async () => {
      const { result } = renderHook(() => useTogglePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        result.current.mutate({
          id: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
          enabled: false,
        })
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useDeletePolicy
  // ─────────────────────────────────────────────────────────────────────────

  describe('useDeletePolicy', () => {
    it('성공(204) 시 notification-policies 쿼리가 invalidate된다', async () => {
      queryClient.setQueryData(NOTIFICATION_POLICIES_QUERY_KEY, [])

      const { result } = renderHook(() => useDeletePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync(SEED_POLICY_IDS.p2)
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(NOTIFICATION_POLICIES_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('삭제 후 목록을 재조회하면 해당 정책이 사라진다', async () => {
      const { result: listResult } = renderHook(() => usePoliciesQuery(), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(listResult.current.isSuccess).toBe(true))

      const beforeCount = listResult.current.data?.length ?? 0

      const { result: deleteResult } = renderHook(() => useDeletePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await deleteResult.current.mutateAsync(SEED_POLICY_IDS.p4)
      })

      await waitFor(() => expect(deleteResult.current.isSuccess).toBe(true))

      await waitFor(() => {
        expect(listResult.current.data?.length).toBe(beforeCount - 1)
        const p4 = listResult.current.data?.find((p) => p.id === SEED_POLICY_IDS.p4)
        expect(p4).toBeUndefined()
      })
    })

    it('존재하지 않는 id(404) 시 mutation이 에러 상태가 된다', async () => {
      const { result } = renderHook(() => useDeletePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        result.current.mutate('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee')
      })

      await waitFor(() => expect(result.current.isError).toBe(true))
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
      queryClient.setQueryData(NOTIFICATION_POLICIES_QUERY_KEY, [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useDeletePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync(SEED_POLICY_IDS.p1)
      })

      expect(spy).not.toHaveBeenCalledWith(NOTIFICATION_POLICIES_QUERY_KEY, expect.anything())
      spy.mockRestore()
    })
  })
})
