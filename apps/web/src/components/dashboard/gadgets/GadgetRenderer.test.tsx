// GadgetRenderer 분기 테스트 — 켠 타입이 「지원되지 않는 가젯」으로 떨어지지 않는지
//
// ★이 파일이 재는 것은 **분기와 config 전달** 두 가지다. 각 가젯의 내용은 그 컴포넌트의
//   테스트가 잰다. 여기서 또 재면 세 번째 목록이 된다.
//
// ★「카탈로그에서 켠 타입 ↔ 여기 case」 의 집합 정합은 소스를 파싱하는
//   `scripts/workflow/gadget-catalog-renderer-parity.test.ts` 가 잰다. 이 파일은 그 집합의
//   각 원소가 **실제로 렌더되는지**를 잰다 — 판별식은 `case` 문자열의 존재만 보므로
//   `case 'x': return null` 같은 껍데기를 구분하지 못한다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { DashboardTile } from '@/lib/dashboard-layout'

vi.mock('./DistributionChartGadget', () => ({
  DistributionChartGadget: (props: { variant?: string; config?: Record<string, unknown> }) => (
    <div
      data-testid="distribution-chart-gadget"
      data-variant={props.variant ?? ''}
      data-project={String(props.config?.['projectKey'] ?? '')}
      data-field={String(props.config?.['field'] ?? '')}
    />
  ),
}))

vi.mock('./SprintBurndownGadget', () => ({
  SprintBurndownGadget: (props: { config?: Record<string, unknown> }) => (
    <div data-testid="sprint-burndown-gadget" data-board={String(props.config?.['boardId'] ?? '')} />
  ),
}))

vi.mock('./ActivityStreamGadget', () => ({
  ActivityStreamGadget: (props: { config?: Record<string, unknown> }) => (
    <div
      data-testid="activity-stream-gadget"
      data-project={String(props.config?.['projectKey'] ?? '')}
      data-max={String(props.config?.['maxItems'] ?? '')}
    />
  ),
}))

const { GadgetRenderer } = await import('./GadgetRenderer')

const BOARD_ID = '11111111-2222-3333-4444-555555555555'
const TILE_BASE = { i: 'g1', x: 0, y: 0, w: 4, h: 3, title: '' } as const

function tile(gadgetType: string, config: Record<string, unknown>): DashboardTile {
  return { ...TILE_BASE, gadgetType, config }
}

/** 「지원되지 않는 가젯입니다」 — 이 문구가 뜨면 켰는데 못 그린다는 뜻이다. */
const UNSUPPORTED = '지원되지 않는 가젯입니다'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('GadgetRenderer — 이 PR 이 켠 4종', () => {
  it.each([
    ['pie_chart', 'distribution-chart-gadget'],
    ['bar_chart', 'distribution-chart-gadget'],
    ['sprint_burndown', 'sprint-burndown-gadget'],
    ['activity_stream', 'activity-stream-gadget'],
  ])('%s → %s 를 그린다 (「지원되지 않는 가젯」이 아니다)', (gadgetType, testId) => {
    render(
      <GadgetRenderer
        tile={tile(gadgetType, { projectKey: 'BTS', field: 'status', boardId: BOARD_ID })}
      />,
    )

    expect(screen.getByTestId(testId)).toBeInTheDocument()
    expect(screen.queryByText(UNSUPPORTED)).not.toBeInTheDocument()
  })

  it('pie 와 bar 는 같은 컴포넌트에 다른 variant 로 간다 (M-4)', () => {
    const { unmount } = render(
      <GadgetRenderer tile={tile('pie_chart', { projectKey: 'BTS', field: 'status' })} />,
    )
    expect(screen.getByTestId('distribution-chart-gadget').getAttribute('data-variant')).toBe('pie')
    unmount()

    render(<GadgetRenderer tile={tile('bar_chart', { projectKey: 'BTS', field: 'status' })} />)
    expect(screen.getByTestId('distribution-chart-gadget').getAttribute('data-variant')).toBe('bar')
  })
})

describe('GadgetRenderer — config 전달', () => {
  // ★toGadgetConfig 가 필드를 빠뜨리면 config 는 저장돼 있는데 가젯이 못 읽는다.
  //   사용자에겐 「저장이 안 됐다」로 보이고, 가젯 자체 테스트는 목이라 이것을 못 잡는다.
  it('★field 와 projectKey 가 분포 차트까지 도달한다', () => {
    render(
      <GadgetRenderer tile={tile('pie_chart', { projectKey: 'ATLAS', field: 'priority' })} />,
    )

    const el = screen.getByTestId('distribution-chart-gadget')
    expect(el.getAttribute('data-project')).toBe('ATLAS')
    expect(el.getAttribute('data-field')).toBe('priority')
  })

  it('★boardId 가 번다운까지 도달한다', () => {
    render(<GadgetRenderer tile={tile('sprint_burndown', { boardId: BOARD_ID })} />)

    expect(screen.getByTestId('sprint-burndown-gadget').getAttribute('data-board')).toBe(BOARD_ID)
  })

  it('★projectKey 와 maxItems 가 활동 스트림까지 도달한다', () => {
    render(<GadgetRenderer tile={tile('activity_stream', { projectKey: 'ATLAS', maxItems: 25 })} />)

    const el = screen.getByTestId('activity-stream-gadget')
    expect(el.getAttribute('data-project')).toBe('ATLAS')
    expect(el.getAttribute('data-max')).toBe('25')
  })

  it('타입이 안 맞는 config 값은 undefined 로 떨어뜨린다 — 저장 JSON 을 손으로 고쳐도 안 죽는다', () => {
    render(
      <GadgetRenderer
        tile={tile('pie_chart', { projectKey: 123, field: { nested: true } })}
      />,
    )

    const el = screen.getByTestId('distribution-chart-gadget')
    expect(el.getAttribute('data-project')).toBe('')
    expect(el.getAttribute('data-field')).toBe('')
  })
})

describe('GadgetRenderer — 안전 분기 (기존 계약 유지)', () => {
  it('아직 안 켠 타입은 「지원되지 않는 가젯」으로 안전하게 떨어진다 (N1)', () => {
    render(<GadgetRenderer tile={tile('comments_recent', { projectKey: 'BTS' })} />)
    expect(screen.getByText(UNSUPPORTED)).toBeInTheDocument()
  })

  it('legacy 타일(gadgetType 없음)은 null 을 반환한다 — 호출측이 처리한다', () => {
    const { container } = render(<GadgetRenderer tile={{ ...TILE_BASE }} />)
    expect(container).toBeEmptyDOMElement()
  })
})
