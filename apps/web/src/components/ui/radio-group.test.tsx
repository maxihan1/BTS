// RadioGroup 컴포넌트 단위 테스트 — role="radiogroup"/"radio" 노출 계약 + 단일 선택 동작
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { RadioGroup, RadioGroupItem } from './radio-group'

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: role="radiogroup" + 항목 role="radio" 2개 노출 (핵심 계약)
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: role="radiogroup" + role="radio" 노출', () => {
  it('그룹과 항목 2개가 각각 role로 노출된다', () => {
    render(
      <RadioGroup aria-label="옵션 선택">
        <RadioGroupItem value="a" aria-label="옵션 A" />
        <RadioGroupItem value="b" aria-label="옵션 B" />
      </RadioGroup>,
    )

    expect(screen.getByRole('radiogroup', { name: '옵션 선택' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: 하나 클릭 시 그것만 checked (단일 선택)
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: 단일 선택', () => {
  it('항목 하나를 클릭하면 그것만 checked이고 나머지는 unchecked이다', async () => {
    const user = userEvent.setup()
    render(
      <RadioGroup aria-label="옵션 선택">
        <RadioGroupItem value="a" aria-label="옵션 A" />
        <RadioGroupItem value="b" aria-label="옵션 B" />
      </RadioGroup>,
    )

    const optionA = screen.getByRole('radio', { name: '옵션 A' })
    const optionB = screen.getByRole('radio', { name: '옵션 B' })

    await user.click(optionA)

    expect(optionA).toHaveAttribute('aria-checked', 'true')
    expect(optionB).toHaveAttribute('aria-checked', 'false')

    await user.click(optionB)

    expect(optionA).toHaveAttribute('aria-checked', 'false')
    expect(optionB).toHaveAttribute('aria-checked', 'true')
  })
})
