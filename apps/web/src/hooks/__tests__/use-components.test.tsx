// 컴포넌트 BC TanStack Query 훅 테스트 — RED phase (FR-CM-01 Task 4)
import React from 'react'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { componentHandlers, resetComponentStore } from '@/mocks/component-handlers'
import type { Component } from '@/api/components.types'
import {
  useComponents,
  useCreateComponent,
  useUpdateComponent,
  useChangeComponentLead,
  useDeleteComponent,
  COMPONENT_KEYS,
} from '../use-components'

// sonner toast spy
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENT_KEYS — queryKey 팩토리 정확성
// ─────────────────────────────────────────────────────────────────────────────

describe('COMPONENT_KEYS', () => {
  it('list 키는 projectKey를 포함한다', () => {
    const key = COMPONENT_KEYS.list('ATLAS')
    expect(key).toContain('ATLAS')
  })

  it('list 키는 동일 projectKey에 대해 동일한 배열을 반환한다', () => {
    expect(COMPONENT_KEYS.list('ATLAS')).toEqual(COMPONENT_KEYS.list('ATLAS'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useComponents — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

describe('useComponents', () => {
  beforeEach(() => {
    resetComponentStore()
    server.use(...componentHandlers)
  })

  it('enabled: false 옵션 전달 시 쿼리가 idle 상태가 되고 fetch가 발생하지 않는다', async () => {
    const fetchSpy = vi.fn()
    server.use(
      http.get('/api/v1/projects/ATLAS/components', () => {
        fetchSpy()
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useComponents('ATLAS', { enabled: false }), { wrapper })

    // idle 상태 — fetchStatus: 'idle', data: undefined
    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('enabled: true 옵션 전달 시 fetch가 발생한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useComponents('ATLAS', { enabled: true }), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // data가 배열로 채워지면 fetch가 일어났음을 의미
    expect(result.current.data).toBeDefined()
  })

  it('options 미전달 시 기본 enabled=true로 fetch가 발생한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useComponents('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toBeDefined()
  })

  it('컴포넌트 목록을 조회해 반환한다', async () => {
    // 사전 조건: 컴포넌트를 하나 추가해 둔다 (MSW 핸들러 직접 사용)
    server.use(
      http.get('/api/v1/projects/ATLAS/components', () =>
        HttpResponse.json({
          data: [
            {
              id: '11111111-1111-4111-8111-111111111111',
              projectId: '22222222-2222-4222-8222-222222222222',
              name: '백엔드',
              description: null,
              leadUserId: null,
            },
          ],
        }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useComponents('ATLAS'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toBeDefined()
    expect(result.current.data?.length).toBeGreaterThanOrEqual(1)
  })

  it('컴포넌트가 없는 프로젝트는 빈 배열을 반환한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useComponents('EMPTY'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([])
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/components', async () => {
        await new Promise((resolve) => setTimeout(resolve, 200))
        return HttpResponse.json({ data: [] })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useComponents('ATLAS'), { wrapper })

    expect(result.current.isPending).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCreateComponent — 생성 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useCreateComponent', () => {
  beforeEach(() => {
    resetComponentStore()
    server.use(...componentHandlers)
  })

  it('생성 성공 시 목록 쿼리가 invalidate되어 refetch 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    // 초기 목록 캐시 채우기
    const listHook = renderHook(() => useComponents('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))

    const initialCount =
      (client.getQueryData<Component[]>(COMPONENT_KEYS.list('ATLAS')) ?? []).length

    const { result } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })

    await act(async () => {
      result.current.mutate({ name: '새 컴포넌트' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidate 후 refetch — 목록이 늘어나야 한다
    await waitFor(() => {
      const cached = client.getQueryData<Component[]>(COMPONENT_KEYS.list('ATLAS')) ?? []
      return cached.length > initialCount
    })
  })

  it('이름 중복(409) 시 toast.error가 호출된다', async () => {
    const { wrapper } = createWrapper()

    // 먼저 같은 이름으로 하나 만든다
    const { result: first } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })
    await act(async () => {
      first.current.mutate({ name: '중복이름' })
    })
    await waitFor(() => expect(first.current.isSuccess).toBe(true))

    // 두 번째 같은 이름 시도 — 409
    const { result: second } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })
    await act(async () => {
      second.current.mutate({ name: '중복이름' })
    })

    await waitFor(() => expect(second.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateComponent — 수정 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateComponent', () => {
  beforeEach(() => {
    resetComponentStore()
    server.use(...componentHandlers)
  })

  it('수정 성공 시 목록 쿼리가 invalidate되어 갱신된 이름이 반영된다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 컴포넌트 생성
    const { result: create } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: '원래이름' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    // 목록 조회로 id 획득
    const listHook = renderHook(() => useComponents('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useUpdateComponent('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({ id: created!.id, input: { name: '바뀐이름' } })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<Component[]>(COMPONENT_KEYS.list('ATLAS')) ?? []
      return cached.some((c) => c.name === '바뀐이름')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeComponentLead — 리드 변경 + invalidate + 에러 토스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeComponentLead', () => {
  beforeEach(() => {
    resetComponentStore()
    server.use(...componentHandlers)
    // toast.error mock 초기화
    vi.mocked(toast.error).mockClear()
    // localStorage 플래그 초기화
    globalThis.localStorage?.removeItem('msw-component-lead-422')
  })

  it('리드 변경 성공 시 목록 쿼리가 invalidate된다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 컴포넌트 생성
    const { result: create } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: '리드테스트' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useComponents('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useChangeComponentLead('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({
        id: created!.id,
        leadUserId: '33333333-3333-4333-8333-333333333333',
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidate 후 refetch — queryKey가 캐시에 있어야 한다
    expect(
      client.getQueryState(COMPONENT_KEYS.list('ATLAS')),
    ).toBeDefined()
  })

  it('422 에러 시 toast.error가 COMPONENT_LEAD_NOT_FOUND 메시지로 호출된다', async () => {
    // localStorage 플래그 세팅 → MSW가 422 반환
    globalThis.localStorage?.setItem('msw-component-lead-422', 'true')

    const { wrapper } = createWrapper()

    // 사전 컴포넌트 생성
    const { result: create } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: '리드422테스트' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useComponents('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useChangeComponentLead('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate({
        id: created!.id,
        leadUserId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalledWith('선택한 리드 사용자를 찾을 수 없습니다.')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useDeleteComponent — 삭제 + invalidate
// ─────────────────────────────────────────────────────────────────────────────

describe('useDeleteComponent', () => {
  beforeEach(() => {
    resetComponentStore()
    server.use(...componentHandlers)
  })

  it('삭제 성공 시 목록에서 해당 컴포넌트가 사라진다', async () => {
    const { client, wrapper } = createWrapper()

    // 사전 컴포넌트 생성
    const { result: create } = renderHook(() => useCreateComponent('ATLAS'), { wrapper })
    await act(async () => {
      create.current.mutate({ name: '삭제할컴포넌트' })
    })
    await waitFor(() => expect(create.current.isSuccess).toBe(true))

    const listHook = renderHook(() => useComponents('ATLAS'), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const created = listHook.result.current.data?.[0]
    expect(created).toBeDefined()

    const { result } = renderHook(() => useDeleteComponent('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate(created!.id)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    await waitFor(() => {
      const cached = client.getQueryData<Component[]>(COMPONENT_KEYS.list('ATLAS')) ?? []
      return !cached.some((c) => c.id === created!.id)
    })
  })

  it('존재하지 않는 id 삭제(404) 시 toast.error가 호출된다', async () => {
    vi.mocked(toast.error).mockClear()
    const { wrapper } = createWrapper()

    const { result } = renderHook(() => useDeleteComponent('ATLAS'), { wrapper })
    await act(async () => {
      result.current.mutate('00000000-dead-4000-8000-000000000000')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalled()
  })
})
