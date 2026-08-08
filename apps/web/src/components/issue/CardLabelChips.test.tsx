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

  /**
   * ★ 오버플로 칩은 `role="img"` 여야 접근성 이름이 실제로 노출된다.
   *
   * 맨 `<span>` 은 role=generic 이고 ARIA 에서 generic 은 **name-prohibited** 라
   * `aria-label` 이 무시된다 — 그러면 스크린리더는 "+2" 만 읽고, 「`title` 은 터치에서
   * 안 뜨니 접근성 이름으로 준다」는 D6 결정의 근거가 통째로 무너진다.
   * `getByLabelText` 는 속성만 보므로 이 결함을 못 잡는다 — **역할까지 함께 단언**한다.
   */
  it('오버플로 칩이 role="img" 라 접근성 이름이 실제로 노출된다', () => {
    render(<CardLabelChips labels={['a', 'b', 'c', 'd', 'e']} />)
    expect(screen.getByRole('img', { name: '라벨 2개 더 — d, e' })).toBeInTheDocument()
  })

  /**
   * E5 — 긴 라벨이 카드 폭을 넘지 않는다.
   *
   * `truncate` 를 Badge 에 직접 걸면 무효다. Badge 는 `inline-flex … justify-center` 이고
   * flex 아이템으로 blockify 되면 `text-overflow: ellipsis` 가 적용되지 않으며,
   * `justify-center` 탓에 글자가 좌·우로 밀려 **가운데 토막만** 남는다.
   * 그래서 안쪽 span 에 `min-w-0 truncate` 를 둔다 — 그 배선을 여기서 못박는다.
   */
  it('긴 라벨은 안쪽 span 의 truncate 로 잘린다 (Badge 직접 truncate 는 무효)', () => {
    const longLabel = '아주-긴-라벨-이름-'.repeat(6)
    render(<CardLabelChips labels={[longLabel]} />)

    const text = screen.getByText(longLabel)
    expect(text.tagName).toBe('SPAN')
    expect(text.className).toContain('truncate')
    expect(text.className).toContain('min-w-0')

    // 칩(Badge)에는 truncate 가 없어야 한다 — 있으면 무효인 곳에 건 것이다.
    const chip = text.closest('[data-slot="badge"]')
    expect(chip).not.toBeNull()
    expect(chip?.className).not.toContain('truncate')
    expect(chip?.className).toContain('max-w-[10rem]')
  })
})
