// RuleConflictWarningModal 단위 테스트 — 규칙 저장 후 충돌 1회성 경고 모달 (FR-AT-04 D6/D7)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RuleConflictWarningModal } from '@/components/automation/RuleConflictWarningModal'
import type { RuleConflict } from '@/api/automation-rules.types'

const CYCLE_CONFLICT: RuleConflict = {
  type: 'CYCLE',
  severity: 'WARNING',
  ruleIds: ['11111111-1111-4111-8111-111111111111'],
  detail: '이 규칙이 다른 규칙과 순환 참조를 형성합니다.',
}

const FIELD_CONFLICT: RuleConflict = {
  type: 'FIELD_CONFLICT',
  severity: 'WARNING',
  ruleIds: ['22222222-2222-4222-8222-222222222222', '33333333-3333-4333-8333-333333333333'],
  detail: '동일한 필드를 다른 값으로 설정하는 규칙이 있습니다.',
}

const PRIORITY_CONFLICT: RuleConflict = {
  type: 'PRIORITY_AMBIGUITY',
  severity: 'WARNING',
  ruleIds: ['44444444-4444-4444-8444-444444444444'],
  detail: '동일 트리거에 우선순위가 모호한 규칙이 여러 개 있습니다.',
}

describe('RuleConflictWarningModal — conflicts null', () => {
  it('conflicts가 null이면 아무것도 렌더하지 않는다', () => {
    const onClose = vi.fn()
    const { container } = render(<RuleConflictWarningModal conflicts={null} onClose={onClose} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('RuleConflictWarningModal — 단건 충돌', () => {
  it('충돌 종류 한국어 라벨과 detail 텍스트를 렌더한다', () => {
    render(<RuleConflictWarningModal conflicts={[CYCLE_CONFLICT]} onClose={vi.fn()} />)

    expect(screen.getByText('순환 참조')).toBeInTheDocument()
    expect(screen.getByText(CYCLE_CONFLICT.detail)).toBeInTheDocument()
  })

  it('관련 규칙 개수를 축약해 표시하고 UUID 원문은 노출하지 않는다', () => {
    render(<RuleConflictWarningModal conflicts={[CYCLE_CONFLICT]} onClose={vi.fn()} />)

    expect(screen.getByText('관련 규칙 1개')).toBeInTheDocument()
    expect(screen.queryByText(CYCLE_CONFLICT.ruleIds[0] ?? '')).not.toBeInTheDocument()
  })
})

describe('RuleConflictWarningModal — 다건 충돌', () => {
  it('충돌 항목 전부를 렌더한다(3건)', () => {
    render(
      <RuleConflictWarningModal
        conflicts={[CYCLE_CONFLICT, FIELD_CONFLICT, PRIORITY_CONFLICT]}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByText('순환 참조')).toBeInTheDocument()
    expect(screen.getByText('필드 충돌')).toBeInTheDocument()
    expect(screen.getByText('우선순위 모호')).toBeInTheDocument()
    expect(screen.getByText(CYCLE_CONFLICT.detail)).toBeInTheDocument()
    expect(screen.getByText(FIELD_CONFLICT.detail)).toBeInTheDocument()
    expect(screen.getByText(PRIORITY_CONFLICT.detail)).toBeInTheDocument()
  })

  it('2건이면 관련 규칙 2개로 축약 표시한다', () => {
    render(<RuleConflictWarningModal conflicts={[FIELD_CONFLICT]} onClose={vi.fn()} />)

    expect(screen.getByText('관련 규칙 2개')).toBeInTheDocument()
  })
})

describe('RuleConflictWarningModal — 닫기', () => {
  it('닫기 버튼을 클릭하면 onClose가 호출된다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(<RuleConflictWarningModal conflicts={[CYCLE_CONFLICT]} onClose={onClose} />)

    await user.click(screen.getByTestId('rule-conflict-close-button'))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('모달 컨테이너와 경고 본문이 role="alert"로 렌더된다', () => {
    render(<RuleConflictWarningModal conflicts={[CYCLE_CONFLICT]} onClose={vi.fn()} />)

    expect(screen.getByTestId('rule-conflict-warning-modal')).toBeInTheDocument()
    expect(screen.getAllByRole('alert').length).toBeGreaterThan(0)
  })
})
