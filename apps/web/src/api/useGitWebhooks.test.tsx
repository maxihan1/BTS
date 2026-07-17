// Git 웹훅 목록 조회 + 등록/삭제 TanStack Query 훅 테스트 — queryKey 검증 + mutation invalidate-only(setQueryData 금지) 검증
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach, afterAll } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { setupServer } from 'msw/node'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { GIT_WEBHOOKS_QUERY_KEY, useGitWebhooks, useCreateGitWebhook, useDeleteGitWebhook } from './useGitWebhooks'
import { gitWebhookHandlers } from '@/mocks/git-webhook-handlers'
import {
  DEFAULT_GIT_WEBHOOK_PROJECT_KEY,
  DEFAULT_GIT_WEBHOOKS,
  resetGitWebhookStore,
  seedGitWebhooks,
} from '@/mocks/git-webhook-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — automation-git-webhooks.test.ts / useAutomationRules.test.tsx와 동형
// (전역 handlers.ts에 gitWebhookHandlers 미등록, 로컬 서버로 격리)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...gitWebhookHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  server.resetHandlers()
  resetGitWebhookStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})
afterAll(() => server.close())

const PROJECT_KEY = DEFAULT_GIT_WEBHOOK_PROJECT_KEY
/** 백엔드 MIN_SECRET_LENGTH(16)를 만족하는 유효 secret — mock 로직과 맞춘다. */
const VALID_SECRET = 'a'.repeat(32)

/** DEFAULT_GIT_WEBHOOKS[0] — noUncheckedIndexedAccess 가드 헬퍼 */
function firstSeedWebhook() {
  const webhook = DEFAULT_GIT_WEBHOOKS[0]
  if (webhook === undefined) {
    throw new Error('fixture DEFAULT_GIT_WEBHOOKS[0]이 비어있음')
  }
  return webhook
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GIT_WEBHOOKS_QUERY_KEY — FR21 함수 헬퍼 형태(useAutomationRules.ts:32-35 동형)
// ─────────────────────────────────────────────────────────────────────────────

describe('GIT_WEBHOOKS_QUERY_KEY', () => {
  it('["automation-git-webhooks", projectKey] 를 낸다', () => {
    expect(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY)).toEqual(['automation-git-webhooks', PROJECT_KEY])
    expect(GIT_WEBHOOKS_QUERY_KEY('OTHER')).not.toEqual(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY))
  })
})

describe('useGitWebhooks 훅 묶음', () => {
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
  // useGitWebhooks
  // ─────────────────────────────────────────────────────────────────────────

  describe('useGitWebhooks', () => {
    it('올바른 queryKey로 목록을 로드한다', async () => {
      seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)

      const { result } = renderHook(() => useGitWebhooks(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data).toHaveLength(DEFAULT_GIT_WEBHOOKS.length)

      const cached = queryClient.getQueryData(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY))
      expect(cached).toBeDefined()
    })

    it('projectKey가 다르면 서로 다른 캐시 키를 사용한다 (filter-aware)', async () => {
      const { result } = renderHook(() => useGitWebhooks(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY)).toEqual(['automation-git-webhooks', PROJECT_KEY])
      expect(GIT_WEBHOOKS_QUERY_KEY('OTHER')).not.toEqual(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY))
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useCreateGitWebhook
  // ─────────────────────────────────────────────────────────────────────────

  describe('useCreateGitWebhook', () => {
    it('성공(201) 시 automation-git-webhooks 쿼리가 invalidate된다', async () => {
      queryClient.setQueryData(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY), [])

      const { result } = renderHook(() => useCreateGitWebhook(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({ provider: 'GITHUB', secret: VALID_SECRET })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY))
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('생성 성공 시 mutateAsync 반환값에 token 원문이 담긴다', async () => {
      const { result } = renderHook(() => useCreateGitWebhook(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      const response = await act(async () => result.current.mutateAsync({ provider: 'GITLAB', secret: VALID_SECRET }))

      expect(typeof response.token).toBe('string')
      expect(response.provider).toBe('GITLAB')
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only, 부분응답 플리커 회귀 방지)', async () => {
      queryClient.setQueryData(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY), [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useCreateGitWebhook(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({ provider: 'GITHUB', secret: VALID_SECRET })
      })

      expect(spy).not.toHaveBeenCalledWith(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY), expect.anything())
      spy.mockRestore()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useDeleteGitWebhook
  // ─────────────────────────────────────────────────────────────────────────

  describe('useDeleteGitWebhook', () => {
    it('성공(204) 시 automation-git-webhooks 쿼리가 invalidate된다', async () => {
      seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
      const target = firstSeedWebhook()
      queryClient.setQueryData(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY), [])

      const { result } = renderHook(() => useDeleteGitWebhook(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync(target.id)
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY))
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('삭제 후 목록을 재조회하면 해당 웹훅이 사라진다', async () => {
      seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
      const target = firstSeedWebhook()
      const wrapper = createWrapper(queryClient)
      const { result } = renderHook(
        () => ({ list: useGitWebhooks(PROJECT_KEY), del: useDeleteGitWebhook(PROJECT_KEY) }),
        { wrapper },
      )

      await waitFor(() => expect(result.current.list.isSuccess).toBe(true))
      const beforeCount = result.current.list.data?.length ?? 0

      await act(async () => {
        await result.current.del.mutateAsync(target.id)
      })

      await waitFor(() => {
        expect(result.current.list.data?.length).toBe(beforeCount - 1)
        expect(result.current.list.data?.find((webhook) => webhook.id === target.id)).toBeUndefined()
      })
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
      seedGitWebhooks(DEFAULT_GIT_WEBHOOKS)
      const target = firstSeedWebhook()
      queryClient.setQueryData(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY), [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useDeleteGitWebhook(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync(target.id)
      })

      expect(spy).not.toHaveBeenCalledWith(GIT_WEBHOOKS_QUERY_KEY(PROJECT_KEY), expect.anything())
      spy.mockRestore()
    })
  })
})
