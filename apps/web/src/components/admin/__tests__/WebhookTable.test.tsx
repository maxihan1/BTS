// WebhookTable 컴포넌트 단위 테스트 — 라벨·배지·null가드·편집/이력/인라인삭제
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WebhookResponse } from '@/api/webhooks'
import { WebhookTable } from '../WebhookTable'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture
// ─────────────────────────────────────────────────────────────────────────────

/** projectKey/secret/updatedAt 모두 값이 있는 표준 케이스 */
const webhookWithProject: WebhookResponse = {
  id: '11111111-1111-4111-a111-111111111111',
  name: '이슈 생성 알림',
  url: 'https://example.com/webhooks/issue-created-notification-endpoint-very-long-path',
  eventFilter: ['issue.created', 'issue.transitioned'],
  projectKey: 'PROJ',
  enabled: true,
  hasSecret: true,
  createdAt: '2026-06-01T00:00:00Z',
  updatedAt: '2026-06-02T00:00:00Z',
  version: 3,
}

/** projectKey=null인데 updatedAt은 값이 있는 케이스 — projectKey null 가드만 단독 검증 */
const webhookNoProject: WebhookResponse = {
  id: '22222222-2222-4222-a222-222222222222',
  name: '전체 프로젝트 알림',
  url: 'https://example.com/hooks/all',
  eventFilter: ['issue.created'],
  projectKey: null,
  enabled: false,
  hasSecret: false,
  createdAt: '2026-06-03T00:00:00Z',
  updatedAt: '2026-06-03T00:00:00Z',
  version: 1,
}

