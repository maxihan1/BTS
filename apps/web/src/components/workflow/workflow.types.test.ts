// transitionKey helper 단위 테스트 (D1 결정 — view layer 타입 정의 인접 위치)

import { describe, it, expect } from 'vitest'
import { transitionKey } from './workflow.types'

describe('transitionKey', () => {
  it('returns "{from}__{to}" composition for plain state keys', () => {
    expect(transitionKey('open', 'in_progress')).toBe('open__in_progress')
  })

  it('preserves underscores in state keys (no escaping)', () => {
    expect(transitionKey('in_progress', 'in_review')).toBe('in_progress__in_review')
  })
})
