// 확인 다이얼로그 프리미티브 테스트 — 고유 aria-label · 확인/취소 분기 · 파괴적 변형
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfirmDialog } from '../confirm-dialog'

function setup(overrides: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  const onConfirm = vi.fn()
  const onOpenChange = vi.fn()
  render(
    <ConfirmDialog
      open
      onOpenChange={onOpenChange}
      title="상태를 뺄까요?"
      description="이 워크플로우에서만 빠지고 카탈로그에는 남습니다."
      confirmLabel="빼기"
      cancelLabel="취소"
      onConfirm={onConfirm}
      {...overrides}
    />,
  )
  return { onConfirm, onOpenChange }
}

describe('ConfirmDialog', () => {
  it('title 이 dialog 의 접근성 이름이 된다 (§2 즉사 계약 — strict mode 충돌 방지)', () => {
    // Radix 가 DialogTitle 을 aria-labelledby 로 걸고 그것이 aria-label 을 이긴다.
    // 별도 aria-label 프롭을 두면 지정한 이름과 실제 이름이 갈린다.
    setup()
    expect(screen.getByRole('dialog', { name: '상태를 뺄까요?' })).toBeInTheDocument()
  })

  it('확인을 누르면 onConfirm 이 불리고 닫힌다', async () => {
    const { onConfirm, onOpenChange } = setup()
    await userEvent.click(screen.getByRole('button', { name: '빼기' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('취소는 onConfirm 을 부르지 않고 닫는다', async () => {
    const { onConfirm, onOpenChange } = setup()
    await userEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(onConfirm).not.toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('confirming 중에는 확인 버튼이 비활성이다 — 이중 제출 차단', () => {
    setup({ confirming: true })
    expect(screen.getByRole('button', { name: '빼기' })).toBeDisabled()
  })

  // `destructive` 만으로 판정하면 안 된다 — button 의 **base** 클래스가 이미
  // `aria-invalid:...destructive/20` 을 달고 있어 어떤 변형이든 참이 된다(실측).
  // `bg-destructive/` 는 destructive 변형에만 있다.
  it('destructive 면 확인 버튼이 파괴적 변형으로 그려진다', () => {
    setup({ destructive: true })
    expect(screen.getByRole('button', { name: '빼기' }).className).toContain('bg-destructive/')
  })

  it('destructive 가 아니면 파괴적 변형이 아니다 — 판정이 항상 참이 아님을 본다', () => {
    setup({ destructive: false })
    expect(screen.getByRole('button', { name: '빼기' }).className).not.toContain('bg-destructive/')
  })
})
