// GitWebhookRegisterDialog 단위 테스트 — secret trim 금지(BLOCKER-0) + provider Select 판별자 (FR-AT-07 PR-D Task 5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode, ReactElement } from 'react'
import { GitWebhookRegisterDialog } from '../GitWebhookRegisterDialog'
import type { GitProvider } from '@/api/automation-git-webhooks.types'

// ─────────────────────────────────────────────────────────────────────────────
// Radix/shadcn Select → 네이티브 <select> mock
// jsdom에서 hasPointerCapture 제약으로 Radix Select 클릭 인터랙션이 불가하므로
// 네이티브 select 엘리먼트로 대체한다 (PatCreateForm.test.tsx 동형 선례, 정본 템플릿).
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

interface RenderOverrides {
  isPending?: boolean
  submitError?: string
  existingProviders?: GitProvider[]
  listUnavailable?: boolean
}

function renderDialog(overrides: RenderOverrides = {}) {
  const onSubmit = vi.fn()
  const onOpenChange = vi.fn()
  const user = userEvent.setup({ delay: null })
  const result = render(
    <GitWebhookRegisterDialog
      open
      onOpenChange={onOpenChange}
      onSubmit={onSubmit}
      isPending={overrides.isPending ?? false}
      submitError={overrides.submitError}
      existingProviders={overrides.existingProviders ?? []}
      listUnavailable={overrides.listUnavailable ?? false}
    />,
  )
  return { user, onSubmit, onOpenChange, ...result }
}

const SECRET_INVALID_TEXT = 'secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다.'

// ─────────────────────────────────────────────────────────────────────────────
// ★★ BLOCKER-0 — secret trim 절대 금지. 이 블록이 사고를 잡는 유일한 단위 테스트다.
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookRegisterDialog — secret 원문 보존(BLOCKER-0)', () => {
  it('secret 앞뒤 공백을 그대로 onSubmit 에 넘긴다', async () => {
    const { user, onSubmit } = renderDialog()
    await user.type(screen.getByLabelText('Secret'), '  abcdefghijklmnop  ')
    await user.click(screen.getByTestId('git-webhook-register-submit'))
    expect(onSubmit).toHaveBeenCalledWith({ provider: 'GITHUB', secret: '  abcdefghijklmnop  ' })
  })

  it('공백 15자 + 문자 1자(원문 16자)는 클라 검증을 통과해 onSubmit 이 호출된다', async () => {
    const { user, onSubmit } = renderDialog()
    const secretValue = `${' '.repeat(15)}a`
    await user.type(screen.getByLabelText('Secret'), secretValue)
    await user.click(screen.getByTestId('git-webhook-register-submit'))
    expect(onSubmit).toHaveBeenCalledWith({ provider: 'GITHUB', secret: secretValue })
  })

  it('15자 이하는 요청 없이 인라인 에러 — 서버 고정 문구를 미러', async () => {
    const { user, onSubmit } = renderDialog()
    await user.type(screen.getByLabelText('Secret'), 'a'.repeat(15))
    await user.click(screen.getByTestId('git-webhook-register-submit'))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(SECRET_INVALID_TEXT)).toBeInTheDocument()
  })

  it('4096자 초과는 원문 길이 기준으로 차단한다', async () => {
    const { user, onSubmit } = renderDialog()
    const input = screen.getByLabelText('Secret')
    // 4097자를 user.type으로 타이핑하면 키 입력 시뮬레이션 비용이 커 timeout 위험이 있다 —
    // 단일 change 이벤트로 값을 채운다(userEvent.type 긴 문자열 timeout 회피).
    fireEvent.change(input, { target: { value: 'a'.repeat(4097) } })
    await user.click(screen.getByTestId('git-webhook-register-submit'))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText(SECRET_INVALID_TEXT)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// secret 필드 속성 — FR5(로그인 비밀번호 autofill 방지) · NFR6(label 연결)
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookRegisterDialog — secret 필드 속성', () => {
  it('secret 입력의 type 이 password 이고 autoComplete 가 off 다', () => {
    renderDialog()
    const input = screen.getByLabelText('Secret')
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveAttribute('autoComplete', 'off')
  })

  it('secret 입력에 <label> 이 연결돼 있다', () => {
    renderDialog()
    expect(screen.getByLabelText('Secret')).toHaveAttribute('id', 'git-webhook-register-secret')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// provider Select — FR3(shim 옵션 배열) · EC2(기본값)
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookRegisterDialog — provider Select', () => {
  it('provider Select 는 GITHUB/GITLAB 2옵션뿐이다', () => {
    renderDialog()
    const select = screen.getByLabelText('Provider') as HTMLSelectElement
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['GITHUB', 'GITLAB'])
  })

  it('provider 기본값이 GITHUB 이다', () => {
    renderDialog()
    const select = screen.getByLabelText('Provider') as HTMLSelectElement
    expect(select.value).toBe('GITHUB')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 중복 provider 경고 — FR13 · EC21(못 읽은 것 ≠ 0건)
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookRegisterDialog — 중복 provider 경고', () => {
  it('existingProviders 에 선택 provider 가 있으면 경고를 렌더하되 등록 버튼을 막지 않는다', () => {
    renderDialog({ existingProviders: ['GITHUB'] })
    expect(screen.getByText(/이미 있습니다/)).toBeInTheDocument()
    expect(screen.getByTestId('git-webhook-register-submit')).not.toBeDisabled()
  })

  it('listUnavailable 이면 중복 경고를 내지 않는다', () => {
    renderDialog({ existingProviders: ['GITHUB'], listUnavailable: true })
    expect(screen.queryByText(/이미 있습니다/)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 진행 상태 / 서버 에러 / 취소
// ─────────────────────────────────────────────────────────────────────────────

describe('GitWebhookRegisterDialog — 진행 상태·에러·취소', () => {
  it('isPending 이면 등록 버튼이 disabled 다', () => {
    renderDialog({ isPending: true })
    expect(screen.getByTestId('git-webhook-register-submit')).toBeDisabled()
  })

  it('submitError 가 있으면 에러 배너를 표시한다', () => {
    renderDialog({ submitError: '이미 등록된 웹훅입니다.' })
    expect(screen.getByText('이미 등록된 웹훅입니다.')).toBeInTheDocument()
  })

  it('취소 버튼을 누르면 onOpenChange(false) 가 호출된다', async () => {
    const { user, onOpenChange } = renderDialog()
    await user.click(screen.getByTestId('git-webhook-register-cancel'))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})
