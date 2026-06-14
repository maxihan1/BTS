// 이슈 그래프 API 클라이언트 및 lazy TanStack Query 훅 단위 테스트 — FR-LK-02 Task-2
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  fetchIssueGraph,
  useIssueGraph,
  issueGraphKey,
  ISSUE_GRAPH_EDGE_TYPES,
} from './issue-graph'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const graphFixture = {
  center: 'ATLAS-1',
  depth: 2,
  nodes: [
    { key: 'ATLAS-1', summary: '중심 이슈', statusKey: 'open', depth: 0 },
    { key: 'ATLAS-2', summary: '연결된 이슈', statusKey: 'in_progress', depth: 1 },
  ],
  edges: [
    { from: 'ATLAS-1', to: 'ATLAS-2', type: 'BLOCKS' },
  ],
  truncated: false,
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
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// fetchIssueGraph 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchIssueGraph', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/graph', ({ request }) => {
        const url = new URL(request.url)
        const depth = url.searchParams.get('depth')
        if (depth === '2') {
          return HttpResponse.json({ data: graphFixture })
        }
        return HttpResponse.json(
          { errorCode: 'INVALID_DEPTH' },
          { status: 400 },
        )
      }),
    )
  })

  it('GET /api/v1/issues/{key}/graph?depth={n} 호출 후 data를 언랩해 반환한다', async () => {
    const result = await fetchIssueGraph('ATLAS-1', 2)
    expect(result.center).toBe('ATLAS-1')
    expect(result.depth).toBe(2)
    expect(result.nodes).toHaveLength(2)
    expect(result.edges).toHaveLength(1)
    expect(result.edges[0]?.type).toBe('BLOCKS')
    expect(result.truncated).toBe(false)
  })

  it('Zod parse — 노드 depth 필드가 number 타입으로 파싱된다', async () => {
    const result = await fetchIssueGraph('ATLAS-1', 2)
    const node = result.nodes[0]
    expect(typeof node?.depth).toBe('number')
  })

  it('400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/graph', () =>
        HttpResponse.json(
          { errorCode: 'INVALID_DEPTH' },
          { status: 400 },
        ),
      ),
    )
    await expect(fetchIssueGraph('ATLAS-1', 99)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchIssueGraph('ATLAS-1', 99)).rejects.toMatchObject({ status: 400 })
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/NO-EXIST/graph', () =>
        HttpResponse.json(
          { errorCode: 'ISSUE_NOT_FOUND' },
          { status: 404 },
        ),
      ),
    )
    await expect(fetchIssueGraph('NO-EXIST', 2)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchIssueGraph('NO-EXIST', 2)).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useIssueGraph 훅 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useIssueGraph', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/graph', () =>
        HttpResponse.json({ data: graphFixture }),
      ),
    )
  })

  it('enabled=false이면 쿼리를 실행하지 않는다 (fetchStatus=idle)', () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(
      () => useIssueGraph('ATLAS-1', 2, false),
      { wrapper },
    )
    // enabled=false이면 data는 undefined, fetchStatus는 idle
    expect(result.current.data).toBeUndefined()
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('enabled=true이면 그래프 데이터를 조회한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(
      () => useIssueGraph('ATLAS-1', 2, true),
      { wrapper },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.center).toBe('ATLAS-1')
    expect(result.current.data?.nodes).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// issueGraphKey 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('issueGraphKey', () => {
  it('key와 depth를 포함한 쿼리 키 배열을 반환한다', () => {
    const key = issueGraphKey('ATLAS-1', 2)
    expect(key).toEqual(['issue-graph', 'ATLAS-1', 2])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ISSUE_GRAPH_EDGE_TYPES 상수 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ISSUE_GRAPH_EDGE_TYPES', () => {
  it('대문자 5종 edge type 상수를 export한다', () => {
    expect(ISSUE_GRAPH_EDGE_TYPES.BLOCKS).toBe('BLOCKS')
    expect(ISSUE_GRAPH_EDGE_TYPES.RELATES).toBe('RELATES')
    expect(ISSUE_GRAPH_EDGE_TYPES.DUPLICATES).toBe('DUPLICATES')
    expect(ISSUE_GRAPH_EDGE_TYPES.CLONES).toBe('CLONES')
    expect(ISSUE_GRAPH_EDGE_TYPES.PARENT).toBe('PARENT')
  })
})
