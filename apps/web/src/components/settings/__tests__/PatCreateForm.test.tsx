// PatCreateForm 컴포넌트 단위 테스트 — name·scope 체크박스·만료 select·검증·경고배너·에러배너 (FR-API-04 Task 7)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode, ReactElement } from 'react'
import { PAT_SCOPE_CATALOG } from '@/api/pats'
import { PatCreateForm } from '../PatCreateForm'

// ─────────────────────────────────────────────────────────────────────────────
// Radix/shadcn Select → 네이티브 <select> mock
// jsdom에서 hasPointerCapture 제약으로 Radix Select 클릭 인터랙션이 불가하므로
// 네이티브 select 엘리먼트로 대체한다 (ResolutionPickerModal.test.tsx 동형 선례).
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const { createElement: ce, useRef, Children } = await vi.importActual<typeof import('react')>('react')

  function Select({
    children,
    onValueChange,
    value,
    disabled,
  }: {
    children: ReactNode
    onValueChange?: (v: string) => void
    value?: string
    disabled?: boolean
  }) {
    const triggerLabel = useRef<string>('')
    const contentOptions = useRef<ReactNode>(null)

    Children.forEach(children, (child) => {
      if (child !== null && typeof child === 'object' && 'props' in (child as object)) {
        const el = child as ReactElement<{ 'aria-label'?: string; children?: ReactNode }>
        if (el.props['aria-label']) {
          triggerLabel.current = el.props['aria-label']
        }
        if (el.props.children) {
          contentOptions.current = el.props.children
        }
      }
    })

    return ce(
      'select',
      {
        'aria-label': triggerLabel.current,
        value: value ?? '',
        disabled,
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (onValueChange) onValueChange(e.target.value)
        },
      },
      contentOptions.current,
    )
  }

  function SelectTrigger({
    children,
    'aria-label': ariaLabel,
  }: {
    children?: ReactNode
    'aria-label'?: string
    id?: string
    className?: string
  }) {
    return ce('span', { 'aria-label': ariaLabel }, children)
  }

  function SelectValue() {
    return null
  }

  function SelectContent({ children }: { children: ReactNode }) {
    return ce('span', {}, children)
  }

  function SelectItem({ value, children }: { value: string; children: ReactNode }) {
    return ce('option', { value }, children)
  }

  return { Select, SelectTrigger, SelectValue, SelectContent, SelectItem }
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderForm(overrides: { submitError?: string; isSubmitting?: boolean } = {}) {
  const onSubmit = vi.fn()
  const user = userEvent.setup({ delay: null })
  const result = render(
    <PatCreateForm
      onSubmit={onSubmit}
      submitError={overrides.submitError}
      isSubmitting={overrides.isSubmitting ?? false}
    />,
  )
  return { user, onSubmit, ...result }
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('PatCreateForm — 렌더', () => {
  it('이름 입력·scope 체크박스 5종·만료 select가 렌더된다', () => {
    renderForm()
    expect(screen.getByLabelText('이름')).toBeInTheDocument()
    PAT_SCOPE_CATALOG.forEach((scope) => {
      expect(screen.getByRole('checkbox', { name: scope })).toBeInTheDocument()
    })
    expect(screen.getByLabelText('만료 기간')).toBeInTheDocument()
  })

  it('만료 select에 30/90/180/365일 프리셋 옵션만 정확히 있다', () => {
    renderForm()
    const select = screen.getByLabelText('만료 기간') as HTMLSelectElement
    const optionLabels = Array.from(select.options).map((o) => o.textContent)
    expect(optionLabels).toEqual(['30일', '90일', '180일', '365일'])
  })

  it('scope 미강제 경고 배너가 상시 노출된다', () => {
    renderForm()
    expect(screen.getByText(/scope는 아직 강제되지 않/)).toBeInTheDocument()
    expect(screen.getByText(/계정 전체 권한/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 유효성 — name 빈값
// ─────────────────────────────────────────────────────────────────────────────

describe('PatCreateForm — 유효성', () => {
  it('이름이 빈값이면 제출 버튼이 비활성화된다', () => {
    renderForm()
    expect(screen.getByRole('button', { name: '발급' })).toBeDisabled()
  })

  it('이름을 입력하면 제출 버튼이 활성화된다', async () => {
    const { user } = renderForm()
    await user.type(screen.getByLabelText('이름'), 'CI token')
    expect(screen.getByRole('button', { name: '발급' })).not.toBeDisabled()
  })

  it('공백만 입력하면 제출 버튼이 다시 비활성화된다', async () => {
    const { user } = renderForm()
    await user.type(screen.getByLabelText('이름'), '   ')
    expect(screen.getByRole('button', { name: '발급' })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 제출
// ─────────────────────────────────────────────────────────────────────────────

describe('PatCreateForm — 제출', () => {
  it('name·scope·expiresInDays(기본 30)를 담아 onSubmit을 호출한다', async () => {
    const { user, onSubmit } = renderForm()
    await user.type(screen.getByLabelText('이름'), 'CI deploy token')
    await user.click(screen.getByRole('checkbox', { name: 'read:issues' }))
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(onSubmit).toHaveBeenCalledWith({
      name: 'CI deploy token',
      scopes: ['read:issues'],
      expiresInDays: 30,
    })
  })

  it('만료 프리셋을 변경하면 해당 값으로 제출된다', async () => {
    const { user, onSubmit } = renderForm()
    await user.type(screen.getByLabelText('이름'), 'CI token')
    await user.selectOptions(screen.getByLabelText('만료 기간'), '90')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ expiresInDays: 90 }),
    )
  })

  it('scope를 아무것도 선택하지 않아도 제출할 수 있다(서버가 검증)', async () => {
    const { user, onSubmit } = renderForm()
    await user.type(screen.getByLabelText('이름'), 'no scope token')
    await user.click(screen.getByRole('button', { name: '발급' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ scopes: [] }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 서버 에러 배너
// ─────────────────────────────────────────────────────────────────────────────

describe('PatCreateForm — 서버 에러', () => {
  it('submitError가 있으면 에러 배너를 표시한다', () => {
    renderForm({ submitError: '이름은 공백일 수 없습니다.' })
    expect(screen.getByText('이름은 공백일 수 없습니다.')).toBeInTheDocument()
  })

  it('submitError가 없으면 에러 배너를 표시하지 않는다', () => {
    renderForm()
    expect(screen.queryByText('이름은 공백일 수 없습니다.')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 제출 중 비활성화
// ─────────────────────────────────────────────────────────────────────────────

describe('PatCreateForm — 제출 중', () => {
  it('isSubmitting=true로 전환되면 제출 버튼이 비활성화된다', async () => {
    const onSubmit = vi.fn()
    const user = userEvent.setup({ delay: null })
    const { rerender } = render(<PatCreateForm onSubmit={onSubmit} isSubmitting={false} />)

    await user.type(screen.getByLabelText('이름'), 'CI token')
    expect(screen.getByRole('button', { name: '발급' })).not.toBeDisabled()

    rerender(<PatCreateForm onSubmit={onSubmit} isSubmitting />)
    expect(screen.getByRole('button', { name: '발급' })).toBeDisabled()
  })

  it('isSubmitting=true면 이름 입력도 비활성화된다', () => {
    renderForm({ isSubmitting: true })
    expect(screen.getByLabelText('이름')).toBeDisabled()
  })
})
