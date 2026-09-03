// 스윔레인 간 카드 필드변경(담당자·우선순위) mutation 훅 — 낙관적 업데이트·롤백·helper 단위 테스트 (FR-UX-06 PR21b Task-2)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/issues 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/issues')
// api/epic-children 전체 mock — connectEpicChild/disconnectEpicChild 단위 테스트 (Task 3)
vi.mock('@/api/epic-children')
// sonner toast mock — use-reorder-card.test.tsx 관례 재사용
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { changeAssignee, updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { connectEpicChild, disconnectEpicChild } from '@/api/epic-children'
import type { EpicChildSummary } from '@/api/epic-children'
import { toast } from 'sonner'
import type { BoardDetail } from '@/api/boards'
import { useChangeCardField, patchCardField } from './use-change-card-field'
import { boardKeys } from './use-boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'
const COL_A_ID = 'ca000000-0000-4000-a000-000000000001'
const USER_1 = 'u1000000-0000-4000-a000-000000000001'
const USER_2 = 'u2000000-0000-4000-a000-000000000002'

/** 1컬럼(카드 2개) — 담당자·우선순위·에픽 필드 포함 */
const INITIAL_BOARD: BoardDetail = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: '기본 보드',
  boardType: 'KANBAN',
  activeSprint: null,
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'ASSIGNEE',
  quickFilters: [],
  columns: [
    {
      columnId: COL_A_ID,
      states: [{ key: 'TODO', name: 'To Do', category: 'TODO' }],
      name: 'To Do',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-1', summary: '카드 1', assigneeId: USER_1, version: 1, priority: 3, epicKey: null, rank: '0|100000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
        { issueKey: 'ATLAS-2', summary: '카드 2', assigneeId: null, version: 1, priority: 2, epicKey: null, rank: '0|200000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
      ],
    },
  ],
}

/** 담당자 변경 성공 응답 — assigneeId=USER_2, version 증가 (issueResponseSchema 필수 필드 전체 충족) */
function buildAssigneeChangedResponse(): IssueResponse {
  return {
    key: 'ATLAS-1',
    id: 'i1000000-0000-4000-a000-000000000001',
    projectKey: 'ATLAS',
    summary: '카드 1',
    currentStateKey: 'TODO',
    reporterId: USER_1,
    assigneeId: USER_2,
    componentIds: [],
    version: 2,
    createdAt: null,
    updatedAt: null,
    typeId: 1,
    typeKey: 'TASK',
    typeName: 'Task',
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
    affectsVersionIds: [],
    fixVersionIds: [],
  }
}

