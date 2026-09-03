// 이관 선택 상태 판정 테스트 — 부분 매핑 차단 · 후보 제한 · 서버 계약 형태 (FR-WF-07 D6b)
import { describe, it, expect } from 'vitest'
import { canStartMigration, toMigrationRequest } from './workflow-migration'
import type { EditableDraft } from '@/lib/workflow-draft'
import type { DraftDefinition } from '@/api/workflows-draft.types'

const OPEN = { key: 'open', name: '열림', category: 'TODO', displayOrder: 1, layoutX: null, layoutY: null } as const
const DOING = { key: 'doing', name: '진행 중', category: 'IN_PROGRESS', displayOrder: 2, layoutX: null, layoutY: null } as const
const DONE = { key: 'done', name: '완료', category: 'DONE', displayOrder: 3, layoutX: null, layoutY: null } as const
const BLOCKED = { key: 'blocked', name: '차단됨', category: 'TODO', displayOrder: 4, layoutX: null, layoutY: null } as const

/** 발행본 — `doing`·`done` 을 포함한 3개 상태. */
function published(): DraftDefinition {
  return {
    key: 'wf',
    name: '워크플로우',
    description: null,
    states: [OPEN, DOING, DONE],
    transitions: [],
  }
}

/**
 * 초안 — `doing`·`done` 두 상태를 뺐다(발행하면 사라질 후보 2개). `blocked` 는 이번에 새로
 * 추가한 상태라 아직 발행본에 없다 — 도착지 후보가 될 수 없어야 한다(F16).
 */
function draftWithTwoRemoved(): EditableDraft {
  return {
    key: 'wf',
    name: '워크플로우',
    description: null,
    states: [OPEN, BLOCKED],
    transitions: [],
  }
}

describe('canStartMigration — 빠지는 상태마다 각각 고른다 (Jira J7)', () => {
  it('사라지는 상태 2개 중 1개만 골랐으면 시작할 수 없다', () => {
    const selection = { doing: 'open' }

    expect(canStartMigration(selection, draftWithTwoRemoved(), published())).toBe(false)
  })

  it('둘 다 골랐으면 시작할 수 있다', () => {
    const selection = { doing: 'open', done: 'open' }

    expect(canStartMigration(selection, draftWithTwoRemoved(), published())).toBe(true)
  })

  it('도착지가 migrationTargets 후보 밖이면 거부한다 — 서버 F16 선반영', () => {
    // blocked 는 초안에는 있지만 아직 발행본에 없다 — migrationTargets 후보가 아니다.
    const selection = { doing: 'blocked', done: 'open' }

    expect(canStartMigration(selection, draftWithTwoRemoved(), published())).toBe(false)
  })

  it('아무것도 고르지 않았으면 시작할 수 없다', () => {
    expect(canStartMigration({}, draftWithTwoRemoved(), published())).toBe(false)
  })
})

describe('toMigrationRequest — 서버 계약(StatusMappingInput) 과 일치', () => {
  it('선택을 fromStatusKey/toStatusKey 쌍 배열로 옮긴다', () => {
    const selection = { doing: 'open', done: 'open' }

    expect(toMigrationRequest(selection)).toEqual([
      { fromStatusKey: 'doing', toStatusKey: 'open' },
      { fromStatusKey: 'done', toStatusKey: 'open' },
    ])
  })
})
