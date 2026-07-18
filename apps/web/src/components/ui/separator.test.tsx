// Separator 컴포넌트 단위 테스트 — 비장식 separator의 role/aria-orientation 검증
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { Separator } from './separator'

describe('TC-1: 비장식(non-decorative) separator 역할 노출', () => {
  it('decorative=false일 때 role="separator"를 노출한다', () => {
    render(<Separator decorative={false} />)

    expect(screen.getByRole('separator')).toBeInTheDocument()
  })

  it('기본(orientation 미지정) horizontal일 때 aria-orientation을 "horizontal"로 노출하지 않는다(Radix 기본값 생략 규약 확인)', () => {
    render(<Separator decorative={false} />)

    const separator = screen.getByRole('separator')
    expect(separator).not.toHaveAttribute('aria-orientation')
  })

  it('orientation="vertical"이면 aria-orientation="vertical"을 노출한다', () => {
    render(<Separator decorative={false} orientation="vertical" />)

    const separator = screen.getByRole('separator')
    expect(separator).toHaveAttribute('aria-orientation', 'vertical')
  })
})
