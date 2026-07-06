// StatusModal 단위 테스트 — 렌더/저장(replace)/해제/프리셋 만료/재열림 초기화 (FR-PR-02 Task 8)
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StatusModal } from '@/components/status/StatusModal'
import { statusLabels } from '@/i18n/status-labels'

// 저장 mutation 과 whoami 재조회를 스파이로 대체 — new Date() 의존 없이 호출 인자를 검증한다.
// mutationState는 각 테스트가 isPending/isError를 조정하도록 mutable(에러 표시 검증용).
const { mutate, refreshWhoamiMock, mutationState } = vi.hoisted(() => ({
  mutate: vi.fn((_body: unknown, opts?: { onSuccess?: () => void }) => {
    opts?.onSuccess?.()
  }),
  refreshWhoamiMock: vi.fn(() => Promise.resolve()),
  mutationState: { isPending: false, isError: false },
}))

vi.mock('@/api/useStatus', () => ({
  useStatusQuery: () => ({ data: { emoji: null, text: null, expiresAt: null } }),
  useUpdateStatusMutation: () => ({
    mutate,
    isPending: mutationState.isPending,
    isError: mutationState.isError,
  }),
}))

vi.mock('@/api/useProfile', () => ({ refreshWhoami: refreshWhoamiMock }))

describe('StatusModal', () => {
  beforeEach(() => {
    mutate.mockClear()
    refreshWhoamiMock.mockClear()
    mutationState.isPending = false
    mutationState.isError = false
  })
  afterEach(cleanup)

  it('open=false면 렌더하지 않는다', () => {
    render(<StatusModal open={false} onOpenChange={vi.fn()} />)
    expect(screen.queryByText(statusLabels.title)).toBeNull()
  })

  it('open=true면 이모지/텍스트/프리셋/저장 버튼을 렌더한다', () => {
    render(<StatusModal open onOpenChange={vi.fn()} />)
    expect(screen.getByText(statusLabels.title)).toBeInTheDocument()
    expect(screen.getByLabelText(statusLabels.emojiLabel)).toBeInTheDocument()
    expect(screen.getByLabelText(statusLabels.textLabel)).toBeInTheDocument()
    expect(screen.getByLabelText(statusLabels.expiryLabel)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: statusLabels.saveButton })).toBeInTheDocument()
  })

  it('저장 시 정규화된 body로 mutate하고 성공 후 whoami 재조회 + 닫기', () => {
    const onOpenChange = vi.fn()
    render(<StatusModal open onOpenChange={onOpenChange} />)

    fireEvent.change(screen.getByLabelText(statusLabels.emojiLabel), { target: { value: '🌴' } })
    fireEvent.change(screen.getByLabelText(statusLabels.textLabel), { target: { value: '회의 중' } })
    fireEvent.click(screen.getByRole('button', { name: statusLabels.saveButton }))

    expect(mutate).toHaveBeenCalledTimes(1)
    const body = mutate.mock.calls[0]?.[0] as { emoji: string | null; text: string | null; expiresAt: string | null }
    expect(body.emoji).toBe('🌴')
    expect(body.text).toBe('회의 중')
    // 프리셋 기본값 'none' → 만료 없음
    expect(body.expiresAt).toBeNull()
    expect(refreshWhoamiMock).toHaveBeenCalledTimes(1)
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('프리셋 선택 시 만료 시각(ISO)이 body에 실린다', () => {
    render(<StatusModal open onOpenChange={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(statusLabels.emojiLabel), { target: { value: '🌴' } })
    fireEvent.change(screen.getByLabelText(statusLabels.expiryLabel), { target: { value: '1h' } })
    fireEvent.click(screen.getByRole('button', { name: statusLabels.saveButton }))

    const body = mutate.mock.calls[0]?.[0] as { expiresAt: string | null }
    expect(body.expiresAt).not.toBeNull()
    expect(() => new Date(body.expiresAt as string).toISOString()).not.toThrow()
  })

  it('상태 지우기 클릭 시 빈 body로 mutate(해제)', () => {
    render(<StatusModal open onOpenChange={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: statusLabels.clearButton }))

    expect(mutate).toHaveBeenCalledTimes(1)
    const body = mutate.mock.calls[0]?.[0] as { emoji: string | null; text: string | null }
    expect(body.emoji).toBeNull()
    expect(body.text).toBeNull()
  })

  it('이모지/텍스트 입력에 maxLength가 걸려 초과 입력을 원천 차단한다 (C1 — 400 무음실패 방지)', () => {
    render(<StatusModal open onOpenChange={vi.fn()} />)

    expect(screen.getByLabelText(statusLabels.emojiLabel)).toHaveAttribute('maxlength', '32')
    expect(screen.getByLabelText(statusLabels.textLabel)).toHaveAttribute('maxlength', '100')
  })

  it('저장 실패(mutation.isError) 시 에러 메시지를 표시한다 (C1 — defense-in-depth)', () => {
    mutationState.isError = true
    render(<StatusModal open onOpenChange={vi.fn()} />)

    expect(screen.getByRole('alert')).toHaveTextContent(statusLabels.errorMessage)
  })

  it('정상 상태에서는 에러 메시지를 표시하지 않는다', () => {
    render(<StatusModal open onOpenChange={vi.fn()} />)

    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('닫았다 다시 열면 폼이 초기화된다(stale 입력 방지)', () => {
    const { rerender } = render(<StatusModal open onOpenChange={vi.fn()} />)
    fireEvent.change(screen.getByLabelText(statusLabels.textLabel), { target: { value: '남는 텍스트' } })

    rerender(<StatusModal open={false} onOpenChange={vi.fn()} />)
    rerender(<StatusModal open onOpenChange={vi.fn()} />)

    expect(screen.getByLabelText(statusLabels.textLabel)).toHaveValue('')
  })
})
