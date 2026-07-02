// 번다운/번업 차트 페이지 i18n 라벨 단위 테스트 — 키 존재 + 콜론 종결 검증 (FR-RP-01 D6)

import { describe, it, expect } from 'vitest'
import { burndownLabels } from './burndown-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 그룹별 키 존재 검증 — page / toggle / series / status / chart
// ─────────────────────────────────────────────────────────────────────────────

describe('burndownLabels — page 그룹', () => {
  it('page.title 키가 존재한다', () => {
    expect(burndownLabels.page.title).toBeTruthy()
  })
})

describe('burndownLabels — toggle 그룹', () => {
  it('toggle.burndown 키가 존재한다', () => {
    expect(burndownLabels.toggle.burndown).toBeTruthy()
  })

  it('toggle.burnup 키가 존재한다', () => {
    expect(burndownLabels.toggle.burnup).toBeTruthy()
  })
})

describe('burndownLabels — series 그룹', () => {
  it('series.remaining 키가 존재한다', () => {
    expect(burndownLabels.series.remaining).toBeTruthy()
  })

  it('series.ideal 키가 존재한다', () => {
    expect(burndownLabels.series.ideal).toBeTruthy()
  })

  it('series.scope 키가 존재한다', () => {
    expect(burndownLabels.series.scope).toBeTruthy()
  })

  it('series.completed 키가 존재한다', () => {
    expect(burndownLabels.series.completed).toBeTruthy()
  })
})

describe('burndownLabels — status 그룹', () => {
  it('status.loading 키가 존재한다', () => {
    expect(burndownLabels.status.loading).toBeTruthy()
  })

  it('status.forbidden 키가 존재한다', () => {
    expect(burndownLabels.status.forbidden).toBeTruthy()
  })

  it('status.forbidden은 "접근 권한이 없습니다"를 포함한다', () => {
    expect(burndownLabels.status.forbidden).toContain('접근 권한이 없습니다')
  })

  it('status.sprintNotFound 키가 존재한다', () => {
    expect(burndownLabels.status.sprintNotFound).toBeTruthy()
  })

  it('status.datesRequired 키가 존재한다', () => {
    expect(burndownLabels.status.datesRequired).toBeTruthy()
  })

  it('status.datesRequired는 "스프린트 시작일과 종료일을 설정해야 번다운을 볼 수 있습니다"를 포함한다', () => {
    expect(burndownLabels.status.datesRequired).toContain(
      '스프린트 시작일과 종료일을 설정해야 번다운을 볼 수 있습니다',
    )
  })

  it('status.empty 키가 존재한다', () => {
    expect(burndownLabels.status.empty).toBeTruthy()
  })
})

describe('burndownLabels — chart 그룹', () => {
  it('chart.ariaLabel 키가 존재한다', () => {
    expect(burndownLabels.chart.ariaLabel).toBeTruthy()
  })
})

// ★ 콜론 종결 금지 검증 (글로벌 CLAUDE.md §5 — 한국어 문장은 콜론으로 끝내지 않음)
describe('burndownLabels — 콜론 종결 금지 검증 (글로벌 §5)', () => {
  /** 중첩 객체를 재귀적으로 순회해 모든 string 값을 추출한다. */
  function collectStrings(obj: Record<string, unknown>): string[] {
    return Object.values(obj).flatMap((v) => {
      if (typeof v === 'string') return [v]
      if (typeof v === 'object' && v !== null) return collectStrings(v as Record<string, unknown>)
      return []
    })
  }

  it('모든 라벨 문자열은 콜론으로 끝나지 않는다', () => {
    const allStrings = collectStrings(burndownLabels as unknown as Record<string, unknown>)
    for (const value of allStrings) {
      expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})
