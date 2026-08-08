// 스프린트 완료 시 미완료 이슈 판정 순수 함수 단위 테스트 (FR-UX-13 F15 · FR-7 · T-WF-1/T-WF-2)
import { describe, it, expect } from 'vitest'
import type { WorkflowView } from '@/api/workflows'
import type { BacklogIssue } from '@/api/backlog'
import { buildStateCategoryMap, isIssueIncomplete } from './backlog-completion'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** WorkflowView 를 states 만 바꿔가며 만드는 헬퍼. transitions 는 판정에 쓰이지 않는다. */
const makeWorkflow = (key: string, states: WorkflowView['states']): WorkflowView => ({
  key,
  name: `${key} 워크플로우`,
  description: '',
  states,
  transitions: [],
})

/**
 * 실제 시드 워크플로우 4종의 상태 구성 (U2 실측 · 2026-08-05).
 *
 * 출처. `backend/modules/project-workflow/src/main/resources/workflows/*.yaml`
 * `done` 은 3개, `closed` 는 2개, `in_progress` 는 3개 워크플로우에 중복 등장하지만
 * **카테고리가 갈리는 키는 0건**이다. 즉 시드 데이터에서는 충돌이 없다.
 */
const SEED_WORKFLOWS: WorkflowView[] = [
  makeWorkflow('software-default', [
    { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
    { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'in_review', name: 'In Review', category: 'IN_PROGRESS', displayOrder: 3 },
    { key: 'done', name: 'Done', category: 'DONE', displayOrder: 4 },
    { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 5 },
  ]),
  makeWorkflow('bug-tracking', [
    { key: 'reported', name: 'Reported', category: 'TODO', displayOrder: 1 },
    { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'resolved', name: 'Resolved', category: 'DONE', displayOrder: 3 },
    { key: 'closed', name: 'Closed', category: 'DONE', displayOrder: 4 },
  ]),
  makeWorkflow('simple', [
    { key: 'todo', name: 'To Do', category: 'TODO', displayOrder: 1 },
    { key: 'doing', name: 'Doing', category: 'IN_PROGRESS', displayOrder: 2 },
    { key: 'done', name: 'Done', category: 'DONE', displayOrder: 3 },
  ]),
]

/**
 * 충돌 키 픽스처. 시드에는 없지만(U2 실측 0건) 관리자가 워크플로우를 편집할 수 있으므로
 * 분기 자체는 지켜야 한다.
 * - `verified` → {DONE, IN_PROGRESS}
 * - `staged` → {TODO, IN_PROGRESS}
 */
const CONFLICTING_WORKFLOWS: WorkflowView[] = [
  makeWorkflow('alpha', [
    { key: 'verified', name: 'Verified', category: 'DONE', displayOrder: 1 },
    { key: 'staged', name: 'Staged', category: 'TODO', displayOrder: 2 },
  ]),
  makeWorkflow('beta', [
    { key: 'verified', name: 'Verified', category: 'IN_PROGRESS', displayOrder: 1 },
    { key: 'staged', name: 'Staged', category: 'IN_PROGRESS', displayOrder: 2 },
  ]),
]

/** 판정 대상 이슈를 상태 키만 바꿔 만든다. */
const issueWithState = (currentStateKey: string) => ({ currentStateKey })

// ─────────────────────────────────────────────────────────────────────────────
// buildStateCategoryMap
// ─────────────────────────────────────────────────────────────────────────────

describe('buildStateCategoryMap', () => {
  it('전 워크플로우의 states 를 훑어 상태 키별 카테고리 집합을 만든다', () => {
    const map = buildStateCategoryMap(SEED_WORKFLOWS)

    expect(map.unavailable).toBe(false)
    expect([...map.categoriesByStateKey.keys()].sort()).toEqual([
      'closed',
      'doing',
      'done',
      'in_progress',
      'in_review',
      'open',
      'reported',
      'resolved',
      'todo',
    ])
    expect([...(map.categoriesByStateKey.get('done') ?? [])]).toEqual(['DONE'])
    expect([...(map.categoriesByStateKey.get('in_progress') ?? [])]).toEqual(['IN_PROGRESS'])
  })

  it('같은 키가 워크플로우마다 다른 카테고리면 집합에 둘 다 담는다', () => {
    const map = buildStateCategoryMap(CONFLICTING_WORKFLOWS)

    expect([...(map.categoriesByStateKey.get('verified') ?? [])].sort()).toEqual([
      'DONE',
      'IN_PROGRESS',
    ])
  })

  it('워크플로우 조회 실패(undefined)면 빈 맵 + unavailable=true (T-WF-2)', () => {
    const map = buildStateCategoryMap(undefined)

    expect(map.unavailable).toBe(true)
    expect(map.categoriesByStateKey.size).toBe(0)
  })

  it('워크플로우가 0개면 빈 맵이지만 unavailable=false — 조회 실패와 구분한다', () => {
    const map = buildStateCategoryMap([])

    expect(map.unavailable).toBe(false)
    expect(map.categoriesByStateKey.size).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isIssueIncomplete
// ─────────────────────────────────────────────────────────────────────────────

describe('isIssueIncomplete', () => {
  const seedMap = buildStateCategoryMap(SEED_WORKFLOWS)
  const conflictMap = buildStateCategoryMap(CONFLICTING_WORKFLOWS)

  it('카테고리 집합이 정확히 {DONE} 인 키만 완료로 본다 (T-WF-1)', () => {
    expect(isIssueIncomplete(issueWithState('done'), seedMap)).toBe(false)
    expect(isIssueIncomplete(issueWithState('resolved'), seedMap)).toBe(false)
  })

  it('여러 워크플로우가 모두 DONE 으로 정의한 키도 완료로 본다 — 실측 done·closed', () => {
    expect(isIssueIncomplete(issueWithState('closed'), seedMap)).toBe(false)
  })

  it.each(['open', 'todo', 'in_progress', 'in_review', 'doing'])(
    'DONE 이 아닌 키(%s)는 미완료로 본다',
    (stateKey) => {
      expect(isIssueIncomplete(issueWithState(stateKey), seedMap)).toBe(true)
    },
  )

  it('어느 워크플로우에도 없는 키는 미완료로 본다 — 빈 집합은 안전측', () => {
    expect(isIssueIncomplete(issueWithState('deleted_state'), seedMap)).toBe(true)
  })

  it('DONE 과 다른 카테고리가 섞인 충돌 키는 미완료로 본다', () => {
    expect(isIssueIncomplete(issueWithState('verified'), conflictMap)).toBe(true)
  })

  it('DONE 이 없는 충돌 키도 미완료로 본다', () => {
    expect(isIssueIncomplete(issueWithState('staged'), conflictMap)).toBe(true)
  })

  it('워크플로우 조회가 실패하면 DONE 키까지 전건 미완료로 본다 (E14 fail-safe)', () => {
    const failedMap = buildStateCategoryMap(undefined)

    expect(isIssueIncomplete(issueWithState('done'), failedMap)).toBe(true)
    expect(isIssueIncomplete(issueWithState('closed'), failedMap)).toBe(true)
  })

  it('BacklogIssue 를 그대로 받는다 — 구조적 호환을 컴파일 타임에 고정한다', () => {
    const issue: BacklogIssue = {
      key: 'ATLAS-1',
      summary: '백로그 이슈',
      currentStateKey: 'open',
      assigneeId: null,
      priority: 3,
      rank: null,
      version: 0,
      epicKey: null,
      typeKey: 'task',
      labels: [],
      originalEstimateSeconds: null,
    }

    expect(isIssueIncomplete(issue, seedMap)).toBe(true)
  })
})