/** 우선순위 변경 성공 응답 — priority=1, version 증가 */
function buildPriorityChangedResponse(): IssueResponse {
  return {
    ...buildAssigneeChangedResponse(),
    assigneeId: USER_1,
    priority: 1,
    version: 2,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 에픽 2-step 재배치 픽스처 (Task 3)
// ─────────────────────────────────────────────────────────────────────────────

const EPIC_A = 'ATLAS-10'
const EPIC_B = 'ATLAS-20'

/** 1컬럼(카드 2개) — ATLAS-1은 EPIC_A 소속, ATLAS-2는 에픽 없음 */
const EPIC_BOARD: BoardDetail = {
  boardId: BOARD_ID,
  projectKey: 'ATLAS',
  name: '기본 보드',
  boardType: 'KANBAN',
  activeSprint: null,
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'EPIC',
  quickFilters: [],
  columns: [
    {
      columnId: COL_A_ID,
      states: [{ key: 'TODO', name: 'To Do', category: 'TODO' }],
      name: 'To Do',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-1', summary: '카드 1', assigneeId: USER_1, version: 1, priority: 3, epicKey: EPIC_A, rank: '0|100000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
        { issueKey: 'ATLAS-2', summary: '카드 2', assigneeId: null, version: 1, priority: 2, epicKey: null, rank: '0|200000:', typeKey: 'task', labels: [], originalEstimateSeconds: null },
      ],
    },
  ],
}

/** connectEpicChild 성공 응답 — epicChildSummarySchema와 1:1 대응하는 최소 픽스처 */
function buildEpicChildSummary(childKey: string): EpicChildSummary {
  return {
    key: childKey,
    summary: '카드 1',
    typeKey: 'TASK',
    currentStateKey: 'TODO',
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 팩토리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// useChangeCardField — mutation 훅 통합 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeCardField', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    queryClient.setQueryData(boardKeys.detail(BOARD_ID), INITIAL_BOARD)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-CF-1: onMutate에서 cancelQueries를 호출한다', async () => {
    vi.mocked(changeAssignee).mockResolvedValue(buildAssigneeChangedResponse())
    const cancelSpy = vi.spyOn(queryClient, 'cancelQueries')

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'assignee',
        toAssigneeId: USER_2,
        expectedVersion: 1,
      })
      await Promise.resolve()
    })

    expect(cancelSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-CF-2: 담당자 낙관적 갱신 — mutate 직후(서버 응답 전) ATLAS-1의 assigneeId가 USER_2다', async () => {
    let resolveChange!: (v: IssueResponse) => void
    vi.mocked(changeAssignee).mockReturnValue(
      new Promise<IssueResponse>((res) => { resolveChange = res }),
    )

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'assignee',
        toAssigneeId: USER_2,
        expectedVersion: 1,
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.assigneeId).toBe(USER_2)
    // 우선순위는 함께 낙관 갱신되지 않는다 — assignee 필드만 변경
    expect(card?.priority).toBe(3)

    resolveChange(buildAssigneeChangedResponse())
  })

  it('T-CF-3: 우선순위 낙관적 갱신 — mutate 직후(서버 응답 전) ATLAS-2의 priority가 1이다', async () => {
    let resolveUpdate!: (v: IssueResponse) => void
    vi.mocked(updateIssue).mockReturnValue(
      new Promise<IssueResponse>((res) => { resolveUpdate = res }),
    )

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-2',
        field: 'priority',
        toPriority: 1,
        expectedVersion: 1,
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-2')
    expect(card?.priority).toBe(1)
    // 담당자는 함께 낙관 갱신되지 않는다 — priority 필드만 변경
    expect(card?.assigneeId).toBe(null)

    resolveUpdate(buildPriorityChangedResponse())
  })

  it('T-CF-4: 담당자 변경 API 호출 인자 — changeAssignee(issueKey, { assigneeId, expectedVersion })', async () => {
    vi.mocked(changeAssignee).mockResolvedValue(buildAssigneeChangedResponse())

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        field: 'assignee',
        toAssigneeId: USER_2,
        expectedVersion: 1,
      })
    })

    expect(changeAssignee).toHaveBeenCalledWith('ATLAS-1', {
      assigneeId: USER_2,
      expectedVersion: 1,
    })
    expect(updateIssue).not.toHaveBeenCalled()
  })

  it('T-CF-5: 우선순위 변경 API 호출 인자 — updateIssue(issueKey, { priority, expectedVersion })', async () => {
    vi.mocked(updateIssue).mockResolvedValue(buildPriorityChangedResponse())

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-2',
        field: 'priority',
        toPriority: 1,
        expectedVersion: 1,
      })
    })

    expect(updateIssue).toHaveBeenCalledWith('ATLAS-2', {
      priority: 1,
      expectedVersion: 1,
    })
    expect(changeAssignee).not.toHaveBeenCalled()
  })

  it('T-CF-6: 성공 후 서버 응답의 assigneeId·version이 캐시에 반영된다', async () => {
    vi.mocked(changeAssignee).mockResolvedValue(buildAssigneeChangedResponse())

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        field: 'assignee',
        toAssigneeId: USER_2,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.assigneeId).toBe(USER_2)
    expect(card?.version).toBe(2)
  })

  it('T-CF-7: 성공 시에도 onSettled가 invalidateQueries를 호출한다', async () => {
    vi.mocked(updateIssue).mockResolvedValue(buildPriorityChangedResponse())
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-2',
        field: 'priority',
        toPriority: 1,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-CF-8: 담당자 변경 실패 롤백 — ATLAS-1이 원래 담당자로 복원되고 toast.error + invalidateQueries가 호출된다', async () => {
    const error = Object.assign(new Error('OCC 충돌'), { status: 409 })
    vi.mocked(changeAssignee).mockRejectedValue(error)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'assignee',
        toAssigneeId: USER_2,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')

    // 롤백 — 원래 담당자(USER_1)로 복원
    expect(card?.assigneeId).toBe(USER_1)

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('담당자'),
    )
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-CF-9: 우선순위 변경 실패 롤백 — ATLAS-2가 원래 우선순위로 복원되고 toast.error가 호출된다', async () => {
    const error = Object.assign(new Error('OCC 충돌'), { status: 409 })
    vi.mocked(updateIssue).mockRejectedValue(error)

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-2',
        field: 'priority',
        toPriority: 1,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-2')

    // 롤백 — 원래 우선순위(2)로 복원
    expect(card?.priority).toBe(2)

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('우선순위'),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeCardField(boardId, filter) — filter-aware queryKey 회귀 가드
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeCardField — filter-aware queryKey', () => {
  let queryClient: QueryClient

  const FILTER: import('@/api/boards').BoardCardFilterParams = {
    assigneeIds: [USER_1],
    includeUnassigned: false,
    labels: ['bug'],
    componentIds: [],
  }

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-FA-1: onMutate가 filter-aware 캐시를 낙관적으로 갱신한다', async () => {
    queryClient.setQueryData(boardKeys.detail(BOARD_ID, FILTER), INITIAL_BOARD)
    // 필터 없는 키는 시드하지 않음 — 이 키를 건드려선 안 된다

    let resolveChange!: (v: IssueResponse) => void
    vi.mocked(changeAssignee).mockReturnValue(
      new Promise<IssueResponse>((res) => { resolveChange = res }),
    )

    const { result } = renderHook(() => useChangeCardField(BOARD_ID, FILTER), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'assignee',
        toAssigneeId: USER_2,
        expectedVersion: 1,
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    const filteredCache = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID, FILTER))
    const card = filteredCache?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.assigneeId).toBe(USER_2)

    const unfilteredCache = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    expect(unfilteredCache).toBeUndefined()

    resolveChange(buildAssigneeChangedResponse())
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useChangeCardField — 에픽 2-step 재배치 (Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('useChangeCardField — 에픽 2-step 재배치', () => {
  let queryClient: QueryClient
  // T-EP-7(재connect도 실패하는 이중 실패 경로)에서만 사용 — console.error 호출 검증 후 원복
  let consoleErrorSpy: ReturnType<typeof vi.spyOn> | undefined

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    queryClient.setQueryData(boardKeys.detail(BOARD_ID), EPIC_BOARD)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
    consoleErrorSpy?.mockRestore()
    consoleErrorSpy = undefined
  })

  it('T-EP-1: 없음→에픽(1-step) — connectEpicChild 1회만 호출되고 disconnectEpicChild는 호출되지 않는다', async () => {
    vi.mocked(connectEpicChild).mockResolvedValue(buildEpicChildSummary('ATLAS-2'))

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-2',
        field: 'epic',
        fromEpicKey: null,
        toEpicKey: EPIC_B,
        expectedVersion: 1,
      })
    })

    expect(connectEpicChild).toHaveBeenCalledTimes(1)
    expect(connectEpicChild).toHaveBeenCalledWith(EPIC_B, 'ATLAS-2')
    expect(disconnectEpicChild).not.toHaveBeenCalled()

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-2')
    expect(card?.epicKey).toBe(EPIC_B)
  })

  it('T-EP-2: 에픽→없음(1-step) — disconnectEpicChild 1회만 호출되고 connectEpicChild는 호출되지 않는다', async () => {
    vi.mocked(disconnectEpicChild).mockResolvedValue(undefined)

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        field: 'epic',
        fromEpicKey: EPIC_A,
        toEpicKey: null,
        expectedVersion: 1,
      })
    })

    expect(disconnectEpicChild).toHaveBeenCalledTimes(1)
    expect(disconnectEpicChild).toHaveBeenCalledWith(EPIC_A, 'ATLAS-1')
    expect(connectEpicChild).not.toHaveBeenCalled()

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.epicKey).toBe(null)
  })

  it('T-EP-3: 에픽A→에픽B(2-step) — disconnectEpicChild가 connectEpicChild보다 먼저 호출된다', async () => {
    vi.mocked(disconnectEpicChild).mockResolvedValue(undefined)
    vi.mocked(connectEpicChild).mockResolvedValue(buildEpicChildSummary('ATLAS-1'))

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.mutateAsync({
        issueKey: 'ATLAS-1',
        field: 'epic',
        fromEpicKey: EPIC_A,
        toEpicKey: EPIC_B,
        expectedVersion: 1,
      })
    })

    expect(disconnectEpicChild).toHaveBeenCalledTimes(1)
    expect(disconnectEpicChild).toHaveBeenCalledWith(EPIC_A, 'ATLAS-1')
    expect(connectEpicChild).toHaveBeenCalledTimes(1)
    expect(connectEpicChild).toHaveBeenCalledWith(EPIC_B, 'ATLAS-1')

    // 호출 순서 — disconnect가 connect보다 먼저 실행됐는지 invocationCallOrder로 검증
    const disconnectOrder = vi.mocked(disconnectEpicChild).mock.invocationCallOrder[0]
    const connectOrder = vi.mocked(connectEpicChild).mock.invocationCallOrder[0]
    expect(disconnectOrder).toBeDefined()
    expect(connectOrder).toBeDefined()
    expect(disconnectOrder as number).toBeLessThan(connectOrder as number)

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.epicKey).toBe(EPIC_B)
  })

  it('T-EP-4: 부분 실패(disconnect 성공+connect 실패) — best-effort 재connect(from) 호출 + 원에러 throw + 롤백 + toast', async () => {
    const connectError = Object.assign(new Error('ISSUE_EPIC_CHILD_ALREADY_LINKED'), { status: 409 })
    vi.mocked(disconnectEpicChild).mockResolvedValue(undefined)
    vi.mocked(connectEpicChild)
      .mockRejectedValueOnce(connectError)
      .mockResolvedValueOnce(buildEpicChildSummary('ATLAS-1'))
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'epic',
        fromEpicKey: EPIC_A,
        toEpicKey: EPIC_B,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // disconnect(A) 1회
    expect(disconnectEpicChild).toHaveBeenCalledTimes(1)
    expect(disconnectEpicChild).toHaveBeenCalledWith(EPIC_A, 'ATLAS-1')

    // connect는 2회 — 1차 목표(B) 실패 후 2차 best-effort 재connect(A)
    expect(connectEpicChild).toHaveBeenCalledTimes(2)
    expect(connectEpicChild).toHaveBeenNthCalledWith(1, EPIC_B, 'ATLAS-1')
    expect(connectEpicChild).toHaveBeenNthCalledWith(2, EPIC_A, 'ATLAS-1')

    // 낙관 캐시 롤백 — 원래 에픽(EPIC_A)으로 복원
    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.epicKey).toBe(EPIC_A)

    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('에픽'))
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-EP-7: 이중 실패(disconnect 성공 → connect(B) 실패 → best-effort 재connect(A)도 실패) — console.error 기록 + 원 connectError throw + 롤백 + toast(리뷰 S5)', async () => {
    const connectError = Object.assign(new Error('ISSUE_EPIC_CHILD_ALREADY_LINKED'), { status: 409 })
    const rollbackError = Object.assign(new Error('네트워크 오류'), { status: 500 })
    consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    vi.mocked(disconnectEpicChild).mockResolvedValue(undefined)
    vi.mocked(connectEpicChild)
      .mockRejectedValueOnce(connectError)
      .mockRejectedValueOnce(rollbackError)
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'epic',
        fromEpicKey: EPIC_A,
        toEpicKey: EPIC_B,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    // disconnect(A) 1회
    expect(disconnectEpicChild).toHaveBeenCalledTimes(1)
    expect(disconnectEpicChild).toHaveBeenCalledWith(EPIC_A, 'ATLAS-1')

    // connect는 2회 — 1차 목표(B) 실패 후 2차 best-effort 재connect(A)도 실패
    expect(connectEpicChild).toHaveBeenCalledTimes(2)
    expect(connectEpicChild).toHaveBeenNthCalledWith(1, EPIC_B, 'ATLAS-1')
    expect(connectEpicChild).toHaveBeenNthCalledWith(2, EPIC_A, 'ATLAS-1')

    // 재connect 실패는 조용히 삼키지 않고 console.error로 남긴다
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining('에픽 best-effort 재연결 실패'),
      rollbackError,
    )

    // 재connect도 실패했지만 mutation은 rollbackError가 아니라 원래 connectError(B)를 그대로 던진다
    expect(result.current.error).toBe(connectError)

    // 낙관 캐시 롤백 — 원래 에픽(EPIC_A)으로 복원 (재connect 실패와 무관하게 스냅샷으로 복원)
    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    expect(card?.epicKey).toBe(EPIC_A)

    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('에픽'))
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: boardKeys.detail(BOARD_ID) }),
    )
  })

  it('T-EP-5: 낙관적 갱신은 최종값 1회 — disconnect가 아직 응답하지 않은 중간에도 캐시는 EPIC_B이고 null이 노출되지 않는다', async () => {
    vi.mocked(disconnectEpicChild).mockReturnValue(new Promise<undefined>(() => { /* 응답 보류 */ }))
    vi.mocked(connectEpicChild).mockResolvedValue(buildEpicChildSummary('ATLAS-1'))

    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'epic',
        fromEpicKey: EPIC_A,
        toEpicKey: EPIC_B,
        expectedVersion: 1,
      })
    })

    await act(async () => {
      await Promise.resolve()
    })

    const cached = queryClient.getQueryData<BoardDetail>(boardKeys.detail(BOARD_ID))
    const card = cached?.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')
    // 2-step 진행 중(disconnect 응답 전)에도 낙관 캐시는 최종 목표값 — 중간 null이 노출되지 않는다
    expect(card?.epicKey).toBe(EPIC_B)
    expect(card?.epicKey).not.toBe(null)
  })

  it('T-EP-6: same-value 방어 — from===to면 API 호출 없이 즉시 실패하고 toast.error가 호출된다', async () => {
    const { result } = renderHook(() => useChangeCardField(BOARD_ID), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate({
        issueKey: 'ATLAS-1',
        field: 'epic',
        fromEpicKey: EPIC_A,
        toEpicKey: EPIC_A,
        expectedVersion: 1,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(connectEpicChild).not.toHaveBeenCalled()
    expect(disconnectEpicChild).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('에픽'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// patchCardField — 순수 helper 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('patchCardField', () => {
  it('T-PCF-1: assigneeId만 patch하면 해당 필드만 갱신된다', () => {
    const result = patchCardField(INITIAL_BOARD, 'ATLAS-1', { assigneeId: USER_2 })
    const card = result.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')

    expect(card?.assigneeId).toBe(USER_2)
    expect(card?.priority).toBe(3)
    expect(card?.version).toBe(1)
  })

  it('T-PCF-2: priority만 patch하면 해당 필드만 갱신된다', () => {
    const result = patchCardField(INITIAL_BOARD, 'ATLAS-2', { priority: 5 })
    const card = result.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-2')

    expect(card?.priority).toBe(5)
    expect(card?.assigneeId).toBe(null)
  })

  it('T-PCF-3: undefined 필드는 미변경 — assigneeId/priority 동시 patch 없이 version만 갱신', () => {
    const result = patchCardField(INITIAL_BOARD, 'ATLAS-1', { version: 9 })
    const card = result.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-1')

    expect(card?.version).toBe(9)
    expect(card?.assigneeId).toBe(USER_1)
    expect(card?.priority).toBe(3)
  })

  it('T-PCF-4: 원본 board가 변형되지 않는다 (immutable)', () => {
    const originalAssignee = INITIAL_BOARD.columns[0]?.cards[0]?.assigneeId

    patchCardField(INITIAL_BOARD, 'ATLAS-1', { assigneeId: USER_2 })

    expect(INITIAL_BOARD.columns[0]?.cards[0]?.assigneeId).toBe(originalAssignee)
  })

  it('T-PCF-5: 다른 카드는 변경되지 않는다', () => {
    const result = patchCardField(INITIAL_BOARD, 'ATLAS-1', { assigneeId: USER_2 })
    const otherCard = result.columns[0]?.cards.find((c) => c.issueKey === 'ATLAS-2')

    expect(otherCard?.assigneeId).toBe(null)
    expect(otherCard?.priority).toBe(2)
  })

  it('T-PCF-6: 존재하지 않는 issueKey는 board를 변형 없이 반환한다', () => {
    const result = patchCardField(INITIAL_BOARD, 'ATLAS-999', { assigneeId: USER_2 })
    const cards = result.columns[0]?.cards

    expect(cards?.map((c) => c.assigneeId)).toEqual([USER_1, null])
  })
})
