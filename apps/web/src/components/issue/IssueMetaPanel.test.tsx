// IssueMetaPanel 유형 행 + 셀렉터 단위 테스트 — FR-IS-02 D6 Task-6
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { IssueResponse } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import { IssueMetaPanel } from '@/components/issue/IssueMetaPanel'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

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
) {
  return render(
    <IssueMetaPanel
      issue={issue}
      availableTypes={types}
      onTypeChange={onTypeChange}
      onDeleteClick={onDeleteClick}
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
   * IMP-2: 현재 이슈의 typeName이 표시된다.
   * issueFixture.typeId=1 → availableTypes에서 id=1 → name="버그"
   */
  it('IMP-2: 현재 이슈의 typeName을 표시한다', () => {
    renderPanel()
    const aside = screen.getByRole('complementary')
    expect(within(aside).getByText('버그')).toBeInTheDocument()
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
