// 이슈 이동 API (previewMove/moveIssue) 단위 테스트 — FR-MV-01 Task 3
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  movePreviewSchema,
  moveResponseSchema,
  previewMove,
  moveIssue,
  useMoveIssue,
} from './issue-move'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** WorkflowStateView 픽스처 — isDone 포함 */
const stateViewFixture = {
  key: 'in_progress',
  name: '진행 중',
  isDone: false,
}

const doneStateViewFixture = {
  key: 'done',
  name: '완료',
  isDone: true,
}

/** 컴포넌트 픽스처 */
const componentFixture = {
  id: '550e8400-e29b-41d4-a716-446655440001',
  projectId: '550e8400-e29b-41d4-a716-446655440010',
  name: 'Backend',
  description: null,
  leadUserId: null,
}

/** 버전 픽스처 */
const versionFixture = {
  id: '550e8400-e29b-41d4-a716-446655440002',
  projectId: '550e8400-e29b-41d4-a716-446655440010',
  name: 'v1.0',
  description: null,
  startDate: null,
  releaseDate: null,
  status: 'UNRELEASED' as const,
}

/** 커스텀 필드 정의 픽스처 */
const customFieldDefFixture = {
  id: '550e8400-e29b-41d4-a716-446655440003',
  projectId: '550e8400-e29b-41d4-a716-446655440010',
  key: 'cf_priority',
  name: '우선순위 레이블',
  description: null,
  fieldType: 'SHORT_TEXT' as const,
  required: true,
  displayOrder: 1,
  options: [],
}

/** WorkflowPreviewSection 픽스처 */
const workflowSectionFixture = {
  compatible: false,
  targetStates: [stateViewFixture, doneStateViewFixture],
  suggestedStateKey: 'in_progress',
}

/** 컴포넌트 매핑 섹션 픽스처 */
const componentsSectionFixture = {
  current: [componentFixture],
  target: [componentFixture],
  autoMapping: {
    '550e8400-e29b-41d4-a716-446655440001': '550e8400-e29b-41d4-a716-446655440004',
  },
}

/** 버전 매핑 섹션 픽스처 — autoMapping value nullable */
const versionsSectionFixture = {
  current: [versionFixture],
  target: [versionFixture],
  autoMapping: {
    '550e8400-e29b-41d4-a716-446655440002': null,
  },
}

/** 커스텀 필드 호환성 섹션 픽스처 */
const customFieldSectionFixture = {
  removed: [],
  requiredMissing: [customFieldDefFixture],
}

/** 서브태스크 preview 노드 픽스처 */
const subtaskPreviewNodeFixture = {
  issueKey: 'ATLAS-2',
  issueTypeKey: 'subtask',
  version: 3,
  workflow: workflowSectionFixture,
  components: componentsSectionFixture,
  affectsVersions: versionsSectionFixture,
  fixVersions: versionsSectionFixture,
  customFields: customFieldSectionFixture,
}

/** MovePreview 전체 픽스처 — version/isDone/subtasks 포함 */
const movePreviewFixture = {
  version: 5,
  workflow: workflowSectionFixture,
  components: componentsSectionFixture,
  affectsVersions: versionsSectionFixture,
  fixVersions: versionsSectionFixture,
  customFields: customFieldSectionFixture,
  subtasks: [subtaskPreviewNodeFixture],
}

