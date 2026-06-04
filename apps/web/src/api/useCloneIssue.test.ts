// useCloneIssue 훅 단위 테스트 — 성공 시 navigate + toast + 캐시 무효화, 실패 시 상태별 toast 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useCloneIssue } from './useCloneIssue'
import { issueAtlas1Fixture } from '@/mocks/issue-fixtures'

// sonner toast mock
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// navigate mock
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

import { toast } from 'sonner'

/** 클론 성공 응답 fixture */
const clonedIssueFixture = {
  ...issueAtlas1Fixture,
  key: 'ATLAS-99',
  id: 'f1e2d3c4-b5a6-4789-8def-0123456789ab',
  summary: '첫 번째 이슈 — 로그인 페이지 구현',
  version: 0,
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useCloneIssue', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
    vi.clearAllMocks()
  })

  it('클론 성공 시 새 이슈 키로 navigate하고 성공 toast를 표시하며 issues 캐시를 무효화한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', () =>
        HttpResponse.json({ data: clonedIssueFixture }, { status: 201 }),
      ),
    )

    queryClient.setQueryData(['issues'], [{ key: 'ATLAS-1' }])

    const { result } = renderHook(() => useCloneIssue(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({ key: 'ATLAS-1' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues/ATLAS-99' })
    expect(toast.success).toHaveBeenCalledWith('이슈가 복제되었습니다.')

    const queryState = queryClient.getQueryState(['issues'])
    expect(queryState?.isInvalidated).toBe(true)
  })

  it('includeAssignee/summaryOverride 옵션을 전달하면 request body에 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', async ({ request }) => {
        capturedBody = await request.clone().json()
        return HttpResponse.json({ data: clonedIssueFixture }, { status: 201 })
      }),
    )

    const { result } = renderHook(() => useCloneIssue(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        key: 'ATLAS-1',
        input: { includeAssignee: false, summaryOverride: '복제된 이슈' },
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(capturedBody).toEqual({ includeAssignee: false, summaryOverride: '복제된 이슈' })
  })

  it('404 응답 시 이슈 없음 toast.error를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-999/clone', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_NOT_FOUND', message: '이슈를 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )

    const { result } = renderHook(() => useCloneIssue(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({ key: 'ATLAS-999' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith('이슈를 찾을 수 없습니다.')
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('403 응답 시 권한 없음 toast.error를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', () =>
        HttpResponse.json(
          { errorCode: 'ACCESS_DENIED', message: '접근 거부' },
          { status: 403 },
        ),
      ),
    )

    const { result } = renderHook(() => useCloneIssue(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({ key: 'ATLAS-1' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith('이슈를 클론할 권한이 없습니다.')
  })

  it('400 응답 시 유효성 오류 toast.error를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', () =>
        HttpResponse.json(
          { errorCode: 'VALIDATION_FAILED', message: '유효성 오류' },
          { status: 400 },
        ),
      ),
    )

    const { result } = renderHook(() => useCloneIssue(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({ key: 'ATLAS-1' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith('입력 값이 올바르지 않습니다. 확인 후 다시 시도해 주세요.')
  })

  it('기타 에러 시 기본 에러 toast.error를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/clone', () =>
        HttpResponse.json(
          { errorCode: 'UNKNOWN', message: '알 수 없는 오류' },
          { status: 500 },
        ),
      ),
    )

    const { result } = renderHook(() => useCloneIssue(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({ key: 'ATLAS-1' })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledWith('이슈 클론 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.')
  })
})
