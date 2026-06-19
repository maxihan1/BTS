// 사용자 알림 구독 설정 API + React Query 훅 단위 테스트 — MSW + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { getSubscriptions, patchSubscriptions } from './user-notification-subscriptions'
import {
  USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY,
  useUserNotificationSubscriptions,
  useUpdateUserNotificationSubscriptions,
} from './useUserNotificationSubscriptions'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — patchSubscriptions CSRF 헤더 검증용
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-subs-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
  useAuthStore.setState({ accessToken: 'test-token', user: null })
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — 백엔드 계약: { data: { subscriptions: [...] } }
// 항상 20개: 10 eventType × { IN_APP, EMAIL }
// ─────────────────────────────────────────────────────────────────────────────

const EVENT_TYPES = [
  'issue.created',
  'issue.assigned',
  'issue.transitioned',
  'issue.commented',
  'issue.due_soon',
  'issue.overdue',
  'sprint.started',
  'sprint.ended',
  'automation.failed',
  'issue.mentioned',
] as const

/** 20개 셀 생성 헬퍼 */
function buildFullMatrix(enabledAll = true) {
  return EVENT_TYPES.flatMap((eventType) => [
    { eventType, channel: 'IN_APP', enabled: enabledAll },
    { eventType, channel: 'EMAIL', enabled: enabledAll },
  ])
}

const FULL_MATRIX_FIXTURE = buildFullMatrix(true)

/** 백엔드 응답 래퍼 형식 */
function makeResponse(subscriptions: typeof FULL_MATRIX_FIXTURE) {
  return { data: { subscriptions } }
}

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-1. getSubscriptions — GET /api/v1/users/me/notifications
// ─────────────────────────────────────────────────────────────────────────────

describe('getSubscriptions', () => {
  it('T-NS-1a: 200 응답 → SubscriptionMatrix 반환 (20개 셀)', async () => {
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE)),
      ),
    )

    const result = await getSubscriptions()
    expect(result.subscriptions).toHaveLength(20)
  })

  it('T-NS-1b: 각 셀에 eventType(string)/channel(string)/enabled(boolean) 필드가 있다', async () => {
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE)),
      ),
    )

    const result = await getSubscriptions()
    const first = result.subscriptions[0]
    expect(typeof first?.eventType).toBe('string')
    expect(typeof first?.channel).toBe('string')
    expect(typeof first?.enabled).toBe('boolean')
  })

  it('T-NS-1c: IN_APP / EMAIL 채널이 모두 포함된다', async () => {
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE)),
      ),
    )

    const result = await getSubscriptions()
    const channels = result.subscriptions.map((s) => s.channel)
    expect(channels).toContain('IN_APP')
    expect(channels).toContain('EMAIL')
  })

  it('T-NS-1d: Zod 파싱 — enabled가 string이면 ZodError를 throw한다', async () => {
    const broken = FULL_MATRIX_FIXTURE.map((s) => ({ ...s, enabled: 'yes' }))
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        // 고의로 잘못된 타입 응답
        HttpResponse.json({ data: { subscriptions: broken } }),
      ),
    )

    await expect(getSubscriptions()).rejects.toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-2. patchSubscriptions — PATCH /api/v1/users/me/notifications
// ─────────────────────────────────────────────────────────────────────────────

