// 이슈 생성 진입점 버튼 단위 테스트 — fail-closed 권한 게이트 + 두 시각 형태 (FR-UX-09 F3 T3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CreateIssueEntryButton } from '@/components/issue/CreateIssueEntryButton'

const LABEL = '백로그 칸에 이슈 추가'

describe('CreateIssueEntryButton — 권한 게이트 (FR-6, fail-closed)', () => {
  it('canCreate=true 면 활성이고 누르면 onClick 이 호출된다', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(
      <CreateIssueEntryButton label={LABEL} variant="icon" canCreate onClick={onClick} />,
    )

    const button = screen.getByRole('button', { name: LABEL })
    expect(button).toBeEnabled()

    await user.click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('★canCreate=false 면 비활성이고 눌러도 onClick 이 호출되지 않는다', async () => {
    // 로딩·에러도 여기로 들어온다 — 호출자가 `permissions.CREATE === true` 로만 true 를 만든다.
    // 「일단 활성 후 서버가 거부」는 금지다 (선례 routes/issues.index.tsx NewIssueButton).
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(
      <CreateIssueEntryButton
        label={LABEL}
        variant="icon"
        canCreate={false}
        onClick={onClick}
      />,
    )

    const button = screen.getByRole('button', { name: LABEL })
    expect(button).toBeDisabled()

    await user.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })
})

describe('CreateIssueEntryButton — 시각 형태 2종 (design 리뷰 DR-1·DR-3)', () => {
  it('variant="icon" 은 라벨을 눈에 보이는 텍스트로 그리지 않는다 (칸 헤더 — 폭 288px)', () => {
    render(<CreateIssueEntryButton label={LABEL} variant="icon" canCreate onClick={vi.fn()} />)

    // 접근 가능한 이름은 있다
    expect(screen.getByRole('button', { name: LABEL })).toBeInTheDocument()
    // 그러나 보이는 텍스트로는 없다
    expect(screen.queryByText(LABEL)).toBeNull()
  })

  it('variant="text" 는 라벨을 눈에 보이는 텍스트로 그린다 (보드 헤더)', () => {
    render(<CreateIssueEntryButton label={LABEL} variant="text" canCreate onClick={vi.fn()} />)

    expect(screen.getByRole('button', { name: LABEL })).toBeInTheDocument()
    expect(screen.getByText(LABEL)).toBeInTheDocument()
  })

  it('★variant="text" 는 primary 가 아니다 — 상단바 「만들기」와 primary 가 2개가 되면 안 된다', () => {
    // DR-1. 상단바 생성 버튼은 모든 페이지에 항상 있고 `variant="default"`(primary) 다.
    // 보드 헤더까지 primary 로 두면 같은 화면에 주 액션이 2개가 된다.
    render(<CreateIssueEntryButton label={LABEL} variant="text" canCreate onClick={vi.fn()} />)

    const button = screen.getByRole('button', { name: LABEL })
    expect(button.className).not.toContain('bg-primary')
  })
})
