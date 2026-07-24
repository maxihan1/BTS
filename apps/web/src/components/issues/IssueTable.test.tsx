// IssueTable 컴포넌트 단위 테스트 — 시맨틱·e2e 셀렉터 보존·네비게이션·정렬 헤더·컬럼 표시 (FR-UX-06 Phase 5 PR18 Task 4)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { issueAtlas1Fixture, issueAtlas2Fixture, ISSUE_FILTER_BOB_ID } from '@/mocks/issue-fixtures'
import { IssueTable } from './IssueTable'
import type { IssueTableProps, IssueTableSelectionProps } from './IssueTable'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 전체 컬럼 키 — issue-columns.ts ISSUE_COLUMNS와 동기화 */
const ALL_COLUMN_KEYS = ['key', 'summary', 'status', 'assignee', 'priority', 'updatedAt']

function makeSelection(overrides: Partial<IssueTableSelectionProps> = {}): IssueTableSelectionProps {
  return {
    isSelected: () => false,
    onToggle: vi.fn(),
    onSelectAllPage: vi.fn(),
    isAllPageSelected: false,
    ...overrides,
  }
}

function renderTable(overrides: Partial<IssueTableProps> = {}): IssueTableProps {
  const props: IssueTableProps = {
    issues: [issueAtlas1Fixture, issueAtlas2Fixture],
    visibleColumnKeys: ALL_COLUMN_KEYS,
    sort: null,
    onSort: vi.fn(),
    selection: makeSelection(),
    onNavigate: vi.fn(),
    assigneeNameMap: new Map([[ISSUE_FILTER_BOB_ID, 'bob']]),
    ...overrides,
  }
  render(<IssueTable {...props} />)
  return props
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 + 접근성
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 렌더 + 접근성', () => {
  it('T-1: <table> 시맨틱으로 렌더되고 접근 가능한 이름을 가진다(W1)', () => {
    renderTable()
    expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★e2e 셀렉터 verbatim 보존 (G2)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — e2e 셀렉터 verbatim 보존', () => {
  it('T-2: 체크박스 — data-testid=select-{key} + aria-label="이슈 선택"', () => {
    renderTable()
    const checkbox = screen.getByTestId(`select-${issueAtlas1Fixture.key}`)
    expect(checkbox).toHaveAttribute('type', 'checkbox')
    expect(checkbox).toHaveAttribute('aria-label', '이슈 선택')
  })

  it('T-3: 요약 — data-testid=issue-summary-{key}', () => {
    renderTable()
    expect(screen.getByTestId(`issue-summary-${issueAtlas1Fixture.key}`)).toHaveTextContent(
      issueAtlas1Fixture.summary,
    )
  })

  it('T-4: 행 링크 — aria-label={key}, role=link, href=/issues/{key}', () => {
    renderTable()
    const link = screen.getByRole('link', { name: issueAtlas1Fixture.key })
    expect(link).toHaveAttribute('href', `/issues/${issueAtlas1Fixture.key}`)
  })

  it('T-5: 상태 배지 — role="status"', () => {
    renderTable()
    const statuses = screen.getAllByRole('status')
    const texts = statuses.map((el) => el.textContent)
    expect(texts).toContain(issueAtlas1Fixture.currentStateKey)
    expect(texts).toContain(issueAtlas2Fixture.currentStateKey)
  })

  it('T-6: 전체 선택 체크박스 — data-testid=select-all-page (기존 IssueListContent 계약 보존)', () => {
    renderTable()
    expect(screen.getByTestId('select-all-page')).toHaveAttribute('aria-label', '현재 페이지 전체 선택')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 네비게이션 — 행 클릭 vs 체크박스 클릭
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 네비게이션', () => {
  it('T-7: 요약 셀 클릭 → onNavigate(key) 호출', async () => {
    const onNavigate = vi.fn()
    renderTable({ onNavigate })
    const user = userEvent.setup()
    await user.click(screen.getByTestId(`issue-summary-${issueAtlas1Fixture.key}`))
    expect(onNavigate).toHaveBeenCalledWith(issueAtlas1Fixture.key)
  })

  it('T-8: 키 링크 클릭 → onNavigate(key)가 정확히 1회만 호출된다(행 클릭과 중복 방지)', async () => {
    const onNavigate = vi.fn()
    renderTable({ onNavigate })
    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: issueAtlas1Fixture.key }))
    expect(onNavigate).toHaveBeenCalledTimes(1)
    expect(onNavigate).toHaveBeenCalledWith(issueAtlas1Fixture.key)
  })

  it('T-9: 체크박스 클릭 → onToggle 호출 + onNavigate는 호출되지 않는다(전파 차단)', async () => {
    const onToggle = vi.fn()
    const onNavigate = vi.fn()
    renderTable({ onNavigate, selection: makeSelection({ onToggle }) })
    const user = userEvent.setup()
    await user.click(screen.getByTestId(`select-${issueAtlas1Fixture.key}`))
    expect(onToggle).toHaveBeenCalledWith(issueAtlas1Fixture.key)
    expect(onNavigate).not.toHaveBeenCalled()
  })

  it('T-10: 전체 선택 체크박스 클릭 → onSelectAllPage 호출', async () => {
    const onSelectAllPage = vi.fn()
    renderTable({ selection: makeSelection({ onSelectAllPage }) })
    const user = userEvent.setup()
    await user.click(screen.getByTestId('select-all-page'))
    expect(onSelectAllPage).toHaveBeenCalledOnce()
  })

  it('T-11: isAllPageSelected=true이면 전체 선택 체크박스가 checked 상태로 렌더된다', () => {
    renderTable({ selection: makeSelection({ isAllPageSelected: true }) })
    expect(screen.getByTestId('select-all-page')).toBeChecked()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 정렬 헤더 (GAP-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 정렬 헤더', () => {
  it('T-12: 정렬 가능 헤더("요약") 클릭 → onSort("summary") 호출', async () => {
    const onSort = vi.fn()
    renderTable({ onSort })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '요약' }))
    expect(onSort).toHaveBeenCalledWith('summary')
  })

  it('T-13: 활성 정렬(summary asc) → 해당 헤더에 aria-sort="ascending" + 방향 아이콘', () => {
    renderTable({ sort: { field: 'summary', dir: 'asc' } })
    const header = screen.getByRole('columnheader', { name: '요약' })
    expect(header).toHaveAttribute('aria-sort', 'ascending')
    expect(header.querySelector('svg')).not.toBeNull()
  })

  it('T-14: 활성 정렬(updatedAt desc) → 해당 헤더에 aria-sort="descending"', () => {
    renderTable({ sort: { field: 'updatedAt', dir: 'desc' } })
    const header = screen.getByRole('columnheader', { name: '수정일' })
    expect(header).toHaveAttribute('aria-sort', 'descending')
  })

  it('T-15: 비활성 정렬가능 컬럼("우선순위")은 aria-sort="none"', () => {
    renderTable({ sort: { field: 'summary', dir: 'asc' } })
    const header = screen.getByRole('columnheader', { name: '우선순위' })
    expect(header).toHaveAttribute('aria-sort', 'none')
  })

  it('T-16: 비정렬 컬럼("상태")은 aria-sort 속성 자체가 없다', () => {
    renderTable()
    const header = screen.getByRole('columnheader', { name: '상태' })
    expect(header).not.toHaveAttribute('aria-sort')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 표시 (visibleColumnKeys)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 컬럼 표시', () => {
  it('T-17: visibleColumnKeys에서 제외된 컬럼("담당자")은 렌더되지 않는다', () => {
    renderTable({ visibleColumnKeys: ['key', 'summary'] })
    expect(screen.queryByRole('columnheader', { name: '담당자' })).not.toBeInTheDocument()
  })

  it('T-18: 필수 컬럼(키·요약)은 visibleColumnKeys가 빈 배열이어도 항상 렌더된다', () => {
    renderTable({ visibleColumnKeys: [] })
    expect(screen.getByRole('columnheader', { name: '키' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '요약' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '상태' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 렌더 내용 — 담당자 해석·날짜 포맷
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — 컬럼 렌더 내용', () => {
  it('T-19: 담당자 매핑 존재 시 이름, 미배정이면 "미배정"을 표시한다', () => {
    renderTable()
    expect(screen.getByText('미배정')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
  })

  it('T-20: 수정일 — null이면 "—", ISO 문자열이면 프리셋(iso) 형식으로 포맷한다', () => {
    renderTable()
    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.getByText('2026-01-03')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// split view 선택 강조 (selectedKey) — bulk selection과 독립 (FR-UX-06 Phase 5 PR20 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueTable — selectedKey 강조(split view 현재 상세 행)', () => {
  it('T-21: selectedKey와 일치하는 행에 aria-current="true" + 선택 배경 강조가 붙고, 다른 행에는 붙지 않는다', () => {
    renderTable({ selectedKey: issueAtlas1Fixture.key })

    const currentRow = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    expect(currentRow).not.toBeNull()
    expect(currentRow).toHaveAttribute('aria-current', 'true')
    expect(currentRow).toHaveAttribute('data-state', 'selected')

    const otherRow = screen.getByRole('link', { name: issueAtlas2Fixture.key }).closest('tr')
    expect(otherRow).not.toHaveAttribute('aria-current')
    expect(otherRow).not.toHaveAttribute('data-state', 'selected')
  })

  it('T-22: selectedKey 미지정 시 어떤 행에도 강조가 없고, 기존 e2e 셀렉터는 그대로 유지된다', () => {
    renderTable()

    const row1 = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    const row2 = screen.getByRole('link', { name: issueAtlas2Fixture.key }).closest('tr')
    expect(row1).not.toHaveAttribute('aria-current')
    expect(row2).not.toHaveAttribute('aria-current')

    expect(screen.getByTestId(`select-${issueAtlas1Fixture.key}`)).toHaveAttribute('aria-label', '이슈 선택')
    expect(screen.getByTestId(`issue-summary-${issueAtlas1Fixture.key}`)).toHaveTextContent(
      issueAtlas1Fixture.summary,
    )
    expect(screen.getByRole('link', { name: issueAtlas1Fixture.key })).toHaveAttribute(
      'href',
      `/issues/${issueAtlas1Fixture.key}`,
    )
  })

  it('T-23: selectedKey=null도 미지정과 동일하게 어떤 행에도 강조가 없다', () => {
    renderTable({ selectedKey: null })

    const row1 = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    expect(row1).not.toHaveAttribute('aria-current')
    expect(row1).not.toHaveAttribute('data-state', 'selected')
  })

  it('T-24: selectedKey 강조는 bulk selection.isSelected와 독립 — 체크박스 미선택이어도 강조된다', () => {
    renderTable({
      selectedKey: issueAtlas1Fixture.key,
      selection: makeSelection({ isSelected: () => false }),
    })

    expect(screen.getByTestId(`select-${issueAtlas1Fixture.key}`)).not.toBeChecked()
    const row = screen.getByRole('link', { name: issueAtlas1Fixture.key }).closest('tr')
    expect(row).toHaveAttribute('aria-current', 'true')
  })
})
