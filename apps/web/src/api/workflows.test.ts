// project-workflow API client 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { fetchWorkflows, fetchWorkflow } from './workflows'
import { transitionKey } from '@/components/workflow/workflow.types'

// ─────────────────────────────────────────────────────────────────────────────
// software-default fixture — backend YAML 기준
// 5 상태 + 6 전이 (software-default.yaml 실제 데이터)
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
    { key: transitionKey('open', 'in_progress'), name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
    { key: transitionKey('in_progress', 'in_review'), name: 'Submit for Review', fromStateKey: 'in_progress', toStateKey: 'in_review' },
    { key: transitionKey('in_review', 'done'), name: 'Approve', fromStateKey: 'in_review', toStateKey: 'done' },
    { key: transitionKey('in_review', 'in_progress'), name: 'Request Changes', fromStateKey: 'in_review', toStateKey: 'in_progress' },
    { key: transitionKey('done', 'closed'), name: 'Close', fromStateKey: 'done', toStateKey: 'closed' },
    { key: transitionKey('open', 'closed'), name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
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
    { key: transitionKey('reported', 'triaged'), name: 'Triage', fromStateKey: 'reported', toStateKey: 'triaged' },
    { key: transitionKey('triaged', 'in_progress'), name: 'Start Fix', fromStateKey: 'triaged', toStateKey: 'in_progress' },
    { key: transitionKey('in_progress', 'resolved'), name: 'Resolve', fromStateKey: 'in_progress', toStateKey: 'resolved' },
    { key: transitionKey('resolved', 'closed'), name: 'Close', fromStateKey: 'resolved', toStateKey: 'closed' },
    { key: transitionKey('resolved', 'in_progress'), name: 'Reopen', fromStateKey: 'resolved', toStateKey: 'in_progress' },
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
    { key: transitionKey('todo', 'doing'), name: 'Start', fromStateKey: 'todo', toStateKey: 'doing' },
    { key: transitionKey('doing', 'done'), name: 'Complete', fromStateKey: 'doing', toStateKey: 'done' },
    { key: transitionKey('done', 'doing'), name: 'Reopen', fromStateKey: 'done', toStateKey: 'doing' },
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
    { key: transitionKey('backlog', 'ready'), name: 'Refine', fromStateKey: 'backlog', toStateKey: 'ready' },
    { key: transitionKey('ready', 'in_progress'), name: 'Pull', fromStateKey: 'ready', toStateKey: 'in_progress' },
    { key: transitionKey('in_progress', 'done'), name: 'Finish', fromStateKey: 'in_progress', toStateKey: 'done' },
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

      // 각 전이가 WorkflowTransitionView 필드를 가져야 한다
      for (const transition of workflow.transitions) {
        expect(typeof transition.key).toBe('string')
        expect(typeof transition.name).toBe('string')
        expect(typeof transition.fromStateKey).toBe('string')
        expect(typeof transition.toStateKey).toBe('string')
      }
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-2. fetchWorkflow('software-default') — 단건 + Zod 파싱
// backend YAML 기준: software-default = 5 상태 + 6 전이
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchWorkflow', () => {
  it('T4-2: software-default 단건 조회 — key 일치, states.length === 5, transitions.length === 6', async () => {
    const result = await fetchWorkflow('software-default')

    expect(result.key).toBe('software-default')
    expect(result.states).toHaveLength(5)
    expect(result.transitions).toHaveLength(6)
  })

  it('T4-2b: 존재하지 않는 key 조회 시 에러를 throw한다', async () => {
    await expect(fetchWorkflow('non-existent')).rejects.toThrow()
  })
})
