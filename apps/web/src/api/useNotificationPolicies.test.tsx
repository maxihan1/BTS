// 알림 정책 React Query 훅 테스트 — query 데이터 로드 + mutation invalidate-only 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
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
// T4 전용 인라인 fixture — Zod v4 UUID RFC4122 검증 통과 보장
// T3 notification-policy-fixtures.ts UUID 일부가 variant bits 조건 미충족
// (zod-v4-uuid-fixture-strictness memory) — T4 테스트는 올바른 UUID로 독립 운용
// ─────────────────────────────────────────────────────────────────────────────

/** T4 테스트 전용 올바른 RFC4122 v4 UUID */
const T4_IDS = {
  p1: 'a1b2c3d4-e5f6-4789-a123-ef0123456701',
  p2: 'b2c3d4e5-f6a7-4890-b234-f01234567802',
  p3: 'c3d4e5f6-a7b8-4901-8efa-012345678903',
  p4: 'd4e5f6a7-b8c9-4012-9ef0-123456789004',
  newId: 'e5f6a7b8-c9d0-4123-af01-23456789abcd',
} as const

/** T4 테스트 전용 정책 시드 — RFC4122 호환 UUID */
const T4_SEED = [
  {
    id: T4_IDS.p1,
    eventType: 'issue.created',
    recipientRole: 'REPORTER',
    channel: 'EMAIL',
    enabled: true,
    createdAt: '2026-06-01T10:00:00Z',
    updatedAt: '2026-06-01T10:00:00Z',
  },
  {
    id: T4_IDS.p2,
    eventType: 'issue.assigned',
    recipientRole: 'ASSIGNEE',
    channel: 'IN_APP',
    enabled: true,
    createdAt: '2026-06-01T10:01:00Z',
    updatedAt: '2026-06-01T10:01:00Z',
  },
  {
    id: T4_IDS.p3,
    eventType: 'issue.transitioned',
    recipientRole: 'WATCHER',
    channel: 'EMAIL',
    enabled: false,
    createdAt: '2026-06-01T10:02:00Z',
    updatedAt: '2026-06-01T10:02:00Z',
  },
  {
    id: T4_IDS.p4,
    eventType: 'sprint.started',
    recipientRole: 'PROJECT_ADMIN',
    channel: 'SLACK',
    enabled: true,
    createdAt: '2026-06-01T10:03:00Z',
    updatedAt: '2026-06-01T10:03:00Z',
  },
] as const

type T4Policy = {
  id: string
  eventType: string
  recipientRole: string
  channel: string
  enabled: boolean
  createdAt: string
  updatedAt: string
  projectKey?: string
}

/** T4 테스트 전용 카탈로그 응답 — 백엔드 enum 1:1 */
const T4_CATALOG = {
  eventTypes: [
    { value: 'issue.created', publishable: true },
    { value: 'issue.assigned', publishable: false },
    { value: 'issue.transitioned', publishable: true },
    { value: 'issue.commented', publishable: false },
    { value: 'issue.due_soon', publishable: false },
    { value: 'issue.overdue', publishable: false },
    { value: 'sprint.started', publishable: false },
    { value: 'sprint.ended', publishable: false },
    { value: 'automation.failed', publishable: false },
  ],
  recipientRoles: [
    'REPORTER', 'ASSIGNEE', 'PREVIOUS_ASSIGNEE', 'WATCHER', 'COMPONENT_LEAD',
    'MENTIONED', 'PROJECT_MEMBER', 'RULE_OWNER', 'PROJECT_ADMIN',
  ],
  channels: ['EMAIL', 'IN_APP', 'SLACK', 'TEAMS', 'WEBHOOK'],
}

/** T4 테스트 전용 stateful store — T3 store와 독립 */
let t4Store: T4Policy[] = []

function resetT4Store(): void {
  t4Store = T4_SEED.map((p) => ({ ...p }))
}

// ─────────────────────────────────────────────────────────────────────────────
// T4 전용 MSW 핸들러 (stateful)
// ─────────────────────────────────────────────────────────────────────────────

const t4CatalogHandler = http.get('/api/v1/notification-policies/catalog', () =>
  HttpResponse.json({ data: T4_CATALOG }),
)

const t4ListHandler = http.get('/api/v1/notification-policies', () =>
  HttpResponse.json({ data: [...t4Store] }),
)

const t4CreateHandler = http.post('/api/v1/notification-policies', async ({ request }) => {
  const body = (await request.json()) as {
    eventType: string
    recipientRole: string
    channel: string
    enabled?: boolean
    projectKey?: string | null
  }

  const now = new Date().toISOString()
  const newPolicy: T4Policy = {
    id: T4_IDS.newId,
    eventType: body.eventType,
    recipientRole: body.recipientRole,
    channel: body.channel,
    enabled: body.enabled ?? true,
    createdAt: now,
    updatedAt: now,
  }
  if (body.projectKey != null) {
    newPolicy.projectKey = body.projectKey
  }
  t4Store.push(newPolicy)
  return HttpResponse.json({ data: newPolicy }, { status: 201 })
})

