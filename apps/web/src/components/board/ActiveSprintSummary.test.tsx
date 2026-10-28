// 활성 스프린트 표기 테스트 — 남은 일수 계산(로컬 날짜) · 표기 배지 (JB-B-3)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ActiveSprintSummary, formatSprintRemaining } from './ActiveSprintSummary'

/** 한국 시각 오후 11시 — `new Date('YYYY-MM-DD')`(UTC 자정)로 셈하면 하루가 어긋나는 시각 */
const LATE_EVENING = new Date(2026, 9, 28, 23, 0)

describe('formatSprintRemaining', () => {
  it('종료일이 미래면 「N일 남음」', () => {
    expect(formatSprintRemaining('2026-11-06', LATE_EVENING)).toBe('9일 남음')
  })

  it('종료일이 오늘이면 「오늘 종료」', () => {
    expect(formatSprintRemaining('2026-10-28', LATE_EVENING)).toBe('오늘 종료')
  })

  it('종료일이 지났으면 「N일 지남」', () => {
    expect(formatSprintRemaining('2026-10-25', LATE_EVENING)).toBe('3일 지남')
  })

  it('월을 넘겨도 맞다', () => {
    expect(formatSprintRemaining('2026-11-01', new Date(2026, 9, 31, 9))).toBe('1일 남음')
  })

  it('종료일이 없거나 날짜가 아니면 null', () => {
    expect(formatSprintRemaining(null, LATE_EVENING)).toBeNull()
    expect(formatSprintRemaining('미정', LATE_EVENING)).toBeNull()
  })
})

describe('ActiveSprintSummary', () => {
  it('종료일이 없으면 남은 일수 배지를 그리지 않는다', () => {
    render(
      <ActiveSprintSummary
        activeSprint={{ sprintId: '00000000-0000-4000-8000-0000000000a1', name: '스프린트 1', startDate: '2026-10-20', endDate: null }}
      />,
    )
    expect(screen.getByTestId('active-sprint-summary')).toHaveTextContent('스프린트 12026-10-20 ~')
    expect(screen.queryByText(/남음|지남|오늘 종료/)).toBeNull()
  })

  it('종료일이 있으면 기간 옆에 남은 일수를 보인다', () => {
    render(
      <ActiveSprintSummary
        activeSprint={{ sprintId: '00000000-0000-4000-8000-0000000000a1', name: '스프린트 1', startDate: null, endDate: '2099-01-01' }}
      />,
    )
    expect(screen.getByText(/일 남음$/)).toBeInTheDocument()
  })
})
