// PostActionFormDialog 컴포넌트 단위 테스트 — url/method 입력·검증·추가/수정·취소/저장 콜백
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { PostActionFormDialog } from '@/components/workflow/PostActionFormDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const defaultProps = {
  open: true,
  mode: 'create' as const,
  onSubmit: vi.fn(),
  onCancel: vi.fn(),
}

// ─────────────────────────────────────────────────────────────────────────────
// PAFD-1 ~ PAFD-3: 렌더 / 열림 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionFormDialog — 렌더', () => {
  /**
   * PAFD-1. open=true 시 dialog role이 렌더된다.
   */
  it('PAFD-1: open=true이면 role=dialog가 렌더된다', () => {
    render(<PostActionFormDialog {...defaultProps} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  /**
   * PAFD-2. open=false 시 dialog가 렌더되지 않는다.
   */
  it('PAFD-2: open=false이면 dialog가 노출되지 않는다', () => {
    render(<PostActionFormDialog {...defaultProps} open={false} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  /**
   * PAFD-3. create 모드 시 url/method 입력 필드가 노출된다.
   */
  it('PAFD-3: create 모드에서 url과 method 입력 필드가 존재한다', () => {
    render(<PostActionFormDialog {...defaultProps} />)
    expect(screen.getByLabelText(/URL/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/메서드|Method/i)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PAFD-4 ~ PAFD-5: create / edit 모드 겸용
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionFormDialog — create/edit 모드', () => {
  /**
   * PAFD-4. create 모드 시 「추가」 계열 제목/버튼 라벨이 표시된다.
   */
  it('PAFD-4: create 모드에서 추가 관련 텍스트가 렌더된다', () => {
    render(<PostActionFormDialog {...defaultProps} mode="create" />)
    // 제목 또는 버튼 중 하나라도 「추가」 텍스트 포함
    const addTexts = screen.getAllByText(/추가/)
    expect(addTexts.length).toBeGreaterThan(0)
  })

  /**
   * PAFD-5. edit 모드 시 initialValues가 url/method 필드에 프리필된다.
   */
  it('PAFD-5: edit 모드에서 initialValues가 프리필된다', () => {
    render(
      <PostActionFormDialog
        {...defaultProps}
        mode="edit"
        initialValues={{ url: 'https://example.com/hook', method: 'POST' }}
      />,
    )
    const urlInput = screen.getByLabelText(/URL/i) as HTMLInputElement
    expect(urlInput.value).toBe('https://example.com/hook')
  })

  /**
   * PAFD-5b. edit 모드 시 「수정」 계열 제목/버튼 라벨이 표시된다.
   */
  it('PAFD-5b: edit 모드에서 수정 관련 텍스트가 렌더된다', () => {
    render(
      <PostActionFormDialog
        {...defaultProps}
        mode="edit"
        initialValues={{ url: 'https://example.com/hook', method: 'POST' }}
      />,
    )
    const editTexts = screen.getAllByText(/수정/)
    expect(editTexts.length).toBeGreaterThan(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PAFD-6 ~ PAFD-9: URL 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionFormDialog — URL 검증', () => {
  /**
   * PAFD-6. url 빈값으로 저장 시 에러 메시지가 표시되고 onSubmit이 호출되지 않는다.
   */
  it('PAFD-6: url 빈값으로 저장하면 에러 메시지가 표시되고 onSubmit은 미호출된다', () => {
    const onSubmit = vi.fn()
    render(<PostActionFormDialog {...defaultProps} onSubmit={onSubmit} />)

    // url 입력 필드를 비운 채 저장 클릭
    const saveBtn = screen.getByRole('button', { name: /저장|추가/ })
    fireEvent.click(saveBtn)

    expect(onSubmit).not.toHaveBeenCalled()
    // 에러 메시지가 DOM에 존재해야 함
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  /**
   * PAFD-7. http:// 가 아닌 ftp://로 시작하면 에러 메시지가 표시되고 onSubmit이 호출되지 않는다.
   */
  it('PAFD-7: ftp:// URL은 검증 실패 → onSubmit 미호출', () => {
    const onSubmit = vi.fn()
    render(<PostActionFormDialog {...defaultProps} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'ftp://invalid.example.com' },
    })
    fireEvent.click(screen.getByRole('button', { name: /저장|추가/ }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  /**
   * PAFD-8. http://로 시작하는 유효한 URL + method 입력 시 onSubmit이 호출된다.
   */
  it('PAFD-8: http:// 유효 URL + method → onSubmit 호출', () => {
    const onSubmit = vi.fn()
    render(<PostActionFormDialog {...defaultProps} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'http://example.com/webhook' },
    })
    // method 선택 (select이면 change, input이면 change)
    const methodEl = screen.getByLabelText(/메서드|Method/i)
    fireEvent.change(methodEl, { target: { value: 'POST' } })

    fireEvent.click(screen.getByRole('button', { name: /저장|추가/ }))

    expect(onSubmit).toHaveBeenCalledWith({
      url: 'http://example.com/webhook',
      method: 'POST',
    })
  })

  /**
   * PAFD-9. https://로 시작하는 유효한 URL + method 입력 시 onSubmit이 호출된다.
   */
  it('PAFD-9: https:// 유효 URL + method → onSubmit 호출', () => {
    const onSubmit = vi.fn()
    render(<PostActionFormDialog {...defaultProps} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'https://secure.example.com/hook' },
    })
    const methodEl = screen.getByLabelText(/메서드|Method/i)
    fireEvent.change(methodEl, { target: { value: 'PUT' } })

    fireEvent.click(screen.getByRole('button', { name: /저장|추가/ }))

    expect(onSubmit).toHaveBeenCalledWith({
      url: 'https://secure.example.com/hook',
      method: 'PUT',
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PAFD-10 ~ PAFD-11: method 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionFormDialog — method 검증', () => {
  /**
   * PAFD-10. method 빈값으로 저장하면 에러 메시지가 표시되고 onSubmit이 호출되지 않는다.
   */
  it('PAFD-10: method 빈값으로 저장하면 에러 메시지가 표시되고 onSubmit은 미호출된다', () => {
    const onSubmit = vi.fn()
    render(<PostActionFormDialog {...defaultProps} onSubmit={onSubmit} />)

    // url은 유효하게, method만 비움
    fireEvent.change(screen.getByLabelText(/URL/i), {
      target: { value: 'https://example.com/hook' },
    })
    const methodEl = screen.getByLabelText(/메서드|Method/i)
    fireEvent.change(methodEl, { target: { value: '' } })

    fireEvent.click(screen.getByRole('button', { name: /저장|추가/ }))

    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PAFD-12 ~ PAFD-13: 취소 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionFormDialog — 취소', () => {
  /**
   * PAFD-12. 취소 버튼 클릭 시 onCancel이 호출된다.
   */
  it('PAFD-12: 취소 버튼 클릭 시 onCancel이 호출된다', () => {
    const onCancel = vi.fn()
    render(<PostActionFormDialog {...defaultProps} onCancel={onCancel} />)

    fireEvent.click(screen.getByRole('button', { name: /취소/ }))

    expect(onCancel).toHaveBeenCalled()
  })

  /**
   * PAFD-13. submitting=true 시 저장 버튼이 비활성화된다.
   */
  it('PAFD-13: submitting=true이면 저장 버튼이 disabled된다', () => {
    render(<PostActionFormDialog {...defaultProps} submitting={true} />)
    const saveBtn = screen.getByRole('button', { name: /저장|추가|처리/ })
    expect(saveBtn).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PAFD-14: 접근성
// ─────────────────────────────────────────────────────────────────────────────

describe('PostActionFormDialog — 접근성', () => {
  /**
   * PAFD-14. Dialog에 접근 가능한 제목(aria-labelledby 또는 aria-label)이 있다.
   */
  it('PAFD-14: dialog에 접근 가능한 제목이 존재한다', () => {
    render(<PostActionFormDialog {...defaultProps} />)
    const dialog = screen.getByRole('dialog')
    // aria-labelledby 또는 aria-label 중 하나 존재
    const hasLabel =
      dialog.hasAttribute('aria-labelledby') || dialog.hasAttribute('aria-label')
    expect(hasLabel).toBe(true)
  })
})
