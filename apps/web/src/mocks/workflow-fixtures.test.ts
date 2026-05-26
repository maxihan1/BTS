// workflow-fixtures.ts 의 transition.key 형식 + description 회귀 가드
// 옵션 B (helper 호출) 채택으로 transition.key drift 본질 차단
// 옵션 A 한계: description 은 정적 문자열이므로 backend yaml seed 와 수동으로 맞춰야 함 — 이 테스트가 그 drift 를 감지한다
import { describe, it, expect } from 'vitest'
import { allWorkflowFixtures } from './workflow-fixtures'
import { transitionKey } from '@/components/workflow/workflow.types'

describe('workflow-fixtures transition.key 형식 검증 (D2 옵션 B 회귀 가드)', () => {
  it.each(allWorkflowFixtures)(
    '$key — 모든 transition.key 가 transitionKey() 결과와 일치',
    (workflow) => {
      workflow.transitions.forEach((t) => {
        expect(t.key).toBe(transitionKey(t.fromStateKey, t.toStateKey))
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
