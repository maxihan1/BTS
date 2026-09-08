// project-workflow BC 4 표준 워크플로우 fixture 데이터
// backend YAML seed 파일(`backend/modules/project-workflow/src/main/resources/workflows/`)과 일치
//
// 주의사항.
// - backend WorkflowDto에는 `description` 필드가 없다.
//   frontend spec §6 WorkflowView 스키마에 맞게 MSW 레벨에서 보완한다.
// - transition.key 는 backend computed property 와 동일 형식으로 통일됨. (PR #16 C-2 옵션 B 채택)
//   정적 문자열 직접 작성 금지 — helper 호출로 drift 를 원천 차단한다.
// - description 은 옵션 A 한계: 정적 문자열이므로 backend yaml seed 와 수동으로 동기화해야 함.
//   workflow-fixtures.test.ts 의 description 회귀 가드가 drift 를 감지한다.
// - INITIAL 전환은 마이그레이션 V207 ⑨ 가 워크플로우마다 1건씩 백필한다(이름 `이슈 생성` ·
//   도착지 = displayOrder 최소 상태 · from 은 NULL). 목이 그 행을 빠뜨리면 실제 응답에만 있는
//   `fromStateKey: null` 원소를 프론트가 한 번도 마주치지 않는다.
// - software-default: 5 상태 + 7 전환 (INITIAL 1 + NORMAL 6)
// - bug-tracking: 5 상태 + 6 전환 (INITIAL 1 + NORMAL 5)
// - simple: 3 상태 + 4 전환 (INITIAL 1 + NORMAL 3)
// - kanban-basic: 4 상태 + 4 전환 (INITIAL 1 + NORMAL 3)
import type { WorkflowView } from '@/api/workflows'
import { transitionKey, kindTransitionKey } from '@/lib/transition-key'

/**
 * 픽스처 전용 결정적 전환 id 를 만든다.
 *
 * 실제 값은 DB 가 정한다(`workflow_transitions.id UUID DEFAULT gen_random_uuid()`). 목은 실행마다
 * 같아야 E2E·스냅샷이 재현되므로 워크플로우 번호와 전환 번호로 UUID 를 합성한다.
 * RFC4122 v4 형식(version=4, variant=8) — Zod v4 `z.string().uuid()` 통과 보장.
 *
 * @param workflowNo 픽스처 워크플로우 번호 (1=software-default · 2=bug-tracking · 3=simple · 4=kanban-basic)
 * @param transitionNo 그 워크플로우 안의 전환 번호. INITIAL 은 0, NORMAL 은 1 부터.
 * @returns `00000000-0000-4000-8000-` 로 시작하는 결정적 UUID 문자열
 */
function fixtureTransitionId(workflowNo: number, transitionNo: number): string {
  const suffix = `${workflowNo}`.padStart(2, '0') + `${transitionNo}`.padStart(10, '0')
  return `00000000-0000-4000-8000-${suffix}`
}

// GLOBAL·INITIAL key 규칙은 `@/lib/transition-key` 가 정본이다 — 여기서 다시 구현하지 않는다.

/** V207 ⑨ 백필이 심는 INITIAL 전환의 이름 — 마이그레이션 SQL 리터럴과 같은 문자열 */
const INITIAL_TRANSITION_NAME = '이슈 생성'

