// NotificationPolicyTable 컴포넌트 단위 테스트 — 라벨·토글·인라인삭제·빈상태
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { NotificationPolicy } from '@/api/notification-policies'
import { NotificationPolicyTable } from './NotificationPolicyTable'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 fixture
// ─────────────────────────────────────────────────────────────────────────────

const policyEnabled: NotificationPolicy = {
  id: '11111111-1111-4111-a111-111111111111',
  eventType: 'issue.created',
  recipientRole: 'REPORTER',
  channel: 'EMAIL',
  enabled: true,
  createdAt: '2026-06-12T00:00:00Z',
  updatedAt: '2026-06-12T00:00:00Z',
}

const policyDisabled: NotificationPolicy = {
  id: '22222222-2222-4222-a222-222222222222',
  eventType: 'sprint.started',
  recipientRole: 'ASSIGNEE',
  channel: 'IN_APP',
  enabled: false,
  createdAt: '2026-06-12T00:01:00Z',
  updatedAt: '2026-06-12T00:01:00Z',
}

const policyUnknown: NotificationPolicy = {
  id: '33333333-3333-4333-a333-333333333333',
  eventType: 'future.unknown_event',
  recipientRole: 'UNKNOWN_ROLE',
  channel: 'UNKNOWN_CHANNEL',
  enabled: true,
  createdAt: '2026-06-12T00:02:00Z',
  updatedAt: '2026-06-12T00:02:00Z',
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('NotificationPolicyTable', () => {
  let onToggle: Mock<(id: string, nextEnabled: boolean) => void>
  let onDelete: Mock<(id: string) => void>

  beforeEach(() => {
    onToggle = vi.fn<(id: string, nextEnabled: boolean) => void>()
    onDelete = vi.fn<(id: string) => void>()
  })

  // ── 빈 상태 ──────────────────────────────────────────────────────────────

  it('policies가 빈 배열이면 빈 상태 문구를 표시한다', () => {
    render(
      <NotificationPolicyTable
        policies={[]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    expect(screen.getByText('등록된 알림 정책이 없습니다.')).toBeInTheDocument()
  })

  // ── 라벨 렌더 ─────────────────────────────────────────────────────────────

  it('이벤트 타입·수신자·채널 한국어 라벨을 표시한다', () => {
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    expect(screen.getByText('이슈 생성')).toBeInTheDocument()
    expect(screen.getByText('보고자')).toBeInTheDocument()
    expect(screen.getByText('이메일')).toBeInTheDocument()
  })

  it('미지 enum 값은 원문을 그대로 표시한다 (전방호환)', () => {
    render(
      <NotificationPolicyTable
        policies={[policyUnknown]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    expect(screen.getByText('future.unknown_event')).toBeInTheDocument()
    expect(screen.getByText('UNKNOWN_ROLE')).toBeInTheDocument()
    expect(screen.getByText('UNKNOWN_CHANNEL')).toBeInTheDocument()
  })

  it('enabled=true 행은 "활성" 상태를 표시한다', () => {
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    // 헤더 th와 중복 — 행 컨테이너 한정으로 검색
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    expect(within(row).getByText('활성')).toBeInTheDocument()
  })

  it('enabled=false 행은 "비활성" 상태를 표시한다', () => {
    render(
      <NotificationPolicyTable
        policies={[policyDisabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    const row = screen.getByRole('row', { name: /스프린트 시작/ })
    expect(within(row).getByText('비활성')).toBeInTheDocument()
  })

  // ── 토글 버튼 ─────────────────────────────────────────────────────────────

  it('enabled=true 행의 토글 버튼 클릭 시 onToggle(id, false)을 호출한다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    // 텍스트 중복 방지 — policyEnabled 행 컨테이너 한정
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    await user.click(within(row).getByRole('button', { name: /비활성화/ }))
    expect(onToggle).toHaveBeenCalledWith(policyEnabled.id, false)
  })

  it('enabled=false 행의 토글 버튼 클릭 시 onToggle(id, true)을 호출한다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyDisabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    const row = screen.getByRole('row', { name: /스프린트 시작/ })
    await user.click(within(row).getByRole('button', { name: /활성화/ }))
    expect(onToggle).toHaveBeenCalledWith(policyDisabled.id, true)
  })

  it('isMutating=true이면 토글 버튼이 비활성화된다', () => {
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={true}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    expect(within(row).getByRole('button', { name: /비활성화/ })).toBeDisabled()
  })

  // ── 인라인 삭제 확인 ─────────────────────────────────────────────────────

  it('삭제 버튼 클릭 시 확인/취소 버튼이 표시된다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    await user.click(within(row).getByRole('button', { name: /삭제/ }))
    // 확인/취소 버튼이 행 안에 나타나야 한다
    expect(within(row).getByRole('button', { name: /확인/ })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: /취소/ })).toBeInTheDocument()
  })

  it('삭제 확인 클릭 시 onDelete(id)를 호출한다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    await user.click(within(row).getByRole('button', { name: /삭제/ }))
    await user.click(within(row).getByRole('button', { name: /확인/ }))
    expect(onDelete).toHaveBeenCalledWith(policyEnabled.id)
  })

  it('삭제 취소 클릭 시 onDelete가 호출되지 않고 확인 버튼이 사라진다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    await user.click(within(row).getByRole('button', { name: /삭제/ }))
    await user.click(within(row).getByRole('button', { name: /취소/ }))
    expect(onDelete).not.toHaveBeenCalled()
    expect(within(row).queryByRole('button', { name: /확인/ })).not.toBeInTheDocument()
  })

  it('isMutating=true이면 삭제 확인 버튼이 비활성화된다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyEnabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={true}
      />,
    )
    const row = screen.getByRole('row', { name: /이슈 생성/ })
    await user.click(within(row).getByRole('button', { name: /삭제/ }))
    expect(within(row).getByRole('button', { name: /확인/ })).toBeDisabled()
  })

  // ── 다중 행 ───────────────────────────────────────────────────────────────

  it('여러 정책이 각각 독립 행으로 렌더된다', () => {
    render(
      <NotificationPolicyTable
        policies={[policyEnabled, policyDisabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    expect(screen.getByText('이슈 생성')).toBeInTheDocument()
    expect(screen.getByText('스프린트 시작')).toBeInTheDocument()
  })

  it('한 행의 인라인 확인이 다른 행에 영향을 주지 않는다', async () => {
    const user = userEvent.setup()
    render(
      <NotificationPolicyTable
        policies={[policyEnabled, policyDisabled]}
        onToggle={onToggle}
        onDelete={onDelete}
        isMutating={false}
      />,
    )
    const row1 = screen.getByRole('row', { name: /이슈 생성/ })
    const row2 = screen.getByRole('row', { name: /스프린트 시작/ })

    await user.click(within(row1).getByRole('button', { name: /삭제/ }))

    // row1에 확인 버튼 등장
    expect(within(row1).getByRole('button', { name: /확인/ })).toBeInTheDocument()
    // row2는 여전히 삭제 버튼만 존재
    expect(within(row2).queryByRole('button', { name: /확인/ })).not.toBeInTheDocument()
  })
})
