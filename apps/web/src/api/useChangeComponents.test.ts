// useChangeComponents mutation 훅 단위 테스트 — invalidate-only + 409/422 toast 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import type { IssueResponse } from '@/api/issues'
import { useChangeComponents } from './useChangeComponents'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast 모킹
// ─────────────────────────────────────────────────────────────────────────────
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
  },
}))

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
  summary: '테스트 이슈',
  currentStateKey: 'open',
  reporterId: 'f0e9d8c7-b6a5-4321-8edc-ba9876543210',
  assigneeId: null,
  componentIds: [],
  version: 1,
  createdAt: '2024-01-15T09:00:00Z',
  updatedAt: null,
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
}

const componentId = 'c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
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
// T-CC-1. 성공 시 invalidateQueries(['issue', key]) 호출 (setQueryData 금지)
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeComponents — 성공 시 invalidate-only', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({
          data: { ...issueFixture, version: 2 },
        }),
      ),
    )
  })

  it('T-CC-1a: 성공 후 invalidateQueries([issue, key])가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })

  it('T-CC-1b: 성공 후 setQueryData가 직접 호출되지 않는다 (descriptionHtml 플리커 방지)', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CC-2. 409 VERSION_CONFLICT — toast.error 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeComponents — 409 VERSION_CONFLICT', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
  })

  it('T-CC-2a: 409 응답 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('T-CC-2b: 409 에러가 ApiError(409) 인스턴스다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CC-3. 422 COMPONENT_NOT_FOUND — toast.error 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeComponents — 422 COMPONENT_NOT_FOUND', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({ errorCode: 'COMPONENT_NOT_FOUND' }, { status: 422 }),
      ),
    )
  })

  it('T-CC-3a: 422 응답 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: ['00000000-0000-4000-8000-000000000099'], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CC-4. onSettled — 실패 시에도 invalidateQueries 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeComponents — onSettled invalidateQueries', () => {
  it('T-CC-4a: 실패 후 onSettled에서도 invalidateQueries가 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )

    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CC-5. i18n 문자열 참조 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeComponents — i18n 문자열 참조', () => {
  it('T-CC-5a: 422 응답 시 toast.error가 issueDetailStrings.componentNotFoundError 값으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({ errorCode: 'COMPONENT_NOT_FOUND' }, { status: 422 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: ['00000000-0000-4000-8000-000000000099'], expectedVersion: 1 })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.componentNotFoundError)
  })

  it('T-CC-5b: 409 응답 시 toast.error가 issueDetailStrings.versionConflictError 값으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 0 })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.versionConflictError)
  })

  it('T-CC-5c: 기타 에러 시 toast.error가 issueDetailStrings.componentChangeError 값으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/components', () =>
        HttpResponse.json({ message: 'Internal Server Error' }, { status: 500 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeComponents(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ key: 'ATLAS-1', componentIds: [componentId], expectedVersion: 1 })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.componentChangeError)
  })
})
