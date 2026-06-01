// IssueMetaPanel 유형 행 + 셀렉터 + 상태전이 + 우선순위/영향도/환경/라벨 + 담당자 단위 테스트 — FR-IS-04 D6 Task-5, FR-IS-03 D6 Task-3
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { IssueResponse, IssueTransition } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { UserSummary } from '@/api/users'
import { IssueMetaPanel } from '@/components/issue/IssueMetaPanel'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트용 전이 목록 픽스처 — open 상태에서 2개 */
const transitionsFixture: IssueTransition[] = [
  { key: 'open__in_progress', name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
  { key: 'open__closed', name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
]

/** 테스트용 이슈 픽스처 — typeId=1(bug), priority=3, impact=null, labels=[], environment=null, assigneeId=null */
const issueFixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '테스트 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
}

/** 테스트용 사용자 목록 픽스처 */
const usersFixture: UserSummary[] = [
  { id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f', username: 'alice', displayName: '김앨리스', email: null },
  { id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a', username: 'bob', displayName: null, email: null },
]

/** 테스트용 이슈 타입 목록 픽스처 */
const availableTypes: IssueTypeResponse[] = [
  { id: 1, key: 'bug', name: '버그', description: '버그', iconName: 'bug' },
  { id: 2, key: 'story', name: '스토리', description: '스토리', iconName: 'story' },
  { id: 3, key: 'task', name: '작업', description: '작업', iconName: 'task' },
]

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPanel(
  issue: IssueResponse = issueFixture,
  types: IssueTypeResponse[] = availableTypes,
  onTypeChange = vi.fn(),
  onDeleteClick = vi.fn(),
  transitions: IssueTransition[] = transitionsFixture,
  onTransition = vi.fn(),
  isTransitioning = false,
  onPriorityChange = vi.fn(),
  onImpactChange = vi.fn(),
  onEnvironmentSave = vi.fn(),
  onLabelsSave = vi.fn(),
  users: UserSummary[] = [],
  onAssigneeSearch = vi.fn(),
  onAssigneeChange = vi.fn(),
  currentAssignee: UserSummary | null = null,
) {
  return render(
    <IssueMetaPanel
      issue={issue}
      availableTypes={types}
      onTypeChange={onTypeChange}
      onDeleteClick={onDeleteClick}
      transitions={transitions}
      onTransition={onTransition}
      isTransitioning={isTransitioning}
      onPriorityChange={onPriorityChange}
      onImpactChange={onImpactChange}
      onEnvironmentSave={onEnvironmentSave}
      onLabelsSave={onLabelsSave}
      users={users}
      onAssigneeSearch={onAssigneeSearch}
      onAssigneeChange={onAssigneeChange}
      currentAssignee={currentAssignee}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// IMP-1. 유형 행 — IssueTypeIcon + typeName 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 유형 행 렌더', () => {
  /**
   * IMP-1: 유형 레이블("유형")이 렌더된다.
   */
  it('IMP-1: 유형 레이블이 렌더된다', () => {
    renderPanel()
    const aside = screen.getByRole('complementary')
    expect(within(aside).getByText('유형')).toBeInTheDocument()
  })

  /**
   * IMP-2: 현재 이슈의 typeName이 span으로 표시된다.
   * issueFixture.typeId=1 → availableTypes에서 id=1 → name="버그"
   * 셀렉터 option에도 "버그"가 있으므로 span 요소로 범위를 좁힌다.
   */
  it('IMP-2: 현재 이슈의 typeName을 표시한다', () => {
    renderPanel()
    // data-testid="issue-type-name" span으로 정확하게 찾음 (option 텍스트와 구분)
    const typeNameSpan = screen.getByTestId('issue-type-name')
    expect(typeNameSpan).toBeInTheDocument()
    expect(typeNameSpan.textContent).toBe('버그')
  })

  /**
   * IMP-3: IssueTypeIcon이 렌더된다 — issue.typeId에 해당하는 iconName을 사용.
   * iconName="bug" → role="img" aria-label="버그"
   */
  it('IMP-3: 현재 타입의 아이콘이 렌더된다', () => {
    renderPanel()
    // IssueTypeIcon은 role="img" aria-label={typeName}으로 렌더됨
    expect(screen.getByRole('img', { name: '버그' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-4. 셀렉터 — availableTypes 옵션 노출
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 셀렉터 옵션', () => {
  /**
   * IMP-4: 셀렉터에 availableTypes 옵션이 모두 노출된다.
   */
  it('IMP-4: 셀렉터에 availableTypes 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ })
    expect(select).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '버그' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '스토리' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '작업' })).toBeInTheDocument()
  })

  /**
   * IMP-5: 셀렉터의 현재 선택값은 issue.typeId로 props 파생된다.
   * issue.typeId=1 → value="1"
   */
  it('IMP-5: 셀렉터 현재 선택값이 issue.typeId와 일치한다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ }) as HTMLSelectElement
    expect(select.value).toBe('1')
  })

  /**
   * IMP-6: issue가 바뀌면(다른 typeId props) 셀렉터 값도 따라 바뀐다 — stale key prop 회귀 가드.
   * useState(issue.typeId) 초기화 패턴이 있으면 이 테스트가 실패한다.
   */
  it('IMP-6: issue props가 바뀌면 셀렉터 선택값도 따라 바뀐다 (stale 회귀 가드)', () => {
    const { rerender } = renderPanel()

    const issueTypeChanged: IssueResponse = { ...issueFixture, typeId: 2, typeKey: 'story', typeName: '스토리' }
    rerender(
      <IssueMetaPanel
        issue={issueTypeChanged}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )

    const select = screen.getByRole('combobox', { name: /유형/ }) as HTMLSelectElement
    expect(select.value).toBe('2')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-7. 타입 변경 — onTypeChange 콜백 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 타입 변경 콜백', () => {
  /**
   * IMP-7: 다른 타입 선택 시 onTypeChange(typeId)가 해당 typeId(number)로 호출된다.
   */
  it('IMP-7: 다른 타입 선택 시 onTypeChange가 typeId(number)로 호출된다', async () => {
    const onTypeChange = vi.fn()
    renderPanel(issueFixture, availableTypes, onTypeChange)
    const user = userEvent.setup()

    const select = screen.getByRole('combobox', { name: /유형/ })
    await user.selectOptions(select, '2')

    expect(onTypeChange).toHaveBeenCalledOnce()
    expect(onTypeChange).toHaveBeenCalledWith(2)
  })

  /**
   * IMP-8: 현재와 같은 타입 선택 시에는 onTypeChange가 호출되지 않는다.
   */
  it('IMP-8: 현재와 같은 타입 선택 시 onTypeChange가 호출되지 않는다', async () => {
    const onTypeChange = vi.fn()
    renderPanel(issueFixture, availableTypes, onTypeChange)
    const user = userEvent.setup()

    const select = screen.getByRole('combobox', { name: /유형/ })
    await user.selectOptions(select, '1') // 현재 typeId=1과 동일

    expect(onTypeChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-9. 접근성 — WCAG AA
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 접근성', () => {
  /**
   * IMP-9: 셀렉터에 aria-label이 있다.
   */
  it('IMP-9: 셀렉터에 aria-label이 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ })
    expect(select).toHaveAttribute('aria-label')
  })

  /**
   * IMP-10: 셀렉터의 min-height가 44px 이상이다 (WCAG AA 터치 타깃).
   */
  it('IMP-10: 셀렉터에 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: /유형/ })
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMP-11~17. 상태전이 컨트롤 — FR-IS-01 Task-4
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 상태전이 컨트롤 렌더', () => {
  /**
   * IMP-11: 가용전이 목록이 있으면 전이 셀렉터가 렌더된다.
   */
  it('IMP-11: 가용전이가 있을 때 전이 셀렉터가 렌더된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toBeInTheDocument()
  })

  /**
   * IMP-12: 전이 셀렉터에 가용전이 name이 옵션으로 노출된다.
   * options: placeholder + "Start Work" + "Cancel"
   */
  it('IMP-12: 전이 셀렉터에 가용전이 name 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: 'Start Work' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: 'Cancel' })).toBeInTheDocument()
  })

  /**
   * IMP-13: 전이 선택 시 onTransition(toStateKey)이 호출된다.
   */
  it('IMP-13: 전이 선택 시 onTransition(toStateKey)이 호출된다', async () => {
    const onTransition = vi.fn()
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), transitionsFixture, onTransition)
    const user = userEvent.setup()

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    await user.selectOptions(select, 'in_progress')

    expect(onTransition).toHaveBeenCalledOnce()
    expect(onTransition).toHaveBeenCalledWith('in_progress')
  })

  /**
   * IMP-14: isTransitioning=true 시 전이 셀렉터가 disabled 상태가 된다 (중복클릭 방지, NFR3).
   */
  it('IMP-14: isTransitioning=true 시 전이 셀렉터가 disabled가 된다', () => {
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), transitionsFixture, vi.fn(), true)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toBeDisabled()
  })
})

describe('IssueMetaPanel — 가용전이 0건 (종료상태 S6)', () => {
  /**
   * IMP-15: 가용전이 0건이면 전이 셀렉터가 렌더되지 않는다.
   */
  it('IMP-15: 가용전이 0건이면 전이 셀렉터가 렌더되지 않는다', () => {
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), [])
    expect(screen.queryByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).not.toBeInTheDocument()
  })

  /**
   * IMP-16: 가용전이 0건이면 "더 진행할 전이 없음" 안내 문구가 렌더된다.
   */
  it('IMP-16: 가용전이 0건이면 "더 진행할 전이 없음" 안내가 렌더된다', () => {
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), [])
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E5 구분 검증 — unavailableReason prop: 'no-workflow' vs 'terminal' vs null
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — E5 미설정(no-workflow) vs 종료상태(terminal) 구분', () => {
  /**
   * IMP-18: unavailableReason='no-workflow' 시 미설정 안내문구가 렌더된다.
   */
  it('IMP-18: unavailableReason=no-workflow 시 transitionWorkflowNotConfiguredError 문구가 렌더된다', () => {
    render(
      <IssueMetaPanel
        issue={issueFixture}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={[]}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason="no-workflow"
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )
    expect(screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).toBeInTheDocument()
    expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
  })

  /**
   * IMP-19: unavailableReason='terminal'(또는 null) 시 noTransitionsAvailable 문구가 렌더된다.
   */
  it('IMP-19: unavailableReason=terminal 시 noTransitionsAvailable 문구가 렌더된다', () => {
    render(
      <IssueMetaPanel
        issue={issueFixture}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={[]}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason="terminal"
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )
    expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
    expect(screen.queryByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).not.toBeInTheDocument()
  })

  /**
   * IMP-20: 전이가 있으면 unavailableReason과 무관하게 셀렉터가 렌더된다.
   */
  it('IMP-20: 전이가 있으면 unavailableReason=no-workflow여도 셀렉터가 렌더된다', () => {
    render(
      <IssueMetaPanel
        issue={issueFixture}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        unavailableReason="no-workflow"
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )
    expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument()
  })
})

