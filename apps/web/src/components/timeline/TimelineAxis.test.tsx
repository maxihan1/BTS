// 타임라인 축 줌 레벨별 눈금 렌더 검증 테스트 (FR-TL-03 Task 3)
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { TimelineAxis } from './TimelineAxis'
import type { DateRange } from '@/lib/timeline-layout'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — UTC 타임스탬프
// ─────────────────────────────────────────────────────────────────────────────

/** UTC 기준 자정 타임스탬프를 반환한다. month는 1-indexed. */
const utc = (year: number, month: number, day: number): number =>
  Date.UTC(year, month - 1, day)

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 날짜 범위
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 31일 범위: 2024-01-01 ~ 2024-02-01.
 * 2024-01-01은 월요일(UTC)이므로 주 눈금 첫 tick이 당일에 찍힌다.
 */
const RANGE_JAN_2024: DateRange = {
  startMs: utc(2024, 1, 1),
  endMs: utc(2024, 2, 1),
}

/**
 * 7일 범위: 2024-01-01 ~ 2024-01-08.
 * week 줌에서 일 눈금 개수(7개)를 단순하게 검증하기 위한 범위.
 */
const RANGE_JAN_WEEK: DateRange = {
  startMs: utc(2024, 1, 1),
  endMs: utc(2024, 1, 8),
}

/**
 * 182일 범위: 2024-01-01 ~ 2024-07-01 (윤년).
 * quarter 줌에서 Q1·Q2 눈금과 6개 월 눈금을 검증하기 위한 범위.
 */
const RANGE_H1_2024: DateRange = {
  startMs: utc(2024, 1, 1),
  endMs: utc(2024, 7, 1),
}

