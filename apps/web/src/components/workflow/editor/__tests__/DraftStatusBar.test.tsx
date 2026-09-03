// 초안 표시줄·복원·충돌 배너 테스트 — 저장 실패가 발행을 잠그는가 · 출구가 하나인가
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DraftStatusBar } from '../DraftStatusBar'
import { ResetToDefaultDialog } from '../ResetToDefaultDialog'
import { DraftConflictBanner } from '../DraftConflictBanner'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'

function renderBar(over: Partial<React.ComponentProps<typeof DraftStatusBar>> = {}) {
  const handlers = { onPublish: vi.fn(), onReset: vi.fn(), onDiscard: vi.fn() }
  render(
    <DraftStatusBar
      saveState="idle"
      saveError={null}
      hasDraft={false}
      canResetToDefault={false}
      busy={false}
      migration={null}
      {...handlers}
      {...over}
    />,
  )
  return handlers
}

describe('초안 표시줄', () => {
  it('저장 상태를 문구로 보여준다', () => {
    renderBar({ saveState: 'saved' })

    expect(screen.getByText(labels.draft.saved)).toBeInTheDocument()
  })

  it('「발행해야 반영된다」를 항상 띄운다', () => {
    // 「편집 ≠ 배포」가 이 화면의 핵심 계약이다. 저장 상태와 무관하게 보여야 한다.
    renderBar({ saveState: 'saved' })

    expect(screen.getByText(labels.draft.unpublishedNotice)).toBeInTheDocument()
  })

  it('★ 저장이 실패하면 발행을 잠근다', () => {
    // 저장 안 된 초안을 발행하면 서버에 있는 **옛 초안**이 나간다. 화면에는 새 정의가
    // 보이는데 발행된 것은 다른 것이라 관리자가 무엇을 발행했는지 알 수 없다.
    renderBar({ saveState: 'error', saveError: '시작 전환은 정확히 하나여야 한다' })

    expect(screen.getByRole('button', { name: labels.draft.publish })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('시작 전환')
  })

  it('복원 가능하지 않으면 그 버튼을 아예 안 그린다', () => {
    // 눌러 보고 400 을 받게 두지 않는다. 판정은 서버가 준 값 하나뿐이다.
    renderBar({ canResetToDefault: false })

    expect(screen.queryByRole('button', { name: labels.draft.reset })).not.toBeInTheDocument()
  })

  it('복원 가능하면 버튼이 있고 눌리면 알린다', async () => {
    const { onReset } = renderBar({ canResetToDefault: true })

    await userEvent.click(screen.getByRole('button', { name: labels.draft.reset }))

    expect(onReset).toHaveBeenCalledTimes(1)
  })

  it('저장된 초안이 없으면 폐기 버튼을 안 그린다', () => {
    renderBar({ hasDraft: false })

    expect(screen.queryByRole('button', { name: labels.draft.discard })).not.toBeInTheDocument()
  })

  it('busy 면 발행이 잠긴다', () => {
    renderBar({ busy: true })

    expect(screen.getByRole('button', { name: labels.draft.publish })).toBeDisabled()
  })
})

describe('기본값 복원 다이얼로그', () => {
  it('「발행해야 반영된다」를 설명이 직접 말한다', () => {
    render(<ResetToDefaultDialog open onOpenChange={vi.fn()} onConfirm={vi.fn()} confirming={false} />)

    expect(screen.getByRole('dialog', { name: labels.reset.dialogTitle })).toBeInTheDocument()
    expect(screen.getByText(labels.reset.dialogDescription)).toBeInTheDocument()
  })

  it('확인이 눌리면 알린다', async () => {
    const onConfirm = vi.fn()
    render(<ResetToDefaultDialog open onOpenChange={vi.fn()} onConfirm={onConfirm} confirming={false} />)

    await userEvent.click(screen.getByRole('button', { name: labels.reset.confirm }))

    expect(onConfirm).toHaveBeenCalledTimes(1)
  })
})

describe('발행 충돌 배너', () => {
  it('★ 「다시 시도」를 권하지 않고 폐기만 준다', async () => {
    // 서버 앵커는 write-once 라 재시도로는 같은 409 가 반복된다. 재시도 버튼을 두면
    // 관리자는 무한히 누르게 된다.
    const onDiscard = vi.fn()
    render(<DraftConflictBanner onDiscard={onDiscard} discarding={false} />)

    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(1)
    await userEvent.click(buttons[0]!)
    expect(onDiscard).toHaveBeenCalledTimes(1)
  })

  it('사라지지 않는 alert 로 알린다', () => {
    render(<DraftConflictBanner onDiscard={vi.fn()} discarding={false} />)

    expect(screen.getByRole('alert', { name: labels.conflict.banner })).toBeInTheDocument()
  })
})
