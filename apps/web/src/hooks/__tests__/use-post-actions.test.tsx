// 워크플로우 전이 post-action CRUD TanStack Query hooks 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { PostActionResponse } from '@/api/post-actions'
import {
  usePostActions,
  useAddPostAction,
  useUpdatePostAction,
  useRemovePostAction,
} from '../use-post-actions'

/** 테스트마다 독립 캐시를 가진 QueryClient 래퍼 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

const WF_KEY = 'software-workflow'
const TX_KEY = 'open__in-progress'
const BASE_PATH = `/api/v1/workflows/${WF_KEY}/transitions/${TX_KEY}/post-actions`

const sampleAction: PostActionResponse = {
  id: '550e8400-e29b-41d4-a716-446655440001',
  type: 'CALL_WEBHOOK',
  config: { url: 'https://example.com/hook' },
  displayOrder: 0,
}

describe('usePostActions', () => {
  it('workflowKey + transitionKey로 post-action 목록을 조회한다', async () => {
    server.use(
      http.get(BASE_PATH, () => HttpResponse.json({ data: [sampleAction] })),
    )

    const { result } = renderHook(
      () => usePostActions(WF_KEY, TX_KEY),
      { wrapper: createWrapper() },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.id).toBe(sampleAction.id)
  })

  it('transitionKey가 빈 문자열이면 query가 비활성화된다', () => {
    const { result } = renderHook(
      () => usePostActions(WF_KEY, ''),
      { wrapper: createWrapper() },
    )

    // enabled: false → fetchStatus는 'idle', data는 undefined
    expect(result.current.fetchStatus).toBe('idle')
    expect(result.current.data).toBeUndefined()
  })

  it('workflowKey가 빈 문자열이면 query가 비활성화된다', () => {
    const { result } = renderHook(
      () => usePostActions('', TX_KEY),
      { wrapper: createWrapper() },
    )

    expect(result.current.fetchStatus).toBe('idle')
    expect(result.current.data).toBeUndefined()
  })
})

describe('useAddPostAction', () => {
  it('post-action 생성 성공 시 목록 캐시를 무효화(invalidate)한다', async () => {
    let listFetchCount = 0

    server.use(
      http.get(BASE_PATH, () => {
        listFetchCount++
        return HttpResponse.json({ data: [sampleAction] })
      }),
      http.post(BASE_PATH, () =>
        HttpResponse.json({ data: sampleAction }, { status: 201 }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    // 목록 캐시를 먼저 채운다
    const listHook = renderHook(() => usePostActions(WF_KEY, TX_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const prevFetchCount = listFetchCount

    const { result } = renderHook(
      () => useAddPostAction(WF_KEY, TX_KEY),
      { wrapper },
    )

    act(() => {
      result.current.mutate({
        type: 'CALL_WEBHOOK',
        config: { url: 'https://example.com/hook' },
        displayOrder: 0,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // invalidate 후 재조회가 발생해야 한다
    await waitFor(() => expect(listFetchCount).toBeGreaterThan(prevFetchCount))
  })

  it('생성 실패 시 isError가 true다', async () => {
    server.use(
      http.post(BASE_PATH, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_POST_ACTION_INVALID', message: '잘못된 요청' } },
          { status: 400 },
        ),
      ),
    )

    const { result } = renderHook(
      () => useAddPostAction(WF_KEY, TX_KEY),
      { wrapper: createWrapper() },
    )

    act(() => {
      result.current.mutate({ type: '', config: {}, displayOrder: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})

describe('useUpdatePostAction', () => {
  it('post-action 수정 성공 시 목록 캐시를 무효화(invalidate)한다', async () => {
    let listFetchCount = 0
    const actionId = sampleAction.id

    server.use(
      http.get(BASE_PATH, () => {
        listFetchCount++
        return HttpResponse.json({ data: [sampleAction] })
      }),
      http.put(`${BASE_PATH}/${actionId}`, () =>
        HttpResponse.json({ data: { ...sampleAction, displayOrder: 1 } }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    // 목록 캐시를 먼저 채운다
    const listHook = renderHook(() => usePostActions(WF_KEY, TX_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const prevFetchCount = listFetchCount

    const { result } = renderHook(
      () => useUpdatePostAction(WF_KEY, TX_KEY, actionId),
      { wrapper },
    )

    act(() => {
      result.current.mutate({
        type: 'CALL_WEBHOOK',
        config: { url: 'https://example.com/hook' },
        displayOrder: 1,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // invalidate 후 재조회가 발생해야 한다
    await waitFor(() => expect(listFetchCount).toBeGreaterThan(prevFetchCount))
  })
})

describe('useRemovePostAction', () => {
  it('post-action 삭제 성공 시 목록 캐시를 무효화(invalidate)한다', async () => {
    let listFetchCount = 0
    const actionId = sampleAction.id

    server.use(
      http.get(BASE_PATH, () => {
        listFetchCount++
        return HttpResponse.json({ data: [sampleAction] })
      }),
      http.delete(`${BASE_PATH}/${actionId}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    // 목록 캐시를 먼저 채운다
    const listHook = renderHook(() => usePostActions(WF_KEY, TX_KEY), { wrapper })
    await waitFor(() => expect(listHook.result.current.isSuccess).toBe(true))
    const prevFetchCount = listFetchCount

    const { result } = renderHook(
      () => useRemovePostAction(WF_KEY, TX_KEY),
      { wrapper },
    )

    act(() => {
      result.current.mutate(actionId)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // invalidate 후 재조회가 발생해야 한다
    await waitFor(() => expect(listFetchCount).toBeGreaterThan(prevFetchCount))
  })

  it('삭제 실패 시 isError가 true다', async () => {
    const actionId = sampleAction.id

    server.use(
      http.delete(`${BASE_PATH}/${actionId}`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_POST_ACTION_NOT_FOUND', message: '없는 항목' } },
          { status: 404 },
        ),
      ),
    )

    const { result } = renderHook(
      () => useRemovePostAction(WF_KEY, TX_KEY),
      { wrapper: createWrapper() },
    )

    act(() => {
      result.current.mutate(actionId)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
