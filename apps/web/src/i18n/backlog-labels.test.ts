// backlog-labels.ts 단위 테스트 — i18n 라벨 값 정합성 및 콜론 종결 금지 가드
import { describe, it, expect } from 'vitest'
import { backlogLabels } from './backlog-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 정적 문자열 값 — 콜론 종결 금지 검증 (글로벌 §5)
// ─────────────────────────────────────────────────────────────────────────────

/** 객체를 재귀적으로 순회하여 모든 string 리프 값을 수집한다. 함수는 건너뜀. */
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

describe('backlogLabels — 콜론 종결 금지 (글로벌 §5)', () => {
  const leaves = collectStringLeaves(backlogLabels)

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
// 함수 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogLabels — columnAriaLabel 함수', () => {
  it('columnAriaLabel("백로그", 5) → "백로그 칸, 5개 이슈"', () => {
    expect(backlogLabels.columnAriaLabel('백로그', 5)).toBe('백로그 칸, 5개 이슈')
  })

  it('columnAriaLabel("스프린트 1", 0) → "스프린트 1 칸, 0개 이슈"', () => {
    expect(backlogLabels.columnAriaLabel('스프린트 1', 0)).toBe('스프린트 1 칸, 0개 이슈')
  })

  it('columnAriaLabel 반환값이 콜론으로 끝나지 않는다', () => {
    expect(backlogLabels.columnAriaLabel('백로그', 3)).not.toMatch(/:$/)
  })
})

describe('backlogLabels — cardAriaLabel 함수', () => {
  it('cardAriaLabel("ATLAS-1", "이슈 제목") → "ATLAS-1 — 이슈 제목"', () => {
    expect(backlogLabels.cardAriaLabel('ATLAS-1', '이슈 제목')).toBe('ATLAS-1 — 이슈 제목')
  })

  it('cardAriaLabel 반환값이 콜론으로 끝나지 않는다', () => {
    expect(backlogLabels.cardAriaLabel('ATLAS-1', '이슈')).not.toMatch(/:$/)
  })
})
