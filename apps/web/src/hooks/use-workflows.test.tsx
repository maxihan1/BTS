// useWorkflows 훅 + extractStatusOptions 순수 함수 단위 테스트 (FR-SR-01 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/workflows 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/workflows')

import { fetchWorkflows } from '@/api/workflows'
import type { WorkflowView } from '@/api/workflows'
import { useWorkflows, extractStatusOptions } from './use-workflows'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** WorkflowView 픽스처 A — in_progress가 displayOrder=2, todo=1, done=3 */
const workflowA: WorkflowView = {
  key: 'default',
  name: '기본 워크플로우',
  description: '',
  states: [
    { key: 'todo', name: '할 일', category: 'TODO', displayOrder: 1 },
    { key: 'in_progress', name: '진행중', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'done', name: '완료', category: 'DONE', displayOrder: 3 },
  ],
  transitions: [],
}

/** WorkflowView 픽스처 B — in_progress가 displayOrder=3(충돌), review=2(신규), done=4(충돌) */
const workflowB: WorkflowView = {
  key: 'bug-flow',
  name: '버그 워크플로우',
  description: '',
  states: [
    { key: 'in_progress', name: '진행중(B)', category: 'IN_PROGRESS', displayOrder: 3 },
    { key: 'review', name: '검토중', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'done', name: '완료(B)', category: 'DONE', displayOrder: 4 },
  ],
  transitions: [],
}

/** WorkflowView 픽스처 C — displayOrder 동일한 두 상태(정렬 결정성 테스트) */
const workflowC: WorkflowView = {
  key: 'simple',
  name: '단순 워크플로우',
  description: '',
  states: [
    { key: 'open', name: '열림', category: 'TODO', displayOrder: 1 },
    { key: 'closed', name: '닫힘', category: 'DONE', displayOrder: 1 },
  ],
  transitions: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 팩토리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { readonly children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

// ─────────────────────────────────────────────────────────────────────────────
// extractStatusOptions — 순수 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('extractStatusOptions', () => {
  it('단일 워크플로우의 states를 {key, name}[] 형태로 반환한다', () => {
    const result = extractStatusOptions([workflowA])
    expect(result).toEqual([
      { key: 'todo', name: '할 일' },
      { key: 'in_progress', name: '진행중' },
      { key: 'done', name: '완료' },
    ])
  })

  it('여러 워크플로우에서 중복 key는 첫 등장 항목을 채택한다', () => {
    // in_progress는 workflowA(displayOrder=2, name='진행중')가 첫 등장
    // workflowB의 in_progress(displayOrder=3, name='진행중(B)')는 무시
    const result = extractStatusOptions([workflowA, workflowB])
    const inProgress = result.find((s) => s.key === 'in_progress')
    expect(inProgress).toEqual({ key: 'in_progress', name: '진행중' })

    // done도 workflowA(displayOrder=3)가 첫 등장
    const done = result.find((s) => s.key === 'done')
    expect(done).toEqual({ key: 'done', name: '완료' })
  })

  it('(displayOrder asc, key asc) 튜플 안정 정렬을 보장한다', () => {
    // workflowA: todo(1), in_progress(2), done(3)
    // workflowB: review(2, 신규), done(4 무시), in_progress(3 무시)
    // 첫 등장 기준: todo=1, in_progress=2, done=3, review=2
    // displayOrder 정렬: 1=todo, 2=in_progress·review(order 같으면 key asc), 3=done
    const result = extractStatusOptions([workflowA, workflowB])
    const keys = result.map((s) => s.key)
    expect(keys).toEqual(['todo', 'in_progress', 'review', 'done'])
  })

  it('displayOrder가 같을 때 key asc로 결정적 정렬한다', () => {
    // workflowC: open(1), closed(1) → key asc 기준 closed < open
    const result = extractStatusOptions([workflowC])
    const keys = result.map((s) => s.key)
    expect(keys).toEqual(['closed', 'open'])
  })

  it('빈 배열 입력 시 빈 배열을 반환한다', () => {
    expect(extractStatusOptions([])).toEqual([])
  })

  it('states가 없는 워크플로우는 무시한다', () => {
    const empty: WorkflowView = { ...workflowA, states: [] }
    expect(extractStatusOptions([empty])).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useWorkflows — 훅 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useWorkflows', () => {
  it('queryFn이 fetchWorkflows를 호출한다', async () => {
    const mockFetchWorkflows = vi.mocked(fetchWorkflows)
    mockFetchWorkflows.mockResolvedValueOnce([workflowA])

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useWorkflows(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(mockFetchWorkflows).toHaveBeenCalledOnce()
  })

  it('queryKey가 [\'workflows\']로 설정된다', async () => {
    const mockFetchWorkflows = vi.mocked(fetchWorkflows)
    mockFetchWorkflows.mockResolvedValueOnce([workflowA])

    const { client, wrapper } = createWrapper()
    renderHook(() => useWorkflows(), { wrapper })

    await waitFor(() => {
      const cache = client.getQueryCache().find({ queryKey: ['workflows'] })
      expect(cache).toBeDefined()
    })
  })

  it('성공 시 WorkflowView[] 데이터를 반환한다', async () => {
    const mockFetchWorkflows = vi.mocked(fetchWorkflows)
    mockFetchWorkflows.mockResolvedValueOnce([workflowA, workflowB])

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useWorkflows(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.key).toBe('default')
  })

  it('fetchWorkflows 실패 시 isError가 true가 된다', async () => {
    const mockFetchWorkflows = vi.mocked(fetchWorkflows)
    mockFetchWorkflows.mockRejectedValueOnce(new Error('네트워크 오류'))

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useWorkflows(), { wrapper })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