describe('patchSubscriptions', () => {
  it('T-NS-2a: 200 응답 → 갱신된 SubscriptionMatrix 반환', async () => {
    const updated = buildFullMatrix(false)
    server.use(
      http.patch('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(updated)),
      ),
    )

    const entries = [{ eventType: 'issue.created', channel: 'EMAIL', enabled: false }]
    const result = await patchSubscriptions(entries)
    expect(result.subscriptions).toHaveLength(20)
    // 첫 번째 셀은 enabled=false로 갱신됨
    expect(result.subscriptions[0]?.enabled).toBe(false)
  })

  it('T-NS-2b: X-XSRF-TOKEN 헤더가 요청에 포함된다 (CSRF 검증)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.patch('/api/v1/users/me/notifications', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE))
      }),
    )

    const entries = [{ eventType: 'issue.commented', channel: 'IN_APP', enabled: false }]
    await patchSubscriptions(entries)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-NS-2c: 요청 body에 subscriptions 배열이 직렬화된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.patch('/api/v1/users/me/notifications', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE))
      }),
    )

    const entries = [
      { eventType: 'sprint.started', channel: 'EMAIL', enabled: true },
      { eventType: 'sprint.started', channel: 'IN_APP', enabled: false },
    ]
    await patchSubscriptions(entries)

    expect(capturedBody).toEqual({ subscriptions: entries })
  })

  it('T-NS-2d: 400 응답 → ApiError(400) throw', async () => {
    server.use(
      http.patch('/api/v1/users/me/notifications', () =>
        HttpResponse.json({ errorCode: 'NOTIF_INVALID_ENUM' }, { status: 400 }),
      ),
    )

    await expect(
      patchSubscriptions([{ eventType: 'invalid.type', channel: 'SMOKE_SIGNAL', enabled: true }]),
    ).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-3. useUserNotificationSubscriptions — Query 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useUserNotificationSubscriptions', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE)),
      ),
    )
  })

  it('T-NS-3a: 구독 목록 20개를 로드한다', async () => {
    const { result } = renderHook(() => useUserNotificationSubscriptions(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.subscriptions).toHaveLength(20)
  })

  it('T-NS-3b: GET /api/v1/users/me/notifications 실패(401) 시 isError가 true가 된다', async () => {
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    const { result } = renderHook(() => useUserNotificationSubscriptions(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-NS-4. useUpdateUserNotificationSubscriptions — Mutation 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateUserNotificationSubscriptions', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    server.use(
      http.get('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(FULL_MATRIX_FIXTURE)),
      ),
      http.patch('/api/v1/users/me/notifications', () =>
        HttpResponse.json(makeResponse(buildFullMatrix(false))),
      ),
    )
  })

  it('T-NS-4a: 성공 시 user-notification-subscriptions 쿼리가 invalidate된다', async () => {
    queryClient.setQueryData(USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY, {
      subscriptions: FULL_MATRIX_FIXTURE,
    })

    const { result } = renderHook(() => useUpdateUserNotificationSubscriptions(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync([
        { eventType: 'issue.created', channel: 'EMAIL', enabled: false },
      ])
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const queryState = queryClient.getQueryState(USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)
  })

  it('T-NS-4b: setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
    queryClient.setQueryData(USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY, {
      subscriptions: FULL_MATRIX_FIXTURE,
    })
    const spy = queryClient.setQueryData.bind(queryClient)
    const calls: unknown[] = []
    queryClient.setQueryData = (...args) => {
      calls.push(args[0])
      return spy(...args)
    }

    const { result } = renderHook(() => useUpdateUserNotificationSubscriptions(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync([
        { eventType: 'issue.commented', channel: 'IN_APP', enabled: false },
      ])
    })

    // mutation onSuccess는 invalidate만 해야 함 — setQueryData 호출 없어야 함
    const targetKeyCalls = calls.filter(
      (k) =>
        Array.isArray(k) &&
        k.length === 1 &&
        k[0] === USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY[0],
    )
    expect(targetKeyCalls).toHaveLength(0)
  })

  it('T-NS-4c: PATCH 실패(400) 시 mutation이 에러 상태가 된다', async () => {
    server.use(
      http.patch('/api/v1/users/me/notifications', () =>
        HttpResponse.json({ errorCode: 'NOTIF_INVALID_ENUM' }, { status: 400 }),
      ),
    )

    const { result } = renderHook(() => useUpdateUserNotificationSubscriptions(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate([
        { eventType: 'bad.event', channel: 'SMOKE_SIGNAL', enabled: true },
      ])
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
