// 이슈 가용전환 조회 + 전환 실행 TanStack Query 훅 테스트 — RED phase
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { IssueTransition, TransitionIssueInput } from '@/api/issues'
import { ApiError } from '@/api/client'
import {
  useIssueTransitions,
  useTransitionIssue,
  useAmbiguousTransition,
} from '../use-issue-transitions'

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

// ─────────────────────────────────────────────────────────────────────────────
// T21. 409 AMBIGUOUS_TRANSITION 왕복 (ADR 2026-08-18 §D3)
//   같은 (from,to) 에 전환이 여럿이면 서버가 후보를 돌려준다. 사용자가 고른 후보의
//   transitionId 를 되실어 재요청해야 전환이 실행된다.
// ─────────────────────────────────────────────────────────────────────────────

/** 후보 전환 1번의 1급 식별자 — backend 통합 테스트와 같은 값. */
const CANDIDATE_ONE_ID = '33333333-3333-4333-8333-333333333333'
/** 후보 전환 2번의 1급 식별자. */
const CANDIDATE_TWO_ID = '44444444-4444-4444-8444-444444444444'

/** backend AmbiguousTransitionErrorResponse 직렬화 형태 — Task 23 RED 출력 실측. */
const AMBIGUOUS_BODY = {
  error: {
    code: 'AMBIGUOUS_TRANSITION',
    message: '이동할 수 있는 전환이 2개입니다. 어느 전환인지 골라 주세요.',
  },
  candidates: [
    { transitionId: CANDIDATE_ONE_ID, name: '조건부 승인' },
    { transitionId: CANDIDATE_TWO_ID, name: '즉시 완료' },
  ],
}

/** 전환 성공 응답 — issueResponseSchema 최소 필드. */
const TRANSITIONED_ISSUE = {
  key: 'ATLAS-1',
  id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
  projectKey: 'ATLAS',
  summary: '모호 전환 후보 지목 재요청',
  currentStateKey: 'done',
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

describe('useAmbiguousTransition', () => {
  it('T21-H1: 초기 prompt 는 null 이다', () => {
    const { result } = renderHook(() => useAmbiguousTransition())
    expect(result.current.prompt).toBeNull()
  })

  it('T21-H2: 409 AMBIGUOUS_TRANSITION 을 잡으면 true 를 돌려주고 후보를 노출한다', () => {
    const { result } = renderHook(() => useAmbiguousTransition())
    const input: TransitionIssueInput = { toStatusKey: 'done', expectedVersion: 1 }

    let captured = false
    act(() => {
      captured = result.current.capture(new ApiError(409, AMBIGUOUS_BODY), input)
    })

    expect(captured).toBe(true)
    expect(result.current.prompt?.candidates).toHaveLength(2)
    expect(result.current.prompt?.candidates[1]?.name).toBe('즉시 완료')
    // 재요청에 쓸 원 요청을 그대로 보관해야 한다 — expectedVersion 을 잃으면 OCC 로 죽는다
    expect(result.current.prompt?.input).toEqual(input)
  })

  it('T21-H3: 다른 에러는 잡지 않는다 — false 를 돌려줘 호출자가 기존 토스트를 띄우게 한다', () => {
    const { result } = renderHook(() => useAmbiguousTransition())

    let captured = true
    act(() => {
      captured = result.current.capture(
        new ApiError(409, { errorCode: 'VERSION_CONFLICT', message: '버전 충돌' }),
        { toStatusKey: 'done', expectedVersion: 1 },
      )
    })

    expect(captured).toBe(false)
    expect(result.current.prompt).toBeNull()
  })

  it('T21-H4: clear() 로 후보 선택을 취소한다', () => {
    const { result } = renderHook(() => useAmbiguousTransition())
    act(() => {
      result.current.capture(new ApiError(409, AMBIGUOUS_BODY), {
        toStatusKey: 'done',
        expectedVersion: 1,
      })
    })
    act(() => {
      result.current.clear()
    })
    expect(result.current.prompt).toBeNull()
  })
})

describe('전환 409 왕복 — 후보 2개 → 고름 → transitionId 재요청 → 성공', () => {
  it('T21-H5: 첫 요청은 transitionId 없이, 재요청은 고른 후보의 transitionId 로 나간다', async () => {
    const sentTransitionIds: (string | undefined)[] = []
    server.use(
      http.post('/api/v1/issues/ATLAS-1/transition', async ({ request }) => {
        const body = (await request.json()) as { transitionId?: string; expectedVersion?: number }
        sentTransitionIds.push(body.transitionId)
        // transitionId 가 없으면 후보가 2개라 못 가른다 → 409 (ADR §D3)
        if (body.transitionId === undefined) {
          return HttpResponse.json(AMBIGUOUS_BODY, { status: 409 })
        }
        return HttpResponse.json({ data: TRANSITIONED_ISSUE })
      }),
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: MOCK_TRANSITIONS } }),
      ),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(
      () => {
        const ambiguous = useAmbiguousTransition()
        const mutation = useTransitionIssue('ATLAS-1')
        return { ambiguous, mutation }
      },
      { wrapper },
    )

    // When 1. toStatusKey 만으로 전환을 시도한다
    const firstInput: TransitionIssueInput = { toStatusKey: 'done', expectedVersion: 1 }
    await act(async () => {
      result.current.mutation.mutate(firstInput, {
        onError: (error) => {
          result.current.ambiguous.capture(error, firstInput)
        },
      })
    })

    // Then 1. 409 후보 목록이 사용자에게 노출된다
    await waitFor(() => expect(result.current.ambiguous.prompt).not.toBeNull())
    expect(result.current.ambiguous.prompt?.candidates).toHaveLength(2)

    // When 2. 사용자가 두 번째 후보를 고른다
    const chosen = result.current.ambiguous.prompt?.candidates[1]?.transitionId
    const retryInput = result.current.ambiguous.prompt?.input
    expect(chosen).toBe(CANDIDATE_TWO_ID)
    if (chosen === undefined || retryInput === undefined) throw new Error('후보 프롬프트 소실')

    await act(async () => {
      result.current.ambiguous.clear()
      await result.current.mutation.mutateAsync({ ...retryInput, transitionId: chosen })
    })

    // Then 2. 재요청이 고른 후보의 transitionId 로 나갔고 전환이 실행됐다
    expect(sentTransitionIds).toEqual([undefined, CANDIDATE_TWO_ID])
    expect(result.current.mutation.data?.currentStateKey).toBe('done')
    expect(result.current.ambiguous.prompt).toBeNull()
  })
})
