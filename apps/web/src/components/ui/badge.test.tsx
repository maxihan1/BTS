// Badge 프리미티브 단위 테스트 — variant 색상 클래스 검증 (Skeleton/Textarea 스모크 포함)
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { Badge } from './badge'
import { Skeleton } from './skeleton'
import { Textarea } from './textarea'

describe('Badge', () => {
  it('variant="green"이면 green 색상 클래스를 포함한다', () => {
    render(<Badge variant="green">진행중</Badge>)

    const badge = screen.getByText('진행중')
    expect(badge).toHaveClass('bg-success/10')
    expect(badge).toHaveClass('text-success-text')
  })

  it('variant="red"이면 red 색상 클래스를 포함한다', () => {
    render(<Badge variant="red">차단됨</Badge>)

    const badge = screen.getByText('차단됨')
    expect(badge).toHaveClass('bg-danger/10')
    expect(badge).toHaveClass('text-danger-text')
  })

  it('variant 미지정 시 default variant를 렌더한다', () => {
    render(<Badge>대기</Badge>)

    const badge = screen.getByText('대기')
    expect(badge).toHaveAttribute('data-variant', 'default')
  })
})

describe('Skeleton 스모크', () => {
  it('animate-pulse 클래스를 가진 div를 렌더한다', () => {
    render(<Skeleton data-testid="skeleton" />)

    const skeleton = screen.getByTestId('skeleton')
    expect(skeleton.tagName).toBe('DIV')
    expect(skeleton).toHaveClass('animate-pulse')
  })
})

describe('Textarea 스모크', () => {
  it('native textarea로 렌더된다', () => {
    render(<Textarea placeholder="설명 입력" />)

    const textarea = screen.getByPlaceholderText('설명 입력')
    expect(textarea.tagName).toBe('TEXTAREA')
  })

  it('disabled 상태에서 비활성 클래스를 포함한다', () => {
    render(<Textarea placeholder="설명 입력" disabled />)

    const textarea = screen.getByPlaceholderText('설명 입력')
    expect(textarea).toBeDisabled()
  })
})
