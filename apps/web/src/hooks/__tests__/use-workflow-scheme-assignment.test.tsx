// 프로젝트-스킴 할당 TanStack Query hooks 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { AssignmentResponse } from '@/api/workflow-schemes'
import {
  useGetAssignment,
  useUpdateAssignment,
} from '../use-workflow-scheme-assignment'

/** 테스트마다 독립 캐시를 가진 QueryClient 래퍼 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

describe('useGetAssignment', () => {
  it('할당이 있을 때 AssignmentResponse를 반환한다', async () => {
    const assignment: AssignmentResponse = {
      projectKey: 'ATLAS',
      schemeKey: 'custom-scheme-alpha',
      schemeName: '사내 개발팀 커스텀 스킴',
    }

    server.use(
      http.get('/api/v1/projects/ATLAS/workflow-scheme', () =>
        HttpResponse.json({ data: assignment }),
      ),
    )

    const { result } = renderHook(() => useGetAssignment('ATLAS'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(assignment)
  })

  it('404 (EC-1 — 미할당) 시 null을 정상 반환한다', async () => {
    server.use(
      http.get('/api/v1/projects/UNASSIGNED/workflow-scheme', () =>
        new HttpResponse(null, { status: 404 }),
      ),
    )

    const { result } = renderHook(() => useGetAssignment('UNASSIGNED'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toBeNull()
  })

  it('로딩 중일 때 isPending이 true다', () => {
    server.use(
      http.get('/api/v1/projects/LOADING/workflow-scheme', () =>
        HttpResponse.json({ data: null }),
      ),
    )

    const { result } = renderHook(() => useGetAssignment('LOADING'), {
      wrapper: createWrapper(),
    })

    expect(result.current.isPending).toBe(true)
  })
})

describe('useUpdateAssignment', () => {
  it('UPSERT 성공 시 AssignmentResponse를 반환한다', async () => {
    const updated: AssignmentResponse = {
      projectKey: 'BTS',
      schemeKey: 'software-default-scheme',
      schemeName: '소프트웨어 개발 기본 스킴',
    }

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ data: updated }),
      ),
    )

    const { result } = renderHook(() => useUpdateAssignment('BTS'), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ schemeKey: 'software-default-scheme' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(updated)
  })

  it('낙관적 업데이트 후 실패 시 이전 값으로 롤백한다', async () => {
    const existing: AssignmentResponse = {
      projectKey: 'BTS',
      schemeKey: 'software-default-scheme',
      schemeName: '소프트웨어 개발 기본 스킴',
    }

    server.use(
      http.get('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ data: existing }),
      ),
      http.put('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ code: 'SCHEME_NOT_FOUND', detail: '없음' }, { status: 404 }),
      ),
    )

    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )

    // 먼저 assignment 캐시 채우기
    const assignmentHook = renderHook(() => useGetAssignment('BTS'), { wrapper })
    await waitFor(() => expect(assignmentHook.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useUpdateAssignment('BTS'), { wrapper })

    act(() => {
      result.current.mutate({ schemeKey: 'nonexistent-scheme' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 롤백 후 기존 캐시 복구 확인
    const cached = client.getQueryData<AssignmentResponse | null>([
      'projects',
      'BTS',
      'workflow-scheme',
    ])
    expect(cached).toEqual(existing)
  })

  it('성공 시 toast.success를 호출한다', async () => {
    const updated: AssignmentResponse = {
      projectKey: 'BTS',
      schemeKey: 'new-scheme',
      schemeName: '새 스킴',
    }

    server.use(
      http.put('/api/v1/projects/BTS/workflow-scheme', () =>
        HttpResponse.json({ data: updated }),
      ),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useUpdateAssignment('BTS'), {
      wrapper: createWrapper(),
    })

    act(() => {
      result.current.mutate({ schemeKey: 'new-scheme' }, { onSuccess })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(onSuccess).toHaveBeenCalledOnce()
  })
})
