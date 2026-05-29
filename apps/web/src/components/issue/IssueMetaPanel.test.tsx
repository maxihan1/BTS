// IssueMetaPanel 유형 행 + 셀렉터 + 상태전이 컨트롤 단위 테스트 — FR-IS-02 D6 + FR-IS-01 Task-4
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { IssueResponse, IssueTransition } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
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

/** 테스트용 이슈 픽스처 — typeId=1(bug) */
const issueFixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '테스트 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
}

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