describe('IssueMetaPanel — 전이 셀렉터 접근성 (WCAG AA)', () => {
  /**
   * IMP-17: 전이 셀렉터에 aria-label이 있고 min-h-[44px] 클래스가 있다.
   */
  it('IMP-17: 전이 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 우선순위 셀렉터 (IMP-21~26)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 우선순위 셀렉터', () => {
  /**
   * IMP-21: 우선순위 레이블이 렌더된다.
   */
  it('IMP-21: 우선순위 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.priorityLabel)).toBeInTheDocument()
  })

  /**
   * IMP-22: 우선순위 셀렉터에 1~5 옵션이 모두 있다.
   */
  it('IMP-22: 우선순위 셀렉터에 1~5 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[1] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[2] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[3] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[4] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[5] })).toBeInTheDocument()
  })

  /**
   * IMP-23: 셀렉터 현재값이 issue.priority props에서 파생된다 (priority=3 → '3').
   */
  it('IMP-23: 셀렉터 현재값이 issue.priority와 일치한다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('3')
  })

  /**
   * IMP-24: issue.priority가 바뀌면 셀렉터 값도 따라 바뀐다 (stale 회귀 가드).
   */
  it('IMP-24: issue.priority props가 바뀌면 셀렉터 값도 따라 바뀐다', () => {
    const { rerender } = renderPanel()
    const issuePriorityChanged: IssueResponse = { ...issueFixture, priority: 1, priorityName: 'Highest' }
    rerender(
      <IssueMetaPanel
        issue={issuePriorityChanged}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('1')
  })

  /**
   * IMP-25: 다른 우선순위 선택 시 onPriorityChange(number)가 호출된다.
   */
  it('IMP-25: 우선순위 변경 시 onPriorityChange가 number로 호출된다', async () => {
    const onPriorityChange = vi.fn()
    renderPanel(issueFixture, availableTypes, vi.fn(), vi.fn(), transitionsFixture, vi.fn(), false, onPriorityChange)
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    await user.selectOptions(select, '1')
    expect(onPriorityChange).toHaveBeenCalledOnce()
    expect(onPriorityChange).toHaveBeenCalledWith(1)
  })

  /**
   * IMP-26: 우선순위 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다 (WCAG AA).
   */
  it('IMP-26: 우선순위 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 영향도 셀렉터 (IMP-27~34)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 영향도 셀렉터', () => {
  /**
   * IMP-27: 영향도 레이블이 렌더된다.
   */
  it('IMP-27: 영향도 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.impactLabel)).toBeInTheDocument()
  })

  /**
   * IMP-28: impact=null이면 "미지정" 옵션이 활성화(enabled) 상태로 있다.
   */
  it('IMP-28: impact=null이면 미지정 옵션이 enabled 상태로 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    const unsetOption = within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactUnset })
    expect(unsetOption).toBeInTheDocument()
    expect(unsetOption).not.toBeDisabled()
  })

  /**
   * IMP-29: impact=null이면 셀렉터 현재값이 '' (미지정 선택됨).
   */
  it('IMP-29: impact=null이면 셀렉터가 미지정을 표시한다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('')
  })

  /**
   * IMP-30: impact가 설정된 상태(impact=2)이면 "미지정" 옵션이 disabled이다 (클리어 불가 제약).
   */
  it('IMP-30: impact가 설정된 상태이면 미지정 옵션이 disabled이다', () => {
    const issueWithImpact: IssueResponse = { ...issueFixture, impact: 2, impactName: 'Medium' }
    renderPanel(issueWithImpact)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    const unsetOption = within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactUnset })
    expect(unsetOption).toBeDisabled()
  })

  /**
   * IMP-31: 영향도 셀렉터에 1~3 옵션이 모두 있다.
   */
  it('IMP-31: 영향도 셀렉터에 1~3 옵션이 모두 노출된다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[1] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[2] })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[3] })).toBeInTheDocument()
  })

  /**
   * IMP-32: impact=2이면 셀렉터 현재값이 '2'이다.
   */
  it('IMP-32: impact=2이면 셀렉터 현재값이 "2"이다', () => {
    const issueWithImpact: IssueResponse = { ...issueFixture, impact: 2, impactName: 'Medium' }
    renderPanel(issueWithImpact)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('2')
  })

  /**
   * IMP-33: 영향도 선택 시 onImpactChange(number)가 호출된다.
   */
  it('IMP-33: 영향도 변경 시 onImpactChange가 number로 호출된다', async () => {
    const onImpactChange = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      onImpactChange,
    )
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    await user.selectOptions(select, '1')
    expect(onImpactChange).toHaveBeenCalledOnce()
    expect(onImpactChange).toHaveBeenCalledWith(1)
  })

  /**
   * IMP-34: 영향도 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다 (WCAG AA).
   */
  it('IMP-34: 영향도 셀렉터에 aria-label과 min-h-[44px] 클래스가 있다', () => {
    renderPanel()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 환경(environment) 편집 (IMP-35~39)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 환경 편집', () => {
  /**
   * IMP-35: 환경 레이블이 렌더된다.
   */
  it('IMP-35: 환경 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.environmentLabel)).toBeInTheDocument()
  })

  /**
   * IMP-36: environment=null이면 textarea가 비어 있다.
   */
  it('IMP-36: environment=null이면 textarea가 비어 있다', () => {
    renderPanel()
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('')
  })

  /**
   * IMP-37: issue.environment가 설정된 경우 textarea에 현재값이 표시된다.
   */
  it('IMP-37: environment 값이 있으면 textarea에 표시된다', () => {
    const issueWithEnv: IssueResponse = { ...issueFixture, environment: 'Chrome 125 / macOS 14' }
    renderPanel(issueWithEnv)
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('Chrome 125 / macOS 14')
  })

  /**
   * IMP-38: 환경 편집 후 저장 버튼 클릭 시 onEnvironmentSave가 호출된다.
   */
  it('IMP-38: 저장 버튼 클릭 시 onEnvironmentSave가 편집값으로 호출된다', async () => {
    const onEnvironmentSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      onEnvironmentSave,
    )
    const user = userEvent.setup()
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder)
    await user.clear(textarea)
    await user.type(textarea, 'Firefox 126')
    const envSection = screen.getByTestId('environment-section')
    const saveBtn = within(envSection).getByRole('button', { name: issueDetailStrings.environmentSaveButton })
    await user.click(saveBtn)
    expect(onEnvironmentSave).toHaveBeenCalledOnce()
    expect(onEnvironmentSave).toHaveBeenCalledWith('Firefox 126')
  })

  /**
   * IMP-39: issue.environment props가 바뀌면 textarea 값도 따라 바뀐다 (stale 회귀 가드).
   */
  it('IMP-39: issue.environment props가 바뀌면 textarea 값이 따라 바뀐다', () => {
    const { rerender } = renderPanel()
    const issueEnvChanged: IssueResponse = { ...issueFixture, environment: 'Safari 17' }
    rerender(
      <IssueMetaPanel
        issue={issueEnvChanged}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('Safari 17')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 라벨(labels) 칩 (IMP-40~48)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 라벨 칩', () => {
  /**
   * IMP-40: 라벨 레이블이 렌더된다.
   */
  it('IMP-40: 라벨 레이블이 렌더된다', () => {
    renderPanel()
    expect(screen.getByText(issueDetailStrings.labelsLabel)).toBeInTheDocument()
  })

  /**
   * IMP-41: issue.labels에 있는 칩이 렌더된다.
   */
  it('IMP-41: issue.labels의 각 라벨이 칩으로 렌더된다', () => {
    const issueWithLabels: IssueResponse = { ...issueFixture, labels: ['bug', 'urgent'] }
    renderPanel(issueWithLabels)
    expect(screen.getByText('bug')).toBeInTheDocument()
    expect(screen.getByText('urgent')).toBeInTheDocument()
  })

  /**
   * IMP-42: 라벨 추가 입력 필드가 있다.
   */
  it('IMP-42: 라벨 추가 입력 필드가 있다', () => {
    renderPanel()
    expect(screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)).toBeInTheDocument()
  })

  /**
   * IMP-43: 라벨 추가 후 저장 시 onLabelsSave가 새 라벨 배열로 호출된다.
   */
  it('IMP-43: 라벨 추가 후 저장 시 onLabelsSave가 새 배열로 호출된다', async () => {
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    await user.type(input, 'new-label')
    await user.keyboard('{Enter}')
    const labelsSection = screen.getByTestId('labels-section')
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledOnce()
    expect(onLabelsSave).toHaveBeenCalledWith(['new-label'])
  })

  /**
   * IMP-44: 라벨 제거 버튼 클릭 후 저장 시 해당 라벨이 제거된 배열로 onLabelsSave가 호출된다.
   */
  it('IMP-44: 라벨 제거 후 저장 시 해당 라벨이 빠진 배열로 onLabelsSave가 호출된다', async () => {
    const onLabelsSave = vi.fn()
    const issueWithLabels: IssueResponse = { ...issueFixture, labels: ['bug', 'urgent'] }
    renderPanel(
      issueWithLabels,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const labelsSection = screen.getByTestId('labels-section')
    // 'bug' 라벨 제거 버튼 클릭 (aria-label='라벨 제거' 버튼 중 첫 번째)
    const removeBtns = within(labelsSection).getAllByRole('button', { name: issueDetailStrings.labelRemoveLabel })
    await user.click(removeBtns[0] as HTMLElement)
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledOnce()
    expect(onLabelsSave).toHaveBeenCalledWith(['urgent'])
  })

  /**
   * IMP-45: 라벨이 50자를 초과하면 추가되지 않는다 (클라이언트 검증).
   */
  it('IMP-45: 50자 초과 라벨은 추가되지 않는다', async () => {
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const labelsSection = screen.getByTestId('labels-section')
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    const longLabel = 'a'.repeat(51)
    await user.type(input, longLabel)
    await user.keyboard('{Enter}')
    // 저장해도 onLabelsSave 호출 안 됨 (또는 []로 호출됨 — 칩이 추가 안 됐으므로)
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledWith([])
  })

  /**
   * IMP-46: 이미 20개 라벨이 있으면 추가 입력 필드가 disabled이다.
   */
  it('IMP-46: 라벨이 20개이면 추가 입력 필드가 disabled이다', () => {
    const labels = Array.from({ length: 20 }, (_, i) => `label-${i}`)
    const issueMaxLabels: IssueResponse = { ...issueFixture, labels }
    renderPanel(issueMaxLabels)
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    expect(input).toBeDisabled()
  })

  /**
   * IMP-47: 공백만인 라벨은 추가되지 않는다 (trim 검증).
   */
  it('IMP-47: 공백만인 라벨은 추가되지 않는다', async () => {
    const onLabelsSave = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      onLabelsSave,
    )
    const user = userEvent.setup()
    const labelsSection = screen.getByTestId('labels-section')
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    await user.type(input, '   ')
    await user.keyboard('{Enter}')
    const saveBtn = within(labelsSection).getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onLabelsSave).toHaveBeenCalledWith([])
  })

  /**
   * IMP-48: issue.labels props가 바뀌면 표시 라벨도 따라 바뀐다 (stale 회귀 가드).
   */
  it('IMP-48: issue.labels props가 바뀌면 표시 라벨도 따라 바뀐다', () => {
    const { rerender } = renderPanel()
    const issueLabelsChanged: IssueResponse = { ...issueFixture, labels: ['refactored'] }
    rerender(
      <IssueMetaPanel
        issue={issueLabelsChanged}
        availableTypes={availableTypes}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        transitions={transitionsFixture}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
      />,
    )
    expect(screen.getByText('refactored')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-IS-03 Task 3 — 담당자 셀렉터 (IMP-50~57)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 담당자 셀렉터', () => {
  /**
   * IMP-50: 담당자 레이블이 렌더된다.
   */
  it('IMP-50: 담당자 레이블이 렌더된다', () => {
    renderPanel()
    const assigneeSection = screen.getByTestId('assignee-section')
    expect(within(assigneeSection).getByText(issueDetailStrings.assigneeLabel)).toBeInTheDocument()
  })

  /**
   * IMP-51: assigneeId=null이면 "미지정" 텍스트가 표시된다.
   */
  it('IMP-51: assigneeId=null이면 미지정 텍스트가 표시된다', () => {
    renderPanel()
    const assigneeSection = screen.getByTestId('assignee-section')
    expect(within(assigneeSection).getByText(issueDetailStrings.assigneeUnassigned)).toBeInTheDocument()
  })

  /**
   * IMP-52: currentAssignee prop이 주어지면 displayName을 표시한다.
   * C1 수정: 검색결과(users)가 아니라 currentAssignee prop에서 이름을 읽는다.
   */
  it('IMP-52: currentAssignee prop이 있으면 displayName을 표시한다', () => {
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [], // users는 빈 배열 — 검색결과에 담당자가 없어도 currentAssignee로 표시돼야 한다 (C1 회귀가드)
      vi.fn(),
      vi.fn(),
      alice, // currentAssignee prop
    )
    // data-testid="assignee-current-name" span으로 정확히 확인 (목록 버튼과 중복 방지)
    const currentNameEl = screen.getByTestId('assignee-current-name')
    expect(currentNameEl.textContent).toBe('김앨리스')
  })

  /**
   * IMP-53: currentAssignee prop이 있고 displayName이 null이면 username을 표시한다.
   */
  it('IMP-53: currentAssignee가 있고 displayName=null이면 username을 표시한다', () => {
    const bob = usersFixture[1]
    if (!bob) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: bob.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [], // users 빈 배열 — C1 회귀가드
      vi.fn(),
      vi.fn(),
      bob, // currentAssignee prop
    )
    // data-testid="assignee-current-name" span으로 정확히 확인 (목록 버튼과 중복 방지)
    const currentNameEl = screen.getByTestId('assignee-current-name')
    expect(currentNameEl.textContent).toBe('bob')
  })

  /**
   * IMP-58: C1 회귀가드 — 현재 담당자가 검색결과 목록(users)에 없어도
   * currentAssignee prop으로 이름이 표시된다.
   * 이것이 핵심 버그 수정 검증이다: 검색어를 바꾸거나 초기 로드 시
   * 담당자가 50건(MAX_RESULTS) 밖에 있어도 미지정으로 잘못 표시되지 않는다.
   */
  it('IMP-58: C1 회귀가드 — users에 없어도 currentAssignee prop으로 이름이 표시된다', () => {
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [], // users 완전히 비어 있음 (검색결과 없음)
      vi.fn(),
      vi.fn(),
      alice, // currentAssignee prop으로 이름 제공
    )
    const currentNameEl = screen.getByTestId('assignee-current-name')
    expect(currentNameEl.textContent).toBe('김앨리스')
    // "미지정"이 표시되면 안 된다
    expect(currentNameEl.textContent).not.toBe(issueDetailStrings.assigneeUnassigned)
  })

  /**
   * IMP-54: 검색 input에 입력 시 onAssigneeSearch 콜백이 호출된다.
   */
  it('IMP-54: 검색 input 입력 시 onAssigneeSearch가 호출된다', async () => {
    const onAssigneeSearch = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      [],
      onAssigneeSearch,
    )
    const user = userEvent.setup()
    const assigneeSection = screen.getByTestId('assignee-section')
    const searchInput = within(assigneeSection).getByPlaceholderText(issueDetailStrings.assigneeSearchPlaceholder)
    await user.type(searchInput, 'ali')
    expect(onAssigneeSearch).toHaveBeenCalled()
  })

  /**
   * IMP-55: 사용자 목록에서 항목 선택 시 onAssigneeChange(userId)가 호출된다.
   */
  it('IMP-55: 사용자 선택 시 onAssigneeChange(userId)가 호출된다', async () => {
    const onAssigneeChange = vi.fn()
    renderPanel(
      issueFixture,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      usersFixture,
      vi.fn(),
      onAssigneeChange,
    )
    const user = userEvent.setup()
    const assigneeSection = screen.getByTestId('assignee-section')
    const aliceBtn = within(assigneeSection).getByRole('button', { name: '김앨리스' })
    await user.click(aliceBtn)
    expect(onAssigneeChange).toHaveBeenCalledOnce()
    expect(onAssigneeChange).toHaveBeenCalledWith(usersFixture[0]?.id)
  })

  /**
   * IMP-56: 담당자가 있을 때 해제 버튼 클릭 시 onAssigneeChange(null)이 호출된다.
   */
  it('IMP-56: 담당자 해제 버튼 클릭 시 onAssigneeChange(null)이 호출된다', async () => {
    const onAssigneeChange = vi.fn()
    const alice = usersFixture[0]
    if (!alice) return
    const issueWithAssignee: IssueResponse = { ...issueFixture, assigneeId: alice.id }
    renderPanel(
      issueWithAssignee,
      availableTypes,
      vi.fn(),
      vi.fn(),
      transitionsFixture,
      vi.fn(),
      false,
      vi.fn(),
      vi.fn(),
      vi.fn(),
      vi.fn(),
      usersFixture,
      vi.fn(),
      onAssigneeChange,
      alice, // currentAssignee prop
    )
    const user = userEvent.setup()
    const assigneeSection = screen.getByTestId('assignee-section')
    const unassignBtn = within(assigneeSection).getByRole('button', { name: issueDetailStrings.assigneeUnassignButton })
    await user.click(unassignBtn)
    expect(onAssigneeChange).toHaveBeenCalledOnce()
    expect(onAssigneeChange).toHaveBeenCalledWith(null)
  })

  /**
   * IMP-57: assigneeId=null이면 담당자 해제 버튼이 표시되지 않는다 (미할당 상태).
   */
  it('IMP-57: assigneeId=null이면 해제 버튼이 없다', () => {
    renderPanel()
    const assigneeSection = screen.getByTestId('assignee-section')
    expect(within(assigneeSection).queryByRole('button', { name: issueDetailStrings.assigneeUnassignButton })).not.toBeInTheDocument()
  })
})
