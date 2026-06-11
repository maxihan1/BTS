// NotificationPolicyForm 컴포넌트 단위 테스트 — 옵션 렌더/제출/에러/비활성 검증 (FR-NT-01 Task 6)
//
// ⚠️ Radix Select jsdom 제약:
//   Radix UI Select는 Portal+포인터이벤트 제약으로 jsdom에서 클릭으로 드롭다운이 열리지 않는다.
//   shadcn Select(Radix 래퍼)를 네이티브 <select>로 mock하여 userEvent.selectOptions로 검증한다.
//   이 패턴은 BulkEditDialog.test.tsx 선례와 동일한 방향이다.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { PolicyCatalog } from '@/api/notification-policies'
import { NotificationPolicyForm } from './NotificationPolicyForm'

// ─────────────────────────────────────────────────────────────────────────────
// Radix/shadcn Select → 네이티브 <select> mock
// Radix Select는 jsdom에서 Portal 제약으로 클릭 인터랙션이 불가하므로
// 네이티브 select 엘리먼트로 대체해 userEvent.selectOptions를 활성화한다.
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/ui/select', async () => {
  const { createElement, useRef, Children } = await vi.importActual<typeof import('react')>('react')

  function Select({
    children,
    onValueChange,
    value,
  }: {
    children: React.ReactNode
    onValueChange?: (v: string) => void
    value?: string
  }) {
    const triggerLabel = useRef<string>('')
    const contentOptions = useRef<React.ReactNode>(null)

    Children.forEach(children, (child) => {
      if (child && typeof child === 'object' && 'props' in (child as object)) {
        const el = child as React.ReactElement<{ 'aria-label'?: string; children?: React.ReactNode }>
        if (el.props['aria-label']) {
          triggerLabel.current = el.props['aria-label']
        }
        if (el.props.children) {
          contentOptions.current = el.props.children
        }
      }
    })

    return createElement(
      'select',
      {
        'aria-label': triggerLabel.current,
        value: value ?? '',
        onChange: (e: React.ChangeEvent<HTMLSelectElement>) => {
          if (onValueChange) onValueChange(e.target.value)
        },
      },
      createElement('option', { value: '' }, '-- 선택 --'),
      contentOptions.current,
    )
  }

  function SelectTrigger({ children, 'aria-label': ariaLabel }: { children?: React.ReactNode; 'aria-label'?: string }) {
    return createElement('span', { 'aria-label': ariaLabel, 'data-testid': 'select-trigger' }, children)
  }

  function SelectValue() {
    return null
  }

  function SelectContent({ children }: { children: React.ReactNode }) {
    return createElement('span', { 'data-testid': 'select-content' }, children)
  }

  function SelectItem({ value, children }: { value: string; children: React.ReactNode }) {
    return createElement('option', { value }, children)
  }

  return { Select, SelectTrigger, SelectValue, SelectContent, SelectItem }
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** 카탈로그 픽스처 — 백엔드 enum 9/9/5 일부 사용 (렌더 검증용) */
const CATALOG: PolicyCatalog = {
  eventTypes: [
    { value: 'issue.created', publishable: true },
    { value: 'issue.assigned', publishable: false },
  ],
  recipientRoles: ['REPORTER', 'ASSIGNEE'],
  channels: ['EMAIL', 'IN_APP'],
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface RenderOptions {
  onSubmit?: (payload: { eventType: string; recipientRole: string; channel: string }) => void
  submitError?: string
  isSubmitting?: boolean
}

function renderForm(opts: RenderOptions = {}): void {
  render(
    <NotificationPolicyForm
      catalog={CATALOG}
      onSubmit={opts.onSubmit ?? vi.fn()}
      submitError={opts.submitError}
      isSubmitting={opts.isSubmitting ?? false}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 그룹
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationPolicyForm', () => {
  // ── (a) select 3종 렌더 ────────────────────────────────────────────────────

  describe('select 3종 렌더', () => {
    beforeEach(() => {
      renderForm()
    })

    it('이벤트 유형 combobox가 렌더된다', () => {
      expect(
        screen.getByRole('combobox', { name: /이벤트 유형/i }),
      ).toBeInTheDocument()
    })

    it('수신자 역할 combobox가 렌더된다', () => {
      expect(
        screen.getByRole('combobox', { name: /수신자/i }),
      ).toBeInTheDocument()
    })

    it('채널 combobox가 렌더된다', () => {
      expect(
        screen.getByRole('combobox', { name: /채널/i }),
      ).toBeInTheDocument()
    })

    it('추가 버튼이 렌더된다', () => {
      expect(
        screen.getByRole('button', { name: /정책 추가/i }),
      ).toBeInTheDocument()
    })
  })

  // ── (b) 카탈로그 옵션 한국어 라벨 렌더 ───────────────────────────────────

  describe('카탈로그 옵션이 한국어 라벨로 렌더된다', () => {
    beforeEach(() => {
      renderForm()
    })

    it('이벤트 유형 select에 이슈 생성 옵션이 있다', () => {
      const eventTypeSelect = screen.getByRole('combobox', { name: /이벤트 유형/i })
      expect(eventTypeSelect).toBeInTheDocument()
      // 네이티브 select mock에서 option으로 렌더됨
      expect(screen.getByRole('option', { name: '이슈 생성' })).toBeInTheDocument()
    })

    it('이벤트 유형 select에 이슈 담당자 지정 옵션이 있다', () => {
      expect(screen.getByRole('option', { name: '이슈 담당자 지정' })).toBeInTheDocument()
    })

    it('수신자 역할 select에 보고자 옵션이 있다', () => {
      expect(screen.getByRole('option', { name: '보고자' })).toBeInTheDocument()
    })

    it('수신자 역할 select에 담당자 옵션이 있다', () => {
      expect(screen.getByRole('option', { name: '담당자' })).toBeInTheDocument()
    })

    it('채널 select에 이메일 옵션이 있다', () => {
      expect(screen.getByRole('option', { name: '이메일' })).toBeInTheDocument()
    })

    it('채널 select에 인앱 알림 옵션이 있다', () => {
      expect(screen.getByRole('option', { name: '인앱 알림' })).toBeInTheDocument()
    })
  })

  // ── (c) 제출 → onSubmit 인자 검증 ────────────────────────────────────────

  describe('제출 시 onSubmit에 선택한 값이 전달된다', () => {
    it('각 select에서 값을 선택하고 추가 버튼 클릭 시 onSubmit이 호출된다', async () => {
      const user = userEvent.setup()
      const onSubmit = vi.fn()
      renderForm({ onSubmit })

      // 이벤트 유형 선택
      await user.selectOptions(
        screen.getByRole('combobox', { name: /이벤트 유형/i }),
        'issue.created',
      )

      // 수신자 역할 선택
      await user.selectOptions(
        screen.getByRole('combobox', { name: /수신자/i }),
        'REPORTER',
      )

      // 채널 선택
      await user.selectOptions(
        screen.getByRole('combobox', { name: /채널/i }),
        'EMAIL',
      )

      // 추가 버튼 클릭
      await user.click(screen.getByRole('button', { name: /정책 추가/i }))

      await waitFor(() => {
        expect(onSubmit).toHaveBeenCalledWith({
          eventType: 'issue.created',
          recipientRole: 'REPORTER',
          channel: 'EMAIL',
        })
      })
    })
  })

  // ── (d) submitError prop → 에러 메시지 표시 ──────────────────────────────

  describe('submitError prop이 전달되면 에러 메시지가 표시된다', () => {
    it('submitError 문자열이 화면에 렌더된다', () => {
      renderForm({
        submitError: '동일한 이벤트·수신자·채널 조합의 정책이 이미 존재합니다.',
      })

      expect(
        screen.getByText('동일한 이벤트·수신자·채널 조합의 정책이 이미 존재합니다.'),
      ).toBeInTheDocument()
    })

    it('submitError가 undefined이면 에러 메시지(role=alert)가 렌더되지 않는다', () => {
      renderForm({ submitError: undefined })

      expect(
        screen.queryByRole('alert'),
      ).not.toBeInTheDocument()
    })
  })

  // ── (e) isSubmitting → 제출 버튼 비활성 ──────────────────────────────────

  describe('isSubmitting 상태에서 추가 버튼이 비활성이다', () => {
    it('isSubmitting=true이면 추가 버튼이 disabled된다', () => {
      renderForm({ isSubmitting: true })

      expect(
        screen.getByRole('button', { name: /정책 추가/i }),
      ).toBeDisabled()
    })

    it('isSubmitting=false이면 추가 버튼이 활성이다', () => {
      renderForm({ isSubmitting: false })

      expect(
        screen.getByRole('button', { name: /정책 추가/i }),
      ).not.toBeDisabled()
    })
  })
})
