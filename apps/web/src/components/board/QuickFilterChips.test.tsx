// 보드 퀵필터 칩 목록 단위 테스트 (FR-UX-01 Task 9)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { QuickFilter } from '@/api/board-quick-filters'
import { quickFilterLabels } from '@/i18n/quick-filter-labels'

// ─────────────────────────────────────────────────────────────────────────────
// mock — useDeleteQuickFilter, SaveQuickFilterDialog, sonner toast
// ─────────────────────────────────────────────────────────────────────────────

const mockDeleteMutate = vi.fn()
const mockUseDeleteQuickFilter = vi.fn()

vi.mock('@/hooks/use-board-quick-filters', () => ({
  useDeleteQuickFilter: (boardId: string) => mockUseDeleteQuickFilter(boardId),
}))

let capturedDialogProps: Record<string, unknown> | null = null

vi.mock('./SaveQuickFilterDialog', () => ({
  SaveQuickFilterDialog: (props: Record<string, unknown>) => {
    capturedDialogProps = props
    if (props['open'] !== true) return null
    return (
      <div data-testid="save-quick-filter-dialog" data-mode={props['mode'] as string}>
        dialog
      </div>
    )
  },
}))

const mockToastError = vi.fn()
vi.mock('sonner', () => ({
  toast: { error: (...args: unknown[]) => mockToastError(...args) },
}))

// ─────────────────────────────────────────────────────────────────────────────
// import 대상 — mock 이후 import (vi.mock hoisting)
// ─────────────────────────────────────────────────────────────────────────────

import { QuickFilterChips } from './QuickFilterChips'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_ID = 'b1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

const FILTER_A: QuickFilter = {
  filterId: 'f1a2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
  name: '내 버그',
  query: 'assignee=abc&label=bug',
}

const FILTER_B: QuickFilter = {
  filterId: 'a2b3c4d5-e6f7-4a8b-9c0d-e1f2a3b4c5d6',
  name: '긴급 작업',
  query: 'label=urgent',
}

// ─────────────────────────────────────────────────────────────────────────────
// 셋업
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  capturedDialogProps = null
  mockUseDeleteQuickFilter.mockReturnValue({
    mutate: mockDeleteMutate,
    isPending: false,
    variables: undefined,
  })
})

afterEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) 렌더 — 칩 목록 + 활성 상태 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('QuickFilterChips — 렌더(a)', () => {
  it('quickFilters 각각을 칩으로 렌더한다', () => {
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A, FILTER_B]}
        activeQuickFilterId={null}
        canManage={false}
        currentQuery=""
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    const list = screen.getByRole('list', { name: quickFilterLabels.list.ariaLabel })
    expect(within(list).getByRole('button', { name: FILTER_A.name })).toBeInTheDocument()
    expect(within(list).getByRole('button', { name: FILTER_B.name })).toBeInTheDocument()
  })

  it('activeQuickFilterId와 일치하는 칩은 aria-pressed=true, 나머지는 false다', () => {
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A, FILTER_B]}
        activeQuickFilterId={FILTER_A.filterId}
        canManage={false}
        currentQuery=""
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: FILTER_A.name })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: FILTER_B.name })).toHaveAttribute('aria-pressed', 'false')
  })

  it('quickFilters가 비어 있고 canManage=false면 아무것도 렌더하지 않는다', () => {
    const { container } = render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[]}
        activeQuickFilterId={null}
        canManage={false}
        currentQuery=""
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    expect(container).toBeEmptyDOMElement()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 클릭 — 적용/토글 해제 (FR6)
// ─────────────────────────────────────────────────────────────────────────────

describe('QuickFilterChips — 칩 클릭(b) FR6', () => {
  it('비활성 칩 클릭 시 onApply(filter)가 호출된다', async () => {
    const onApply = vi.fn()
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={null}
        canManage={false}
        currentQuery=""
        onApply={onApply}
        onFilterDeleted={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: FILTER_A.name }))

    expect(onApply).toHaveBeenCalledWith(FILTER_A)
  })

  it('활성 칩을 재클릭하면 onApply(null)가 호출된다 (토글 해제)', async () => {
    const onApply = vi.fn()
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={FILTER_A.filterId}
        canManage={false}
        currentQuery=""
        onApply={onApply}
        onFilterDeleted={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: FILTER_A.name }))

    expect(onApply).toHaveBeenCalledWith(null)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 권한 게이팅 — canManage
// ─────────────────────────────────────────────────────────────────────────────

describe('QuickFilterChips — 권한 게이팅(c)', () => {
  it('canManage=false면 편집/삭제 버튼과 "필터 저장" 버튼이 렌더되지 않는다', () => {
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={null}
        canManage={false}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    expect(
      screen.queryByRole('button', { name: quickFilterLabels.list.editAriaLabel(FILTER_A.name) }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: quickFilterLabels.list.deleteAriaLabel(FILTER_A.name) }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: quickFilterLabels.list.saveCurrentButton }),
    ).not.toBeInTheDocument()
  })

  it('canManage=true면 편집/삭제 버튼과 "필터 저장" 버튼이 렌더된다', () => {
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={null}
        canManage={true}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    expect(
      screen.getByRole('button', { name: quickFilterLabels.list.editAriaLabel(FILTER_A.name) }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: quickFilterLabels.list.deleteAriaLabel(FILTER_A.name) }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: quickFilterLabels.list.saveCurrentButton }),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (d) "필터 저장" 버튼 — EC1 빈 필터 비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('QuickFilterChips — 필터 저장 버튼(d) EC1', () => {
  it('currentQuery가 빈 문자열이면 "필터 저장" 버튼이 비활성이다', () => {
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[]}
        activeQuickFilterId={null}
        canManage={true}
        currentQuery=""
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: quickFilterLabels.list.saveCurrentButton })).toBeDisabled()
  })

  it('currentQuery가 있으면 "필터 저장" 버튼이 활성이고 클릭 시 SaveQuickFilterDialog(mode=create)가 열린다', async () => {
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[]}
        activeQuickFilterId={null}
        canManage={true}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    const saveButton = screen.getByRole('button', { name: quickFilterLabels.list.saveCurrentButton })
    expect(saveButton).not.toBeDisabled()

    await user.click(saveButton)

    const dialog = screen.getByTestId('save-quick-filter-dialog')
    expect(dialog).toHaveAttribute('data-mode', 'create')
    expect(capturedDialogProps?.['boardId']).toBe(BOARD_ID)
    expect(capturedDialogProps?.['currentQuery']).toBe('assignee=abc')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (e) 편집 버튼 — SaveQuickFilterDialog(mode=edit, filter) 오픈
// ─────────────────────────────────────────────────────────────────────────────

describe('QuickFilterChips — 편집 버튼(e)', () => {
  it('편집 버튼 클릭 시 SaveQuickFilterDialog가 mode=edit, filter=해당 필터로 열린다', async () => {
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={null}
        canManage={true}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    await user.click(
      screen.getByRole('button', { name: quickFilterLabels.list.editAriaLabel(FILTER_A.name) }),
    )

    const dialog = screen.getByTestId('save-quick-filter-dialog')
    expect(dialog).toHaveAttribute('data-mode', 'edit')
    expect(capturedDialogProps?.['filter']).toEqual(FILTER_A)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (f) 삭제 버튼 — useDeleteQuickFilter.mutate + onFilterDeleted(filterId, wasActive)
// ─────────────────────────────────────────────────────────────────────────────

describe('QuickFilterChips — 삭제 버튼(f)', () => {
  it('비활성 칩 삭제 시 mutate 호출 후 onFilterDeleted(filterId, false)가 호출된다', async () => {
    mockDeleteMutate.mockImplementation((_id: string, options?: { onSuccess?: () => void }) => {
      options?.onSuccess?.()
    })
    const onFilterDeleted = vi.fn()
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={null}
        canManage={true}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={onFilterDeleted}
      />,
    )

    await user.click(
      screen.getByRole('button', { name: quickFilterLabels.list.deleteAriaLabel(FILTER_A.name) }),
    )

    expect(mockDeleteMutate).toHaveBeenCalledWith(FILTER_A.filterId, expect.anything())
    expect(onFilterDeleted).toHaveBeenCalledWith(FILTER_A.filterId, false)
  })

  it('활성 칩 삭제 시 onFilterDeleted(filterId, true)가 호출된다 (C3-c)', async () => {
    mockDeleteMutate.mockImplementation((_id: string, options?: { onSuccess?: () => void }) => {
      options?.onSuccess?.()
    })
    const onFilterDeleted = vi.fn()
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={FILTER_A.filterId}
        canManage={true}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={onFilterDeleted}
      />,
    )

    await user.click(
      screen.getByRole('button', { name: quickFilterLabels.list.deleteAriaLabel(FILTER_A.name) }),
    )

    expect(onFilterDeleted).toHaveBeenCalledWith(FILTER_A.filterId, true)
  })

  it('삭제 실패 시 toast.error가 호출된다', async () => {
    mockDeleteMutate.mockImplementation((_id: string, options?: { onError?: () => void }) => {
      options?.onError?.()
    })
    const user = userEvent.setup()
    render(
      <QuickFilterChips
        boardId={BOARD_ID}
        quickFilters={[FILTER_A]}
        activeQuickFilterId={null}
        canManage={true}
        currentQuery="assignee=abc"
        onApply={vi.fn()}
        onFilterDeleted={vi.fn()}
      />,
    )

    await user.click(
      screen.getByRole('button', { name: quickFilterLabels.list.deleteAriaLabel(FILTER_A.name) }),
    )

    expect(mockToastError).toHaveBeenCalled()
  })
})
