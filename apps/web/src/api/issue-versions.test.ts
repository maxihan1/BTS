// useChangeAffectsVersions / useChangeFixVersions mutation 훅 단위 테스트 — FR-VR-03 Task-7
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import type { IssueResponse } from '@/api/issues'
import { useChangeAffectsVersions, useChangeFixVersions } from './issue-versions'
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
// Fixture — IssueResponse (affectsVersionIds/fixVersionIds 포함)
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

const versionId = 'a1b2c3d4-e5f6-4789-8abc-def012345678'

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
// T-IV-1. useChangeAffectsVersions — 성공 시 invalidate-only
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAffectsVersions — 성공 시 invalidate-only', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({
          data: { ...issueFixture, affectsVersionIds: [versionId], version: 2 },
        }),
      ),
    )
  })

  it('T-IV-1a: 성공 후 invalidateQueries([issue, key])가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })

  it('T-IV-1b: 성공 후 setQueryData가 직접 호출되지 않는다 (descriptionHtml 플리커 방지)', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const setQueryDataSpy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(setQueryDataSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IV-2. useChangeAffectsVersions — 409 VERSION_CONFLICT
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAffectsVersions — 409 VERSION_CONFLICT', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({ errorCode: 'ISSUE_VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
  })

  it('T-IV-2a: 409 응답 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('T-IV-2b: 409 toast.error가 versionConflictError 메시지로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({ errorCode: 'ISSUE_VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.versionConflictError)
  })

  it('T-IV-2c: 409 에러가 ApiError(409) 인스턴스다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).status).toBe(409)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IV-3. useChangeAffectsVersions — 422 ISSUE_LINKED_VERSION_NOT_FOUND
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAffectsVersions — 422 ISSUE_LINKED_VERSION_NOT_FOUND', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({ errorCode: 'ISSUE_LINKED_VERSION_NOT_FOUND' }, { status: 422 }),
      ),
    )
  })

  it('T-IV-3a: 422 응답 시 versionLinkedNotFoundError 메시지로 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: ['00000000-0000-4000-8000-000000000099'], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.versionLinkedNotFoundError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IV-4. useChangeAffectsVersions — onSettled invalidateQueries (실패 포함)
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeAffectsVersions — onSettled invalidateQueries', () => {
  it('T-IV-4a: 실패 후 onSettled에서도 invalidateQueries가 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({ errorCode: 'ISSUE_VERSION_CONFLICT' }, { status: 409 }),
      ),
    )

    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 0 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IV-5. useChangeFixVersions — 성공 + 에러 (대칭 검증)
// ─────────────────────────────────────────────────────────────────────────────
describe('useChangeFixVersions — 성공 시 invalidate-only', () => {
  beforeEach(() => {
    server.use(
      http.patch('/api/v1/issues/:key/fix-versions', () =>
        HttpResponse.json({
          data: { ...issueFixture, fixVersionIds: [versionId], version: 2 },
        }),
      ),
    )
  })

  it('T-IV-5a: 성공 후 invalidateQueries([issue, key])가 호출된다', async () => {
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeFixVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['issue', 'ATLAS-1'] })
  })
})

describe('useChangeFixVersions — 422 에러 메시지', () => {
  it('T-IV-5b: 422 응답 시 fixVersionsChangeError 에 대한 토스트가 fallback으로 호출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key/fix-versions', () =>
        HttpResponse.json({ errorCode: 'ISSUE_LINKED_VERSION_NOT_FOUND' }, { status: 422 }),
      ),
    )
    const { toast } = await import('sonner')
    const { queryClient, Wrapper } = createWrapper()
    queryClient.setQueryData(['issue', 'ATLAS-1'], issueFixture)

    const { result } = renderHook(() => useChangeFixVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: ['00000000-0000-4000-8000-000000000099'], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith(issueDetailStrings.versionLinkedNotFoundError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-IV-6. Zod 파싱 — IssueResponse affectsVersionIds/fixVersionIds 포함
// ─────────────────────────────────────────────────────────────────────────────
describe('IssueResponse Zod — affectsVersionIds/fixVersionIds 파싱', () => {
  it('T-IV-6a: 응답에 affectsVersionIds/fixVersionIds 있으면 정상 파싱된다', async () => {
    const responseData = {
      ...issueFixture,
      affectsVersionIds: [versionId],
      fixVersionIds: [versionId],
    }

    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({ data: responseData }),
      ),
    )

    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [versionId], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.affectsVersionIds).toEqual([versionId])
  })

  it('T-IV-6b: 응답에 affectsVersionIds/fixVersionIds 없으면 default([])로 파싱된다', async () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { affectsVersionIds: _a, fixVersionIds: _f, ...withoutVersionIds } = issueFixture

    server.use(
      http.patch('/api/v1/issues/:key/affects-versions', () =>
        HttpResponse.json({ data: withoutVersionIds }),
      ),
    )

    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useChangeAffectsVersions(), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({ key: 'ATLAS-1', versionIds: [], expectedVersion: 1 })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.affectsVersionIds).toEqual([])
    expect(result.current.data?.fixVersionIds).toEqual([])
  })
})
