// 에픽 자식 이슈 API 함수 및 TanStack Query 훅 단위 테스트 — FR-EP-01 Task-3
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  fetchEpicChildren,
  connectEpicChild,
  disconnectEpicChild,
  useEpicChildren,
  useConnectEpicChild,
  useDisconnectEpicChild,
  epicChildrenKey,
} from './epic-children'
import { issueResponseSchema } from './issues'
import { issueQueryKey } from './useUpdateIssueSummary'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const childSummaryFixture = {
  key: 'ATLAS-2',
  summary: '두 번째 이슈',
  typeKey: 'task',
  currentStateKey: 'open',
}

const childNullableTypeFixture = {
  key: 'ATLAS-3',
  summary: '세 번째 이슈',
  typeKey: null,
  currentStateKey: 'in_progress',
}

const childListFixture = {
  children: [childSummaryFixture],
}

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
// T-EC-0. IssueResponse.epic 필드 Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('issueResponseSchema.epic — nullish 파싱', () => {
  const baseIssue = {
    key: 'ATLAS-1',
    id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
    projectKey: 'ATLAS',
    summary: '이슈 요약',
    currentStateKey: 'open',
    reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
    assigneeId: null,
    version: 0,
    createdAt: null,
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
  }

  it('T-EC-0a: epic 필드 없는 응답(목록 API)이 파싱 성공한다', () => {
    const result = issueResponseSchema.safeParse(baseIssue)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.epic).toBeUndefined()
    }
  })

  it('T-EC-0b: epic 필드가 null인 응답이 파싱 성공한다', () => {
    const result = issueResponseSchema.safeParse({ ...baseIssue, epic: null })
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.epic).toBeNull()
    }
  })

  it('T-EC-0c: epic 필드가 채워진 응답(단건 GET)이 파싱 성공하고 key/summary를 가진다', () => {
    const result = issueResponseSchema.safeParse({
      ...baseIssue,
      epic: { key: 'ATLAS-10', summary: '에픽 이슈 요약' },
    })
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.epic?.key).toBe('ATLAS-10')
      expect(result.data.epic?.summary).toBe('에픽 이슈 요약')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-1. fetchEpicChildren — GET /epic-children 200 언랩
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchEpicChildren — GET /epic-children 200 언랩', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: childListFixture }),
      ),
    )
  })

  it('T-EC-1a: children 배열을 반환한다', async () => {
    const result = await fetchEpicChildren('ATLAS-10')
    expect(result.children).toHaveLength(1)
    expect(result.children[0]?.key).toBe('ATLAS-2')
  })

  it('T-EC-1b: currentStateKey 필드가 포함된다', async () => {
    const result = await fetchEpicChildren('ATLAS-10')
    expect(result.children[0]?.currentStateKey).toBe('open')
  })

  it('T-EC-1c: 404 응답 시 ApiError가 throw된다', async () => {
    server.use(
      http.get('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: '에픽을 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    await expect(fetchEpicChildren('ATLAS-INVALID')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-2. fetchEpicChildren — typeKey nullable 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchEpicChildren — typeKey nullable 처리', () => {
  it('T-EC-2a: typeKey가 null인 자식 이슈가 파싱 성공한다', async () => {
    server.use(
      http.get('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: { children: [childNullableTypeFixture] } }),
      ),
    )
    const result = await fetchEpicChildren('ATLAS-10')
    expect(result.children[0]?.typeKey).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-3. connectEpicChild — POST /epic-children 201
// ─────────────────────────────────────────────────────────────────────────────

describe('connectEpicChild — POST /epic-children 201', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: childSummaryFixture }, { status: 201 }),
      ),
    )
  })

  it('T-EC-3a: 성공 시 EpicChildSummaryResponse를 반환한다', async () => {
    const result = await connectEpicChild('ATLAS-10', 'ATLAS-2')
    expect(result.key).toBe('ATLAS-2')
    expect(result.summary).toBe('두 번째 이슈')
  })

  it('T-EC-3b: 409 ISSUE_EPIC_CHILD_ALREADY_LINKED 응답 시 ApiError가 throw된다', async () => {
    server.use(
      http.post('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_ALREADY_LINKED', message: '이미 연결된 이슈입니다' },
          { status: 409 },
        ),
      ),
    )
    await expect(connectEpicChild('ATLAS-10', 'ATLAS-2')).rejects.toBeInstanceOf(ApiError)
  })

  it('T-EC-3c: 422 ISSUE_EPIC_CHILD_INVALID_TYPE 응답 시 ApiError가 throw된다', async () => {
    server.use(
      http.post('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_CHILD_INVALID_TYPE', message: '에픽은 자식이 될 수 없습니다' },
          { status: 422 },
        ),
      ),
    )
    await expect(connectEpicChild('ATLAS-10', 'ATLAS-10')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-4. disconnectEpicChild — DELETE /epic-children/:childKey 204
// ─────────────────────────────────────────────────────────────────────────────

describe('disconnectEpicChild — DELETE /epic-children/:childKey 204', () => {
  beforeEach(() => {
    server.use(
      http.delete('/api/v1/issues/:epicKey/epic-children/:childKey', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('T-EC-4a: 성공 시 undefined를 반환한다', async () => {
    const result = await disconnectEpicChild('ATLAS-10', 'ATLAS-2')
    expect(result).toBeUndefined()
  })

  it('T-EC-4b: 404 응답 시 ApiError가 throw된다', async () => {
    server.use(
      http.delete('/api/v1/issues/:epicKey/epic-children/:childKey', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND', message: '에픽 또는 자식 이슈를 찾을 수 없습니다' },
          { status: 404 },
        ),
      ),
    )
    await expect(disconnectEpicChild('ATLAS-10', 'ATLAS-INVALID')).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-5. useEpicChildren 훅 — 쿼리 훅 동작
// ─────────────────────────────────────────────────────────────────────────────

describe('useEpicChildren 훅 — 쿼리 훅 동작', () => {
  it('T-EC-5a: 에픽 자식 목록을 조회한다', async () => {
    server.use(
      http.get('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: childListFixture }),
      ),
    )
    const { Wrapper } = createWrapper()
    const { result } = renderHook(() => useEpicChildren('ATLAS-10'), {
      wrapper: Wrapper,
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.children).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-6. useConnectEpicChild 훅 — onSuccess invalidate 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('useConnectEpicChild 훅 — onSuccess 이후 invalidate', () => {
  it('T-EC-6a: 성공 후 epic-children 쿼리 키와 이슈 쿼리 키가 invalidate된다', async () => {
    server.use(
      http.get('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: childListFixture }),
      ),
      http.post('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: childSummaryFixture }, { status: 201 }),
      ),
    )
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    // epic-children 쿼리를 미리 fetch해서 캐시에 넣는다.
    await queryClient.prefetchQuery({
      queryKey: epicChildrenKey('ATLAS-10'),
      queryFn: () => fetchEpicChildren('ATLAS-10'),
    })

    const { result } = renderHook(() => useConnectEpicChild('ATLAS-10'), {
      wrapper: Wrapper,
    })

    await act(async () => {
      result.current.mutate('ATLAS-2')
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // onSettled에서 epicChildrenKey와 issueQueryKey 둘 다 invalidate 호출되어야 한다.
    // invalidateSpy.mock.calls가 비어있으면 onSettled 자체가 실행되지 않은 것 — false-green 차단.
    await waitFor(() => {
      const calledKeys = invalidateSpy.mock.calls.map((args) => args[0])
      expect(calledKeys).toContainEqual({ queryKey: epicChildrenKey('ATLAS-10') })
      expect(calledKeys).toContainEqual({ queryKey: issueQueryKey('ATLAS-10') })
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-EC-7. useDisconnectEpicChild 훅 — onSuccess invalidate 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('useDisconnectEpicChild 훅 — onSuccess 이후 invalidate', () => {
  it('T-EC-7a: 성공 후 epic-children 쿼리 키와 이슈 쿼리 키가 invalidate된다', async () => {
    server.use(
      http.get('/api/v1/issues/:epicKey/epic-children', () =>
        HttpResponse.json({ data: childListFixture }),
      ),
      http.delete('/api/v1/issues/:epicKey/epic-children/:childKey', () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
    const { queryClient, Wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await queryClient.prefetchQuery({
      queryKey: epicChildrenKey('ATLAS-10'),
      queryFn: () => fetchEpicChildren('ATLAS-10'),
    })

    const { result } = renderHook(() => useDisconnectEpicChild('ATLAS-10'), {
      wrapper: Wrapper,
    })

    await act(async () => {
      result.current.mutate('ATLAS-2')
    })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // onSettled에서 epicChildrenKey와 issueQueryKey 둘 다 invalidate 호출되어야 한다.
    // invalidateSpy.mock.calls가 비어있으면 onSettled 자체가 실행되지 않은 것 — false-green 차단.
    await waitFor(() => {
      const calledKeys = invalidateSpy.mock.calls.map((args) => args[0])
      expect(calledKeys).toContainEqual({ queryKey: epicChildrenKey('ATLAS-10') })
      expect(calledKeys).toContainEqual({ queryKey: issueQueryKey('ATLAS-10') })
    })
  })
})
