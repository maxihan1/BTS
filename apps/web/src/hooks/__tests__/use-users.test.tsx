// useUsers TanStack Query 훅 단위 테스트 — fetchUsers 호출 + 쿼리 변경 시 재조회
import { describe, it, expect } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { UserSummary } from '@/api/users'
import { useUsers, useUsersByIds } from '../use-users'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

const usersFixture: UserSummary[] = [
  { id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890', username: 'alice', displayName: '김앨리스', email: null },
  { id: 'b2c3d4e5-f6a7-4891-bcde-ef2345678901', username: 'bob', displayName: null, email: null },
]

describe('useUsers', () => {
  it('T-UU-1: query 없이 호출 시 전체 사용자 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(usersFixture)),
    )

    const { result } = renderHook(() => useUsers(''), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.username).toBe('alice')
  })

  it('T-UU-2: query 문자열 전달 시 fetchUsers(query)를 호출한다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/users', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([usersFixture[0]])
      }),
    )

    const { result } = renderHook(() => useUsers('alice'), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(capturedUrl).toContain('query=alice')
    expect(result.current.data).toHaveLength(1)
  })

  it('T-UU-3: query 변경 시 새 쿼리키로 재조회한다', async () => {
    let fetchCount = 0
    server.use(
      http.get('/api/v1/users', () => {
        fetchCount++
        return HttpResponse.json(usersFixture)
      }),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const { result, rerender } = renderHook(
      ({ query }: { query: string }) => useUsers(query),
      { wrapper, initialProps: { query: '' } },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const firstFetchCount = fetchCount

    rerender({ query: 'alice' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(fetchCount).toBeGreaterThan(firstFetchCount)
  })

  it('T-UU-4: UserSummary[] 타입으로 데이터가 반환된다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(usersFixture)),
    )

    const { result } = renderHook(() => useUsers(''), { wrapper: createWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const data = result.current.data ?? []
    for (const user of data) {
      expect(typeof user.id).toBe('string')
      expect(typeof user.username).toBe('string')
    }
  })
})

describe('useUsersByIds', () => {
  it('T-UU-5a: ids 배열 전달 시 사용자 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json([usersFixture[0]])),
    )

    const { result } = renderHook(
      () => useUsersByIds(['a1b2c3d4-e5f6-4890-abcd-ef1234567890']),
      { wrapper: createWrapper() },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]?.username).toBe('alice')
  })

  it('T-UU-5b: ids가 빈 배열이면 쿼리가 실행되지 않는다(enabled:false)', async () => {
    let fetchCalled = false
    server.use(
      http.get('/api/v1/users', () => {
        fetchCalled = true
        return HttpResponse.json(usersFixture)
      }),
    )

    const { result } = renderHook(
      () => useUsersByIds([]),
      { wrapper: createWrapper() },
    )

    // fetchCalled가 false인 채로 상태가 idle이어야 한다
    expect(result.current.isFetching).toBe(false)
    expect(fetchCalled).toBe(false)
  })

  it('T-UU-5c: queryKey에 ids 배열이 포함된다 (캐시 분리)', async () => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json(usersFixture)),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const ids = ['a1b2c3d4-e5f6-4890-abcd-ef1234567890']
    const { result } = renderHook(() => useUsersByIds(ids), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // 캐시에 byIds queryKey로 저장됐는지 확인
    const cached = client.getQueryData(['users', 'byIds', ids])
    expect(cached).toBeDefined()
  })
})
