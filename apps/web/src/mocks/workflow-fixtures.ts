// project-workflow BC 4 표준 워크플로우 fixture 데이터
// backend YAML seed 파일(`backend/modules/project-workflow/src/main/resources/workflows/`)과 일치
//
// 주의사항.
// - backend WorkflowDto에는 `description`과 transition의 `key` 필드가 없다.
//   frontend spec §6 WorkflowView 스키마에 맞게 MSW 레벨에서 보완한다.
// - transition.key 는 backend computed property 와 동일 형식(`${fromStateKey}__${toStateKey}`)으로 통일.
//   정적 문자열 직접 작성 금지 — transitionKey() helper 호출로 drift 를 원천 차단한다.
// - software-default: 5 상태 + 6 전이 (backend YAML 기준)
// - bug-tracking: 5 상태 + 5 전이
// - simple: 3 상태 + 3 전이
// - kanban-basic: 4 상태 + 3 전이
import type { WorkflowView } from '@/api/workflows'
import { transitionKey } from '@/components/workflow/workflow.types'

/** 소프트웨어 개발 기본 워크플로우 — 5 상태 + 6 전이 */
export const softwareDefaultFixture: WorkflowView = {
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
    { key: transitionKey('open', 'in_progress'), name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
    { key: transitionKey('in_progress', 'in_review'), name: 'Submit for Review', fromStateKey: 'in_progress', toStateKey: 'in_review' },
    { key: transitionKey('in_review', 'done'), name: 'Approve', fromStateKey: 'in_review', toStateKey: 'done' },
    { key: transitionKey('in_review', 'in_progress'), name: 'Request Changes', fromStateKey: 'in_review', toStateKey: 'in_progress' },
    { key: transitionKey('done', 'closed'), name: 'Close', fromStateKey: 'done', toStateKey: 'closed' },
    { key: transitionKey('open', 'closed'), name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
  ],
}

/** 버그 추적 워크플로우 — 5 상태 + 5 전이 */
export const bugTrackingFixture: WorkflowView = {
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
    { key: transitionKey('reported', 'triaged'), name: 'Triage', fromStateKey: 'reported', toStateKey: 'triaged' },
    { key: transitionKey('triaged', 'in_progress'), name: 'Start Fix', fromStateKey: 'triaged', toStateKey: 'in_progress' },
    { key: transitionKey('in_progress', 'resolved'), name: 'Resolve', fromStateKey: 'in_progress', toStateKey: 'resolved' },
    { key: transitionKey('resolved', 'closed'), name: 'Close', fromStateKey: 'resolved', toStateKey: 'closed' },
    { key: transitionKey('resolved', 'in_progress'), name: 'Reopen', fromStateKey: 'resolved', toStateKey: 'in_progress' },
  ],
}

/** 단순 워크플로우 — 3 상태 + 3 전이 */
export const simpleFixture: WorkflowView = {
  key: 'simple',
  name: '단순 워크플로우 (TODO/DOING/DONE)',
  description: '3단계 단순 워크플로우',
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

/** 칸반 기본 워크플로우 — 4 상태 + 3 전이 */
export const kanbanBasicFixture: WorkflowView = {
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
    { key: transitionKey('backlog', 'ready'), name: 'Refine', fromStateKey: 'backlog', toStateKey: 'ready' },
    { key: transitionKey('ready', 'in_progress'), name: 'Pull', fromStateKey: 'ready', toStateKey: 'in_progress' },
    { key: transitionKey('in_progress', 'done'), name: 'Finish', fromStateKey: 'in_progress', toStateKey: 'done' },
  ],
}

/** 4 표준 워크플로우 전체 목록 */
export const allWorkflowFixtures: WorkflowView[] = [
  softwareDefaultFixture,
  bugTrackingFixture,
  simpleFixture,
  kanbanBasicFixture,
]
