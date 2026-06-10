// useChangeAssignee mutation 훅 단위 테스트 — invalidate-only + 409/422 toast 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import type { IssueResponse } from '@/api/issues'
import { useChangeAssignee } from './useChangeAssignee'
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
  affectsVersionIds: [],
  fixVersionIds: [],
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
  restrictedFields: [],
  noneditableFields: [],
}

const assigneeId = 'b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e'

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
// T-CA-1. 성공 시 invalidateQueries(['issue', key]) 호출 (setQueryData 금지)
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAssignee — 성공 시 invalidate-only', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({
          data: { ...issueFixture, assigneeId, version: 2 },
        }),
      ),
    )
  })

  it('T-CA-1a: 성공 후 invalidateQueries([issue, key])가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })

  it('T-CA-1b: 성공 후 setQueryData가 직접 호출되지 않는다 (descriptionHtml 플리커 방지)', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CA-2. 409 VERSION_CONFLICT — toast.error 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAssignee — 409 VERSION_CONFLICT', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
  })

  it('T-CA-2a: 409 응답 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('T-CA-2b: 409 에러가 ApiError(409) 인스턴스다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CA-3. 422 ASSIGNEE_NOT_FOUND — toast.error 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAssignee — 422 ASSIGNEE_NOT_FOUND', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'ASSIGNEE_NOT_FOUND' }, { status: 422 }),
      ),
    )
  })

  it('T-CA-3a: 422 응답 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId: 'nonexistent-0000-0000-0000-000000000000', expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CA-5. S1 — toast 메시지가 ko.ts issueDetailStrings 상수를 참조한다
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAssignee — S1 i18n 문자열 참조', () => {
  it('T-CA-5a: 422 응답 시 toast.error가 issueDetailStrings.assigneeNotFoundError 값으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'ASSIGNEE_NOT_FOUND' }, { status: 422 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId: 'nonexistent-0000-0000-0000-000000000000', expectedVersion: 1 })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.assigneeNotFoundError)
  })

  it('T-CA-5b: 409 응답 시 toast.error가 issueDetailStrings.versionConflictError 값으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 0 })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.versionConflictError)
  })

  it('T-CA-5c: 기타 에러 시 toast.error가 issueDetailStrings.assigneeChangeError 값으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ message: 'Internal Server Error' }, { status: 500 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })
    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 1 })
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.assigneeChangeError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-CA-4. onSettled — 실패 시에도 invalidateQueries 호출
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAssignee — onSettled invalidateQueries', () => {
  it('T-CA-4a: 실패 후 onSettled에서도 invalidateQueries가 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )

    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeAssignee(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', assigneeId, expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })
})
