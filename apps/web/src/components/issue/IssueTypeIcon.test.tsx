// IssueTypeIcon 컴포넌트 단위 테스트 — iconName → lucide 아이콘 매핑, fallback, aria-label
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { IssueTypeIcon } from '@/components/issue/IssueTypeIcon'

describe('IssueTypeIcon', () => {
  /**
   * ITI-1. "epic" iconName → svg 아이콘 렌더 + aria-label 확인.
   */
  it('ITI-1: iconName="epic"이면 아이콘이 렌더되고 aria-label이 typeName과 일치한다', () => {
    render(<IssueTypeIcon iconName="epic" typeName="에픽" />)
    const icon = screen.getByRole('img', { name: '에픽' })
    expect(icon).toBeInTheDocument()
  })

  /**
   * ITI-2. "story" iconName → 아이콘 렌더.
   */
  it('ITI-2: iconName="story"이면 아이콘이 렌더된다', () => {
    render(<IssueTypeIcon iconName="story" typeName="스토리" />)
    expect(screen.getByRole('img', { name: '스토리' })).toBeInTheDocument()
  })

  /**
   * ITI-3. "task" iconName → 아이콘 렌더.
   */
  it('ITI-3: iconName="task"이면 아이콘이 렌더된다', () => {
    render(<IssueTypeIcon iconName="task" typeName="작업" />)
    expect(screen.getByRole('img', { name: '작업' })).toBeInTheDocument()
  })

  /**
   * ITI-4. "subtask" iconName → 아이콘 렌더.
   */
  it('ITI-4: iconName="subtask"이면 아이콘이 렌더된다', () => {
    render(<IssueTypeIcon iconName="subtask" typeName="하위작업" />)
    expect(screen.getByRole('img', { name: '하위작업' })).toBeInTheDocument()
  })

  /**
   * ITI-5. "bug" iconName → 아이콘 렌더.
   */
  it('ITI-5: iconName="bug"이면 아이콘이 렌더된다', () => {
    render(<IssueTypeIcon iconName="bug" typeName="버그" />)
    expect(screen.getByRole('img', { name: '버그' })).toBeInTheDocument()
  })

  /**
   * ITI-6. 미상 iconName → fallback 아이콘 렌더.
   */
  it('ITI-6: 미상 iconName이면 fallback 아이콘이 렌더된다', () => {
    render(<IssueTypeIcon iconName="custom-foo" typeName="커스텀" />)
    expect(screen.getByRole('img', { name: '커스텀' })).toBeInTheDocument()
  })

  /**
   * ITI-7. iconName=null → fallback 아이콘 렌더.
   */
  it('ITI-7: iconName=null이면 fallback 아이콘이 렌더된다', () => {
    render(<IssueTypeIcon iconName={null} typeName="알수없음" />)
    expect(screen.getByRole('img', { name: '알수없음' })).toBeInTheDocument()
  })

  /**
   * ITI-8. aria-label이 typeName 값과 정확히 일치한다.
   */
  it('ITI-8: aria-label이 typeName과 정확히 일치한다', () => {
    render(<IssueTypeIcon iconName="task" typeName="내 작업 타입" />)
    const icon = screen.getByRole('img', { name: '내 작업 타입' })
    expect(icon).toHaveAttribute('aria-label', '내 작업 타입')
  })

  /**
   * ITI-9. img src 사용 금지 — <img> 태그가 렌더되지 않는다.
   */
  it('ITI-9: <img> 태그가 렌더되지 않는다 (iconName은 URL이 아님)', () => {
    const { container } = render(<IssueTypeIcon iconName="epic" typeName="에픽" />)
    expect(container.querySelector('img')).toBeNull()
  })
})
