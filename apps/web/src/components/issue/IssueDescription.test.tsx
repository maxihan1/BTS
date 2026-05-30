// IssueDescription 컴포넌트 단위 테스트 — Write/Preview 탭, 저장/취소 흐름 검증
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { IssueDescription } from './IssueDescription'

describe('IssueDescription', () => {
  const defaultProps = {
    descriptionHtml: '<p>본문 HTML</p>',
    description: '본문 마크다운',
    onSave: vi.fn(),
    isSaving: false,
  }

  // ── Preview 탭 (읽기 모드) ────────────────────────────────────────────────

  it('Preview 탭에서 descriptionHtml을 렌더한다', () => {
    render(<IssueDescription {...defaultProps} />)
    // dangerouslySetInnerHTML으로 삽입된 HTML 내용 확인
    const content = screen.getByTestId('description-preview-content')
    expect(content.innerHTML).toBe('<p>본문 HTML</p>')
  })

  it('description=null이면 descriptionEmpty placeholder를 표시한다', () => {
    render(
      <IssueDescription
        descriptionHtml={null}
        description={null}
        onSave={vi.fn()}
        isSaving={false}
      />,
    )
    expect(screen.getByText('본문이 없습니다.')).toBeInTheDocument()
    // dangerouslySetInnerHTML 분기가 호출되지 않아야 함
    expect(screen.queryByTestId('description-preview-content')).not.toBeInTheDocument()
  })

  it('descriptionHtml=null이면 descriptionEmpty placeholder를 표시한다', () => {
    render(
      <IssueDescription
        descriptionHtml={null}
        description="원본 마크다운"
        onSave={vi.fn()}
        isSaving={false}
      />,
    )
    expect(screen.getByText('본문이 없습니다.')).toBeInTheDocument()
  })

  // ── 편집 모드 진입 ────────────────────────────────────────────────────────

  it('본문 편집 버튼 클릭 시 Write 탭 textarea에 raw description이 표시된다', () => {
    render(<IssueDescription {...defaultProps} />)
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    expect(textarea).toHaveValue('본문 마크다운')
  })

  it('편집 모드에서 Write/Preview 탭이 표시된다', () => {
    render(<IssueDescription {...defaultProps} />)
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    expect(screen.getByRole('tab', { name: '편집' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '미리보기' })).toBeInTheDocument()
  })

  // ── 저장 흐름 ─────────────────────────────────────────────────────────────

  it('저장 버튼 클릭 시 onSave(rawMarkdown)을 호출한다', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '수정된 내용' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(onSave).toHaveBeenCalledWith('수정된 내용')
  })

  it('빈 입력 저장 시 onSave("")를 호출한다 (클리어)', () => {
    const onSave = vi.fn()
    render(<IssueDescription {...defaultProps} onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    const textarea = screen.getByRole('textbox', { name: '본문 편집' })
    fireEvent.change(textarea, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(onSave).toHaveBeenCalledWith('')
  })

  // ── 취소 흐름 ─────────────────────────────────────────────────────────────

  it('취소 버튼 클릭 시 편집 모드가 닫힌다', () => {
    render(<IssueDescription {...defaultProps} />)
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    expect(screen.getByRole('textbox', { name: '본문 편집' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('textbox', { name: '본문 편집' })).not.toBeInTheDocument()
  })

  // ── isSaving 상태 ─────────────────────────────────────────────────────────

  it('isSaving=true이면 저장/취소 버튼이 disabled된다', () => {
    render(<IssueDescription {...defaultProps} isSaving={true} />)
    fireEvent.click(screen.getByRole('button', { name: '본문 편집' }))
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  })

  // ── 보안 NFR1/NFR2: raw description은 표시 경로에 절대 렌더 금지 ─────────

  it('Preview 탭에서 raw description 텍스트가 직접 노출되지 않는다', () => {
    render(
      <IssueDescription
        descriptionHtml="<p>HTML 본문</p>"
        description="raw_마크다운_절대_노출금지"
        onSave={vi.fn()}
        isSaving={false}
      />,
    )
    // 편집 모드가 아닌 상태에서 raw 텍스트가 DOM에 없어야 함
    expect(screen.queryByText('raw_마크다운_절대_노출금지')).not.toBeInTheDocument()
  })
})
