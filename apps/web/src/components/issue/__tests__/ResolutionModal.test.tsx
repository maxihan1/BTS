// 종료 전이 시 Resolution 선택 모달 단위 테스트 (FR-IS-07 Task B9)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ResolutionModal } from '../ResolutionModal'
import type { Resolution } from '@/api/resolutions'

// ─────────────────────────────────────────────────────────────────────────────
// useResolutions mock — 네트워크 없이 resolution 목록 제어
// ─────────────────────────────────────────────────────────────────────────────

const mockUseResolutions = vi.fn()

vi.mock('@/hooks/use-resolutions', () => ({
  useResolutions: () => mockUseResolutions(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// shadcn Select mock — jsdom에서 Radix Select Portal의 pointer-capture 미지원
// 문제를 우회한다. 네이티브 select처럼 동작하는 최소 mock으로 교체한다.
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
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const resolutionsFixture: Resolution[] = [
  {
    id: '00000000-0000-4000-8000-000000000001',
    key: 'fixed',
    name: 'Fixed',
    description: null,
    displayOrder: 1,
    isStandard: true,
  },
  {
    id: '00000000-0000-4000-8000-000000000002',
    key: 'wontfix',
    name: "Won't Fix",
    description: null,
    displayOrder: 2,
    isStandard: true,
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

interface RenderOptions {
  open?: boolean
  prefilledResolution?: Resolution | null
  onConfirm?: (resolutionId: string) => void
  onCancel?: () => void
}

function renderModal({
  open = true,
  prefilledResolution = null,
  onConfirm = vi.fn(),
  onCancel = vi.fn(),
}: RenderOptions = {}) {
  mockUseResolutions.mockReturnValue({
    data: resolutionsFixture,
    isLoading: false,
    isError: false,
  })

  const queryClient = makeQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <ResolutionModal
        open={open}
        prefilledResolution={prefilledResolution}
        onConfirm={onConfirm}
        onCancel={onCancel}
      />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ResolutionModal (FR-IS-07 B9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('B9-T1: open=true → 모달이 표시되어야 한다', () => {
    renderModal({ open: true })

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('종료 결의안 선택')).toBeInTheDocument()
  })

  it('흡수 후 우상단 X 닫기 버튼(Jira 시각 통일)이 렌더된다', () => {
    renderModal({ open: true })

    // ui/dialog 래퍼로 흡수되면 DialogContent가 우상단 X(sr-only "Close")를 강제 렌더한다.
    expect(screen.getByRole('button', { name: /close/i })).toBeTruthy()
  })

  it('B9-T2: open=false → 모달이 표시되지 않아야 한다', () => {
    renderModal({ open: false })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('B9-T3: resolution 미선택 시 확인 버튼이 비활성이어야 한다', () => {
    renderModal()

    const confirmButton = screen.getByRole('button', { name: '확인' })
    expect(confirmButton).toBeDisabled()
  })

  it('B9-T4: resolution 선택 후 확인 버튼이 활성화되어야 한다', async () => {
    const user = userEvent.setup()
    renderModal()

    const fixedOption = screen.getByRole('option', { name: 'Fixed' })
    await user.click(fixedOption)

    const confirmButton = screen.getByRole('button', { name: '확인' })
    expect(confirmButton).not.toBeDisabled()
  })

  it('B9-T5: resolution 선택 후 확인 → onConfirm이 해당 resolutionId로 호출되어야 한다', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    renderModal({ onConfirm })

    const fixedOption = screen.getByRole('option', { name: 'Fixed' })
    await user.click(fixedOption)

    const confirmButton = screen.getByRole('button', { name: '확인' })
    await user.click(confirmButton)

    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onConfirm).toHaveBeenCalledWith('00000000-0000-4000-8000-000000000001')
  })

  it('B9-T6: 취소 버튼 클릭 → onCancel이 호출되어야 한다', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    renderModal({ onCancel })

    const cancelButton = screen.getByRole('button', { name: '취소' })
    await user.click(cancelButton)

    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('B9-T7: prefilledResolution이 있으면 초기 선택값으로 pre-fill되어야 한다', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    renderModal({
      prefilledResolution: resolutionsFixture[0] ?? null,
      onConfirm,
    })

    // pre-fill 상태에서 확인 버튼이 바로 활성화되어야 한다
    const confirmButton = screen.getByRole('button', { name: '확인' })
    expect(confirmButton).not.toBeDisabled()

    await user.click(confirmButton)
    expect(onConfirm).toHaveBeenCalledWith('00000000-0000-4000-8000-000000000001')
  })

  it('B9-T8: resolution 목록이 드롭다운에 표시되어야 한다', async () => {
    renderModal()

    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'Fixed' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: "Won't Fix" })).toBeInTheDocument()
    })
  })
})
