// 전환 생성·수정 다이얼로그 — 전환 종류 선택 (장부 「전환 종류를 고를 수 없어 전역 전환을 만들 방법이 없다」)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/api/workflows'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import { TransitionFormDialog } from '../TransitionFormDialog'

const STATES: WorkflowView['states'] = [
  { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
  { key: 'done', name: 'Done', category: 'DONE', displayOrder: 2 },
] as WorkflowView['states']

function renderDialog(
  overrides: Partial<React.ComponentProps<typeof TransitionFormDialog>> = {},
): { onSubmit: ReturnType<typeof vi.fn> } {
  const onSubmit = vi.fn()
  render(
    <TransitionFormDialog
      open
      onOpenChange={vi.fn()}
      states={STATES}
      editing={null}
      onSubmit={onSubmit}
      {...overrides}
    />,
  )
  return { onSubmit }
}

describe('TransitionFormDialog — 전환 종류', () => {
  it('생성 폼에 전환 종류를 고르는 자리가 있다', () => {
    renderDialog()

    expect(screen.getByRole('radiogroup', { name: labels.transitionForm.kind })).toBeInTheDocument()
    expect(
      screen.getByRole('radio', { name: labels.transitionForm.kindNormal }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('radio', { name: labels.transitionForm.kindGlobal }),
    ).toBeInTheDocument()
  })

  it('기본값은 NORMAL 이고 출발 상태를 묻는다', () => {
    renderDialog()

    expect(screen.getByRole('radio', { name: labels.transitionForm.kindNormal })).toBeChecked()
    expect(screen.getByLabelText(labels.transitionForm.fromState)).toBeInTheDocument()
  })

  it('★「모든 상태에서」를 고르면 출발 상태 입력이 사라진다', async () => {
    const user = userEvent.setup()
    renderDialog()

    // 마우스로 고른다 — Radix roving-focus 가 keyboard.press 의 0ms keydown→keyup 을 흘린다
    await user.click(screen.getByRole('radio', { name: labels.transitionForm.kindGlobal }))

    // 보이면 사용자가 고를 수 있고, 실으면 백엔드가 400 이다 — 고를 수 있는데
    // 항상 실패하는 입력을 두지 않는다는 것이 이 폼의 기존 계약이다.
    expect(screen.queryByLabelText(labels.transitionForm.fromState)).not.toBeInTheDocument()
  })

  it('★GLOBAL 로 저장하면 kind 와 출발 상태가 그대로 실린다', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog()

    await user.type(screen.getByLabelText(labels.transitionForm.name), '강제 완료')
    await user.click(screen.getByRole('radio', { name: labels.transitionForm.kindGlobal }))
    await user.click(screen.getByRole('button', { name: labels.transitionForm.submit }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ name: '강제 완료', kind: 'GLOBAL', fromStatusKey: null }),
    )
  })

  it('NORMAL 로 저장하면 고른 출발 상태가 실린다 — GLOBAL 단언이 공허해지지 않게 하는 짝', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog({
      prefill: { from: 'open', to: 'done' },
    })

    await user.type(screen.getByLabelText(labels.transitionForm.name), '완료 처리')
    await user.click(screen.getByRole('button', { name: labels.transitionForm.submit }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'NORMAL', fromStatusKey: 'open', toStatusKey: 'done' }),
    )
  })

  it('★INITIAL 전환을 수정할 때는 종류를 바꾸지 못한다', () => {
    // 워크플로우당 하나뿐이고 V207 백필이 심는 전환이다. 사용자가 이것을 GLOBAL 로 바꾸면
    // 이슈 생성 진입점이 사라진다 — 고를 수 있게 두면 안 되는 자리다.
    renderDialog({
      editing: {
        key: 'INITIAL__open',
        name: '이슈 생성',
        fromStateKey: null,
        toStateKey: 'open',
        kind: 'INITIAL',
      } as WorkflowView['transitions'][number],
    })

    expect(
      screen.queryByRole('radiogroup', { name: labels.transitionForm.kind }),
    ).not.toBeInTheDocument()
  })
})