/** MoveResponse 픽스처 */
const moveResponseFixture = {
  issueKey: 'INFRA-5',
  previousKey: 'ATLAS-12',
  movedSubtasks: [
    { previousKey: 'ATLAS-13', issueKey: 'INFRA-6' },
    { previousKey: 'ATLAS-14', issueKey: 'INFRA-7' },
  ],
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
// T-MV-1. movePreviewSchema — version / isDone / subtasks 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('movePreviewSchema — version/isDone/subtasks 파싱', () => {
  it('T-MV-1a: version·isDone·subtasks 포함 응답을 성공적으로 파싱한다', () => {
    const result = movePreviewSchema.parse(movePreviewFixture)
    expect(result.version).toBe(5)
    expect(result.workflow.targetStates[0]?.isDone).toBe(false)
    expect(result.workflow.targetStates[1]?.isDone).toBe(true)
    expect(result.subtasks).toHaveLength(1)
    expect(result.subtasks[0]?.version).toBe(3)
    expect(result.subtasks[0]?.issueTypeKey).toBe('subtask')
  })

  it('T-MV-1b: issueTypeKey가 null이면 통과한다(nullable)', () => {
    const withNullTypeKey = {
      ...movePreviewFixture,
      subtasks: [{ ...subtaskPreviewNodeFixture, issueTypeKey: null }],
    }
    const result = movePreviewSchema.parse(withNullTypeKey)
    expect(result.subtasks[0]?.issueTypeKey).toBeNull()
  })

  it('T-MV-1c: suggestedStateKey가 null이면 통과한다(nullable)', () => {
    const withNullSuggested = {
      ...movePreviewFixture,
      workflow: { ...workflowSectionFixture, suggestedStateKey: null },
    }
    const result = movePreviewSchema.parse(withNullSuggested)
    expect(result.workflow.suggestedStateKey).toBeNull()
  })

  it('T-MV-1d: autoMapping의 value가 null이면 통과한다(value nullable)', () => {
    const result = movePreviewSchema.parse(movePreviewFixture)
    expect(result.affectsVersions.autoMapping['550e8400-e29b-41d4-a716-446655440002']).toBeNull()
  })

  it('T-MV-1e: subtasks가 빈 배열이면 단건 경로도 통과한다', () => {
    const singleIssue = { ...movePreviewFixture, subtasks: [] }
    const result = movePreviewSchema.parse(singleIssue)
    expect(result.subtasks).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MV-2. moveResponseSchema — movedSubtasks 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('moveResponseSchema — movedSubtasks 파싱', () => {
  it('T-MV-2a: movedSubtasks 포함 응답을 파싱한다', () => {
    const result = moveResponseSchema.parse(moveResponseFixture)
    expect(result.issueKey).toBe('INFRA-5')
    expect(result.previousKey).toBe('ATLAS-12')
    expect(result.movedSubtasks).toHaveLength(2)
    expect(result.movedSubtasks[0]?.previousKey).toBe('ATLAS-13')
    expect(result.movedSubtasks[0]?.issueKey).toBe('INFRA-6')
  })

  it('T-MV-2b: movedSubtasks가 빈 배열이면 단건 경로도 통과한다', () => {
    const singleResponse = { ...moveResponseFixture, movedSubtasks: [] }
    const result = moveResponseSchema.parse(singleResponse)
    expect(result.movedSubtasks).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MV-3. previewMove — POST /{key}/move/preview 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('previewMove — POST /move/preview 경로·바디 검증', () => {
  it('T-MV-3: 올바른 경로·바디로 호출하고 MovePreview를 반환한다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/:key/move/preview', async ({ request, params }) => {
        if (params['key'] !== 'ATLAS-12') {
          return HttpResponse.json({ error: 'wrong key' }, { status: 400 })
        }
        capturedBody = await request.json()
        return HttpResponse.json({ data: movePreviewFixture })
      }),
    )

    const result = await previewMove('ATLAS-12', 'INFRA')
    expect(capturedBody).toEqual({ targetProjectKey: 'INFRA' })
    expect(result.version).toBe(5)
    expect(result.subtasks).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MV-4. moveIssue — POST /{key}/move 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('moveIssue — POST /move 경로·바디 검증', () => {
  it('T-MV-4: 올바른 경로·페이로드로 호출하고 MoveResponse를 반환한다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/issues/:key/move', async ({ request, params }) => {
        if (params['key'] !== 'ATLAS-12') {
          return HttpResponse.json({ error: 'wrong key' }, { status: 400 })
        }
        capturedBody = await request.json()
        return HttpResponse.json({ data: moveResponseFixture })
      }),
    )

    const payload = {
      targetProjectKey: 'INFRA',
      expectedVersion: 5,
      targetStateKey: 'in_progress',
      targetStateIsDone: false,
      componentMapping: {} as Record<string, string | null>,
      affectsVersionMapping: {} as Record<string, string | null>,
      fixVersionMapping: {} as Record<string, string | null>,
      customFieldValues: {} as Record<string, unknown>,
      subtasks: [
        {
          issueKey: 'ATLAS-2',
          expectedVersion: 3,
          targetStateKey: 'in_progress',
          targetStateIsDone: false,
          componentMapping: {} as Record<string, string | null>,
          affectsVersionMapping: {} as Record<string, string | null>,
          fixVersionMapping: {} as Record<string, string | null>,
          customFieldValues: {} as Record<string, unknown>,
        },
      ],
    }

    const result = await moveIssue('ATLAS-12', payload)
    expect(capturedBody).toMatchObject({ targetProjectKey: 'INFRA' })
    expect(result.issueKey).toBe('INFRA-5')
    expect(result.movedSubtasks).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MV-5. useMoveIssue — mutation 훅
// ─────────────────────────────────────────────────────────────────────────────

describe('useMoveIssue — mutation 훅', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/:key/move', () =>
        HttpResponse.json({ data: moveResponseFixture }),
      ),
    )
  })

  it('T-MV-5: mutate 호출 시 MoveResponse를 반환한다', async () => {
    const { Wrapper } = createWrapper()

    const { result } = renderHook(() => useMoveIssue('ATLAS-12'), { wrapper: Wrapper })

    act(() => {
      result.current.mutate({
        targetProjectKey: 'INFRA',
        expectedVersion: 5,
        targetStateKey: null,
        targetStateIsDone: false,
        componentMapping: {},
        affectsVersionMapping: {},
        fixVersionMapping: {},
        customFieldValues: {},
        subtasks: [],
      })
    })

    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true)
    })

    expect(result.current.data?.issueKey).toBe('INFRA-5')
  })
})
