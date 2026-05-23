// workflow-fixtures.ts 의 transition.key 형식 회귀 가드 — 옵션 B (helper 호출) 채택으로 drift 본질 차단
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
