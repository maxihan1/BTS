// 이슈 가용전환 조회 + 전환 실행 TanStack Query 훅 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { IssueTransition } from '@/api/issues'
import { useIssueTransitions, useTransitionIssue } from '../use-issue-transitions'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    client,
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  }
}

const MOCK_TRANSITIONS: IssueTransition[] = [
  { key: 'tr-1', name: '진행 중으로', fromStateKey: 'todo', toStateKey: 'in-progress' },
  { key: 'tr-2', name: '완료로', fromStateKey: 'todo', toStateKey: 'done' },
]

describe('useIssueTransitions', () => {
  it('가용 전환 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: MOCK_TRANSITIONS } }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueTransitions('ATLAS-1'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.toStateKey).toBe('in-progress')
  })

  it('로딩 중일 때 isPending이 true다', () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueTransitions('ATLAS-1'), { wrapper })

    expect(result.current.isPending).toBe(true)
  })

  it('queryKey에 key가 포함돼 각 이슈별로 독립 캐시를 유지한다', async () => {
    let fetchCount1 = 0
    let fetchCount2 = 0

    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () => {
        fetchCount1++
        return HttpResponse.json({ data: { transitions: MOCK_TRANSITIONS } })
      }),
      http.get('/api/v1/issues/ATLAS-2/transitions', () => {
        fetchCount2++
        return HttpResponse.json({ data: { transitions: [] } })
      }),
    )

    const { wrapper } = createWrapper()
    const hook1 = renderHook(() => useIssueTransitions('ATLAS-1'), { wrapper })
    const hook2 = renderHook(() => useIssueTransitions('ATLAS-2'), { wrapper })

    await waitFor(() => {
      expect(hook1.result.current.isSuccess).toBe(true)
      expect(hook2.result.current.isSuccess).toBe(true)
    })

    expect(fetchCount1).toBe(1)
    expect(fetchCount2).toBe(1)
    expect(hook1.result.current.data).toHaveLength(2)
    expect(hook2.result.current.data).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E5 구분 검증 — 422(워크플로우 미설정) vs 200 빈 배열(종료상태)
// ─────────────────────────────────────────────────────────────────────────────

describe('useIssueTransitions — E5 분기', () => {
  it('E5-1: 422 응답 시 isError=true이고 data가 undefined다 (빈 배열 폴백 금지)', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-NOWF/transitions', () =>
        HttpResponse.json(
          { errorCode: 'workflow_not_configured', message: '워크플로우 미설정' },
          { status: 422 },
        ),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueTransitions('ATLAS-NOWF'), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
    // 422는 에러로 노출돼야 하며 빈 배열로 폴백해선 안 된다
    expect(result.current.data).toBeUndefined()
  })

  it('E5-2: 200 + 빈 배열 응답 시 isSuccess=true이고 data가 빈 배열이다 (종료상태 S6)', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-4/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useIssueTransitions('ATLAS-4'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([])
  })
})

describe('useTransitionIssue', () => {
  it('전환 성공 후 issue + issue-transitions 캐시를 무효화한다', async () => {
    const updatedIssue = {
      key: 'ATLAS-1',
      id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
      projectKey: 'ATLAS',
      summary: '테스트 이슈',
      currentStateKey: 'in-progress',
      reporterId: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a22',
      assigneeId: null,
      version: 2,
      createdAt: null,
      updatedAt: null,
      typeId: 1,
      typeKey: 'task',
      typeName: '작업',
      description: null,
      descriptionHtml: null,
      priority: 3,
      priorityName: 'Medium',
      labels: [],
      environment: null,
      impact: null,
      impactName: null,
    }

    server.use(
      http.post('/api/v1/issues/ATLAS-1/transition', () =>
        HttpResponse.json({ data: updatedIssue }),
      ),
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: MOCK_TRANSITIONS } }),
      ),
    )

    const { client, wrapper } = createWrapper()

    // issue-transitions 캐시 미리 세팅
    client.setQueryData(['issue-transitions', 'ATLAS-1'], MOCK_TRANSITIONS)
    // issue 캐시 미리 세팅
    client.setQueryData(['issue', 'ATLAS-1'], { key: 'ATLAS-1', version: 1 })

    const { result } = renderHook(() => useTransitionIssue('ATLAS-1'), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ toStatusKey: 'in-progress', expectedVersion: 1 })
    })

    // 두 캐시 모두 무효화됐는지 확인 (state가 undefined로 리셋)
    expect(client.getQueryState(['issue', 'ATLAS-1'])?.isInvalidated).toBe(true)
    expect(client.getQueryState(['issue-transitions', 'ATLAS-1'])?.isInvalidated).toBe(true)
  })

  it('전환 실패 시 에러를 throw한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/transition', () =>
        HttpResponse.json({ message: '전환 불가' }, { status: 409 }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useTransitionIssue('ATLAS-1'), { wrapper })

    await expect(
      act(async () => {
        await result.current.mutateAsync({ toStatusKey: 'done', expectedVersion: 1 })
      }),
    ).rejects.toThrow()
  })
})
