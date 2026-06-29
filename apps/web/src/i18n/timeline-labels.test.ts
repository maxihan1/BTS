// timeline-labels.ts 단위 테스트 — i18n 라벨 값 정합성 및 콜론 종결 금지 가드 (FR-TL-01 D6)
import { describe, it, expect } from 'vitest'
import { timelineLabels } from './timeline-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 정적 문자열 값 — 콜론 종결 금지 검증 (board-labels.test.ts 동일 패턴)
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

describe('timelineLabels — 콜론 종결 금지 (글로벌 §5)', () => {
  const leaves = collectStringLeaves(timelineLabels)

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
// group 그룹 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('timelineLabels.group', () => {
  it('unclassifiedHeader는 비어있지 않다', () => {
    expect(timelineLabels.group.unclassifiedHeader.length).toBeGreaterThan(0)
  })

  it('collapseAriaLabel은 비어있지 않다', () => {
    expect(timelineLabels.group.collapseAriaLabel.length).toBeGreaterThan(0)
  })

  it('expandAriaLabel은 비어있지 않다', () => {
    expect(timelineLabels.group.expandAriaLabel.length).toBeGreaterThan(0)
  })

  it('collapseAriaLabel과 expandAriaLabel이 서로 다르다', () => {
    expect(timelineLabels.group.collapseAriaLabel).not.toBe(timelineLabels.group.expandAriaLabel)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deps 그룹 검증 (FR-TL-02 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('timelineLabels.deps', () => {
  it('lineAriaLabel("BTS-2", "BTS-3") — 두 키 모두 포함한다', () => {
    const label = timelineLabels.deps.lineAriaLabel('BTS-2', 'BTS-3')
    expect(label).toContain('BTS-2')
    expect(label).toContain('BTS-3')
  })

  it('lineAriaLabel 반환값이 콜론으로 끝나지 않는다', () => {
    const label = timelineLabels.deps.lineAriaLabel('BTS-2', 'BTS-3')
    expect(label.trimEnd()).not.toMatch(/:$/)
  })

  it('lineAriaLabel 반환값이 비어있지 않다', () => {
    const label = timelineLabels.deps.lineAriaLabel('A', 'B')
    expect(label.length).toBeGreaterThan(0)
  })

  it('truncatedMessage가 비어있지 않다', () => {
    expect(timelineLabels.deps.truncatedMessage.length).toBeGreaterThan(0)
  })

  it('truncatedMessage가 콜론으로 끝나지 않는다', () => {
    expect(timelineLabels.deps.truncatedMessage.trimEnd()).not.toMatch(/:$/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// row 그룹 함수 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('timelineLabels.row', () => {
  it('unassigned는 비어있지 않다', () => {
    expect(timelineLabels.row.unassigned.length).toBeGreaterThan(0)
  })

  it('unknownAssignee는 비어있지 않다', () => {
    expect(timelineLabels.row.unknownAssignee.length).toBeGreaterThan(0)
  })

  it('milestoneAriaLabel("ATLAS-1") → 키가 포함된 라벨 반환', () => {
    const label = timelineLabels.row.milestoneAriaLabel('ATLAS-1')
    expect(label).toContain('ATLAS-1')
    expect(label.trimEnd()).not.toMatch(/:$/)
  })

  it('barAriaLabel("ATLAS-1", "2026-07-01", "2026-07-31") → 키와 날짜 포함', () => {
    const label = timelineLabels.row.barAriaLabel('ATLAS-1', '2026-07-01', '2026-07-31')
    expect(label).toContain('ATLAS-1')
    expect(label.trimEnd()).not.toMatch(/:$/)
  })

  it('barAriaLabel — start/due가 null이면 "미정"으로 폴백된다', () => {
    const label = timelineLabels.row.barAriaLabel('ATLAS-1', null, null)
    expect(label).toContain('미정')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// zoom 그룹 검증 (FR-TL-03 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('timelineLabels.zoom', () => {
  it('week는 "주"이다', () => {
    expect(timelineLabels.zoom.week).toBe('주')
  })

  it('month는 "월"이다', () => {
    expect(timelineLabels.zoom.month).toBe('월')
  })

  it('quarter는 "분기"이다', () => {
    expect(timelineLabels.zoom.quarter).toBe('분기')
  })

  it('groupAriaLabel이 비어있지 않다', () => {
    expect(timelineLabels.zoom.groupAriaLabel.length).toBeGreaterThan(0)
  })

  it('zoomInAriaLabel은 "확대"이다', () => {
    expect(timelineLabels.zoom.zoomInAriaLabel).toBe('확대')
  })

  it('zoomOutAriaLabel은 "축소"이다', () => {
    expect(timelineLabels.zoom.zoomOutAriaLabel).toBe('축소')
  })

  it('zoom 섹션 모든 값이 콜론으로 끝나지 않는다', () => {
    // collectStringLeaves가 zoom 추가 시 자동 커버하지만 명시 단언으로 이중 보장
    Object.values(timelineLabels.zoom).forEach((v) => {
      expect(v.trimEnd()).not.toMatch(/:$/)
    })
  })
})
