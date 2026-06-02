// 일괄 편집 Dialog 컴포넌트 단위 테스트 — priority/impact 선택 + submit 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BulkEditDialog } from './BulkEditDialog'

// ─────────────────────────────────────────────────────────────────────────────
// useSubmitBulkOperation mock
// ─────────────────────────────────────────────────────────────────────────────

const mockMutateAsync = vi.fn()

vi.mock('@/hooks/use-bulk-operation', () => ({
  useSubmitBulkOperation: () => ({
    mutateAsync: mockMutateAsync,
    isPending: false,
  }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

interface RenderOptions {
  readonly issueKeys?: string[]
  readonly open?: boolean
  readonly onOpenChange?: (o: boolean) => void
  readonly onSubmitted?: (bulkOperationId: string) => void
}

function renderDialog(opts: RenderOptions = {}) {
  const {
    issueKeys = ['PROJ-1', 'PROJ-2'],
    open = true,
    onOpenChange = vi.fn(),
    onSubmitted = vi.fn(),
  } = opts

  const queryClient = makeQueryClient()

  render(
    <QueryClientProvider client={queryClient}>
      <BulkEditDialog
        issueKeys={issueKeys}
        open={open}
        onOpenChange={onOpenChange}
        onSubmitted={onSubmitted}
      />
    </QueryClientProvider>,
  )

  return { onOpenChange, onSubmitted }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BulkEditDialog', () => {
  beforeEach(() => {
    mockMutateAsync.mockReset()
  })

  // (a) priority select(1~5), impact select(1~3) 노출
  it('(a) priority 선택(1~5)과 impact 선택(1~3)이 렌더된다', () => {
    renderDialog()

    expect(screen.getByRole('combobox', { name: /priority/i })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: /impact/i })).toBeInTheDocument()
  })

  // (b) 둘 다 무변경이면 적용 버튼 비활성
  it('(b) priority와 impact 모두 미선택이면 적용 버튼이 비활성화된다', () => {
    renderDialog()

    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).toBeDisabled()
  })

  // (c) priority만 선택 후 적용 → mutateAsync 호출 검증 + onSubmitted + onOpenChange(false)
  it('(c) priority만 선택 후 적용 시 올바른 payload로 mutateAsync를 호출하고 콜백을 실행한다', async () => {
    const bulkOperationId = 'aabbccdd-0000-0000-0000-000000000001'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 2 })

    const { onSubmitted, onOpenChange } = renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    const user = userEvent.setup()

    // priority select 클릭 후 옵션 선택
    const priorityTrigger = screen.getByRole('combobox', { name: /priority/i })
    await user.click(priorityTrigger)

    // "Highest" 또는 "1" 레이블 옵션 선택
    const option = await screen.findByRole('option', { name: /highest/i })
    await user.click(option)

    // 적용 버튼 활성화 확인 후 클릭
    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).not.toBeDisabled()
    await user.click(applyButton)

    await waitFor(() => {
      expect(mockMutateAsync).toHaveBeenCalledWith({
        operationType: 'BULK_EDIT',
        issueKeys: ['PROJ-1', 'PROJ-2'],
        editPayload: { priority: 1, impact: null },
        transitionPayload: null,
      })
    })

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalledWith(bulkOperationId)
    })

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  // 취소 버튼 클릭 시 onOpenChange(false) 호출
  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    const { onOpenChange } = renderDialog()

    const user = userEvent.setup()
    const cancelButton = screen.getByRole('button', { name: /취소/i })
    await user.click(cancelButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
