// 단축키 도움말 모달 단위 테스트 — SHORTCUTS 레지스트리 렌더, 비구현 키 미표시, 닫힘 콜백 (FR-UX-05 Task-3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ShortcutsHelpDialog } from './ShortcutsHelpDialog'

describe('ShortcutsHelpDialog', () => {
  it('open=true이면 role=dialog로 표시되고 제목이 "키보드 단축키"이다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('키보드 단축키')).toBeInTheDocument()
  })

  it('open=false이면 화면에 표시되지 않는다', () => {
    render(<ShortcutsHelpDialog open={false} onOpenChange={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('SHORTCUTS 5종 설명이 각각 표시된다', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByText('단축키 도움말 열기/닫기')).toBeInTheDocument()
    expect(screen.getByText('새 이슈 생성')).toBeInTheDocument()
    expect(screen.getByText('검색으로 이동')).toBeInTheDocument()
    expect(screen.getByText('내 이슈로 이동')).toBeInTheDocument()
    expect(screen.getByText('대시보드로 이동')).toBeInTheDocument()
  })

  it('Cmd+K 명령 팔레트 항목이 표시된다 (혼합 OS 대응 Cmd/Ctrl 병기)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByText('명령 팔레트 열기')).toBeInTheDocument()
    // 혼합 OS에서 Windows 사용자에게 Cmd만 오표기하지 않도록 Cmd/Ctrl 병기 검증 (C1)
    expect(screen.getByText('Cmd/Ctrl')).toBeInTheDocument()
  })

  it('비구현 단축키(편집/담당자 변경/상태 변경)는 표시되지 않는다 (FR8)', () => {
    render(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.queryByText('편집')).not.toBeInTheDocument()
    expect(screen.queryByText('담당자 변경')).not.toBeInTheDocument()
    expect(screen.queryByText('상태 변경')).not.toBeInTheDocument()
  })

  it('onOpenChange prop이 전달되고 Esc 입력 시 false로 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    render(<ShortcutsHelpDialog open onOpenChange={onOpenChange} />)
    await user.keyboard('{Escape}')
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('open=false → true로 리렌더하면 모달이 나타난다', () => {
    const { rerender } = render(<ShortcutsHelpDialog open={false} onOpenChange={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    rerender(<ShortcutsHelpDialog open onOpenChange={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('X 닫기 버튼 클릭 시 onOpenChange가 false로 호출된다', async () => {
    const user = userEvent.setup()
    const onOpenChange = vi.fn()
    render(<ShortcutsHelpDialog open onOpenChange={onOpenChange} />)
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
