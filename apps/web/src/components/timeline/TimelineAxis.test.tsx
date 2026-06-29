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
})
