// workflow-fixtures.ts 의 transition.key 형식 + description + 전환 identity(id·kind·INITIAL) 회귀 가드
// 옵션 B (helper 호출) 채택으로 transition.key drift 본질 차단
// 옵션 A 한계: description 은 정적 문자열이므로 backend yaml seed 와 수동으로 맞춰야 함 — 이 테스트가 그 drift 를 감지한다
import { describe, it, expect } from 'vitest'
import { allWorkflowFixtures } from './workflow-fixtures'
import { transitionKey } from '@/components/workflow/workflow.types'

/** RFC4122 v4 UUID — Zod v4 `z.string().uuid()` 가 통과시키는 형식과 같은 판정 */
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

describe('workflow-fixtures transition.key 형식 검증 (D2 옵션 B 회귀 가드)', () => {
  it.each(allWorkflowFixtures)(
    '$key — 모든 transition.key 가 backend WorkflowTransition.key 게터 규칙과 일치',
    (workflow) => {
      workflow.transitions.forEach((t) => {
        // backend 게터: NORMAL 은 `from__to`, GLOBAL·INITIAL 은 `KIND__to`
        const expected =
          t.fromStateKey === null
            ? `${t.kind}__${t.toStateKey}`
            : transitionKey(t.fromStateKey, t.toStateKey)
        expect(t.key).toBe(expected)
      })
    },
  )
})

// 옵션 A 한계: description 은 코드가 아닌 정적 문자열 — backend yaml seed 와 수동으로 동기화해야 하므로 이 회귀 가드가 필수
describe('workflow-fixtures description 회귀 가드 — backend yaml seed 1:1 일치', () => {
  const expectedDescriptions: Record<string, string> = {
    'software-default':
      'Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우',
    'bug-tracking':
      'Reported → Triaged → In Progress → Resolved → Closed 흐름의 버그 추적 워크플로우',
    simple: 'To Do → Doing → Done 3단계 단순 워크플로우',
    'kanban-basic':
      'Backlog → Ready → In Progress → Done 흐름의 칸반 기본 워크플로우',
  }

  it.each(allWorkflowFixtures)(
    '$key — description 이 backend yaml seed 값과 일치',
    (workflow) => {
      const expected = expectedDescriptions[workflow.key]
      expect(expected).toBeDefined()
      expect(workflow.description).toBe(expected)
    },
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// 전환 identity 회귀 가드 (FR-WF-05).
// 목이 `id`·`kind`·`fromStateKey: null` 을 빠뜨리면 프론트가 실제 응답 모양을 한 번도
// 마주치지 않는다 — 그 상태로 초록인 테스트는 배포 시점에 처음 깨진다.
// ─────────────────────────────────────────────────────────────────────────────
describe('workflow-fixtures 전환 identity — id·kind (backend WorkflowTransitionDto 6필드)', () => {
  it.each(allWorkflowFixtures)('$key — 모든 전환이 UUID id 와 kind 를 갖는다', (workflow) => {
    workflow.transitions.forEach((t) => {
      expect(t.id).toMatch(UUID_V4)
      expect(['NORMAL', 'GLOBAL', 'INITIAL']).toContain(t.kind)
    })
  })

  it('전환 id 는 4 워크플로우 전체에서 유일하다', () => {
    const ids = allWorkflowFixtures.flatMap((w) => w.transitions.map((t) => t.id))
    expect(new Set(ids).size).toBe(ids.length)
  })

  it.each(allWorkflowFixtures)(
    '$key — NORMAL 전환은 출발 상태가 반드시 있다',
    (workflow) => {
      workflow.transitions
        .filter((t) => t.kind === 'NORMAL')
        .forEach((t) => {
          expect(t.fromStateKey).not.toBeNull()
        })
    },
  )
})

describe('workflow-fixtures INITIAL 전환 — 마이그레이션 V207 ⑨ 백필과 같은 모양', () => {
  it.each(allWorkflowFixtures)(
    '$key — INITIAL 이 정확히 1건이고 fromStateKey 가 null 이다',
    (workflow) => {
      const initials = workflow.transitions.filter((t) => t.kind === 'INITIAL')
      expect(initials).toHaveLength(1)
      expect(initials[0]?.fromStateKey).toBeNull()
    },
  )

  it.each(allWorkflowFixtures)(
    '$key — INITIAL 의 도착지가 displayOrder 최소 상태이고 이름이 「이슈 생성」이다',
    (workflow) => {
      const firstState = [...workflow.states].sort(
        (a, b) => a.displayOrder - b.displayOrder,
      )[0]
      const initial = workflow.transitions.find((t) => t.kind === 'INITIAL')
      expect(firstState).toBeDefined()
      expect(initial?.toStateKey).toBe(firstState?.key)
      expect(initial?.name).toBe('이슈 생성')
    },
  )
})
