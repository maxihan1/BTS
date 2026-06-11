// NotificationPolicyForm 컴포넌트 단위 테스트 — 옵션 렌더/제출/에러/비활성 검증 (FR-NT-01 Task 6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { PolicyCatalog } from '@/api/notification-policies'
import { NotificationPolicyForm } from './NotificationPolicyForm'

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

    it('이벤트 유형 select combobox가 렌더된다', () => {
      expect(
        screen.getByRole('combobox', { name: /이벤트 유형/i }),
      ).toBeInTheDocument()
    })

    it('수신자 역할 select combobox가 렌더된다', () => {
      expect(
        screen.getByRole('combobox', { name: /수신자/i }),
      ).toBeInTheDocument()
    })

    it('채널 select combobox가 렌더된다', () => {
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
    it('이벤트 유형 select를 열면 이슈 생성 옵션이 보인다', async () => {
      const user = userEvent.setup()
      renderForm()

      const eventTypeCombobox = screen.getByRole('combobox', { name: /이벤트 유형/i })
      await user.click(eventTypeCombobox)

      await waitFor(() => {
        expect(screen.getByRole('option', { name: '이슈 생성' })).toBeInTheDocument()
      })
    })

    it('이벤트 유형 select를 열면 이슈 담당자 지정 옵션이 보인다', async () => {
      const user = userEvent.setup()
      renderForm()

      const eventTypeCombobox = screen.getByRole('combobox', { name: /이벤트 유형/i })
      await user.click(eventTypeCombobox)

      await waitFor(() => {
        expect(screen.getByRole('option', { name: '이슈 담당자 지정' })).toBeInTheDocument()
      })
    })

    it('수신자 역할 select를 열면 보고자 옵션이 보인다', async () => {
      const user = userEvent.setup()
      renderForm()

      const recipientCombobox = screen.getByRole('combobox', { name: /수신자/i })
      await user.click(recipientCombobox)

      await waitFor(() => {
        expect(screen.getByRole('option', { name: '보고자' })).toBeInTheDocument()
      })
    })

    it('채널 select를 열면 이메일 옵션이 보인다', async () => {
      const user = userEvent.setup()
      renderForm()

      const channelCombobox = screen.getByRole('combobox', { name: /채널/i })
      await user.click(channelCombobox)

      await waitFor(() => {
        expect(screen.getByRole('option', { name: '이메일' })).toBeInTheDocument()
      })
    })
  })

  // ── (c) 제출 → onSubmit 인자 검증 ────────────────────────────────────────

  describe('제출 시 onSubmit에 선택한 값이 전달된다', () => {
    it('각 select에서 값을 선택하고 추가 버튼 클릭 시 onSubmit이 호출된다', async () => {
      const user = userEvent.setup()
      const onSubmit = vi.fn()
      renderForm({ onSubmit })

      // 이벤트 유형 선택
      const eventTypeCombobox = screen.getByRole('combobox', { name: /이벤트 유형/i })
      await user.click(eventTypeCombobox)
      await waitFor(() => screen.getByRole('option', { name: '이슈 생성' }))
      await user.click(screen.getByRole('option', { name: '이슈 생성' }))

      // 수신자 역할 선택
      const recipientCombobox = screen.getByRole('combobox', { name: /수신자/i })
      await user.click(recipientCombobox)
      await waitFor(() => screen.getByRole('option', { name: '보고자' }))
      await user.click(screen.getByRole('option', { name: '보고자' }))

      // 채널 선택
      const channelCombobox = screen.getByRole('combobox', { name: /채널/i })
      await user.click(channelCombobox)
      await waitFor(() => screen.getByRole('option', { name: '이메일' }))
      await user.click(screen.getByRole('option', { name: '이메일' }))

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

    it('submitError가 undefined이면 에러 메시지가 렌더되지 않는다', () => {
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
