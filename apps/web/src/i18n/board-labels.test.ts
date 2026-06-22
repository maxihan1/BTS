// board-labels.ts 단위 테스트 — i18n 라벨 값 정합성 및 콜론 종결 금지 가드
import { describe, it, expect } from 'vitest'
import { boardLabels } from './board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 정적 문자열 값 — 콜론 종결 금지 검증
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 객체를 재귀적으로 순회하여 모든 string 리프(leaf) 값을 수집한다.
 * 함수는 건너뛴다 (동적 생성 값은 별도 테스트).
 */
function collectStringLeaves(obj: unknown, path = ''): Array<{ path: string; value: string }> {
  if (typeof obj === 'string') return [{ path, value: obj }]
  if (typeof obj === 'function') return []
  if (obj !== null && typeof obj === 'object') {
    return Object.entries(obj as Record<string, unknown>).flatMap(([k, v]) =>
      collectStringLeaves(v, path ? `${path}.${k}` : k),
    )
  }
  return []
}

describe('boardLabels — 콜론 종결 금지 (글로벌 §5)', () => {
  const leaves = collectStringLeaves(boardLabels)

  it('정적 문자열 라벨이 하나 이상 존재한다', () => {
    expect(leaves.length).toBeGreaterThan(0)
  })

  leaves.forEach(({ path, value }) => {
    it(`"${path}" 값이 콜론으로 끝나지 않는다`, () => {
      expect(value.trimEnd()).not.toMatch(/:$/)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// wip 그룹 함수 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('boardLabels.wip', () => {
  it('countLabel(2, 5) → "2/5"', () => {
    expect(boardLabels.wip.countLabel(2, 5)).toBe('2/5')
  })

  it('countLabel(3, 2) → "3/2" (초과 케이스)', () => {
    expect(boardLabels.wip.countLabel(3, 2)).toBe('3/2')
  })

  it('exceededAriaLabel은 비어있지 않다', () => {
    expect(boardLabels.wip.exceededAriaLabel.length).toBeGreaterThan(0)
  })

  it('exceededAriaLabel은 콜론으로 끝나지 않는다', () => {
    expect(boardLabels.wip.exceededAriaLabel.trimEnd()).not.toMatch(/:$/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// column 그룹 함수 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('boardLabels.column', () => {
  it('ariaLabel("진행 중", 3) → "진행 중 컬럼, 3개 카드"', () => {
    expect(boardLabels.column.ariaLabel('진행 중', 3)).toBe('진행 중 컬럼, 3개 카드')
  })

  it('cardCountAriaLabel(5) → "카드 5개"', () => {
    expect(boardLabels.column.cardCountAriaLabel(5)).toBe('카드 5개')
  })
})
