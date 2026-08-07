// card-labels.ts 단위 테스트 — i18n 라벨 값 정합성 및 콜론 종결 금지 가드
import { describe, it, expect } from 'vitest'
import { cardLabels } from './card-labels'

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

describe('cardLabels — 콜론 종결 금지 (글로벌 §5)', () => {
  const leaves = collectStringLeaves(cardLabels)

  // ★ `expect(leaves).toHaveLength(0)` 을 두지 않는다.
  // 지금은 함수 2종뿐이라 리프가 0개지만, 그 0 을 단언해 두면 **나중에 정적 문자열을
  // 정당하게 추가하는 순간 콜론과 무관한 이 단언이 깨진다** — 가드가 자기가 지키려는
  // 확장을 막는 모양이다. 리프가 늘면 아래 루프가 자연스럽게 검사 건수를 늘린다.
  leaves.forEach(({ path, value }) => {
    it(`"${path}" 값이 콜론으로 끝나지 않는다`, () => {
      expect(value.trimEnd()).not.toMatch(/:$/)
    })
  })

  // 함수가 만드는 문자열은 리프 수집기가 못 본다 — 대표 입력으로 직접 검사한다.
  it('함수가 만드는 문자열도 콜론으로 끝나지 않는다', () => {
    expect(cardLabels.estimateAriaLabel('2h 30m').trimEnd()).not.toMatch(/:$/)
    expect(cardLabels.moreLabelsAriaLabel(['a', 'b']).trimEnd()).not.toMatch(/:$/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// estimateAriaLabel
// ─────────────────────────────────────────────────────────────────────────────

describe('cardLabels.estimateAriaLabel', () => {
  it('estimateAriaLabel("2h 30m") → "추정 2h 30m"', () => {
    expect(cardLabels.estimateAriaLabel('2h 30m')).toBe('추정 2h 30m')
  })

  it('estimateAriaLabel 결과는 콜론으로 끝나지 않는다', () => {
    expect(cardLabels.estimateAriaLabel('45m').trimEnd()).not.toMatch(/:$/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// moreLabelsAriaLabel
// ─────────────────────────────────────────────────────────────────────────────

describe('cardLabels.moreLabelsAriaLabel', () => {
  it('moreLabelsAriaLabel(["d", "e"]) → "라벨 2개 더 — d, e"', () => {
    expect(cardLabels.moreLabelsAriaLabel(['d', 'e'])).toBe('라벨 2개 더 — d, e')
  })

  it('숨은 라벨이 1개면 "라벨 1개 더 — a"', () => {
    expect(cardLabels.moreLabelsAriaLabel(['a'])).toBe('라벨 1개 더 — a')
  })

  it('moreLabelsAriaLabel 결과는 콜론으로 끝나지 않는다', () => {
    expect(cardLabels.moreLabelsAriaLabel(['a', 'b']).trimEnd()).not.toMatch(/:$/)
  })
})
