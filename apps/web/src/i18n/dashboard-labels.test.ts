// dashboardLabels 단위 테스트 — i18n 라벨 값 정합성 및 콜론 종결 금지 가드
import { describe, it, expect } from 'vitest'
import { dashboardLabels } from './dashboard-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 유틸 — 재귀 string leaf 수집
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

// ─────────────────────────────────────────────────────────────────────────────
// 콜론 종결 금지 (글로벌 §5)
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels — 콜론 종결 금지 (글로벌 §5)', () => {
  const leaves = collectStringLeaves(dashboardLabels)

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
// 내부 FR 용어 노출 금지 (DESIGN.md §10)
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels — 내부 FR 용어 노출 금지', () => {
  const leaves = collectStringLeaves(dashboardLabels)
  const frPattern = /FR-[A-Z]{2}-\d+/

  leaves.forEach(({ path, value }) => {
    it(`"${path}" 값에 내부 FR 식별자(FR-DB-01 등)가 없다`, () => {
      expect(value).not.toMatch(frPattern)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// list 그룹 — 대시보드 목록 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels.list', () => {
  it('title 이 비어있지 않다', () => {
    expect(dashboardLabels.list.title.length).toBeGreaterThan(0)
  })

  it('empty.title 이 비어있지 않다', () => {
    expect(dashboardLabels.list.empty.title.length).toBeGreaterThan(0)
  })

  it('empty.description 이 비어있지 않다', () => {
    expect(dashboardLabels.list.empty.description.length).toBeGreaterThan(0)
  })

  it('empty.cta 이 비어있지 않다', () => {
    expect(dashboardLabels.list.empty.cta.length).toBeGreaterThan(0)
  })

  it('loading 이 비어있지 않다', () => {
    expect(dashboardLabels.list.loading.length).toBeGreaterThan(0)
  })

  it('error 이 비어있지 않다', () => {
    expect(dashboardLabels.list.error.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// card 그룹 — 대시보드 카드 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels.card', () => {
  it('visibility.PRIVATE 이 비어있지 않다', () => {
    expect(dashboardLabels.card.visibility.PRIVATE.length).toBeGreaterThan(0)
  })

  it('visibility.TEAM 이 비어있지 않다', () => {
    expect(dashboardLabels.card.visibility.TEAM.length).toBeGreaterThan(0)
  })

  it('visibility.ORG 이 비어있지 않다', () => {
    expect(dashboardLabels.card.visibility.ORG.length).toBeGreaterThan(0)
  })

  it('ownerBadge 이 비어있지 않다', () => {
    expect(dashboardLabels.card.ownerBadge.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// detail 그룹 — 대시보드 상세/편집 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels.detail', () => {
  it('save 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.save.length).toBeGreaterThan(0)
  })

  it('delete 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.delete.length).toBeGreaterThan(0)
  })

  it('settings 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.settings.length).toBeGreaterThan(0)
  })

  it('addWidget 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.addWidget.length).toBeGreaterThan(0)
  })

  it('emptyGrid 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.emptyGrid.length).toBeGreaterThan(0)
  })

  it('saving 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.saving.length).toBeGreaterThan(0)
  })

  it('unsavedChanges 이 비어있지 않다', () => {
    expect(dashboardLabels.detail.unsavedChanges.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// form 그룹 — 대시보드 생성/수정 폼 라벨
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels.form', () => {
  it('name 이 비어있지 않다', () => {
    expect(dashboardLabels.form.name.length).toBeGreaterThan(0)
  })

  it('description 이 비어있지 않다', () => {
    expect(dashboardLabels.form.description.length).toBeGreaterThan(0)
  })

  it('visibility 이 비어있지 않다', () => {
    expect(dashboardLabels.form.visibility.length).toBeGreaterThan(0)
  })

  it('share 이 비어있지 않다', () => {
    expect(dashboardLabels.form.share.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// placeholder 그룹 — 위젯 자리 표시 문구
// ─────────────────────────────────────────────────────────────────────────────

describe('dashboardLabels.placeholder', () => {
  it('title 이 비어있지 않다', () => {
    expect(dashboardLabels.placeholder.title.length).toBeGreaterThan(0)
  })

  it('description 이 비어있지 않다', () => {
    expect(dashboardLabels.placeholder.description.length).toBeGreaterThan(0)
  })

  it('title 이 중립 한국어로 구성된다 (내부 개발 용어 미포함)', () => {
    // "위젯", "자리" 등 일반 사용자가 이해하는 단어여야 한다
    expect(dashboardLabels.placeholder.title).toMatch(/[가-힣]/)
  })
})
