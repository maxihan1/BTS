// project-workflow API client 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { fetchWorkflows, fetchWorkflow, planTransition } from './workflows'
import { transitionKey } from '@/components/workflow/workflow.types'

// ─────────────────────────────────────────────────────────────────────────────
// 인라인 목은 backend 가 보내는 **날 JSON** 을 흉내 낸다 (TS 픽스처 타입을 거치지 않는다).
// 그래서 스키마가 실제 payload 를 받아내는지가 여기서 판정된다 — 목이 신 필드를 빠뜨리면
// 스키마 강화가 목 위에서만 초록인 채 배포된다 (learnings 2026-05-30).
//
// backend WorkflowTransitionDto 6필드 = key · fromStateKey(nullable) · toStateKey · name · id · kind
// ─────────────────────────────────────────────────────────────────────────────

/** RFC4122 v4 UUID — Zod v4 `z.string().uuid()` 가 통과시키는 형식과 같은 판정 */
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/**
 * 인라인 목 전용 결정적 전환 id.
 * 실제 값은 DB 가 정하지만(`workflow_transitions.id`) 목은 실행마다 같아야 한다.
 *
 * @param ordinal 목 안에서 유일한 전환 번호
 * @returns RFC4122 v4 형식 UUID 문자열
 */
function mockTransitionId(ordinal: number): string {
  return `11111111-1111-4111-8111-${`${ordinal}`.padStart(12, '0')}`
}

