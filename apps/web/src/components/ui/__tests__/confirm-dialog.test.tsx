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

  // ★확인은 **스스로 닫지 않는다.** 닫는 책임이 소비자에게 있어야 `confirming` 이 관측되고
  //   실패 시 확인 맥락이 남는다. 종전에는 `onConfirm()` 직후 `onOpenChange(false)` 를 불러
  //   그 둘이 **구조적으로 불가능**했다 — 소비자가 무엇을 해도 창은 이미 닫힌 뒤였다.
  it('확인을 누르면 onConfirm 만 부르고 스스로 닫지 않는다', async () => {
    const { onConfirm, onOpenChange } = setup()
    await userEvent.click(screen.getByRole('button', { name: '빼기' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  // 취소는 여전히 프리미티브가 닫는다. 취소에는 「진행 중」도 「실패」도 없어서 소비자에게
  // 넘길 이유가 없고, 넘기면 소비처마다 같은 한 줄이 복제된다.
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

  // ★처리 중에는 **아무 경로로도** 닫히지 않는다. 확인 버튼만 잠그면 그 사이 창을 닫을 수 있고,
  //   뒤늦게 도착한 실패는 보여줄 창이 없어 조용히 사라진다 — 소비자가 배너나 toast 를 따로
  //   두지 않았다면 사용자는 실패한 사실을 통보받지 못한다.
  it('confirming 중에는 취소 버튼도 비활성이다', () => {
    setup({ confirming: true })
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  })

  it('confirming 중에는 Esc 로 닫히지 않는다', async () => {
    const { onOpenChange } = setup({ confirming: true })
    await userEvent.keyboard('{Escape}')
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  // 음성 짝 — 위 두 판정이 「항상 잠긴다」로 만족되지 않음을 본다.
  it('confirming 이 아니면 Esc 로 닫힌다', async () => {
    const { onOpenChange } = setup()
    await userEvent.keyboard('{Escape}')
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('error 를 주면 창 안에 alert 로 뜬다', () => {
    setup({ error: '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.' })
    expect(screen.getByRole('alert')).toHaveTextContent(
      '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  })

  it('error 가 없으면 alert 가 없다 — 판정이 항상 참이 아님을 본다', () => {
    setup()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // ★위 판정은 `confirming` 을 **직접 넘겨** 재므로 prop 계약만 본다. 그 상태에 실제로
  //   도달하는지는 소비자 테스트가 잰다(`ValidatorConfigSection` · `WorkflowEditorPage` ·
  //   `admin.workflows`). 종전에는 프리미티브가 확인 직후 닫아 **도달 자체가 불가능**했고,
  //   그래서 이 판정만으로는 도달 불가 조합을 지키는 가짜 그린이었다
  //   (`unreachable-state-fixture-is-fake-green`).

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