const t4ToggleHandler = http.patch('/api/v1/notification-policies/:id', async ({ request, params }) => {
  const id = params['id'] as string
  const body = (await request.json()) as { enabled: boolean }
  const index = t4Store.findIndex((p) => p.id === id)
  if (index === -1) {
    return HttpResponse.json({ errorCode: 'NOTIF_POLICY_NOT_FOUND' }, { status: 404 })
  }
  const existing = t4Store[index]
  if (existing === undefined) {
    return HttpResponse.json({ errorCode: 'NOTIF_POLICY_NOT_FOUND' }, { status: 404 })
  }
  t4Store[index] = { ...existing, enabled: body.enabled, updatedAt: new Date().toISOString() }
  return new HttpResponse(null, { status: 204 })
})

const t4DeleteHandler = http.delete('/api/v1/notification-policies/:id', ({ params }) => {
  const id = params['id'] as string
  const index = t4Store.findIndex((p) => p.id === id)
  if (index === -1) {
    return HttpResponse.json({ errorCode: 'NOTIF_POLICY_NOT_FOUND' }, { status: 404 })
  }
  t4Store.splice(index, 1)
  return new HttpResponse(null, { status: 204 })
})

const t4Handlers = [t4CatalogHandler, t4ListHandler, t4CreateHandler, t4ToggleHandler, t4DeleteHandler]

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
    // T4 전용 MSW 핸들러 등록 (T3 핸들러와 독립, UUID 정합 보장)
    server.use(...t4Handlers)
    // T4 store 초기화
    resetT4Store()
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
      // 두 훅을 같은 래퍼에서 실행해야 QueryClient 캐시 이벤트를 공유한다
      const wrapper = createWrapper(queryClient)
      const { result } = renderHook(
        () => ({ list: usePoliciesQuery(), create: useCreatePolicy() }),
        { wrapper },
      )

      await waitFor(() => expect(result.current.list.isSuccess).toBe(true))

      const beforeCount = result.current.list.data?.length ?? 0

      await act(async () => {
        await result.current.create.mutateAsync({
          eventType: 'sprint.ended',
          recipientRole: 'RULE_OWNER',
          channel: 'TEAMS',
          enabled: true,
        })
      })

      await waitFor(() => expect(result.current.create.isSuccess).toBe(true))

      // invalidate 후 refetch를 기다린다
      await waitFor(() => {
        expect(result.current.list.data?.length).toBe(beforeCount + 1)
      })
    })

    it('중복(409) 시 mutation이 에러 상태가 된다', async () => {
      // 409 응답을 내려주는 오버라이드 핸들러
      server.use(
        http.post('/api/v1/notification-policies', () =>
          HttpResponse.json({ errorCode: 'NOTIF_POLICY_DUPLICATE' }, { status: 409 }),
        ),
      )

      const { result } = renderHook(() => useCreatePolicy(), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
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
        await result.current.mutateAsync({ id: T4_IDS.p1, enabled: false })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(NOTIFICATION_POLICIES_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('토글 후 목록을 재조회하면 enabled 값이 반영된다', async () => {
      // 두 훅을 같은 래퍼에서 실행해야 QueryClient 캐시 이벤트를 공유한다
      const wrapper = createWrapper(queryClient)
      const { result } = renderHook(
        () => ({ list: usePoliciesQuery(), toggle: useTogglePolicy() }),
        { wrapper },
      )

      await waitFor(() => expect(result.current.list.isSuccess).toBe(true))

      // p3는 시드에서 enabled=false
      await act(async () => {
        await result.current.toggle.mutateAsync({ id: T4_IDS.p3, enabled: true })
      })

      await waitFor(() => expect(result.current.toggle.isSuccess).toBe(true))

      await waitFor(() => {
        const p3 = result.current.list.data?.find((p) => p.id === T4_IDS.p3)
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
        await result.current.mutateAsync(T4_IDS.p2)
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(NOTIFICATION_POLICIES_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('삭제 후 목록을 재조회하면 해당 정책이 사라진다', async () => {
      // 두 훅을 같은 래퍼에서 실행해야 QueryClient 캐시 이벤트를 공유한다
      const wrapper = createWrapper(queryClient)
      const { result } = renderHook(
        () => ({ list: usePoliciesQuery(), del: useDeletePolicy() }),
        { wrapper },
      )

      await waitFor(() => expect(result.current.list.isSuccess).toBe(true))

      const beforeCount = result.current.list.data?.length ?? 0

      await act(async () => {
        await result.current.del.mutateAsync(T4_IDS.p4)
      })

      await waitFor(() => expect(result.current.del.isSuccess).toBe(true))

      await waitFor(() => {
        expect(result.current.list.data?.length).toBe(beforeCount - 1)
        const p4 = result.current.list.data?.find((p) => p.id === T4_IDS.p4)
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
        await result.current.mutateAsync(T4_IDS.p1)
      })

      expect(spy).not.toHaveBeenCalledWith(NOTIFICATION_POLICIES_QUERY_KEY, expect.anything())
      spy.mockRestore()
    })
  })
})
