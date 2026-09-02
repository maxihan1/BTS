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
  useDeleteWorkflow,

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

describe('useDeleteWorkflow — 무효화 범위', () => {
  it('성공하면 목록을 무효화하고 상세 캐시는 제거한다', async () => {
    // ★ 종전 이 자리는 `useUpdateWorkflow` 를 재고 있었다. 그 훅은 초안 전환(FR-WF-07 D6)으로
    //   소비처가 사라져 함께 지웠고, 「쓰기 뒤 무효화」 계약은 살아 있는 훅으로 옮겼다 —
    //   계약을 재는 판정까지 함께 지우면 그 계약이 아무도 안 보는 상태가 된다.
    server.use(http.delete('/api/v1/workflows/custom', () => new HttpResponse(null, { status: 204 })))
    const client = newClient()
    const spy = vi.spyOn(client, 'invalidateQueries')
    const removeSpy = vi.spyOn(client, 'removeQueries')

    const { result } = renderHook(() => useDeleteWorkflow(), { wrapper: wrapper(client) })
    result.current.mutate('custom')
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const invalidated = spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey))
    expect(invalidated).toContain(JSON.stringify(WORKFLOW_ADMIN_KEYS.list))
    // 지운 워크플로우는 되살릴 대상이 없다 — 무효화가 아니라 제거다.
    const removed = removeSpy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey))
    expect(removed).toContain(JSON.stringify(WORKFLOW_ADMIN_KEYS.detail('custom')))
  })
})

describe('mutation 실패 — 사유별 토스트', () => {
  beforeEach(() => vi.clearAllMocks())

  it('서버가 실어 보낸 막은 대상 이름을 버리지 않는다', async () => {
    // 종전에는 상태 편성 제거로 쟀다. 그 훅이 사라져 살아 있는 쓰기 경로로 옮겼고,
    // 재는 계약(코드별 문구 + 서버 문장 덧붙임)은 그대로다.
    server.use(
      http.delete('/api/v1/workflows/custom', () =>
        // 백엔드와 같은 중첩 봉투. 평면으로 적으면 파서가 UNKNOWN 으로 떨어뜨린다.
        HttpResponse.json(
          { error: { code: 'WORKFLOW_IN_USE', message: '스킴 「기본」이 사용 중' } },
          { status: 409 },
        ),
      ),
    )
    const { result } = renderHook(() => useDeleteWorkflow(), { wrapper: wrapper(newClient()) })
    result.current.mutate('custom')
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(vi.mocked(toast.error).mock.calls[0]?.[0]).toContain('스킴')
  })
})
