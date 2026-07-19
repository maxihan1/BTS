// WebhookDeliveryTable 컴포넌트 단위 테스트 — status 배지 색상·null 방어·로딩/빈 상태
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WebhookDeliveryResponse } from '@/api/webhooks'
import { formatDateTime } from '@/lib/datetime'
import { WebhookDeliveryTable } from '../WebhookDeliveryTable'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture
// ─────────────────────────────────────────────────────────────────────────────

const succeededDelivery: WebhookDeliveryResponse = {
  id: '11111111-1111-4111-8111-111111111111',
  eventType: 'issue.created',
  status: 'SUCCEEDED',
  responseCode: 200,
  attemptCount: 1,
  errorDetail: null,
  createdAt: '2026-06-10T10:00:00Z',
  deliveredAt: '2026-06-10T10:00:05Z',
}

const failedDelivery: WebhookDeliveryResponse = {
  id: '22222222-2222-4222-8222-222222222222',
  eventType: 'issue.transitioned',
  status: 'FAILED',
  responseCode: null,
  attemptCount: 3,
  errorDetail: 'Connection timeout',
  createdAt: '2026-06-10T09:00:00Z',
  deliveredAt: null,
}

const unknownStatusDelivery: WebhookDeliveryResponse = {
  id: '33333333-3333-4333-8333-333333333333',
  eventType: 'issue.created',
  status: 'PENDING',
  responseCode: null,
  attemptCount: 0,
  errorDetail: null,
  createdAt: null,
  deliveredAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('WebhookDeliveryTable', () => {
  it('빈 배열이면 빈 상태 메시지를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[]} isLoading={false} />)
    expect(screen.getByText('발송 이력이 없습니다')).toBeInTheDocument()
  })

  it('isLoading=true이면 스켈레톤 행이 렌더된다', () => {
    render(<WebhookDeliveryTable deliveries={[]} isLoading={true} />)
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('eventType을 한국어 라벨로 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[succeededDelivery]} isLoading={false} />)
    expect(screen.getByText('이슈 생성')).toBeInTheDocument()
  })

  it('status=SUCCEEDED이면 초록 배지와 "성공" 텍스트를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[succeededDelivery]} isLoading={false} />)
    const badge = screen.getByText('성공')
    expect(badge.className).toContain('bg-success/10')
  })

  it('status=FAILED이면 빨강 배지와 "실패" 텍스트를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[failedDelivery]} isLoading={false} />)
    const badge = screen.getByText('실패')
    expect(badge.className).toContain('bg-danger/10')
  })

  it('status가 미지 값이면 중립 배지와 원문을 표시한다 (전방호환)', () => {
    render(<WebhookDeliveryTable deliveries={[unknownStatusDelivery]} isLoading={false} />)
    const badge = screen.getByText('PENDING')
    expect(badge.className).toContain('bg-muted')
    expect(badge.className).not.toContain('bg-success/10')
    expect(badge.className).not.toContain('bg-danger/10')
  })

  it('responseCode=null이면 "—"를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[failedDelivery]} isLoading={false} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('responseCode가 있으면 값을 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[succeededDelivery]} isLoading={false} />)
    expect(screen.getByText('200')).toBeInTheDocument()
  })

  it('errorDetail=null이면 "—"를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[succeededDelivery]} isLoading={false} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('errorDetail이 있으면 값을 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[failedDelivery]} isLoading={false} />)
    expect(screen.getByText('Connection timeout')).toBeInTheDocument()
  })

  it('deliveredAt이 있으면 deliveredAt 기준 시각을 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[succeededDelivery]} isLoading={false} />)
    expect(screen.getByText(formatDateTime('2026-06-10T10:00:05Z'))).toBeInTheDocument()
  })

  it('deliveredAt=null이면 createdAt 기준 시각을 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[failedDelivery]} isLoading={false} />)
    expect(screen.getByText(formatDateTime('2026-06-10T09:00:00Z'))).toBeInTheDocument()
  })

  it('deliveredAt·createdAt 모두 null이면 "—"를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[unknownStatusDelivery]} isLoading={false} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('attemptCount를 표시한다', () => {
    render(<WebhookDeliveryTable deliveries={[failedDelivery]} isLoading={false} />)
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('여러 항목이 모두 렌더된다', () => {
    render(
      <WebhookDeliveryTable deliveries={[succeededDelivery, failedDelivery]} isLoading={false} />,
    )
    expect(screen.getByText('이슈 생성')).toBeInTheDocument()
    expect(screen.getByText('이슈 상태 전이')).toBeInTheDocument()
  })
})
