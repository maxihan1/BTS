// project-workflow BC MSW mock handlers (4 표준 워크플로우 fixture)
import { http, HttpResponse } from 'msw'

/**
 * 4 표준 워크플로우 fixture 데이터.
 * backend YAML seed 파일(`backend/modules/project-workflow/src/main/resources/workflows/`)과 일치.
 *
 * 주의: backend WorkflowDto에 없는 필드(description, transition.key)는
 * frontend spec §6 WorkflowView 스키마에 맞게 MSW 레벨에서 보완한다.
 */
const workflowFixtures = [
  {
    key: 'software-default',
    name: '소프트웨어 개발 기본 워크플로우',
    description: '소프트웨어 개발 팀을 위한 기본 워크플로우',
    states: [
      { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
      { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 2 },
      { key: 'in_review', name: 'In Review', category: 'IN_PROGRESS', displayOrder: 3 },
      { key: 'done', name: 'Done', category: 'DONE', displayOrder: 4 },
      { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 5 },
    ],
    transitions: [
      { key: 'open-to-in_progress', name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
      { key: 'in_progress-to-in_review', name: 'Submit for Review', fromStateKey: 'in_progress', toStateKey: 'in_review' },
      { key: 'in_review-to-done', name: 'Approve', fromStateKey: 'in_review', toStateKey: 'done' },
      { key: 'in_review-to-in_progress', name: 'Request Changes', fromStateKey: 'in_review', toStateKey: 'in_progress' },
      { key: 'done-to-closed', name: 'Close', fromStateKey: 'done', toStateKey: 'closed' },
      { key: 'open-to-closed', name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
    ],
  },
  {
    key: 'bug-tracking',
    name: '버그 추적 워크플로우',
    description: '버그 수명 주기를 추적하는 워크플로우',
    states: [
      { key: 'reported', name: 'Reported', category: 'TODO', displayOrder: 1 },
      { key: 'triaged', name: 'Triaged', category: 'TODO', displayOrder: 2 },
      { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 3 },
      { key: 'resolved', name: 'Resolved', category: 'DONE', displayOrder: 4 },
      { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 5 },
    ],
    transitions: [
      { key: 'reported-to-triaged', name: 'Triage', fromStateKey: 'reported', toStateKey: 'triaged' },
      { key: 'triaged-to-in_progress', name: 'Start Fix', fromStateKey: 'triaged', toStateKey: 'in_progress' },
      { key: 'in_progress-to-resolved', name: 'Resolve', fromStateKey: 'in_progress', toStateKey: 'resolved' },
      { key: 'resolved-to-closed', name: 'Close', fromStateKey: 'resolved', toStateKey: 'closed' },
      { key: 'resolved-to-in_progress', name: 'Reopen', fromStateKey: 'resolved', toStateKey: 'in_progress' },
    ],
  },
  {
    key: 'simple',
    name: '단순 워크플로우 (TODO/DOING/DONE)',
    description: '3단계 단순 워크플로우',
    states: [
      { key: 'todo', name: 'To Do', category: 'TODO', displayOrder: 1 },
      { key: 'doing', name: 'Doing', category: 'IN_PROGRESS', displayOrder: 2 },
      { key: 'done', name: 'Done', category: 'DONE', displayOrder: 3 },
    ],
    transitions: [
      { key: 'todo-to-doing', name: 'Start', fromStateKey: 'todo', toStateKey: 'doing' },
      { key: 'doing-to-done', name: 'Complete', fromStateKey: 'doing', toStateKey: 'done' },
      { key: 'done-to-doing', name: 'Reopen', fromStateKey: 'done', toStateKey: 'doing' },
    ],
  },
  {
    key: 'kanban-basic',
    name: '칸반 기본 워크플로우',
    description: '칸반 방식의 기본 워크플로우',
    states: [
      { key: 'backlog', name: 'Backlog', category: 'TODO', displayOrder: 1 },
      { key: 'ready', name: 'Ready', category: 'TODO', displayOrder: 2 },
      { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 3 },
      { key: 'done', name: 'Done', category: 'DONE', displayOrder: 4 },
    ],
    transitions: [
      { key: 'backlog-to-ready', name: 'Refine', fromStateKey: 'backlog', toStateKey: 'ready' },
      { key: 'ready-to-in_progress', name: 'Pull', fromStateKey: 'ready', toStateKey: 'in_progress' },
      { key: 'in_progress-to-done', name: 'Finish', fromStateKey: 'in_progress', toStateKey: 'done' },
    ],
  },
] as const

/**
 * MSW 요청 핸들러 목록.
 *
 * - GET  /api/v1/workflows       — 4 표준 워크플로우 배열 반환
 * - GET  /api/v1/workflows/:key  — key 매칭 단건 반환 (없으면 404)
 * - POST /api/v1/workflows/:key/transitions — mock TransitionPlan 반환
 */
export const workflowHandlers = [
  http.get('/api/v1/workflows', () => {
    return HttpResponse.json({ data: workflowFixtures })
  }),

  http.get('/api/v1/workflows/:key', ({ params }) => {
    const found = workflowFixtures.find((w) => w.key === params['key'])
    if (found === undefined) {
      return HttpResponse.json({ message: '워크플로우를 찾을 수 없습니다' }, { status: 404 })
    }
    return HttpResponse.json({ data: found })
  }),

  http.post('/api/v1/workflows/:key/transitions', ({ params }) => {
    const key = params['key'] as string
    const workflow = workflowFixtures.find((w) => w.key === key)
    if (workflow === undefined) {
      return HttpResponse.json({ message: '워크플로우를 찾을 수 없습니다' }, { status: 404 })
    }
    const firstTransition = workflow.transitions[0]
    const toStateKey = firstTransition !== undefined ? firstTransition.toStateKey : 'done'

    return HttpResponse.json({
      data: {
        toStateKey,
        fieldChanges: [],
        events: [{ type: 'ISSUE_TRANSITIONED', payload: { workflowKey: key } }],
      },
    })
  }),
]
