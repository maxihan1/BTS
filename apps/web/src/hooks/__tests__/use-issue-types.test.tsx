// 이슈 타입 TanStack Query hook 테스트 — RED phase
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { IssueTypeResponse } from '@/api/issue-types'
import { useIssueTypes } from '../use-issue-types'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('useIssueTypes', () => {
  it('5 표준 이슈 타입 목록을 반환한다', async () => {
    const issueTypes: IssueTypeResponse[] = [
      { key: 'bug', name: '버그', description: '버그', iconUrl: null },
      { key: 'story', name: '스토리', description: '스토리', iconUrl: null },
      { key: 'task', name: '작업', description: '작업', iconUrl: null },
      { key: 'epic', name: '에픽', description: '에픽', iconUrl: null },
      { key: 'subtask', name: '하위 작업', description: '하위 작업', iconUrl: null },
    ]

    server.use(
      http.get('/api/v1/issue-types', () =>
        HttpResponse.json({ data: issueTypes }),
      ),
    )

    const { result } = renderHook(() => useIssueTypes(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(5)
    expect(result.current.data?.[0]?.key).toBe('bug')
  })

  it('로딩 중일 때 isLoading이 true다', () => {
    server.use(
      http.get('/api/v1/issue-types', () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    const { result } = renderHook(() => useIssueTypes(), {
      wrapper: createWrapper(),
    })

    expect(result.current.isLoading || result.current.isPending).toBe(true)
  })

  it('staleTime이 1시간으로 설정돼 반복 조회 시 캐시를 재사용한다', async () => {
    let fetchCount = 0

    server.use(
      http.get('/api/v1/issue-types', () => {
        fetchCount++
        return HttpResponse.json({ data: [] })
      }),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    const hook1 = renderHook(() => useIssueTypes(), { wrapper })
    await waitFor(() => expect(hook1.result.current.isSuccess).toBe(true))

    // 같은 QueryClient로 두 번째 훅 — staleTime 내이므로 재요청 없음
    const hook2 = renderHook(() => useIssueTypes(), { wrapper })
    await waitFor(() => expect(hook2.result.current.isSuccess).toBe(true))

    expect(fetchCount).toBe(1)
  })
})
