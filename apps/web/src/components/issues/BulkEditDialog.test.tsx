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

  it('흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다', () => {
    renderDialog()

    // ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
    expect(screen.getByRole('button', { name: /close/i })).toBeTruthy()
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

    // priority select에서 1(Highest) 선택
    const prioritySelect = screen.getByRole('combobox', { name: /priority/i })
    await user.selectOptions(prioritySelect, '1')

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

// ─────────────────────────────────────────────────────────────────────────────
// 실패 경로 — mutateAsync 거부가 전역으로 새지 않는다
//
// `handleApply` 는 `onClick={() => { void handleApply() }}` 로 띄운다. `mutateAsync` 는
// `onError` 를 부른 뒤에도 **reject 를 re-throw** 하므로, catch 가 없으면 그 rejection 이
// 전역으로 새어 **프로덕션에서도 unhandled rejection** 이 된다.
//
// ★「사용자에게 피드백이 없다」는 거짓이다 — `use-bulk-operation.ts:58` 의 `onError` 가
//   이미 `toast.error` 를 띄운다. 그래서 봉합은 **빈 catch 만** 넣는다.
//   토스트를 새로 추가하면 같은 실패 1회에 토스트가 2건 뜬다.
//
// ★이 테스트가 잡는 방식. 아래 단언들은 봉합 전후 모두 통과할 수 있다(양쪽 다 성공 경로를
//   안 탄다). 결정적인 것은 **실제 거부를 흘려보낸다**는 것이다 — catch 가 없으면 vitest 가
//   `Errors 1 error` 를 보고하고 **스위트 종료 코드가 1** 이 된다. `src/test/setup.ts` 가
//   `unhandledRejection` 리스너 추가를 금지하는 이유가 바로 이 신호를 살려 두기 위함이다.
//   `CloneIssueDialog.test.tsx` 의 「에러 발생 시 toast.error가 호출된다」와 같은 형태다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BulkEditDialog — 실패 경로', () => {
  it('mutateAsync 가 거부돼도 콜백을 부르지 않고 다이얼로그를 닫지 않는다 (rejection 을 삼킨다)', async () => {
    mockMutateAsync.mockRejectedValueOnce(new Error('403 ACCESS_DENIED'))

    const { onSubmitted, onOpenChange } = renderDialog({ issueKeys: ['PROJ-1'] })
    const user = userEvent.setup()

    await user.selectOptions(screen.getByRole('combobox', { name: /priority/i }), '1')
    await user.click(screen.getByRole('button', { name: /적용/i }))

    await waitFor(() => {
      expect(mockMutateAsync).toHaveBeenCalled()
    })
    expect(onSubmitted).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })
})
