// 스프린트 번다운 가젯 테스트 — 세 가지 「비어 있음」을 구분하는지가 핵심
//
// ★칸반 보드(활성 스프린트 없음)를 오류로 표시하면 사용자가 자기 설정이 틀렸다고 오해한다.
//   그래서 「스프린트 없음」과 「불러오지 못함」을 각각 잰다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@/components/burndown/BurndownChart', () => ({
  BurndownChart: (props: { view?: string }) => (
    <div data-testid="burndown-chart" data-view={props.view ?? ''} />
  ),
}))

vi.mock('@/hooks/use-sprint-burndown', () => ({
  useSprintBurndownByBoard: vi.fn(),
}))

const { useSprintBurndownByBoard } = await import('@/hooks/use-sprint-burndown')
const { SprintBurndownGadget } = await import('./SprintBurndownGadget')
const { gadgetStateLabels } = await import('@/i18n/dashboard-labels')

const BOARD_ID = '11111111-2222-3333-4444-555555555555'

type HookResult = ReturnType<typeof useSprintBurndownByBoard>

function mockHook(over: Partial<HookResult>): void {
  vi.mocked(useSprintBurndownByBoard).mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    hasNoActiveSprint: false,
    ...over,
  } as HookResult)
}

/** 차트 자체는 목이라 내용이 필요 없다 — 존재만으로 「그렸다」를 잰다. */
const burndownFixture = { sprintId: BOARD_ID } as unknown as NonNullable<HookResult['data']>

beforeEach(() => {
  vi.clearAllMocks()
})

describe('SprintBurndownGadget', () => {
  it('★boardId 를 훅에 그대로 넘긴다 (목이 삼키는 것 방지)', () => {
    mockHook({ data: burndownFixture })
    render(<SprintBurndownGadget config={{ boardId: BOARD_ID }} />)
    expect(useSprintBurndownByBoard).toHaveBeenCalledWith(BOARD_ID)
  })

  it('활성 스프린트가 있으면 번다운 차트를 그린다 — 기존 컴포넌트 재사용', () => {
    mockHook({ data: burndownFixture })
    render(<SprintBurndownGadget config={{ boardId: BOARD_ID }} />)

    const chart = screen.getByTestId('burndown-chart')
    expect(chart).toBeInTheDocument()
    expect(chart.getAttribute('data-view')).toBe('burndown')
  })

  it('★칸반 보드(활성 스프린트 없음)는 오류가 아니라 안내를 낸다 (E1/S6)', () => {
    mockHook({ hasNoActiveSprint: true })
    render(<SprintBurndownGadget config={{ boardId: BOARD_ID }} />)

    expect(screen.getByText(gadgetStateLabels.noActiveSprint)).toBeInTheDocument()
    // 오류 문구가 나오면 안 된다 — 둘을 뭉치면 사용자가 설정을 의심한다.
    expect(screen.queryByText(gadgetStateLabels.loadFailed)).not.toBeInTheDocument()
    expect(screen.queryByTestId('burndown-chart')).not.toBeInTheDocument()
  })

  it('★스프린트 부재와 오류가 동시에 참이면 부재를 먼저 낸다 (판정 순서)', () => {
    // 보드는 읽혔는데 스프린트가 없고, 번다운 쿼리가 별개로 실패한 상황.
    mockHook({ hasNoActiveSprint: true, isError: true })
    render(<SprintBurndownGadget config={{ boardId: BOARD_ID }} />)

    expect(screen.getByText(gadgetStateLabels.noActiveSprint)).toBeInTheDocument()
  })

  it('보드 404 는 빈 상태로 흡수한다 — 대시보드가 죽지 않는다 (E2)', () => {
    mockHook({ isError: true })
    render(<SprintBurndownGadget config={{ boardId: BOARD_ID }} />)

    expect(screen.getByText(gadgetStateLabels.loadFailed)).toBeInTheDocument()
  })

  it('boardId 가 없으면 설정 안내를 낸다', () => {
    mockHook({})
    render(<SprintBurndownGadget config={{}} />)

    expect(screen.getByText(gadgetStateLabels.notConfigured)).toBeInTheDocument()
  })

  it('로딩 중에는 차트를 그리지 않는다', () => {
    mockHook({ isLoading: true })
    render(<SprintBurndownGadget config={{ boardId: BOARD_ID }} />)

    expect(screen.getByText(gadgetStateLabels.loading)).toBeInTheDocument()
    expect(screen.queryByTestId('burndown-chart')).not.toBeInTheDocument()
  })
})
