// 이슈 일괄 액션 바 컴포넌트 단위 테스트 — FR-IS-05 D6 Task-5
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueBulkActionBar } from '@/components/issues/IssueBulkActionBar'

// ─────────────────────────────────────────────────────────────────────────────
// IBAB-1. count === 0 이면 아무것도 렌더하지 않는다
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueBulkActionBar — count === 0', () => {
  /**
   * IBAB-1: count=0이면 null을 반환하고 DOM에 아무것도 렌더되지 않는다.
   */
  it('IBAB-1: count=0이면 아무것도 렌더되지 않는다', () => {
    render(
      <IssueBulkActionBar
        count={0}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    expect(screen.queryByRole('toolbar')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bulk-action-bar')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IBAB-2. count > 0 이면 텍스트 + 버튼 3개가 노출된다
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueBulkActionBar — count > 0', () => {
  /**
   * IBAB-2: count=3이면 "3건 선택됨" 텍스트가 렌더된다.
   */
  it('IBAB-2: count=3이면 "3건 선택됨" 텍스트가 렌더된다', () => {
    render(
      <IssueBulkActionBar
        count={3}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const toolbar = screen.getByRole('toolbar')
    expect(within(toolbar).getByText('3건 선택됨')).toBeInTheDocument()
  })

  /**
   * IBAB-3: "일괄 편집" 버튼이 렌더된다.
   */
  it('IBAB-3: "일괄 편집" 버튼이 렌더된다', () => {
    render(
      <IssueBulkActionBar
        count={1}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const toolbar = screen.getByRole('toolbar')
    expect(within(toolbar).getByRole('button', { name: '일괄 편집' })).toBeInTheDocument()
  })

  /**
   * IBAB-4: "일괄 전환" 버튼이 렌더된다.
   */
  it('IBAB-4: "일괄 전환" 버튼이 렌더된다', () => {
    render(
      <IssueBulkActionBar
        count={1}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const toolbar = screen.getByRole('toolbar')
    expect(within(toolbar).getByRole('button', { name: '일괄 전환' })).toBeInTheDocument()
  })

  /**
   * IBAB-5: "선택 해제" 버튼이 렌더된다.
   */
  it('IBAB-5: "선택 해제" 버튼이 렌더된다', () => {
    render(
      <IssueBulkActionBar
        count={1}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const toolbar = screen.getByRole('toolbar')
    expect(within(toolbar).getByRole('button', { name: '선택 해제' })).toBeInTheDocument()
  })

  /**
   * IBAB-6: count=1이면 "1건 선택됨" 텍스트가 렌더된다 (경계값).
   */
  it('IBAB-6: count=1이면 "1건 선택됨" 텍스트가 렌더된다', () => {
    render(
      <IssueBulkActionBar
        count={1}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const toolbar = screen.getByRole('toolbar')
    expect(within(toolbar).getByText('1건 선택됨')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IBAB-3. 버튼 클릭 시 콜백 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueBulkActionBar — 버튼 클릭 콜백', () => {
  /**
   * IBAB-7: "일괄 편집" 버튼 클릭 시 onEdit 콜백이 호출된다.
   */
  it('IBAB-7: "일괄 편집" 버튼 클릭 시 onEdit가 호출된다', async () => {
    const onEdit = vi.fn()
    render(
      <IssueBulkActionBar
        count={2}
        onEdit={onEdit}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    const toolbar = screen.getByRole('toolbar')
    await user.click(within(toolbar).getByRole('button', { name: '일괄 편집' }))
    expect(onEdit).toHaveBeenCalledOnce()
  })

  /**
   * IBAB-8: "일괄 전환" 버튼 클릭 시 onTransition 콜백이 호출된다.
   */
  it('IBAB-8: "일괄 전환" 버튼 클릭 시 onTransition이 호출된다', async () => {
    const onTransition = vi.fn()
    render(
      <IssueBulkActionBar
        count={2}
        onEdit={vi.fn()}
        onTransition={onTransition}
        onClear={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    const toolbar = screen.getByRole('toolbar')
    await user.click(within(toolbar).getByRole('button', { name: '일괄 전환' }))
    expect(onTransition).toHaveBeenCalledOnce()
  })

  /**
   * IBAB-9: "선택 해제" 버튼 클릭 시 onClear 콜백이 호출된다.
   */
  it('IBAB-9: "선택 해제" 버튼 클릭 시 onClear가 호출된다', async () => {
    const onClear = vi.fn()
    render(
      <IssueBulkActionBar
        count={2}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={onClear}
      />,
    )
    const user = userEvent.setup()
    const toolbar = screen.getByRole('toolbar')
    await user.click(within(toolbar).getByRole('button', { name: '선택 해제' }))
    expect(onClear).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IBAB-4. 셀렉터 견고성 — 컨테이너로 감싸 strict mode 충돌 방지
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueBulkActionBar — 컨테이너 견고성', () => {
  /**
   * IBAB-10: role="toolbar"인 컨테이너가 존재한다 (식별 가능한 컨테이너).
   */
  it('IBAB-10: role="toolbar" 컨테이너가 렌더된다', () => {
    render(
      <IssueBulkActionBar
        count={5}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    expect(screen.getByRole('toolbar')).toBeInTheDocument()
  })

  /**
   * IBAB-11: toolbar에 aria-label이 있다 (접근성).
   */
  it('IBAB-11: toolbar에 aria-label이 있다', () => {
    render(
      <IssueBulkActionBar
        count={5}
        onEdit={vi.fn()}
        onTransition={vi.fn()}
        onClear={vi.fn()}
      />,
    )
    const toolbar = screen.getByRole('toolbar')
    expect(toolbar).toHaveAttribute('aria-label')
  })
})