/** projectKey는 값이 있는데 updatedAt=null인 케이스 — updatedAt null 가드만 단독 검증 */
const webhookNoUpdatedAt: WebhookResponse = {
  id: '33333333-3333-4333-a333-333333333333',
  name: '미갱신 알림',
  url: 'https://example.com/hooks/never-updated',
  eventFilter: ['issue.transitioned'],
  projectKey: 'OTHER',
  enabled: true,
  hasSecret: false,
  createdAt: '2026-06-04T00:00:00Z',
  updatedAt: null,
  version: 1,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('WebhookTable', () => {
  let onEdit: Mock<(webhook: WebhookResponse) => void>
  let onDelete: Mock<(id: string) => void>
  let onViewDeliveries: Mock<(id: string) => void>

  beforeEach(() => {
    onEdit = vi.fn<(webhook: WebhookResponse) => void>()
    onDelete = vi.fn<(id: string) => void>()
    onViewDeliveries = vi.fn<(id: string) => void>()
  })

  // ── 렌더 ──────────────────────────────────────────────────────────────────

  it('name·url·이벤트 라벨·projectKey·배지를 렌더한다', () => {
    render(
      <WebhookTable
        webhooks={[webhookWithProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    expect(screen.getByText('이슈 생성 알림')).toBeInTheDocument()
    expect(screen.getByText(webhookWithProject.url)).toBeInTheDocument()
    // 구독 이벤트 두 종류가 라벨 조인된 셀 — 이름 셀과 텍스트가 겹치므로 전체 조인 문자열로 단정
    expect(screen.getByText('이슈 생성, 이슈 상태 전이')).toBeInTheDocument()
    expect(screen.getByText('PROJ')).toBeInTheDocument()
    expect(screen.getByText('활성')).toBeInTheDocument()
    expect(screen.getByText('서명 설정')).toBeInTheDocument()
  })

  it('projectKey=null이면 프로젝트 셀에 "—"를 표시한다', () => {
    render(
      <WebhookTable
        webhooks={[webhookNoProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    // updatedAt은 값이 있어 "—"가 이 행에서 유일 — 프로젝트 셀임이 보장된다
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('enabled=false·hasSecret=false 배지를 표시한다', () => {
    render(
      <WebhookTable
        webhooks={[webhookNoProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    expect(screen.getByText('비활성')).toBeInTheDocument()
    expect(screen.getByText('없음')).toBeInTheDocument()
  })

  it('updatedAt=null이면 갱신 시각 셀에 "—"를 표시한다', () => {
    render(
      <WebhookTable
        webhooks={[webhookNoUpdatedAt]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    // projectKey는 값이 있어 "—"가 이 행에서 유일 — 갱신 시각 셀임이 보장된다
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  // ── 로딩 / 빈 상태 ────────────────────────────────────────────────────────

  it('isLoading=true이면 로딩 상태를 표시한다', () => {
    render(
      <WebhookTable
        webhooks={[]}
        isLoading={true}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('webhooks가 빈 배열이면 빈 상태 문구를 표시한다', () => {
    render(
      <WebhookTable
        webhooks={[]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    expect(screen.getByText('등록된 Webhook이 없습니다')).toBeInTheDocument()
  })

  // ── 편집 / 이력 버튼 ──────────────────────────────────────────────────────

  it('편집 버튼 클릭 시 onEdit(webhook)을 호출한다', async () => {
    const user = userEvent.setup()
    render(
      <WebhookTable
        webhooks={[webhookWithProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성 알림/ })
    await user.click(within(row).getByRole('button', { name: /이슈 생성 알림 편집/ }))
    expect(onEdit).toHaveBeenCalledWith(webhookWithProject)
  })

  it('이력 버튼 클릭 시 onViewDeliveries(id)를 호출한다', async () => {
    const user = userEvent.setup()
    render(
      <WebhookTable
        webhooks={[webhookWithProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성 알림/ })
    await user.click(within(row).getByRole('button', { name: /이슈 생성 알림 발송 이력/ }))
    expect(onViewDeliveries).toHaveBeenCalledWith(webhookWithProject.id)
  })

  // ── 인라인 삭제 확인 ──────────────────────────────────────────────────────

  it('삭제 버튼 클릭 시 확인/취소 버튼이 표시된다', async () => {
    const user = userEvent.setup()
    render(
      <WebhookTable
        webhooks={[webhookWithProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성 알림/ })
    await user.click(within(row).getByRole('button', { name: /이슈 생성 알림 삭제$/ }))
    expect(within(row).getByRole('button', { name: /확인/ })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: /취소/ })).toBeInTheDocument()
  })

  it('삭제 확인 클릭 시 onDelete(id)를 호출한다', async () => {
    const user = userEvent.setup()
    render(
      <WebhookTable
        webhooks={[webhookWithProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성 알림/ })
    await user.click(within(row).getByRole('button', { name: /이슈 생성 알림 삭제$/ }))
    await user.click(within(row).getByRole('button', { name: /확인/ }))
    expect(onDelete).toHaveBeenCalledWith(webhookWithProject.id)
  })

  it('삭제 취소 클릭 시 onDelete가 호출되지 않고 확인 버튼이 사라진다', async () => {
    const user = userEvent.setup()
    render(
      <WebhookTable
        webhooks={[webhookWithProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성 알림/ })
    await user.click(within(row).getByRole('button', { name: /이슈 생성 알림 삭제$/ }))
    await user.click(within(row).getByRole('button', { name: /취소/ }))
    expect(onDelete).not.toHaveBeenCalled()
    expect(within(row).queryByRole('button', { name: /확인/ })).not.toBeInTheDocument()
  })

  it('한 행의 인라인 삭제 확인이 다른 행에 영향을 주지 않는다', async () => {
    const user = userEvent.setup()
    render(
      <WebhookTable
        webhooks={[webhookWithProject, webhookNoProject]}
        isLoading={false}
        onEdit={onEdit}
        onDelete={onDelete}
        onViewDeliveries={onViewDeliveries}
      />,
    )
    const row1 = screen.getByRole('row', { name: /이슈 생성 알림/ })
    const row2 = screen.getByRole('row', { name: /전체 프로젝트 알림/ })

    await user.click(within(row1).getByRole('button', { name: /이슈 생성 알림 삭제$/ }))

    expect(within(row1).getByRole('button', { name: /확인/ })).toBeInTheDocument()
    expect(within(row2).queryByRole('button', { name: /확인/ })).not.toBeInTheDocument()
  })
})
