// 이슈 목록 상태 셀 테스트 — role="status" 보존 · 전이 선택 · 사유 2종 · 권한 · 종료 전이 (FR-UX-11 F9 FR7·FR8·FR9·FR10·FR14)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { IssueTransition } from '@/api/issues'
import { issueDetailStrings } from '@/i18n/ko'
import { StatusCellDisplay, StatusCellEditor } from './StatusCell'

/**
 * 비종료 전이 1건.
 *
 * `as IssueTransition` 캐스팅을 쓰지 않고 전 필드를 채운다 — 캐스팅은 스키마가 자라도
 * 조용히 통과해 mock drift 를 감춘다(형제 task 와 동일 판단).
 */
const TRANSITIONS: IssueTransition[] = [
  {
    key: 'start',
    name: '진행 시작',
    fromStateKey: 'TODO',
    toStateKey: 'IN_PROGRESS',
    toCategory: 'IN_PROGRESS',
  },
]

describe('StatusCellDisplay', () => {
  it('상태 배지의 role="status" 를 유지한다 (FR9 — 즉사 계약)', () => {
    render(<StatusCellDisplay currentStateKey="TODO" />)

    expect(screen.getByRole('status')).toHaveTextContent('TODO')
  })
})

describe('StatusCellEditor', () => {
  it('가용 전이만 버튼으로 노출하고 고르면 toStateKey 로 onTransition 을 부른다 (FR7)', async () => {
    const onTransition = vi.fn()
    render(
      <StatusCellEditor
        transitions={TRANSITIONS}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={onTransition}
        onDoneTransition={vi.fn()}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '진행 시작' }))

    expect(onTransition).toHaveBeenCalledWith('IN_PROGRESS')
  })

  it('워크플로우 미설정이면 그 사유를 안내한다 (FR8)', () => {
    render(
      <StatusCellEditor
        transitions={[]}
        unavailableReason="no-workflow"
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(
      screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError),
    ).toBeInTheDocument()
    expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
  })

  it('종료 상태면 다른 사유를 안내한다 (FR8)', () => {
    render(
      <StatusCellEditor
        transitions={[]}
        unavailableReason="terminal"
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
    expect(
      screen.queryByText(issueDetailStrings.transitionWorkflowNotConfiguredError),
    ).not.toBeInTheDocument()
  })

  it('전이 권한이 없으면 선택지가 비활성이다 (FR10 fail-closed)', () => {
    render(
      <StatusCellEditor
        transitions={TRANSITIONS}
        unavailableReason={null}
        canTransition={false}
        isSaving={false}
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '진행 시작' })).toBeDisabled()
  })

  it('저장 중이면 선택지가 비활성이다 (NFR3 중복 제출 차단)', () => {
    render(
      <StatusCellEditor
        transitions={TRANSITIONS}
        unavailableReason={null}
        canTransition
        isSaving
        isLoading={false}
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '진행 시작' })).toBeDisabled()
  })

  it('조회 중이면 "결과 없음" 대신 로딩을 알린다 (전이 0건과 구분)', () => {
    render(
      <StatusCellEditor
        transitions={[]}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading
        onTransition={vi.fn()}
        onDoneTransition={vi.fn()}
      />,
    )

    expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
  })

  it('종료 전이(toCategory=DONE)는 즉시 전이하지 않고 결의안 요청을 올린다 (FR14)', async () => {
    const onTransition = vi.fn()
    const onDoneTransition = vi.fn()
    const doneTransition: IssueTransition = {
      key: 'finish',
      name: '완료',
      fromStateKey: 'IN_PROGRESS',
      toStateKey: 'DONE',
      toCategory: 'DONE',
    }

    render(
      <StatusCellEditor
        transitions={[doneTransition]}
        unavailableReason={null}
        canTransition
        isSaving={false}
        isLoading={false}
        onTransition={onTransition}
        onDoneTransition={onDoneTransition}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '완료' }))

    expect(onDoneTransition).toHaveBeenCalledWith(doneTransition)
    // ★결의안 없이 전이하지 않는다 — 해결 결과는 종료 전이의 필수 입력이다
    expect(onTransition).not.toHaveBeenCalled()
  })
})
