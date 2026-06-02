// 일괄 상태 전이 Dialog 컴포넌트 단위 테스트 — 교집합 전이 선택 + submit 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BulkTransitionDialog } from './BulkTransitionDialog'

// ─────────────────────────────────────────────────────────────────────────────
// shadcn Select mock — jsdom에서 Radix Select Portal의 pointer-capture 미지원
// 문제를 우회한다. 네이티브 <select>로 교체해 option 선택을 DOM-level로 처리한다.
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const React = (await vi.importActual<typeof import('react')>('react'))

  interface SelectContextValue {
    value: string
    onValueChange: (v: string) => void
  }
  const SelectContext = React.createContext<SelectContextValue>({ value: '', onValueChange: () => undefined })

  return {
    Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) =>
      React.createElement(SelectContext.Provider, { value: { value, onValueChange } }, children),
    SelectTrigger: ({ children, 'aria-label': ariaLabel, id }: { children: React.ReactNode; 'aria-label'?: string; id?: string }) => {
      const ctx = React.useContext(SelectContext)
      return React.createElement('button', { role: 'combobox', 'aria-label': ariaLabel, id, onClick: () => ctx.onValueChange('__open__') }, children)
    },
    SelectValue: ({ placeholder }: { placeholder?: string }) => {
      const ctx = React.useContext(SelectContext)
      return React.createElement('span', null, ctx.value || placeholder || '')
    },
    SelectContent: ({ children }: { children: React.ReactNode }) => {
      const ctx = React.useContext(SelectContext)
      return React.createElement('ul', { role: 'listbox' },
        React.Children.map(children, (child) => {
          if (!React.isValidElement(child)) return child
          const props = child.props as unknown as { value?: string; children?: React.ReactNode }
          return React.createElement('li', {
            role: 'option',
            key: props.value,
            onClick: () => { if (props.value !== undefined) ctx.onValueChange(props.value) },
            'data-value': props.value,
          }, props.children)
        }),
      )
    },
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) =>
      React.createElement('span', { 'data-value': value }, children),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchIssueTransitions mock
// ─────────────────────────────────────────────────────────────────────────────

const mockFetchIssueTransitions = vi.fn()

vi.mock('@/api/issues', () => ({
  fetchIssueTransitions: (key: string) => mockFetchIssueTransitions(key),
}))

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
      <BulkTransitionDialog
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

