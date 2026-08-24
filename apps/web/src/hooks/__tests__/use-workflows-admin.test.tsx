// 워크플로우 관리 TanStack Query 훅 테스트 — 캐시 분열·무효화·에러 토스트
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { WORKFLOW_QUERY_KEY } from '../use-workflows'
import {
  WORKFLOW_ADMIN_KEYS,
  useStatusCatalog,
  useWorkflowDetail,
  useUpdateWorkflow,
  useRemoveWorkflowStatus,
} from '../use-workflows-admin'

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }))

const STATUS_ID = '11111111-1111-4111-8111-111111111111'

function wrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}

describe('WORKFLOW_ADMIN_KEYS — 캐시 분열 금지', () => {
  it('목록 키가 use-workflows 의 키와 동일하다', () => {
    // 다른 키를 쓰면 편집 후 필터 UI(useWorkflows)가 낡은 목록을 계속 준다.
    // 두 캐시가 서로를 모르는 상태는 화면에서만 드러나므로 여기서 못박는다.
    expect(WORKFLOW_ADMIN_KEYS.list).toEqual(WORKFLOW_QUERY_KEY)
  })

  it('상세 키가 워크플로우마다 다르다', () => {
    expect(WORKFLOW_ADMIN_KEYS.detail('a')).not.toEqual(WORKFLOW_ADMIN_KEYS.detail('b'))
  })
})

describe('useStatusCatalog', () => {
  it('전역 상태 카탈로그를 조회한다', async () => {
    server.use(
      http.get('/api/v1/statuses', () =>
        HttpResponse.json({
          data: [{ id: STATUS_ID, key: 'todo', name: '할 일', description: null, category: 'TODO', isSystem: true }],
        }),
      ),
    )
    const { result } = renderHook(() => useStatusCatalog(), { wrapper: wrapper(newClient()) })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]?.name).toBe('할 일')
  })
})

describe('useWorkflowDetail', () => {
  it('단건을 조회한다', async () => {
    server.use(
      http.get('/api/v1/workflows/custom', () =>
        HttpResponse.json({
          data: {
            key: 'custom',
            name: '커스텀',
            description: '',
            states: [{ key: 'todo', name: '할 일', category: 'TODO', displayOrder: 0 }],
            transitions: [],
          },
        }),
      ),
    )
    const { result } = renderHook(() => useWorkflowDetail('custom'), { wrapper: wrapper(newClient()) })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.name).toBe('커스텀')
  })
})

describe('useUpdateWorkflow — 무효화 범위', () => {
  it('성공하면 목록과 상세를 함께 무효화한다', async () => {
    server.use(http.put('/api/v1/workflows/custom', () => HttpResponse.json({ data: null })))
    const client = newClient()
    const spy = vi.spyOn(client, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateWorkflow('custom'), { wrapper: wrapper(client) })
    result.current.mutate({ name: '새 이름', description: null })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const invalidated = spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey))
    expect(invalidated).toContain(JSON.stringify(WORKFLOW_ADMIN_KEYS.list))
    expect(invalidated).toContain(JSON.stringify(WORKFLOW_ADMIN_KEYS.detail('custom')))
  })
})

describe('mutation 실패 — 사유별 토스트', () => {
  beforeEach(() => vi.clearAllMocks())

  it('전환이 가리키는 상태를 빼려 하면 전환 때문이라고 알린다', async () => {
    server.use(
      http.delete(`/api/v1/workflows/custom/statuses/${STATUS_ID}`, () =>
        HttpResponse.json({ code: 'WORKFLOW_STATUS_REFERENCED_BY_TRANSITION', detail: '' }, { status: 409 }),
      ),
    )
    const { result } = renderHook(() => useRemoveWorkflowStatus('custom'), { wrapper: wrapper(newClient()) })
    result.current.mutate(STATUS_ID)
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(vi.mocked(toast.error).mock.calls[0]?.[0]).toContain('전환')
  })
})
