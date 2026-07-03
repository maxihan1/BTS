// Cycle Time / Lead Time 분포 리포트 페이지 i18n 라벨 단위 테스트 — 키 존재 + 콜론 종결 검증 (FR-RP-04 D6)

import { describe, it, expect } from 'vitest'
import { cycleTimeLabels } from './cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 그룹별 키 존재 검증 — page / metric / stats / status / metricEmpty / chart / window
// ─────────────────────────────────────────────────────────────────────────────

describe('cycleTimeLabels — page 그룹', () => {
  it('page.title 키가 존재한다', () => {
    expect(cycleTimeLabels.page.title).toBeTruthy()
  })

  it('page.description 키가 존재한다', () => {
    expect(cycleTimeLabels.page.description).toBeTruthy()
  })
})

describe('cycleTimeLabels — metric 그룹', () => {
  it('metric.cycleTitle 키가 존재한다', () => {
    expect(cycleTimeLabels.metric.cycleTitle).toBeTruthy()
  })

  it('metric.leadTitle 키가 존재한다', () => {
    expect(cycleTimeLabels.metric.leadTitle).toBeTruthy()
  })

  it('metric.cycleDesc 키가 존재한다', () => {
    expect(cycleTimeLabels.metric.cycleDesc).toBeTruthy()
  })

  it('metric.leadDesc 키가 존재한다', () => {
    expect(cycleTimeLabels.metric.leadDesc).toBeTruthy()
  })
})

describe('cycleTimeLabels — stats 그룹', () => {
  it('count/min/max/avg/p25/p50/p75/p90 키가 모두 존재한다', () => {
    expect(cycleTimeLabels.stats.count).toBeTruthy()
    expect(cycleTimeLabels.stats.min).toBeTruthy()
    expect(cycleTimeLabels.stats.max).toBeTruthy()
    expect(cycleTimeLabels.stats.avg).toBeTruthy()
    expect(cycleTimeLabels.stats.p25).toBeTruthy()
    expect(cycleTimeLabels.stats.p50).toBeTruthy()
    expect(cycleTimeLabels.stats.p75).toBeTruthy()
    expect(cycleTimeLabels.stats.p90).toBeTruthy()
  })

  it('stats.p50은 "중앙값"이다', () => {
    expect(cycleTimeLabels.stats.p50).toBe('중앙값')
  })
})

describe('cycleTimeLabels — status 그룹', () => {
  it('status.loading 키가 존재한다', () => {
    expect(cycleTimeLabels.status.loading).toBeTruthy()
  })

  it('status.forbidden은 "접근 권한이 없습니다"를 포함한다', () => {
    expect(cycleTimeLabels.status.forbidden).toContain('접근 권한이 없습니다')
  })

  it('status.empty 키가 존재하며 맥락(완료된 이슈 없음)을 담는다', () => {
    expect(cycleTimeLabels.status.empty).toBeTruthy()
    expect(cycleTimeLabels.status.empty).toContain('완료')
  })

  it('status.loadFailed 키가 존재한다', () => {
    expect(cycleTimeLabels.status.loadFailed).toBeTruthy()
  })
})

describe('cycleTimeLabels — metricEmpty 그룹', () => {
  it('metricEmpty.cycle 키가 존재하며 맥락(진행 중 미경유)을 담는다', () => {
    expect(cycleTimeLabels.metricEmpty.cycle).toBeTruthy()
    expect(cycleTimeLabels.metricEmpty.cycle).toContain('진행 중')
  })
})

describe('cycleTimeLabels — chart 그룹', () => {
  it('chart.histogramAriaLabel 키가 존재한다', () => {
    expect(cycleTimeLabels.chart.histogramAriaLabel).toBeTruthy()
  })

  it('chart.boxPlotAriaLabel 키가 존재한다', () => {
    expect(cycleTimeLabels.chart.boxPlotAriaLabel).toBeTruthy()
  })

  it('chart.xAxisTitle은 "소요 시간"이다', () => {
    expect(cycleTimeLabels.chart.xAxisTitle).toBe('소요 시간')
  })

  it('chart.yAxisTitle은 "이슈 수"이다', () => {
    expect(cycleTimeLabels.chart.yAxisTitle).toBe('이슈 수')
  })
})

describe('cycleTimeLabels — window 그룹', () => {
  it('window.prefix 키가 존재한다', () => {
    expect(cycleTimeLabels.window.prefix).toBeTruthy()
  })
})

// ★ 콜론 종결 금지 검증 (글로벌 CLAUDE.md §5 — 한국어 문장은 콜론으로 끝내지 않음)
describe('cycleTimeLabels — 콜론 종결 금지 검증 (글로벌 §5)', () => {
  /** 중첩 객체를 재귀적으로 순회해 모든 string 값을 추출한다. */
  function collectStrings(obj: Record<string, unknown>): string[] {
    return Object.values(obj).flatMap((v) => {
      if (typeof v === 'string') return [v]
      if (typeof v === 'object' && v !== null) return collectStrings(v as Record<string, unknown>)
      return []
    })
  }

  it('모든 라벨 문자열은 콜론으로 끝나지 않는다', () => {
    const allStrings = collectStrings(cycleTimeLabels as unknown as Record<string, unknown>)
    for (const value of allStrings) {
      expect(value, `"${value}" 는 콜론으로 끝나면 안 됩니다`).not.toMatch(/:$/)
    }
  })
})
