// IssueStateTransition 단위 테스트 — IssueMetaPanel 분해 B (FR-UX-06 PR19 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { IssueTransition } from '@/api/issues'
import { IssueStateTransition } from '@/components/issue/meta/IssueStateTransition'
import { issueDetailStrings } from '@/i18n/ko'

const transitionsFixture: IssueTransition[] = [
  { key: 'open__in_progress', name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
  { key: 'open__closed', name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
]

describe('IssueStateTransition', () => {
  it('가용 전환이 있으면 셀렉터와 옵션이 렌더된다', () => {
    render(
      <IssueStateTransition
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason={null}
        selectedValue=""
        onSelectedValueChange={vi.fn()}
      />,
    )
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Start Work' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('전환 선택 시 onSelectedValueChange와 onTransition이 toStateKey로 호출된다', async () => {
    const onTransition = vi.fn()
    const onSelectedValueChange = vi.fn()
    render(
      <IssueStateTransition
        transitions={transitionsFixture}
        onTransition={onTransition}
        isTransitioning={false}
        unavailableReason={null}
        selectedValue=""
        onSelectedValueChange={onSelectedValueChange}
      />,
    )
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    await user.selectOptions(select, 'in_progress')
    expect(onSelectedValueChange).toHaveBeenCalledWith('in_progress')
    expect(onTransition).toHaveBeenCalledOnce()
    expect(onTransition).toHaveBeenCalledWith('in_progress')
  })

  it('isTransitioning=true이면 셀렉터가 disabled된다', () => {
    render(
      <IssueStateTransition
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning
        unavailableReason={null}
        selectedValue=""
        onSelectedValueChange={vi.fn()}
      />,
    )
    expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeDisabled()
  })

  it("가용 전환 0건 + unavailableReason='no-workflow'이면 워크플로우 미설정 문구가 렌더된다", () => {
    render(
      <IssueStateTransition
        transitions={[]}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason="no-workflow"
        selectedValue=""
        onSelectedValueChange={vi.fn()}
      />,
    )
    expect(screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it("가용 전환 0건 + unavailableReason='terminal'이면 더 이상 전환 없음 문구가 렌더된다", () => {
    render(
      <IssueStateTransition
        transitions={[]}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason="terminal"
        selectedValue=""
        onSelectedValueChange={vi.fn()}
      />,
    )
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
  })

  it('가용 전환 0건 + unavailableReason=null이면 더 이상 전환 없음 문구가 렌더된다', () => {
    render(
      <IssueStateTransition
        transitions={[]}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason={null}
        selectedValue=""
        onSelectedValueChange={vi.fn()}
      />,
    )
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
  })
})
