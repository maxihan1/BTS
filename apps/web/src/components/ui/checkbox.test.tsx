// Checkbox 컴포넌트 단위 테스트 — role="checkbox" 노출 계약 + 클릭 시 aria-checked 토글
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect } from 'vitest'
import { Checkbox } from './checkbox'

// ─────────────────────────────────────────────────────────────────────────────
// TC-1: role="checkbox" 노출 (핵심 계약)
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-1: role="checkbox" 노출', () => {
  it('렌더 시 role="checkbox"가 노출된다', () => {
    render(<Checkbox aria-label="동의" />)

    expect(screen.getByRole('checkbox', { name: '동의' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TC-2: 클릭 시 aria-checked false → true 토글
// ─────────────────────────────────────────────────────────────────────────────

describe('TC-2: 클릭 시 aria-checked 토글', () => {
  it('초기 상태는 aria-checked="false"이고 클릭하면 "true"로 바뀐다', async () => {
    const user = userEvent.setup()
    render(<Checkbox aria-label="동의" />)

    const checkbox = screen.getByRole('checkbox', { name: '동의' })
    expect(checkbox).toHaveAttribute('aria-checked', 'false')

    await user.click(checkbox)

    expect(checkbox).toHaveAttribute('aria-checked', 'true')
  })
})