const DAY_WIDTH = 20

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineAxis', () => {
  describe('month 줌 (기본값)', () => {
    it('zoomLevel 미지정 시 상단 월(YYYY.MM)·하단 주(MM/DD) 눈금을 렌더한다', () => {
      render(<TimelineAxis range={RANGE_JAN_2024} dayWidth={DAY_WIDTH} />)

      // 상단: 월 레이블 (Jan 1 = 범위 시작 = day 0)
      expect(screen.getByText('2024.01')).toBeInTheDocument()

      // 하단: 주 레이블 — 2024-01-01은 월요일이므로 01/01, 01/08, 01/15, 01/22, 01/29
      expect(screen.getByText('01/01')).toBeInTheDocument()
      expect(screen.getByText('01/08')).toBeInTheDocument()
      expect(screen.getByText('01/15')).toBeInTheDocument()
      expect(screen.getByText('01/22')).toBeInTheDocument()
      expect(screen.getByText('01/29')).toBeInTheDocument()
    })

    it("zoomLevel='month' 명시 시 주 눈금이 정확히 5개 렌더된다", () => {
      render(
        <TimelineAxis range={RANGE_JAN_2024} dayWidth={DAY_WIDTH} zoomLevel="month" />,
      )
      // MM/DD 형식 패턴: 5개 (01/01, 01/08, 01/15, 01/22, 01/29)
      expect(screen.getAllByText(/^\d{2}\/\d{2}$/).length).toBe(5)
    })

    it('분기(Q) 눈금을 렌더하지 않는다', () => {
      render(
        <TimelineAxis range={RANGE_JAN_2024} dayWidth={DAY_WIDTH} zoomLevel="month" />,
      )
      expect(screen.queryByText(/Q\d/)).toBeNull()
    })
  })

  describe('week 줌', () => {
    it('상단에 월 눈금, 하단에 일(DD) 눈금을 렌더한다', () => {
      render(
        <TimelineAxis range={RANGE_JAN_WEEK} dayWidth={DAY_WIDTH} zoomLevel="week" />,
      )

      // 상단: 월 레이블
      expect(screen.getByText('2024.01')).toBeInTheDocument()

      // 하단: 일 레이블 (DD 형식)
      expect(screen.getByText('01')).toBeInTheDocument()
      expect(screen.getByText('02')).toBeInTheDocument()
      expect(screen.getByText('07')).toBeInTheDocument()
    })

    it('일 눈금 개수가 기간 일수(7)와 같다', () => {
      render(
        <TimelineAxis range={RANGE_JAN_WEEK} dayWidth={DAY_WIDTH} zoomLevel="week" />,
      )
      // DD 형식(정확히 2자리 숫자)인 요소: 01~07 = 7개
      expect(screen.getAllByText(/^\d{2}$/).length).toBe(7)
    })

    it('주(MM/DD) 눈금을 렌더하지 않는다', () => {
      render(
        <TimelineAxis range={RANGE_JAN_WEEK} dayWidth={DAY_WIDTH} zoomLevel="week" />,
      )
      expect(screen.queryByText(/^\d{2}\/\d{2}$/)).toBeNull()
    })
  })

  describe('quarter 줌', () => {
    it('상단에 분기(YYYY Q{n}) 눈금, 하단에 월(YYYY.MM) 눈금을 렌더한다', () => {
      render(
        <TimelineAxis range={RANGE_H1_2024} dayWidth={DAY_WIDTH} zoomLevel="quarter" />,
      )

      // 상단: 분기 레이블 — Q1(Jan 1), Q2(Apr 1)
      expect(screen.getByText('2024 Q1')).toBeInTheDocument()
      expect(screen.getByText('2024 Q2')).toBeInTheDocument()

      // 하단: 월 레이블
      expect(screen.getByText('2024.01')).toBeInTheDocument()
      expect(screen.getByText('2024.04')).toBeInTheDocument()
    })

    it('하단 월 눈금이 6개다 (2024-01 ~ 2024-06)', () => {
      render(
        <TimelineAxis range={RANGE_H1_2024} dayWidth={DAY_WIDTH} zoomLevel="quarter" />,
      )
      // YYYY.MM 형식: Jan~Jun = 6개 (Jul 1은 endMs로 범위 밖)
      expect(screen.getAllByText(/^\d{4}\.\d{2}$/).length).toBe(6)
    })

    it('주(MM/DD)·일(DD) 눈금을 렌더하지 않는다', () => {
      render(
        <TimelineAxis range={RANGE_H1_2024} dayWidth={DAY_WIDTH} zoomLevel="quarter" />,
      )
      expect(screen.queryByText(/^\d{2}\/\d{2}$/)).toBeNull()
      // DD 단독 숫자 없음 — YYYY.MM은 점이 있으므로 이 패턴에 매치되지 않는다
      expect(screen.queryByText(/^\d{2}$/)).toBeNull()
    })
  })

  describe('quarter 줌 — partial 시작 레이블 (C2)', () => {
    /**
     * partial 필요 케이스: 범위가 분기 경계를 포함하지 않을 때.
     *
     * 범위 2026-08-15 ~ 2026-09-30 (UTC).
     * Q3(7/1)·Q4(10/1) 모두 범위 밖 → computeQuarterTicks 정규 눈금 0개.
     * 시작일 2026-08-15 (UTC month=7) → quarter = floor(7/3)+1 = 3.
     * 기대: offsetDay 0 위치에 '2026 Q3' 레이블 1개.
     *
     * RED 실패 예상: 미구현 시 분기 레이블 0개 → getByText 실패.
     */
    it('범위가 분기 경계 미포함일 때 offsetDay 0에 시작 분기 레이블이 렌더된다', () => {
      const range: DateRange = {
        startMs: utc(2026, 8, 15),
        endMs: utc(2026, 9, 30),
      }
      render(<TimelineAxis range={range} dayWidth={DAY_WIDTH} zoomLevel="quarter" />)

      // 분기 레이블 '2026 Q3' 이 최소 1개 렌더되어야 한다
      expect(screen.getByText('2026 Q3')).toBeInTheDocument()

      // offsetDay 0 위치(left: 0px)에 렌더되어야 한다
      const label = screen.getByText('2026 Q3')
      expect(parseFloat(label.style.left)).toBe(0)
    })

    /**
     * 중복 금지 케이스: 범위 시작이 정확히 분기 경계(7/1)일 때.
     *
     * 범위 2026-07-01 ~ 2026-09-30 (UTC).
     * day 0 = 2026-07-01 (UTC month=6, 6%3=0) → 정규 눈금 {offsetDay:0, '2026 Q3'}.
     * 정규 눈금 offsetDay === 0 → partial 삽입 금지.
     * 기대: '2026 Q3' 레이블이 정확히 1개 (2개 아님).
     *
     * GREEN 전/후 모두 통과해야 하는 무회귀 가드.
     */
    it('범위 시작이 분기 경계와 일치하면 분기 레이블이 중복 삽입되지 않는다', () => {
      const range: DateRange = {
        startMs: utc(2026, 7, 1),
        endMs: utc(2026, 9, 30),
      }
      render(<TimelineAxis range={range} dayWidth={DAY_WIDTH} zoomLevel="quarter" />)

      // '2026 Q3' 이 정확히 1개만 렌더되어야 한다
      expect(screen.getAllByText('2026 Q3')).toHaveLength(1)
    })
  })
})