/** 소프트웨어 개발 기본 워크플로우 — 5 상태 + 7 전환(INITIAL 1 + NORMAL 6) */
export const softwareDefaultFixture: WorkflowView = {
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
    { key: kindTransitionKey('INITIAL', 'open'), name: INITIAL_TRANSITION_NAME, fromStateKey: null, toStateKey: 'open', id: fixtureTransitionId(1, 0), kind: 'INITIAL' },
    { key: transitionKey('open', 'in_progress'), name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress', id: fixtureTransitionId(1, 1), kind: 'NORMAL' },
    { key: transitionKey('in_progress', 'in_review'), name: 'Submit for Review', fromStateKey: 'in_progress', toStateKey: 'in_review', id: fixtureTransitionId(1, 2), kind: 'NORMAL' },
    { key: transitionKey('in_review', 'done'), name: 'Approve', fromStateKey: 'in_review', toStateKey: 'done', id: fixtureTransitionId(1, 3), kind: 'NORMAL' },
    { key: transitionKey('in_review', 'in_progress'), name: 'Request Changes', fromStateKey: 'in_review', toStateKey: 'in_progress', id: fixtureTransitionId(1, 4), kind: 'NORMAL' },
    { key: transitionKey('done', 'closed'), name: 'Close', fromStateKey: 'done', toStateKey: 'closed', id: fixtureTransitionId(1, 5), kind: 'NORMAL' },
    { key: transitionKey('open', 'closed'), name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed', id: fixtureTransitionId(1, 6), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** 버그 추적 워크플로우 — 5 상태 + 6 전환(INITIAL 1 + NORMAL 5) */
export const bugTrackingFixture: WorkflowView = {
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
    { key: kindTransitionKey('INITIAL', 'reported'), name: INITIAL_TRANSITION_NAME, fromStateKey: null, toStateKey: 'reported', id: fixtureTransitionId(2, 0), kind: 'INITIAL' },
    { key: transitionKey('reported', 'triaged'), name: 'Triage', fromStateKey: 'reported', toStateKey: 'triaged', id: fixtureTransitionId(2, 1), kind: 'NORMAL' },
    { key: transitionKey('triaged', 'in_progress'), name: 'Start Fix', fromStateKey: 'triaged', toStateKey: 'in_progress', id: fixtureTransitionId(2, 2), kind: 'NORMAL' },
    { key: transitionKey('in_progress', 'resolved'), name: 'Resolve', fromStateKey: 'in_progress', toStateKey: 'resolved', id: fixtureTransitionId(2, 3), kind: 'NORMAL' },
    { key: transitionKey('resolved', 'closed'), name: 'Close', fromStateKey: 'resolved', toStateKey: 'closed', id: fixtureTransitionId(2, 4), kind: 'NORMAL' },
    { key: transitionKey('resolved', 'in_progress'), name: 'Reopen', fromStateKey: 'resolved', toStateKey: 'in_progress', id: fixtureTransitionId(2, 5), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** 단순 워크플로우 — 3 상태 + 4 전환(INITIAL 1 + NORMAL 3) */
export const simpleFixture: WorkflowView = {
  key: 'simple',
  name: '단순 워크플로우 (TODO/DOING/DONE)',
  description: 'To Do → Doing → Done 3단계 단순 워크플로우',
  states: [
    { key: 'todo', name: 'To Do', category: 'TODO', displayOrder: 1 },
    { key: 'doing', name: 'Doing', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'done', name: 'Done', category: 'DONE', displayOrder: 3 },
  ],
  transitions: [
    { key: kindTransitionKey('INITIAL', 'todo'), name: INITIAL_TRANSITION_NAME, fromStateKey: null, toStateKey: 'todo', id: fixtureTransitionId(3, 0), kind: 'INITIAL' },
    { key: transitionKey('todo', 'doing'), name: 'Start', fromStateKey: 'todo', toStateKey: 'doing', id: fixtureTransitionId(3, 1), kind: 'NORMAL' },
    { key: transitionKey('doing', 'done'), name: 'Complete', fromStateKey: 'doing', toStateKey: 'done', id: fixtureTransitionId(3, 2), kind: 'NORMAL' },
    { key: transitionKey('done', 'doing'), name: 'Reopen', fromStateKey: 'done', toStateKey: 'doing', id: fixtureTransitionId(3, 3), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** 칸반 기본 워크플로우 — 4 상태 + 4 전환(INITIAL 1 + NORMAL 3) */
export const kanbanBasicFixture: WorkflowView = {
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
    { key: kindTransitionKey('INITIAL', 'backlog'), name: INITIAL_TRANSITION_NAME, fromStateKey: null, toStateKey: 'backlog', id: fixtureTransitionId(4, 0), kind: 'INITIAL' },
    { key: transitionKey('backlog', 'ready'), name: 'Refine', fromStateKey: 'backlog', toStateKey: 'ready', id: fixtureTransitionId(4, 1), kind: 'NORMAL' },
    { key: transitionKey('ready', 'in_progress'), name: 'Pull', fromStateKey: 'ready', toStateKey: 'in_progress', id: fixtureTransitionId(4, 2), kind: 'NORMAL' },
    { key: transitionKey('in_progress', 'done'), name: 'Finish', fromStateKey: 'in_progress', toStateKey: 'done', id: fixtureTransitionId(4, 3), kind: 'NORMAL' },
  ],
  projectId: null,
}

/** 4 표준 워크플로우 전체 목록 */
export const allWorkflowFixtures: WorkflowView[] = [
  softwareDefaultFixture,
  bugTrackingFixture,
  simpleFixture,
  kanbanBasicFixture,
]
