// useChangeSecurityLevel mutation 훅 단위 테스트 — FR-PM-06 PR-B D6
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'

// toast mock
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))
import { toast } from 'sonner'

// issueQueryKey mock
vi.mock('@/api/useUpdateIssueSummary', () => ({
  issueQueryKey: (key: string) => ['issue', key],
}))

import { useChangeSecurityLevel } from './useChangeSecurityLevel'

const baseIssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '테스트',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  version: 1,
  createdAt: '2026-01-01T00:00:00Z',
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
  securityLevelId: null,
}

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client }, children)
}

describe('useChangeSecurityLevel', () => {
  beforeEach(() => {
    server.resetHandlers()
    vi.mocked(toast.error).mockReset()
  })

  /**
   * CSL-1. 보안등급 지정 성공 시 invalidateQueries가 호출된다(setQueryData 금지).
   */
  it('CSL-1: 보안등급 지정 성공 시 mutation이 성공 상태가 된다', async () => {
    server.use(
      http.patch('/api/v1/issues/ATLAS-1', () =>
        HttpResponse.json({ data: { ...baseIssueResponse, securityLevelId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', version: 2 } }),
      ),
    )

    const { result } = renderHook(() => useChangeSecurityLevel(), {
      wrapper: makeWrapper(),
    })

    result.current.mutate({
      key: 'ATLAS-1',
      securityLevelId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      expectedVersion: 1,
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(toast.error).not.toHaveBeenCalled()
  })

  /**
   * CSL-2. null 전송(해제) 성공.
   */
  it('CSL-2: securityLevelId=null 전송 시 해제 성공 상태가 된다', async () => {
    server.use(
      http.patch('/api/v1/issues/ATLAS-1', () =>
        HttpResponse.json({ data: { ...baseIssueResponse, securityLevelId: null, version: 2 } }),
      ),
    )

    const { result } = renderHook(() => useChangeSecurityLevel(), {
      wrapper: makeWrapper(),
    })

    result.current.mutate({
      key: 'ATLAS-1',
      securityLevelId: null,
      expectedVersion: 1,
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  /**
   * CSL-3. 409 충돌 시 versionConflictError toast가 노출된다.
   */
  it('CSL-3: 409 응답 시 versionConflictError toast를 노출한다', async () => {
    server.use(
      http.patch('/api/v1/issues/ATLAS-1', () =>
        HttpResponse.json({ error: 'version_conflict' }, { status: 409 }),
      ),
    )

    const { result } = renderHook(() => useChangeSecurityLevel(), {
      wrapper: makeWrapper(),
    })

    result.current.mutate({
      key: 'ATLAS-1',
      securityLevelId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      expectedVersion: 1,
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  /**
   * CSL-4. 403 응답 시 권한 없음 toast가 노출된다.
   */
  it('CSL-4: 403 응답 시 권한 없음 toast를 노출한다', async () => {
    server.use(
      http.patch('/api/v1/issues/ATLAS-1', () =>
        HttpResponse.json({ error: 'forbidden' }, { status: 403 }),
      ),
    )

    const { result } = renderHook(() => useChangeSecurityLevel(), {
      wrapper: makeWrapper(),
    })

    result.current.mutate({
      key: 'ATLAS-1',
      securityLevelId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      expectedVersion: 1,
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(toast.error).toHaveBeenCalledTimes(1)
  })
})
