// 길이 카운터 판별식 — 임계 표시 · 초과 경고 (Jira 패리티 J4·J5)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TextLengthCounter } from '../TextLengthCounter'
import { editorLabels } from '@/i18n/editor-labels'
import {
  DESCRIPTION_MAX_LENGTH,
  LENGTH_COUNTER_VISIBLE_RATIO,
  shouldShowLengthCounter,
} from '@/lib/issue-text-constraints'

const MAX = 1000
const THRESHOLD = MAX * LENGTH_COUNTER_VISIBLE_RATIO

describe('shouldShowLengthCounter — 표시 임계', () => {
  it('임계 아래에서는 숨긴다 — 32,767 상한에 늘 숫자를 붙이면 신호가 소음이 된다', () => {
    expect(shouldShowLengthCounter(THRESHOLD - 1, MAX)).toBe(false)
  })

  it('임계에 닿으면 보인다 (경계 포함)', () => {
    expect(shouldShowLengthCounter(THRESHOLD, MAX)).toBe(true)
  })

  it('상한을 넘으면 당연히 보인다', () => {
    expect(shouldShowLengthCounter(MAX + 1, MAX)).toBe(true)
  })
})

describe('TextLengthCounter', () => {
  it('임계 아래면 아무것도 그리지 않는다', () => {
    render(<TextLengthCounter length={0} max={MAX} testId="counter" />)
    expect(screen.queryByTestId('counter')).not.toBeInTheDocument()
  })

  it('임계에 닿으면 현재/상한을 role=status 로 알린다', () => {
    render(<TextLengthCounter length={THRESHOLD} max={MAX} testId="counter" />)

    const counter = screen.getByTestId('counter')
    expect(counter).toHaveAttribute('role', 'status')
    expect(counter).toHaveTextContent(editorLabels.lengthCounter(THRESHOLD, MAX))
  })

  it('상한 이하에서는 초과 안내를 띄우지 않는다', () => {
    render(<TextLengthCounter length={MAX} max={MAX} testId="counter" />)
    expect(screen.queryByText(editorLabels.lengthOverLimit(MAX))).not.toBeInTheDocument()
  })

  it('상한을 넘으면 저장이 막힌 이유를 문장으로 함께 말한다 — 색에만 기대지 않는다', () => {
    render(<TextLengthCounter length={MAX + 1} max={MAX} testId="counter" />)

    expect(screen.getByTestId('counter')).toHaveTextContent(editorLabels.lengthOverLimit(MAX))
  })

  it('상한을 넘으면 destructive 색으로 바꾼다', () => {
    render(<TextLengthCounter length={MAX + 1} max={MAX} testId="counter" />)
    expect(screen.getByTestId('counter').className).toContain('text-destructive')
  })

  it('상한 이하에서는 destructive 가 아니다 — 위 단언의 비-공허 짝', () => {
    render(<TextLengthCounter length={MAX} max={MAX} testId="counter" />)
    expect(screen.getByTestId('counter').className).not.toContain('text-destructive')
  })

  it('숫자를 천 단위로 끊어 읽기 쉽게 쓴다 — 32,767 은 눈으로 세기 어렵다', () => {
    render(
      <TextLengthCounter
        length={DESCRIPTION_MAX_LENGTH}
        max={DESCRIPTION_MAX_LENGTH}
        testId="counter"
      />,
    )
    expect(screen.getByTestId('counter')).toHaveTextContent('32,767 / 32,767자')
  })
})
