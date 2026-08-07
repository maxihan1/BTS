// CardEstimateBadge 컴포넌트 단위 테스트 — FR-UX-14 F14 Task 2
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CardEstimateBadge } from './CardEstimateBadge'

describe('CardEstimateBadge — null은 미추정', () => {
  it('null이면 아무것도 렌더하지 않는다', () => {
    const { container } = render(<CardEstimateBadge seconds={null} />)
    expect(container).toBeEmptyDOMElement() // FR10 · E2
  })
})

describe('CardEstimateBadge — 0은 미추정과 다르다', () => {
  it('0이면 "0m"을 표시한다 (추정함·값이 0은 미추정과 다르다)', () => {
    render(<CardEstimateBadge seconds={0} />)
    expect(screen.getByText('0m')).toBeInTheDocument() // E3
  })
})

describe('CardEstimateBadge — formatSeconds 위임', () => {
  it('9000이면 "2h 30m"', () => {
    render(<CardEstimateBadge seconds={9000} />)
    expect(screen.getByText('2h 30m')).toBeInTheDocument()
  })

  it('aria-label에 "추정" 접두사가 붙는다', () => {
    render(<CardEstimateBadge seconds={9000} />)
    expect(screen.getByLabelText('추정 2h 30m')).toBeInTheDocument()
  })
})