// ─────────────────────────────────────────────────────────────────────────────
// software-default fixture — backend YAML 기준
// 5 상태 + 7 전환 (INITIAL 1 + NORMAL 6). INITIAL 은 V207 ⑨ 백필이 심는 행이다.
// ─────────────────────────────────────────────────────────────────────────────
const softwareDefault = {
  key: 'software-default',
  name: '소프트웨어 개발 기본 워크플로우',
  description: 'Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우',
  states: [
    { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
    { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'in_review', name: 'In Review', category: 'IN_PROGRESS', displayOrder: 3 },
    { key: 'done', name: 'Done', category: 'DONE', displayOrder: 4 },
    { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 5 },
  ],
  transitions: [
    { key: 'INITIAL__open', name: '이슈 생성', fromStateKey: null, toStateKey: 'open', id: mockTransitionId(10), kind: 'INITIAL' },
    { key: transitionKey('open', 'in_progress'), name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress', id: mockTransitionId(11), kind: 'NORMAL' },
    { key: transitionKey('in_progress', 'in_review'), name: 'Submit for Review', fromStateKey: 'in_progress', toStateKey: 'in_review', id: mockTransitionId(12), kind: 'NORMAL' },
    { key: transitionKey('in_review', 'done'), name: 'Approve', fromStateKey: 'in_review', toStateKey: 'done', id: mockTransitionId(13), kind: 'NORMAL' },
    { key: transitionKey('in_review', 'in_progress'), name: 'Request Changes', fromStateKey: 'in_review', toStateKey: 'in_progress', id: mockTransitionId(14), kind: 'NORMAL' },
    { key: transitionKey('done', 'closed'), name: 'Close', fromStateKey: 'done', toStateKey: 'closed', id: mockTransitionId(15), kind: 'NORMAL' },
    { key: transitionKey('open', 'closed'), name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed', id: mockTransitionId(16), kind: 'NORMAL' },
  ],
}

const bugTracking = {
  key: 'bug-tracking',
  name: '버그 추적 워크플로우',
  description: 'Reported → Triaged → In Progress → Resolved → Closed 흐름의 버그 추적 워크플로우',
  states: [
    { key: 'reported', name: 'Reported', category: 'TODO', displayOrder: 1 },
    { key: 'triaged', name: 'Triaged', category: 'TODO', displayOrder: 2 },
    { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 3 },
    { key: 'resolved', name: 'Resolved', category: 'DONE', displayOrder: 4 },
    { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 5 },
  ],
  transitions: [
    { key: 'INITIAL__reported', name: '이슈 생성', fromStateKey: null, toStateKey: 'reported', id: mockTransitionId(20), kind: 'INITIAL' },
    { key: transitionKey('reported', 'triaged'), name: 'Triage', fromStateKey: 'reported', toStateKey: 'triaged', id: mockTransitionId(21), kind: 'NORMAL' },
    { key: transitionKey('triaged', 'in_progress'), name: 'Start Fix', fromStateKey: 'triaged', toStateKey: 'in_progress', id: mockTransitionId(22), kind: 'NORMAL' },
    { key: transitionKey('in_progress', 'resolved'), name: 'Resolve', fromStateKey: 'in_progress', toStateKey: 'resolved', id: mockTransitionId(23), kind: 'NORMAL' },
    { key: transitionKey('resolved', 'closed'), name: 'Close', fromStateKey: 'resolved', toStateKey: 'closed', id: mockTransitionId(24), kind: 'NORMAL' },
    { key: transitionKey('resolved', 'in_progress'), name: 'Reopen', fromStateKey: 'resolved', toStateKey: 'in_progress', id: mockTransitionId(25), kind: 'NORMAL' },
  ],
}

const simple = {
  key: 'simple',
  name: '단순 워크플로우 (TODO/DOING/DONE)',
  description: 'To Do → Doing → Done 3단계 단순 워크플로우',
  states: [
    { key: 'todo', name: 'To Do', category: 'TODO', displayOrder: 1 },
    { key: 'doing', name: 'Doing', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'done', name: 'Done', category: 'DONE', displayOrder: 3 },
  ],
  transitions: [
    { key: 'INITIAL__todo', name: '이슈 생성', fromStateKey: null, toStateKey: 'todo', id: mockTransitionId(30), kind: 'INITIAL' },
    { key: transitionKey('todo', 'doing'), name: 'Start', fromStateKey: 'todo', toStateKey: 'doing', id: mockTransitionId(31), kind: 'NORMAL' },
    { key: transitionKey('doing', 'done'), name: 'Complete', fromStateKey: 'doing', toStateKey: 'done', id: mockTransitionId(32), kind: 'NORMAL' },
    { key: transitionKey('done', 'doing'), name: 'Reopen', fromStateKey: 'done', toStateKey: 'doing', id: mockTransitionId(33), kind: 'NORMAL' },
  ],
}

const kanbanBasic = {
  key: 'kanban-basic',
  name: '칸반 기본 워크플로우',
  description: 'Backlog → Ready → In Progress → Done 흐름의 칸반 기본 워크플로우',
  states: [
    { key: 'backlog', name: 'Backlog', category: 'TODO', displayOrder: 1 },
    { key: 'ready', name: 'Ready', category: 'TODO', displayOrder: 2 },
    { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 3 },
    { key: 'done', name: 'Done', category: 'DONE', displayOrder: 4 },
  ],
  transitions: [
    { key: 'INITIAL__backlog', name: '이슈 생성', fromStateKey: null, toStateKey: 'backlog', id: mockTransitionId(40), kind: 'INITIAL' },
    { key: transitionKey('backlog', 'ready'), name: 'Refine', fromStateKey: 'backlog', toStateKey: 'ready', id: mockTransitionId(41), kind: 'NORMAL' },
    { key: transitionKey('ready', 'in_progress'), name: 'Pull', fromStateKey: 'ready', toStateKey: 'in_progress', id: mockTransitionId(42), kind: 'NORMAL' },
    { key: transitionKey('in_progress', 'done'), name: 'Finish', fromStateKey: 'in_progress', toStateKey: 'done', id: mockTransitionId(43), kind: 'NORMAL' },
  ],
}

const allWorkflows = [softwareDefault, bugTracking, simple, kanbanBasic]

beforeEach(() => {
  server.use(
    http.get('/api/v1/workflows', () => {
      return HttpResponse.json({ data: allWorkflows })
    }),
    http.get('/api/v1/workflows/:key', ({ params }) => {
      const found = allWorkflows.find((w) => w.key === params['key'])
      if (found === undefined) {
        return HttpResponse.json({ message: 'Not Found' }, { status: 404 })
      }
      return HttpResponse.json({ data: found })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-1. fetchWorkflows — 4 워크플로우 목록 반환 + Zod parse 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchWorkflows', () => {
  it('T4-1: MSW mock에서 4 워크플로우 목록을 반환하고 각 항목이 WorkflowView 스키마를 통과한다', async () => {
    const result = await fetchWorkflows()

    expect(result).toHaveLength(4)

    // 각 항목이 WorkflowView 필드를 가져야 한다
    for (const workflow of result) {
      expect(typeof workflow.key).toBe('string')
      expect(typeof workflow.name).toBe('string')
      expect(typeof workflow.description).toBe('string')
      expect(Array.isArray(workflow.states)).toBe(true)
      expect(Array.isArray(workflow.transitions)).toBe(true)

      // 각 상태가 WorkflowStateView 필드를 가져야 한다
      for (const state of workflow.states) {
        expect(typeof state.key).toBe('string')
        expect(typeof state.name).toBe('string')
        expect(['TODO', 'IN_PROGRESS', 'DONE']).toContain(state.category)
        expect(typeof state.displayOrder).toBe('number')
      }

      // 각 전환이 backend WorkflowTransitionDto 6필드를 그대로 갖는다
      for (const transition of workflow.transitions) {
        expect(typeof transition.key).toBe('string')
        expect(typeof transition.name).toBe('string')
        expect(typeof transition.toStateKey).toBe('string')
        expect(transition.id).toMatch(UUID_V4)
        expect(['NORMAL', 'GLOBAL', 'INITIAL']).toContain(transition.kind)
        // 출발 상태 없는 전환(GLOBAL·INITIAL)은 null 로 실린다 — 생략도, 빈 문자열도 아니다
        if (transition.kind === 'NORMAL') {
          expect(typeof transition.fromStateKey).toBe('string')
        } else {
          expect(transition.fromStateKey).toBeNull()
        }
      }
    }
  })

  it('T4-1b: INITIAL 전환의 fromStateKey 가 null 그대로 흐른다 (빈 문자열 폴백 금지)', async () => {
    const result = await fetchWorkflows()

    const initials = result.flatMap((w) => w.transitions.filter((t) => t.kind === 'INITIAL'))

    // 4 워크플로우 각각 INITIAL 1건 — V207 ⑨ 백필과 같은 모양
    expect(initials).toHaveLength(4)
    for (const initial of initials) {
      expect(initial.fromStateKey).toBeNull()
      expect(initial.fromStateKey).not.toBe('')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-2. fetchWorkflow('software-default') — 단건 + Zod 파싱
// backend YAML 기준: software-default = 5 상태 + 7 전환 (INITIAL 1 + NORMAL 6)
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchWorkflow', () => {
  it('T4-2: software-default 단건 조회 — key 일치, states.length === 5, transitions.length === 7', async () => {
    const result = await fetchWorkflow('software-default')

    expect(result.key).toBe('software-default')
    expect(result.states).toHaveLength(5)
    expect(result.transitions).toHaveLength(7)
  })

  it('T4-2b: 존재하지 않는 key 조회 시 에러를 throw한다', async () => {
    await expect(fetchWorkflow('non-existent')).rejects.toThrow()
  })

  it('T4-2c: 전환 id 로 같은 상태쌍의 전환을 하나로 지목할 수 있다', async () => {
    const result = await fetchWorkflow('software-default')

    const startWork = result.transitions.find((t) => t.name === 'Start Work')
    expect(startWork?.id).toBe(mockTransitionId(11))
    expect(startWork?.kind).toBe('NORMAL')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T6-1. planTransition — transitionName 없이 호출 가능 (회귀 가드)
// 경로는 POST /api/v1/workflows/{key}/transitions/plan (결정 D-1).
// 옛 경로 /transitions 는 이제 전환 정의 CRUD 라 계획 요청을 보내면 오라우팅이다.
// 시그니처에 transitionName이 남아 있으면 이 테스트는 컴파일 단계에서 실패한다.
// ─────────────────────────────────────────────────────────────────────────────
describe('planTransition', () => {
  beforeEach(() => {
    server.use(
      // 옛 경로(전환 정의 CRUD)로 계획 요청이 새면 400 으로 즉시 드러난다
      http.post('/api/v1/workflows/:key/transitions', () => {
        return HttpResponse.json(
          { message: '전환 정의 CRUD 경로다 — 계획 요청은 /transitions/plan 이다' },
          { status: 400 },
        )
      }),
      http.post('/api/v1/workflows/:key/transitions/plan', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>
        // transitionName이 body에 포함되어 있으면 500으로 거부 (회귀 가드)
        if ('transitionName' in body) {
          return HttpResponse.json({ message: 'transitionName은 허용되지 않는 필드입니다' }, { status: 500 })
        }
        return HttpResponse.json({
          data: {
            toStateKey: 'in_progress',
            fieldChanges: [],
            events: [{ type: 'ISSUE_TRANSITIONED', payload: { workflowKey: 'software-default' } }],
          },
        })
      }),
    )
  })

  it('T6-1: transitionName 없이 planTransition 호출이 성공하고 toStateKey를 반환한다', async () => {
    const result = await planTransition('software-default', {
      issueKey: 'PROJ-1',
      transitionKey: transitionKey('open', 'in_progress'),
      fromStateKey: 'open',
      toStateKey: 'in_progress',
      actorId: 'user-1',
      actorRoles: ['MEMBER'],
      version: 1,
    })

    expect(result.toStateKey).toBe('in_progress')
  })

  it('T6-2: planTransition이 직렬화하는 body에 transitionName 키가 없다', async () => {
    let capturedBody: Record<string, unknown> | null = null

    server.use(
      http.post('/api/v1/workflows/:key/transitions/plan', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({
          data: {
            toStateKey: 'in_progress',
            fieldChanges: [],
            events: [],
          },
        })
      }),
    )

    await planTransition('software-default', {
      issueKey: 'PROJ-1',
      transitionKey: transitionKey('open', 'in_progress'),
      fromStateKey: 'open',
      toStateKey: 'in_progress',
      actorId: 'user-1',
      version: 1,
    })

    expect(capturedBody).not.toBeNull()
    expect(capturedBody).not.toHaveProperty('transitionName')
  })

  it('T6-3: 계획 요청은 /transitions/plan 으로 간다 — 전환 정의 CRUD 경로가 아니다 (D-1)', async () => {
    let capturedUrl: string | null = null

    server.use(
      http.post('/api/v1/workflows/:key/transitions/plan', ({ request }) => {
        capturedUrl = new URL(request.url).pathname
        return HttpResponse.json({
          data: { toStateKey: 'in_progress', fieldChanges: [], events: [] },
        })
      }),
    )

    await planTransition('software-default', {
      issueKey: 'PROJ-1',
      transitionKey: transitionKey('open', 'in_progress'),
      fromStateKey: 'open',
      toStateKey: 'in_progress',
      actorId: 'user-1',
      version: 1,
    })

    expect(capturedUrl).toBe('/api/v1/workflows/software-default/transitions/plan')
  })
})