describe('BulkTransitionDialog', () => {
  beforeEach(() => {
    mockFetchIssueTransitions.mockReset()
    mockMutateAsync.mockReset()
  })

  // (a) 교집합 전이가 드롭다운에 노출된다
  it('(a) 각 issueKey별로 fetchIssueTransitions를 호출하고 교집합 전이를 드롭다운에 노출한다', async () => {
    // PROJ-1: IN_PROGRESS, DONE 전이 가능
    // PROJ-2: IN_PROGRESS, DONE 전이 가능
    // 교집합: IN_PROGRESS, DONE
    mockFetchIssueTransitions.mockImplementation((key: string) => {
      if (key === 'PROJ-1') {
        return Promise.resolve([
          { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
          { key: 't2', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE' },
        ])
      }
      if (key === 'PROJ-2') {
        return Promise.resolve([
          { key: 't3', name: '진행 중', fromStateKey: 'REVIEW', toStateKey: 'IN_PROGRESS' },
          { key: 't4', name: '완료', fromStateKey: 'REVIEW', toStateKey: 'DONE' },
        ])
      }
      return Promise.resolve([])
    })

    renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    // fetchIssueTransitions가 각 이슈 키에 대해 호출되어야 한다
    await waitFor(() => {
      expect(mockFetchIssueTransitions).toHaveBeenCalledWith('PROJ-1')
      expect(mockFetchIssueTransitions).toHaveBeenCalledWith('PROJ-2')
    })

    // 교집합 전이 드롭다운이 노출된다
    const select = await screen.findByRole('combobox', { name: /전이 상태/i })
    expect(select).toBeInTheDocument()

    // 교집합 옵션 확인 (mock SelectContent는 항상 DOM에 노출)
    expect(await screen.findByRole('option', { name: /진행 중/i })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /완료/i })).toBeInTheDocument()
  })

  // (b) 교집합 0건이면 안내 메시지 + 적용 비활성
  it('(b) 교집합이 0건이면 안내 메시지가 표시되고 적용 버튼이 비활성화된다', async () => {
    // PROJ-1: IN_PROGRESS만 가능
    // PROJ-2: DONE만 가능
    // 교집합: 없음
    mockFetchIssueTransitions.mockImplementation((key: string) => {
      if (key === 'PROJ-1') {
        return Promise.resolve([
          { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
        ])
      }
      if (key === 'PROJ-2') {
        return Promise.resolve([
          { key: 't2', name: '완료', fromStateKey: 'REVIEW', toStateKey: 'DONE' },
        ])
      }
      return Promise.resolve([])
    })

    renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    await screen.findByText(/선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다/i)

    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).toBeDisabled()
  })

  // (c) 일부 조회 실패 시 경고 표시 + 성공분만으로 교집합 계산 + 적용 시 전체 issueKeys 전송
  it('(c) 일부 조회 실패 시 경고를 표시하고 성공분만으로 교집합을 계산하며 적용 시 전체 issueKeys를 전송한다', async () => {
    const bulkOperationId = 'aabbccdd-0000-0000-0000-000000000002'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 2 })

    // PROJ-1: 조회 성공
    // PROJ-2: 조회 실패
    // 성공분(PROJ-1)만으로 교집합 계산 → PROJ-1의 전이 목록 그대로 노출
    mockFetchIssueTransitions.mockImplementation((key: string) => {
      if (key === 'PROJ-1') {
        return Promise.resolve([
          { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
        ])
      }
      if (key === 'PROJ-2') {
        return Promise.reject(new Error('fetch failed'))
      }
      return Promise.resolve([])
    })

    const { onSubmitted, onOpenChange } = renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    // 경고 메시지가 표시된다
    await screen.findByText(/일부 이슈의 전이 정보를 불러오지 못했습니다/i)

    // 드롭다운에 성공분 교집합 옵션이 노출된다
    const select = screen.getByRole('combobox', { name: /전이 상태/i })
    const user = userEvent.setup()
    await user.click(select)

    const option = await screen.findByRole('option', { name: /진행 중/i })
    await user.click(option)

    // 적용 버튼 클릭
    const applyButton = screen.getByRole('button', { name: /적용/i })
    await user.click(applyButton)

    // 적용 시 issueKeys는 전체(PROJ-1, PROJ-2) 전송
    await waitFor(() => {
      expect(mockMutateAsync).toHaveBeenCalledWith({
        operationType: 'BULK_TRANSITION',
        issueKeys: ['PROJ-1', 'PROJ-2'],
        editPayload: null,
        transitionPayload: { toStateKey: 'IN_PROGRESS' },
      })
    })

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalledWith(bulkOperationId)
    })

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  // (d) 교집합 선택 후 적용 → 올바른 payload로 mutateAsync 호출 + 콜백 실행
  it('(d) 교집합 전이 선택 후 적용 시 올바른 payload로 mutateAsync를 호출하고 콜백을 실행한다', async () => {
    const bulkOperationId = 'aabbccdd-0000-0000-0000-000000000003'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 3 })

    mockFetchIssueTransitions.mockImplementation(() =>
      Promise.resolve([
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
        { key: 't2', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE' },
      ]),
    )

    const { onSubmitted, onOpenChange } = renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2', 'PROJ-3'] })

    // 드롭다운에서 '완료' 선택
    const select = await screen.findByRole('combobox', { name: /전이 상태/i })
    const user = userEvent.setup()
    await user.click(select)

    const option = await screen.findByRole('option', { name: /완료/i })
    await user.click(option)

    // 적용
    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).not.toBeDisabled()
    await user.click(applyButton)

    await waitFor(() => {
      expect(mockMutateAsync).toHaveBeenCalledWith({
        operationType: 'BULK_TRANSITION',
        issueKeys: ['PROJ-1', 'PROJ-2', 'PROJ-3'],
        editPayload: null,
        transitionPayload: { toStateKey: 'DONE' },
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
    mockFetchIssueTransitions.mockResolvedValue([])

    const { onOpenChange } = renderDialog()

    const user = userEvent.setup()
    const cancelButton = await screen.findByRole('button', { name: /취소/i })
    await user.click(cancelButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
