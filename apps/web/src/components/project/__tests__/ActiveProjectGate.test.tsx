// ActiveProjectGate 컴포넌트 테스트 — 미해소 상태 3종(loading/error/empty) 렌더 검증 (FR-UX-07 CR-A)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ActiveProjectGate } from '../ActiveProjectGate'

describe('ActiveProjectGate — error', () => {
  it('error 상태에서 "다시 시도" 버튼이 렌더되고 클릭 시 retry가 호출된다', async () => {
    const retry = vi.fn()
    const user = userEvent.setup()
    render(<ActiveProjectGate state={{ status: 'error', retry }} />)

    const retryButton = screen.getByRole('button', { name: '다시 시도' })
    await user.click(retryButton)

    expect(retry).toHaveBeenCalledTimes(1)
  })
})

describe('ActiveProjectGate — loading', () => {
  it('loading 상태에는 "다시 시도" 버튼이 없다', () => {
    render(<ActiveProjectGate state={{ status: 'loading' }} />)

    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })
})

describe('ActiveProjectGate — empty', () => {
  it('empty 상태에는 버튼 없이 /projects로 가는 링크만 렌더된다', () => {
    render(<ActiveProjectGate state={{ status: 'empty' }} />)

    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', '/projects')
  })
})
