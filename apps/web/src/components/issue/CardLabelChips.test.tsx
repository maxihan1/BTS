// CardLabelChips 컴포넌트 단위 테스트 — FR-UX-14 F14 Task 2
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CardLabelChips } from './CardLabelChips'

describe('CardLabelChips — 빈 라벨', () => {
  it('라벨 0개면 아무것도 렌더하지 않는다', () => {
    const { container } = render(<CardLabelChips labels={[]} />)
    expect(container).toBeEmptyDOMElement() // FR9 · E1 · NFR3
  })
})

describe('CardLabelChips — 3개 이하는 그대로 노출', () => {
  it('라벨 3개까지는 그대로 보인다', () => {
    render(<CardLabelChips labels={['frontend', 'urgent', 'bug']} />)

    expect(screen.getByText('frontend')).toBeInTheDocument()
    expect(screen.getByText('urgent')).toBeInTheDocument()
    expect(screen.getByText('bug')).toBeInTheDocument()
    expect(screen.queryByText(/^\+/)).not.toBeInTheDocument()
  })
})

describe('CardLabelChips — 4개 이상은 오버플로 칩', () => {
  it('라벨 4개 이상이면 앞 3개 + "+N" 오버플로 칩', () => {
    render(<CardLabelChips labels={['a', 'b', 'c', 'd', 'e']} />)

    expect(screen.getByText('a')).toBeInTheDocument()
    expect(screen.getByText('b')).toBeInTheDocument()
    expect(screen.getByText('c')).toBeInTheDocument()
    expect(screen.queryByText('d')).not.toBeInTheDocument()
    expect(screen.queryByText('e')).not.toBeInTheDocument()
    expect(screen.getByText('+2')).toBeInTheDocument() // FR8 · E4
  })

  it('오버플로 칩의 접근성 이름에 숨은 라벨이 전부 담긴다', () => {
    render(<CardLabelChips labels={['a', 'b', 'c', 'd', 'e']} />)
    expect(screen.getByLabelText('라벨 2개 더 — d, e')).toBeInTheDocument() // FR15 · D6
  })
})
