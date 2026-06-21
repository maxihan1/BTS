// ResolutionPickerModal 단위 테스트 — open 시 목록 렌더, 미선택 확인 비활성, onConfirm/onCancel, 빈 목록
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// useResolutions 훅 mock — 테스트마다 목록을 교체할 수 있도록 변수로 관리
const mockResolutions = vi.hoisted(() => ({
  data: [
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
  ],
}))

vi.mock('@/hooks/use-resolutions', () => ({
  useResolutions: () => mockResolutions,
}))

// ResolutionPickerModal — RED: 아직 존재하지 않음
import { ResolutionPickerModal } from './ResolutionPickerModal'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

interface RenderModalOptions {
  open?: boolean
  onConfirm?: (id: string) => void
  onCancel?: () => void
}

function renderModal({
  open = true,
  onConfirm = vi.fn(),
  onCancel = vi.fn(),
}: RenderModalOptions = {}) {
  const wrapper = createWrapper()
  return render(
    createElement(ResolutionPickerModal, { open, onConfirm, onCancel }),
    { wrapper },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 열림 상태 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('ResolutionPickerModal — S1 열림 상태 렌더', () => {
  it('S1a: open=true이면 모달이 화면에 표시된다', () => {
    renderModal({ open: true })
    // 다이얼로그 타이틀 또는 결의안 레이블 존재 확인
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('S1b: 결의안 목록이 Select에 렌더된다', async () => {
    renderModal({ open: true })
    // Select 트리거를 클릭해 옵션 확인
    const trigger = screen.getByRole('combobox')
    await userEvent.click(trigger)
    await waitFor(() => {
      expect(screen.getByText('Fixed')).toBeInTheDocument()
      expect(screen.getByText("Won't Fix")).toBeInTheDocument()
    })
  })

  it('S1c: open=false이면 모달이 화면에 없다', () => {
    renderModal({ open: false })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 확인 버튼 활성/비활성
// ─────────────────────────────────────────────────────────────────────────────

describe('ResolutionPickerModal — S2 확인 버튼 활성/비활성', () => {
  it('S2a: 결의안 미선택 시 확인 버튼이 비활성이다', () => {
    renderModal({ open: true })
    const confirmBtn = screen.getByRole('button', { name: '확인' })
    expect(confirmBtn).toBeDisabled()
  })

  it('S2b: 결의안 선택 후 확인 버튼이 활성화된다', async () => {
    renderModal({ open: true })
    const trigger = screen.getByRole('combobox')
    await userEvent.click(trigger)
    await waitFor(() => screen.getByText('Fixed'))
    await userEvent.click(screen.getByText('Fixed'))
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '확인' })).not.toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 확인 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('ResolutionPickerModal — S3 확인 콜백', () => {
  it('S3a: 결의안 선택 후 확인 클릭 시 onConfirm(resolutionId)이 호출된다', async () => {
    const onConfirm = vi.fn()
    renderModal({ open: true, onConfirm })

    const trigger = screen.getByRole('combobox')
    await userEvent.click(trigger)
    await waitFor(() => screen.getByText('Fixed'))
    await userEvent.click(screen.getByText('Fixed'))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '확인' })).not.toBeDisabled()
    })
    await userEvent.click(screen.getByRole('button', { name: '확인' }))

    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onConfirm).toHaveBeenCalledWith('00000000-0000-4000-8000-000000000001')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 취소 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('ResolutionPickerModal — S4 취소 콜백', () => {
  it('S4a: 취소 버튼 클릭 시 onCancel이 호출된다', async () => {
    const onCancel = vi.fn()
    renderModal({ open: true, onCancel })
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onCancel).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 빈 목록(G3) 안내
// ─────────────────────────────────────────────────────────────────────────────

describe('ResolutionPickerModal — S5 빈 목록 안내', () => {
  beforeEach(() => {
    // 빈 목록으로 교체
    mockResolutions.data = []
  })

  it('S5a: resolutions가 0개이면 안내 문구를 표시한다', () => {
    renderModal({ open: true })
    expect(screen.getByText('설정된 해결 방안이 없습니다')).toBeInTheDocument()
  })

  it('S5b: 빈 목록이면 확인 버튼이 비활성이다', () => {
    renderModal({ open: true })
    expect(screen.getByRole('button', { name: '확인' })).toBeDisabled()
  })
})
