// 누적 흐름도(CFD) 차트 페이지 i18n 라벨 단위 테스트 — 키 존재 + 콜론 종결 검증 (FR-RP-03 D6)

import { describe, it, expect } from 'vitest'
import { cfdLabels } from './cfd-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 그룹별 키 존재 검증 — page / series / status / chart
// ─────────────────────────────────────────────────────────────────────────────

describe('cfdLabels — page 그룹', () => {
  it('page.title 키가 존재한다', () => {
    expect(cfdLabels.page.title).toBeTruthy()
  })

  it('page.description 키가 존재한다', () => {
    expect(cfdLabels.page.description).toBeTruthy()
  })
})

describe('cfdLabels — series 그룹', () => {
  it('series.todo 키가 존재한다', () => {
    expect(cfdLabels.series.todo).toBeTruthy()
  })

  it('series.inProgress 키가 존재한다', () => {
    expect(cfdLabels.series.inProgress).toBeTruthy()
  })

  it('series.done 키가 존재한다', () => {
    expect(cfdLabels.series.done).toBeTruthy()
  })
})

describe('cfdLabels — status 그룹', () => {
  it('status.loading 키가 존재한다', () => {
    expect(cfdLabels.status.loading).toBeTruthy()
  })

  it('status.forbidden 키가 존재한다', () => {
    expect(cfdLabels.status.forbidden).toBeTruthy()
  })

  it('status.forbidden은 "접근 권한이 없습니다"를 포함한다', () => {
    expect(cfdLabels.status.forbidden).toContain('접근 권한이 없습니다')
  })

  it('status.empty 키가 존재한다', () => {
    expect(cfdLabels.status.empty).toBeTruthy()
  })

  it('status.loadFailed 키가 존재한다', () => {
    expect(cfdLabels.status.loadFailed).toBeTruthy()
  })
})

describe('cfdLabels — chart 그룹', () => {
  it('chart.ariaLabel 키가 존재한다', () => {
    expect(cfdLabels.chart.ariaLabel).toBeTruthy()
  })

  it('chart.yAxisTitle 키가 존재한다', () => {
    expect(cfdLabels.chart.yAxisTitle).toBeTruthy()
  })
})

// ★ 콜론 종결 금지 검증 (글로벌 CLAUDE.md §5 — 한국어 문장은 콜론으로 끝내지 않음)
describe('cfdLabels — 콜론 종결 금지 검증 (글로벌 §5)', () => {
  /** 중첩 객체를 재귀적으로 순회해 모든 string 값을 추출한다. */
  function collectStrings(obj: Record<string, unknown>): string[] {
    return Object.values(obj).flatMap((v) => {
      if (typeof v === 'string') return [v]
      if (typeof v === 'object' && v !== null) return collectStrings(v as Record<string, unknown>)
      return []
    })
  }

  it('모든 라벨 문자열은 콜론으로 끝나지 않는다', () => {
    const allStrings = collectStrings(cfdLabels as unknown as Record<string, unknown>)
    for (const value of allStrings) {
      expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})
