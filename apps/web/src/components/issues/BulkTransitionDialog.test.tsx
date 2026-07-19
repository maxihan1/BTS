// 일괄 상태 전이 Dialog 컴포넌트 단위 테스트 — 단일 bulk API 기반 전이 선택 + submit 검증
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
// fetchBulkAvailableTransitions mock
// ─────────────────────────────────────────────────────────────────────────────

const mockFetchBulkAvailableTransitions = vi.fn()

vi.mock('@/api/issues', () => ({
  fetchBulkAvailableTransitions: (issueKeys: string[]) => mockFetchBulkAvailableTransitions(issueKeys),
}))

// ─────────────────────────────────────────────────────────────────────────────
// useResolutions mock — resolution 드롭다운 테스트용 (B14)
// ─────────────────────────────────────────────────────────────────────────────

const mockResolutions = [
  { id: '00000000-0000-4000-8000-000000000001', key: 'fixed', name: 'Fixed', description: null, displayOrder: 1, isStandard: true },
  { id: '00000000-0000-4000-8000-000000000002', key: 'wontfix', name: "Won't Fix", description: null, displayOrder: 2, isStandard: true },
]

vi.mock('@/hooks/use-resolutions', () => ({
  useResolutions: () => ({ data: mockResolutions, isLoading: false }),
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
    mockFetchBulkAvailableTransitions.mockReset()
    mockMutateAsync.mockReset()
  })

  // (a) 서버가 반환한 공통 전이가 드롭다운에 노출된다
  it('(a) fetchBulkAvailableTransitions를 단일 호출하고 반환된 transitions를 드롭다운에 노출한다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
        { key: 't2', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE' },
      ],
      unresolvedIssueKeys: [],
    })

    renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    // fetchBulkAvailableTransitions가 issueKeys 배열로 단일 호출되어야 한다
    await waitFor(() => {
      expect(mockFetchBulkAvailableTransitions).toHaveBeenCalledWith(['PROJ-1', 'PROJ-2'])
    })

    // 전이 드롭다운이 노출된다
    const select = await screen.findByRole('combobox', { name: /전이 상태/i })
    expect(select).toBeInTheDocument()

    // 서버가 반환한 옵션 확인 (mock SelectContent는 항상 DOM에 노출)
    expect(await screen.findByRole('option', { name: /진행 중/i })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: /완료/i })).toBeInTheDocument()
  })

  // (b) transitions 0건 + unresolvedIssueKeys 0건이면 교집합 없음 안내 + 적용 비활성
  it('(b) transitions가 0건이고 unresolvedIssueKeys가 없으면 안내 메시지가 표시되고 적용 버튼이 비활성화된다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [],
      unresolvedIssueKeys: [],
    })

    renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    await screen.findByText(/선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다/i)

    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).toBeDisabled()
  })

  // (c) unresolvedIssueKeys > 0 이면 경고 표시 + 나머지 transitions로 드롭다운 구성 + 적용 시 전체 issueKeys 전송
  it('(c) unresolvedIssueKeys가 있으면 경고를 표시하고 나머지 transitions를 드롭다운에 노출하며 적용 시 전체 issueKeys를 전송한다', async () => {
    const bulkOperationId = 'aabbccdd-4000-4000-8000-000000000002'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 2 })

    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
      ],
      unresolvedIssueKeys: ['PROJ-2'],
    })

    const { onSubmitted, onOpenChange } = renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    // 경고 메시지가 표시된다
    await screen.findByText(/일부 이슈의 전이 정보를 불러오지 못했습니다/i)

    // 드롭다운에 transitions 옵션이 노출된다
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

  // (d) 전이 선택 후 적용 → 올바른 payload로 mutateAsync 호출 + 콜백 실행
  it('(d) 전이 선택 후 적용 시 올바른 payload로 mutateAsync를 호출하고 콜백을 실행한다', async () => {
    const bulkOperationId = 'aabbccdd-4000-4000-8000-000000000003'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 3 })

    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS' },
        { key: 't2', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE' },
      ],
      unresolvedIssueKeys: [],
    })

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

  // (e) transitions 0건 + unresolvedIssueKeys === issueKeys 전량이면 에러 안내 + Select 숨김 + 적용 비활성
  it('(e) transitions가 0건이고 unresolvedIssueKeys가 issueKeys 전체이면 에러 안내가 표시되고 적용 버튼이 비활성화된다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [],
      unresolvedIssueKeys: ['PROJ-1', 'PROJ-2'],
    })

    renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })

    // 에러 안내 메시지가 표시되어야 한다
    await screen.findByText(/전이 정보를 불러오지 못했습니다/i)

    // Select(combobox)가 DOM에 없어야 한다
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()

    // 적용 버튼이 비활성화되어야 한다
    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).toBeDisabled()
  })

  // (f) open 토글 시 stale fetch 결과가 새 상태를 덮어쓰지 않는다 (cleanup 플래그)
  it('(f) open이 false → true로 변경될 때 stale fetch 결과가 새 상태를 덮어쓰지 않는다', async () => {
    // 첫 open: 느린 resolve (stale)
    // 두 번째 open: 즉시 resolve (새 결과)
    let resolveFirst: (value: { transitions: unknown[]; unresolvedIssueKeys: string[] }) => void = () => undefined
    const firstCall = new Promise<{ transitions: unknown[]; unresolvedIssueKeys: string[] }>((resolve) => { resolveFirst = resolve })

    let callIndex = 0
    mockFetchBulkAvailableTransitions.mockImplementation(() => {
      callIndex++
      if (callIndex === 1) return firstCall
      // 두 번째 열림에서 즉시 다른 결과 반환
      return Promise.resolve({
        transitions: [
          { key: 't-new', name: '새 상태', fromStateKey: 'TODO', toStateKey: 'NEW_STATE' },
        ],
        unresolvedIssueKeys: [],
      })
    })

    const { rerender } = render(
      <QueryClientProvider client={makeQueryClient()}>
        <BulkTransitionDialog
          issueKeys={['PROJ-1']}
          open={true}
          onOpenChange={vi.fn()}
          onSubmitted={vi.fn()}
        />
      </QueryClientProvider>,
    )

    // close → open (새 issueKeys로 재오픈 시뮬레이션)
    rerender(
      <QueryClientProvider client={makeQueryClient()}>
        <BulkTransitionDialog
          issueKeys={['PROJ-2']}
          open={false}
          onOpenChange={vi.fn()}
          onSubmitted={vi.fn()}
        />
      </QueryClientProvider>,
    )
    rerender(
      <QueryClientProvider client={makeQueryClient()}>
        <BulkTransitionDialog
          issueKeys={['PROJ-2']}
          open={true}
          onOpenChange={vi.fn()}
          onSubmitted={vi.fn()}
        />
      </QueryClientProvider>,
    )

    // 두 번째 open의 결과(새 상태)가 드롭다운에 나타나야 한다
    await screen.findByRole('option', { name: /새 상태/i })

    // 이제 첫 번째 stale 결과를 늦게 resolve — 새 상태가 덮이지 않아야 한다
    resolveFirst({
      transitions: [{ key: 't-stale', name: '낡은 상태', fromStateKey: 'TODO', toStateKey: 'STALE_STATE' }],
      unresolvedIssueKeys: [],
    })

    // 잠시 대기 후 stale 결과가 반영되지 않아야 한다
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByRole('option', { name: /낡은 상태/i })).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: /새 상태/i })).toBeInTheDocument()
  })

  // 취소 버튼 클릭 시 onOpenChange(false) 호출
  it('취소 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValue({
      transitions: [],
      unresolvedIssueKeys: [],
    })

    const { onOpenChange } = renderDialog()

    const user = userEvent.setup()
    const cancelButton = await screen.findByRole('button', { name: /취소/i })
    await user.click(cancelButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  // X 닫기 버튼(shadcn DialogContent 기본 제공) 클릭 시 onOpenChange(false) 호출
  it('X 닫기 버튼 클릭 시 onOpenChange(false)가 호출된다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValue({
      transitions: [],
      unresolvedIssueKeys: [],
    })

    const { onOpenChange } = renderDialog()

    const user = userEvent.setup()
    const closeButton = await screen.findByRole('button', { name: /close/i })
    await user.click(closeButton)

    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  // ─── B14: DONE 전이 시 resolution 드롭다운 ────────────────────────────────

  // (g) toCategory=DONE 전이 선택 시 resolution 드롭다운 표시 + 미선택 시 적용 비활성
  it('(g) toCategory=DONE 전이를 선택하면 resolution 드롭다운이 나타나고 미선택 시 적용 버튼이 비활성이다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS', toCategory: 'IN_PROGRESS' },
        { key: 't2', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE', toCategory: 'DONE' },
      ],
      unresolvedIssueKeys: [],
    })

    renderDialog()

    // '완료' 전이 선택
    const select = await screen.findByRole('combobox', { name: /전이 상태/i })
    const user = userEvent.setup()
    await user.click(select)
    const doneOption = await screen.findByRole('option', { name: /완료/i })
    await user.click(doneOption)

    // resolution 드롭다운이 나타나야 한다
    const resolutionSelect = await screen.findByRole('combobox', { name: /결의안/i })
    expect(resolutionSelect).toBeInTheDocument()

    // resolution 미선택 상태에서 적용 버튼은 비활성
    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).toBeDisabled()
  })

  // (h) toCategory=DONE 전이 + resolution 선택 시 resolutionId가 payload에 포함된다
  it('(h) DONE 전이에서 resolution을 선택 후 적용하면 resolutionId가 transitionPayload에 포함된다', async () => {
    const bulkOperationId = 'aabbccdd-4000-4000-8000-000000000010'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 2 })

    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE', toCategory: 'DONE' },
      ],
      unresolvedIssueKeys: [],
    })

    const { onSubmitted, onOpenChange } = renderDialog({ issueKeys: ['PROJ-1', 'PROJ-2'] })
    const user = userEvent.setup()

    // 전이 드롭다운에서 '완료' 선택
    const transitionSelect = await screen.findByRole('combobox', { name: /전이 상태/i })
    await user.click(transitionSelect)
    const doneOption = await screen.findByRole('option', { name: /완료/i })
    await user.click(doneOption)

    // resolution 드롭다운이 나타난 뒤 'Fixed' 선택
    const resolutionSelect = await screen.findByRole('combobox', { name: /결의안/i })
    await user.click(resolutionSelect)
    const fixedOption = await screen.findByRole('option', { name: /Fixed/i })
    await user.click(fixedOption)

    // 적용 버튼이 활성화된 뒤 클릭
    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).not.toBeDisabled()
    await user.click(applyButton)

    await waitFor(() => {
      expect(mockMutateAsync).toHaveBeenCalledWith({
        operationType: 'BULK_TRANSITION',
        issueKeys: ['PROJ-1', 'PROJ-2'],
        editPayload: null,
        transitionPayload: {
          toStateKey: 'DONE',
          resolutionId: '00000000-0000-4000-8000-000000000001',
        },
      })
    })

    await waitFor(() => {
      expect(onSubmitted).toHaveBeenCalledWith(bulkOperationId)
    })

    await waitFor(() => {
      expect(onOpenChange).toHaveBeenCalledWith(false)
    })
  })

  // (i) 비DONE 전이 선택 시 resolution 드롭다운이 없고 바로 적용 가능하다
  it('(i) 비DONE 전이를 선택하면 resolution 드롭다운이 없고 즉시 적용 가능하다', async () => {
    const bulkOperationId = 'aabbccdd-4000-4000-8000-000000000011'
    mockMutateAsync.mockResolvedValueOnce({ bulkOperationId, status: 'PENDING', totalCount: 1 })

    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS', toCategory: 'IN_PROGRESS' },
      ],
      unresolvedIssueKeys: [],
    })

    renderDialog()
    const user = userEvent.setup()

    // '진행 중' 전이 선택
    const transitionSelect = await screen.findByRole('combobox', { name: /전이 상태/i })
    await user.click(transitionSelect)
    const inProgressOption = await screen.findByRole('option', { name: /진행 중/i })
    await user.click(inProgressOption)

    // resolution 드롭다운이 없어야 한다
    expect(screen.queryByRole('combobox', { name: /결의안/i })).not.toBeInTheDocument()

    // 적용 버튼이 활성화된다
    const applyButton = screen.getByRole('button', { name: /적용/i })
    expect(applyButton).not.toBeDisabled()
    await user.click(applyButton)

    await waitFor(() => {
      expect(mockMutateAsync).toHaveBeenCalledWith({
        operationType: 'BULK_TRANSITION',
        issueKeys: ['PROJ-1', 'PROJ-2'],
        editPayload: null,
        transitionPayload: { toStateKey: 'IN_PROGRESS' },
      })
    })
  })

  // (j) DONE 전이 → 다른 비DONE 전이로 변경 시 resolution 드롭다운이 사라진다
  it('(j) DONE 전이 선택 후 비DONE 전이로 변경하면 resolution 드롭다운이 사라진다', async () => {
    mockFetchBulkAvailableTransitions.mockResolvedValueOnce({
      transitions: [
        { key: 't1', name: '진행 중', fromStateKey: 'TODO', toStateKey: 'IN_PROGRESS', toCategory: 'IN_PROGRESS' },
        { key: 't2', name: '완료', fromStateKey: 'TODO', toStateKey: 'DONE', toCategory: 'DONE' },
      ],
      unresolvedIssueKeys: [],
    })

    renderDialog()
    const user = userEvent.setup()

    // '완료' 선택 → resolution 드롭다운 나타남
    const transitionSelect = await screen.findByRole('combobox', { name: /전이 상태/i })
    await user.click(transitionSelect)
    await user.click(await screen.findByRole('option', { name: /완료/i }))
    expect(await screen.findByRole('combobox', { name: /결의안/i })).toBeInTheDocument()

    // '진행 중'으로 변경 → resolution 드롭다운 사라짐
    await user.click(screen.getByRole('combobox', { name: /전이 상태/i }))
    await user.click(await screen.findByRole('option', { name: /진행 중/i }))
    expect(screen.queryByRole('combobox', { name: /결의안/i })).not.toBeInTheDocument()
  })
})
