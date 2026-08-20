// IssueStateTransition 단위 테스트 — IssueMetaPanel 분해 B (FR-UX-06 PR19 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { IssueTransition } from '@/api/issues'
import {
  IssueStateTransition,
  AmbiguousTransitionDialog,
  ambiguousTransitionStrings,
} from '@/components/issue/meta/IssueStateTransition'
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

// ─────────────────────────────────────────────────────────────────────────────
// T21. 409 AMBIGUOUS_TRANSITION 후보 선택 다이얼로그 (ADR 2026-08-18 §D3)
// ─────────────────────────────────────────────────────────────────────────────

/** 후보 전환 1번의 1급 식별자. */
const CANDIDATE_ONE_ID = '33333333-3333-4333-8333-333333333333'
/** 후보 전환 2번의 1급 식별자. */
const CANDIDATE_TWO_ID = '44444444-4444-4444-8444-444444444444'

const CANDIDATES = [
  { transitionId: CANDIDATE_ONE_ID, name: '조건부 승인' },
  { transitionId: CANDIDATE_TWO_ID, name: '즉시 완료' },
]

const AMBIGUOUS_MESSAGE = '이동할 수 있는 전환이 2개입니다. 어느 전환인지 골라 주세요.'

describe('IssueStateTransition — 같은 도착 상태 전환이 둘일 때 (T21)', () => {
  it('T21-C0: 도착 상태가 같아도 두 옵션이 모두 렌더된다 (React key 중복 없음)', () => {
    const duplicated: IssueTransition[] = [
      { key: 'open__done', name: '조건부 승인', fromStateKey: 'open', toStateKey: 'done', transitionId: CANDIDATE_ONE_ID },
      { key: 'open__done', name: '즉시 완료', fromStateKey: 'open', toStateKey: 'done', transitionId: CANDIDATE_TWO_ID },
    ]
    render(
      <IssueStateTransition
        transitions={duplicated}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason={null}
        selectedValue=""
        onSelectedValueChange={vi.fn()}
      />,
    )
    expect(screen.getByRole('option', { name: '조건부 승인' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '즉시 완료' })).toBeInTheDocument()
  })
})

describe('AmbiguousTransitionDialog', () => {
  it('T21-C1: 고유 aria-label 을 가진 다이얼로그와 후보 전량이 렌더된다', () => {
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning={false}
        onSelect={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(
      screen.getByRole('dialog', { name: ambiguousTransitionStrings.dialogTitle }),
    ).toBeInTheDocument()
    // 서버가 준 안내 문구를 그대로 보여 준다 — 후보 개수가 문구에 들어 있다
    expect(screen.getByText(AMBIGUOUS_MESSAGE)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '조건부 승인' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '즉시 완료' })).toBeInTheDocument()
  })

  it('T21-C2: 후보를 고르면 그 transitionId 로 onSelect 가 호출된다', async () => {
    const onSelect = vi.fn()
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning={false}
        onSelect={onSelect}
        onCancel={vi.fn()}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '즉시 완료' }))
    expect(onSelect).toHaveBeenCalledOnce()
    expect(onSelect).toHaveBeenCalledWith(CANDIDATE_TWO_ID)
  })

  it('T21-C3: 취소하면 onCancel 이 호출되고 onSelect 는 호출되지 않는다', async () => {
    const onSelect = vi.fn()
    const onCancel = vi.fn()
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning={false}
        onSelect={onSelect}
        onCancel={onCancel}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: ambiguousTransitionStrings.cancel }))
    expect(onCancel).toHaveBeenCalledOnce()
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('T21-C4: 재요청 진행 중이면 후보 버튼이 disabled 된다 (중복 제출 방지)', () => {
    render(
      <AmbiguousTransitionDialog
        candidates={CANDIDATES}
        message={AMBIGUOUS_MESSAGE}
        isTransitioning
        onSelect={vi.fn()}
        onCancel={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '조건부 승인' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '즉시 완료' })).toBeDisabled()
  })
})
