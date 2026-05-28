// SchemeInUseModal 컴포넌트 단위 테스트 — 렌더/aria/확인 버튼/닫힘
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SchemeInUseModal } from '@/components/admin/SchemeInUseModal'

describe('SchemeInUseModal', () => {
  /**
   * SIM-1. isOpen=true 시 alertdialog role이 렌더된다.
   */
  it('SIM-1: isOpen=true이면 role=alertdialog가 렌더된다', () => {
    render(
      <SchemeInUseModal isOpen={true} onClose={vi.fn()} usedByProjectsCount={3} />,
    )
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  /**
   * SIM-2. isOpen=false 시 모달이 렌더되지 않는다.
   */
  it('SIM-2: isOpen=false이면 모달이 노출되지 않는다', () => {
    render(
      <SchemeInUseModal isOpen={false} onClose={vi.fn()} usedByProjectsCount={3} />,
    )
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  /**
   * SIM-3. 모달 내 안내 메시지가 렌더된다.
   */
  it('SIM-3: 사용 중 프로젝트 안내 메시지가 렌더된다', () => {
    render(
      <SchemeInUseModal isOpen={true} onClose={vi.fn()} usedByProjectsCount={3} />,
    )
    // AlertDialog는 Portal로 렌더 — getAllByText로 존재 확인
    expect(screen.getAllByText(/사용 중인 프로젝트/).length).toBeGreaterThan(0)
  })

  /**
   * SIM-4. usedByProjectsCount가 모달에 표시된다.
   */
  it('SIM-4: usedByProjectsCount가 표시된다', () => {
    render(
      <SchemeInUseModal isOpen={true} onClose={vi.fn()} usedByProjectsCount={5} />,
    )
    expect(screen.getByText(/5/)).toBeInTheDocument()
  })

  /**
   * SIM-5. 「확인」 버튼 클릭 시 onClose가 호출된다.
   * AlertDialog.Action은 onOpenChange(false)를 트리거하고, Button.onClick도 onClose를 직접 호출한다.
   * at-least-once 검증.
   */
  it('SIM-5: 확인 버튼 클릭 시 onClose가 호출된다', () => {
    const onClose = vi.fn()
    render(
      <SchemeInUseModal isOpen={true} onClose={onClose} usedByProjectsCount={3} />,
    )
    fireEvent.click(screen.getByRole('button', { name: /확인/ }))
    expect(onClose).toHaveBeenCalled()
  })

  /**
   * SIM-6. aria-describedby가 설정되어 있다.
   */
  it('SIM-6: aria-describedby 속성이 존재한다', () => {
    render(
      <SchemeInUseModal isOpen={true} onClose={vi.fn()} usedByProjectsCount={3} />,
    )
    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveAttribute('aria-describedby')
  })

  /**
   * SIM-7. 모달 제목이 렌더된다.
   * AlertDialog Portal 렌더 특성상 getAllByText 사용.
   */
  it('SIM-7: 모달 제목이 렌더된다', () => {
    render(
      <SchemeInUseModal isOpen={true} onClose={vi.fn()} usedByProjectsCount={2} />,
    )
    expect(screen.getAllByText(/삭제할 수 없습니다/).length).toBeGreaterThan(0)
  })
})
