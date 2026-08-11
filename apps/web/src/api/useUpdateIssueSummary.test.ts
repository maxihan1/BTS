// 이슈 요약 수정 훅 단위 테스트 — 낙관적 업데이트 + 409 롤백 + sonner toast 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import type { IssueResponse } from '@/api/issues'
import { useUpdateIssueSummary } from './useUpdateIssueSummary'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast 모킹 — 실제 DOM 렌더링 없이 호출 여부만 검증
// ─────────────────────────────────────────────────────────────────────────────
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
  },
}))

// 모든 테스트 전에 toast mock 호출 기록 초기화 (테스트 간 오염 방지)
beforeEach(async () => {
  const { toast } = await import('sonner')
  vi.mocked(toast.error).mockClear()
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture
// ─────────────────────────────────────────────────────────────────────────────
const issueFixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  summary: '원본 요약',
  currentStateKey: 'open',
  reporterId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
  version: 1,
  createdAt: '2024-01-15T09:00:00Z',
  updatedAt: '2024-01-15T10:30:00Z',
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 테스트마다 독립적인 QueryClient + Provider 래퍼 생성
// ─────────────────────────────────────────────────────────────────────────────
function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }

  return { queryClient, Wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// T3-1. onMutate — 낙관적 업데이트
// ─────────────────────────────────────────────────────────────────────────────
describe('useUpdateIssueSummary — onMutate 낙관적 업데이트', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        // 응답을 지연시켜 캐시 중간 상태 검증
        await new Promise((resolve) => setTimeout(resolve, 50))
        return HttpResponse.json({
          data: { ...issueFixture, summary: '낙관적 요약', version: 2 },
        })
      }),
    )
  })

  it('T3-1a: mutate 직후 캐시에 낙관적 summary가 선반영된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '낙관적 요약', expectedVersion: 1 })
    })

    // mutation이 진행 중일 때 캐시가 이미 업데이트되어 있어야 함
    await waitFor(() => {
      const cached = queryClient.getQueryData<IssueResponse>(['issue', 'ATLAS-1'])
      expect(cached?.summary).toBe('낙관적 요약')
    })

    // 낙관적 반영만 보고 끝내면 mutation 이 pending 인 채 다음 파일로 번진다.
    await settlePendingMutations()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-2. 성공 — 응답 version 캐시 반영
// ─────────────────────────────────────────────────────────────────────────────
describe('useUpdateIssueSummary — 성공 시 version 반영', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        return HttpResponse.json({
          data: { ...issueFixture, summary: '수정된 요약', version: 2 },
        })
      }),
    )
  })

  it('T3-2a: 성공 응답의 version이 캐시에 반영된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '수정된 요약', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const cached = queryClient.getQueryData<IssueResponse>(['issue', 'ATLAS-1'])
    expect(cached?.version).toBe(2)
    expect(cached?.summary).toBe('수정된 요약')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-3. 409 VERSION_CONFLICT — 롤백 + toast.error 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useUpdateIssueSummary — 409 VERSION_CONFLICT 처리', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        return HttpResponse.json(
          { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
          { status: 409 },
        )
      }),
    )
  })

  it('T3-3a: 409 응답 시 캐시가 원본으로 롤백된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '충돌 요약', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<IssueResponse>(['issue', 'ATLAS-1'])
    expect(cached?.summary).toBe('원본 요약')
    expect(cached?.version).toBe(1)
  })

  it('T3-3b: 409 응답 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '충돌 요약', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('T3-3c: 409 에러가 ApiError 인스턴스이고 status 409를 가진다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '충돌 요약', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3-4. onSettled — 성공/실패 무관 invalidateQueries 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useUpdateIssueSummary — onSettled invalidateQueries', () => {
  it('T3-4a: 성공 후 onSettled에서 쿼리가 stale로 마킹된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        return HttpResponse.json({
          data: { ...issueFixture, summary: '수정됨', version: 2 },
        })
      }),
    )

    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '수정됨', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })

  it('T3-4b: 실패 후 onSettled에서도 쿼리가 stale로 마킹된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        return HttpResponse.json(
          { errorCode: 'VERSION_CONFLICT' },
          { status: 409 },
        )
      }),
    )

    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateIssueSummary(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', summary: '실패', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })
})
