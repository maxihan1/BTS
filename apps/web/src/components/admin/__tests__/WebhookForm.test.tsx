// WebhookForm 컴포넌트 단위 테스트 — 생성/수정 겸용 인라인 폼 검증 (FR-API-03 PR4 Task 5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WebhookResponse } from '@/api/webhooks'
import { WebhookForm } from '@/components/admin/WebhookForm'

/** edit 모드 프리필 픽스처 — 필드 5종 + version(OCC) 보유 */
const EDIT_VALUE: WebhookResponse = {
  id: '11111111-1111-4111-8111-111111111111',
  name: '기존 구독',
  url: 'https://example.com/hook',
  eventFilter: ['issue.created'],
  projectKey: 'PROJ',
  enabled: true,
  hasSecret: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 3,
}

describe('WebhookForm', () => {
  describe('create 모드', () => {
    it('name/url 입력 + 이벤트 1개 선택 후 제출하면 onSubmit에 올바른 payload가 전달된다', async () => {
      const user = userEvent.setup({ delay: null })
      const onSubmit = vi.fn()
      render(
        <WebhookForm mode="create" onSubmit={onSubmit} isSubmitting={false} onCancel={vi.fn()} />,
      )

      await user.type(screen.getByLabelText('이름'), '신규 구독')
      await user.type(screen.getByLabelText('URL'), 'https://example.com/hook')
      await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))
      await user.click(screen.getByRole('button', { name: '생성' }))

      expect(onSubmit).toHaveBeenCalledWith({
        name: '신규 구독',
        url: 'https://example.com/hook',
        eventFilter: ['issue.created'],
        enabled: true,
      })
    })

    it('이벤트를 하나도 선택하지 않으면 제출이 차단된다(EC-9)', async () => {
      const user = userEvent.setup({ delay: null })
      const onSubmit = vi.fn()
      render(
        <WebhookForm mode="create" onSubmit={onSubmit} isSubmitting={false} onCancel={vi.fn()} />,
      )

      await user.type(screen.getByLabelText('이름'), '신규 구독')
      await user.type(screen.getByLabelText('URL'), 'https://example.com/hook')
      await user.click(screen.getByRole('button', { name: '생성' }))

      expect(onSubmit).not.toHaveBeenCalled()
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })

    it('name이 비어있으면 제출이 차단된다', async () => {
      const user = userEvent.setup({ delay: null })
      const onSubmit = vi.fn()
      render(
        <WebhookForm mode="create" onSubmit={onSubmit} isSubmitting={false} onCancel={vi.fn()} />,
      )

      await user.type(screen.getByLabelText('URL'), 'https://example.com/hook')
      await user.click(screen.getByRole('checkbox', { name: '이슈 생성' }))
      await user.click(screen.getByRole('button', { name: '생성' }))

      expect(onSubmit).not.toHaveBeenCalled()
    })
  })

  describe('edit 모드', () => {
    it('initialValue로 필드가 프리필된다', () => {
      render(
        <WebhookForm
          mode="edit"
          initialValue={EDIT_VALUE}
          onSubmit={vi.fn()}
          isSubmitting={false}
          onCancel={vi.fn()}
        />,
      )

      expect(screen.getByLabelText('이름')).toHaveValue('기존 구독')
      expect(screen.getByLabelText('URL')).toHaveValue('https://example.com/hook')
      expect(screen.getByRole('checkbox', { name: '이슈 생성' })).toBeChecked()
      expect(screen.getByLabelText('프로젝트 키')).toHaveValue('PROJ')
    })

    it('제출 시 payload에 initialValue.version이 포함된다(OCC EC-4)', async () => {
      const user = userEvent.setup({ delay: null })
      const onSubmit = vi.fn()
      render(
        <WebhookForm
          mode="edit"
          initialValue={EDIT_VALUE}
          onSubmit={onSubmit}
          isSubmitting={false}
          onCancel={vi.fn()}
        />,
      )

      await user.click(screen.getByRole('button', { name: '저장' }))

      expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ version: 3 }))
    })

    it('secret 입력란을 비워두면 payload에 secret 키가 없다(3-state EC-3)', async () => {
      const user = userEvent.setup({ delay: null })
      const onSubmit = vi.fn()
      render(
        <WebhookForm
          mode="edit"
          initialValue={EDIT_VALUE}
          onSubmit={onSubmit}
          isSubmitting={false}
          onCancel={vi.fn()}
        />,
      )

      await user.click(screen.getByRole('button', { name: '저장' }))

      const payload = onSubmit.mock.calls[0]?.[0] as Record<string, unknown> | undefined
      expect(payload).toBeDefined()
      expect(payload).not.toHaveProperty('secret')
    })

    it('secret에 값을 입력하면 payload에 포함된다', async () => {
      const user = userEvent.setup({ delay: null })
      const onSubmit = vi.fn()
      render(
        <WebhookForm
          mode="edit"
          initialValue={EDIT_VALUE}
          onSubmit={onSubmit}
          isSubmitting={false}
          onCancel={vi.fn()}
        />,
      )

      await user.type(screen.getByLabelText('서명 Secret'), 'new-secret')
      await user.click(screen.getByRole('button', { name: '저장' }))

      expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ secret: 'new-secret' }))
    })

    it('비워두면 기존 서명 키를 유지한다는 안내 문구가 표시된다', () => {
      render(
        <WebhookForm
          mode="edit"
          initialValue={EDIT_VALUE}
          onSubmit={vi.fn()}
          isSubmitting={false}
          onCancel={vi.fn()}
        />,
      )

      expect(screen.getByText('비워두면 기존 서명 키를 유지합니다.')).toBeInTheDocument()
    })
  })

  describe('submitError prop', () => {
    it('submitError가 있으면 배너로 표시된다', () => {
      render(
        <WebhookForm
          mode="create"
          onSubmit={vi.fn()}
          submitError="이미 사용 중인 이름입니다."
          isSubmitting={false}
          onCancel={vi.fn()}
        />,
      )

      expect(screen.getByText('이미 사용 중인 이름입니다.')).toBeInTheDocument()
    })
  })
})
