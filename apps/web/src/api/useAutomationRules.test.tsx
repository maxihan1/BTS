// 자동화 룰 React Query 훅 테스트 — queryKey 검증 + mutation invalidate-only(setQueryData 금지) 검증
import { server } from '@/test/server'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import {
  AUTOMATION_RULES_QUERY_KEY,
  useAutomationRules,
  useCreateAutomationRule,
  useUpdateAutomationRule,
  useDeleteAutomationRule,
} from './useAutomationRules'
import { automationRuleHandlers } from '@/mocks/automation-rule-handlers'
import {
  DEFAULT_AUTOMATION_PROJECT_KEY,
  DEFAULT_AUTOMATION_RULES,
  resetAutomationRuleStore,
  seedAutomationRules,
} from '@/mocks/automation-rule-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — automation-rules.test.ts / automation-rule-handlers.test.ts와 동형
// (전역 handlers.ts에 automationRuleHandlers 미등록, 로컬 서버로 격리)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  server.use(...automationRuleHandlers)
})
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})
afterEach(() => {
  resetAutomationRuleStore()
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
})

const PROJECT_KEY = DEFAULT_AUTOMATION_PROJECT_KEY

/** DEFAULT_AUTOMATION_RULES[0] — noUncheckedIndexedAccess 가드 헬퍼 */
function firstSeedRule() {
  const rule = DEFAULT_AUTOMATION_RULES[0]
  if (rule === undefined) {
    throw new Error('fixture DEFAULT_AUTOMATION_RULES[0]이 비어있음')
  }
  return rule
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

describe('useAutomationRules 훅 묶음', () => {
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
  // useAutomationRules
  // ─────────────────────────────────────────────────────────────────────────

  describe('useAutomationRules', () => {
    it('올바른 queryKey로 목록을 로드한다', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)

      const { result } = renderHook(() => useAutomationRules(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data).toHaveLength(DEFAULT_AUTOMATION_RULES.length)

      const cached = queryClient.getQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY))
      expect(cached).toBeDefined()
    })

    it('projectKey가 다르면 서로 다른 캐시 키를 사용한다 (filter-aware)', async () => {
      const { result } = renderHook(() => useAutomationRules(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })
      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY)).toEqual(['automation-rules', PROJECT_KEY])
      expect(AUTOMATION_RULES_QUERY_KEY('OTHER')).not.toEqual(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY))
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useCreateAutomationRule
  // ─────────────────────────────────────────────────────────────────────────

  describe('useCreateAutomationRule', () => {
    it('성공(201) 시 automation-rules 쿼리가 invalidate된다', async () => {
      queryClient.setQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), [])

      const { result } = renderHook(() => useCreateAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({
          name: '새 룰',
          triggerType: 'ISSUE_CREATED',
          triggerConfig: '{}',
        })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY))
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('WEBHOOK 트리거 생성 시 mutateAsync 반환값에 webhookToken 원문이 담긴다', async () => {
      const { result } = renderHook(() => useCreateAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      const response = await act(async () =>
        result.current.mutateAsync({
          name: '웹훅 룰',
          triggerType: 'WEBHOOK',
          triggerConfig: '{}',
        }),
      )

      expect(typeof response.webhookToken).toBe('string')
      expect(response.rule.hasWebhookToken).toBe(true)
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only, 부분응답 플리커 회귀 방지)', async () => {
      queryClient.setQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useCreateAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({
          name: '플리커 방지 확인용',
          triggerType: 'ISSUE_COMMENTED',
          triggerConfig: '{}',
        })
      })

      expect(spy).not.toHaveBeenCalledWith(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), expect.anything())
      spy.mockRestore()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useUpdateAutomationRule
  // ─────────────────────────────────────────────────────────────────────────

  describe('useUpdateAutomationRule', () => {
    it('성공(200) 시 automation-rules 쿼리가 invalidate된다', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)
      const target = firstSeedRule()
      queryClient.setQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), [])

      const { result } = renderHook(() => useUpdateAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({ id: target.id, body: { version: target.version, enabled: false } })
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY))
      expect(queryState?.isInvalidated).toBe(true)
      expect(result.current.data?.enabled).toBe(false)
    })

    it('수정 후 목록을 재조회하면 변경 내용이 반영된다', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)
      const target = firstSeedRule()
      const wrapper = createWrapper(queryClient)
      const { result } = renderHook(
        () => ({ list: useAutomationRules(PROJECT_KEY), update: useUpdateAutomationRule(PROJECT_KEY) }),
        { wrapper },
      )

      await waitFor(() => expect(result.current.list.isSuccess).toBe(true))

      await act(async () => {
        await result.current.update.mutateAsync({ id: target.id, body: { version: target.version, enabled: false } })
      })

      await waitFor(() => {
        const updated = result.current.list.data?.find((rule) => rule.id === target.id)
        expect(updated?.enabled).toBe(false)
      })
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)
      const target = firstSeedRule()
      queryClient.setQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useUpdateAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync({ id: target.id, body: { version: target.version, enabled: false } })
      })

      expect(spy).not.toHaveBeenCalledWith(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), expect.anything())
      spy.mockRestore()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useDeleteAutomationRule
  // ─────────────────────────────────────────────────────────────────────────

  describe('useDeleteAutomationRule', () => {
    it('성공(204) 시 automation-rules 쿼리가 invalidate된다', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)
      const target = firstSeedRule()
      queryClient.setQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), [])

      const { result } = renderHook(() => useDeleteAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync(target.id)
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY))
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('삭제 후 목록을 재조회하면 해당 룰이 사라진다', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)
      const target = firstSeedRule()
      const wrapper = createWrapper(queryClient)
      const { result } = renderHook(
        () => ({ list: useAutomationRules(PROJECT_KEY), del: useDeleteAutomationRule(PROJECT_KEY) }),
        { wrapper },
      )

      await waitFor(() => expect(result.current.list.isSuccess).toBe(true))
      const beforeCount = result.current.list.data?.length ?? 0

      await act(async () => {
        await result.current.del.mutateAsync(target.id)
      })

      await waitFor(() => {
        expect(result.current.list.data?.length).toBe(beforeCount - 1)
        expect(result.current.list.data?.find((rule) => rule.id === target.id)).toBeUndefined()
      })
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only)', async () => {
      seedAutomationRules(DEFAULT_AUTOMATION_RULES)
      const target = firstSeedRule()
      queryClient.setQueryData(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), [])
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useDeleteAutomationRule(PROJECT_KEY), {
        wrapper: createWrapper(queryClient),
      })

      await act(async () => {
        await result.current.mutateAsync(target.id)
      })

      expect(spy).not.toHaveBeenCalledWith(AUTOMATION_RULES_QUERY_KEY(PROJECT_KEY), expect.anything())
      spy.mockRestore()
    })
  })
})
