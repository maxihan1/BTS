// ColumnSelector 컴포넌트 단위 테스트 — 컬럼 표시 토글 드롭다운 + 필수 컬럼 비활성 (FR-UX-06 Phase 5 PR18 Task 4)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ColumnSelector } from './ColumnSelector'
import { ISSUE_COLUMNS } from './issue-columns'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface RenderOverrides {
  isVisible?: (key: string) => boolean
  onToggle?: (key: string) => void
}

function renderSelector(overrides: RenderOverrides = {}): { onToggle: ReturnType<typeof vi.fn> } {
  const isVisible = overrides.isVisible ?? (() => true)
  const onToggle = overrides.onToggle ?? vi.fn()
  render(<ColumnSelector allColumns={ISSUE_COLUMNS} isVisible={isVisible} onToggle={onToggle} />)
  return { onToggle: onToggle as ReturnType<typeof vi.fn> }
}

async function openMenu(): Promise<void> {
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: /컬럼/ }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 트리거
// ─────────────────────────────────────────────────────────────────────────────

describe('ColumnSelector — 트리거', () => {
  it('CS-1: "컬럼" 라벨을 가진 트리거 버튼이 렌더된다', () => {
    renderSelector()
    expect(screen.getByRole('button', { name: /컬럼/ })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컬럼 목록 + 필수 컬럼 비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('ColumnSelector — 컬럼 목록', () => {
  it('CS-2: 트리거 클릭 시 모든 컬럼이 체크박스 항목으로 렌더된다', async () => {
    renderSelector()
    await openMenu()
    for (const column of ISSUE_COLUMNS) {
      expect(await screen.findByRole('menuitemcheckbox', { name: column.header })).toBeInTheDocument()
    }
  })

  it('CS-3: 필수 컬럼("키")은 aria-disabled=true + 항상 체크(aria-checked=true) 상태다', async () => {
    renderSelector({ isVisible: () => false })
    await openMenu()
    const keyItem = await screen.findByRole('menuitemcheckbox', { name: '키' })
    expect(keyItem).toHaveAttribute('aria-disabled', 'true')
    expect(keyItem).toHaveAttribute('aria-checked', 'true')
  })

  it('CS-4: 비필수 컬럼은 isVisible 결과를 그대로 체크 상태(aria-checked)에 반영한다', async () => {
    renderSelector({ isVisible: (key) => key === 'assignee' })
    await openMenu()
    expect(await screen.findByRole('menuitemcheckbox', { name: '담당자' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    expect(screen.getByRole('menuitemcheckbox', { name: '우선순위' })).toHaveAttribute('aria-checked', 'false')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 토글 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('ColumnSelector — 토글 콜백', () => {
  it('CS-5: 비필수 컬럼("담당자") 클릭 → onToggle("assignee") 호출', async () => {
    const { onToggle } = renderSelector()
    await openMenu()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('menuitemcheckbox', { name: '담당자' }))
    expect(onToggle).toHaveBeenCalledWith('assignee')
  })

  it('CS-6: 필수 컬럼("요약") 클릭 → onToggle이 호출되지 않는다(비활성)', async () => {
    const { onToggle } = renderSelector()
    await openMenu()
    const user = userEvent.setup()
    await user.click(await screen.findByRole('menuitemcheckbox', { name: '요약' }))
    expect(onToggle).not.toHaveBeenCalled()
  })
})
