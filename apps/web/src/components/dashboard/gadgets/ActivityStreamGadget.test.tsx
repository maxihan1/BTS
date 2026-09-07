// 활동 스트림 가젯 테스트 — 스코프 전달 · 빈 항목 거르기 · 세 상태
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@/components/project/summary/ActivityEntryList', () => ({
  // ★entries 개수를 되뱉는다 — 목이 삼키면 「걸러서 넘겼나」가 안 보인다.
  ActivityEntryList: (props: { entries?: readonly unknown[] }) => (
    <div data-testid="activity-entry-list" data-count={String(props.entries?.length ?? -1)} />
  ),
}))

vi.mock('@/hooks/use-project-summary', () => ({
  useProjectActivity: vi.fn(),
}))

const { useProjectActivity } = await import('@/hooks/use-project-summary')
const { ActivityStreamGadget } = await import('./ActivityStreamGadget')
const { gadgetStateLabels } = await import('@/i18n/dashboard-labels')

type HookResult = ReturnType<typeof useProjectActivity>

function entry(issueKey: string, itemCount: number) {
  return {
    issueKey,
    actorId: null,
    actorName: '홍길동',
    createdAt: '2026-09-07T00:00:00Z',
    items: Array.from({ length: itemCount }, () => ({ field: 'status' })),
  }
}

function mockActivity(over: Partial<{ data: unknown; isPending: boolean; isError: boolean }>): void {
  vi.mocked(useProjectActivity).mockReturnValue({
    data: { entries: [entry('BTS-1', 1), entry('BTS-2', 2)] },
    isPending: false,
    isError: false,
    ...over,
  } as unknown as HookResult)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ActivityStreamGadget', () => {
  it('★projectKey 와 클램프된 limit 을 훅에 넘긴다 (목이 삼키는 것 방지)', () => {
    mockActivity({})
    render(<ActivityStreamGadget config={{ projectKey: 'BTS', maxItems: 25 }} />)
    expect(useProjectActivity).toHaveBeenCalledWith('BTS', 25)
  })

  it('maxItems 미지정이면 기본 10 으로 부른다', () => {
    mockActivity({})
    render(<ActivityStreamGadget config={{ projectKey: 'BTS' }} />)
    expect(useProjectActivity).toHaveBeenCalledWith('BTS', 10)
  })

  it.each([
    [0, 1],
    [999, 50],
  ])('maxItems=%i 는 %i 로 클램프된다 — 저장 JSON 을 손으로 고쳐도 500 이 안 난다', (given, want) => {
    mockActivity({})
    render(<ActivityStreamGadget config={{ projectKey: 'BTS', maxItems: given }} />)
    expect(useProjectActivity).toHaveBeenCalledWith('BTS', want)
  })

  it('★변경 항목이 0개인 그룹은 걸러서 넘긴다 — 요약 화면과 같은 규칙', () => {
    mockActivity({
      data: { entries: [entry('BTS-1', 1), entry('BTS-2', 0), entry('BTS-3', 3)] },
    })
    render(<ActivityStreamGadget config={{ projectKey: 'BTS' }} />)

    // 3건 중 items 가 빈 1건을 뺀 2건만 목록으로 간다.
    expect(screen.getByTestId('activity-entry-list').getAttribute('data-count')).toBe('2')
  })

  it('걸러낸 결과가 0건이면 빈 상태를 낸다 — 목록을 그리지 않는다', () => {
    mockActivity({ data: { entries: [entry('BTS-1', 0)] } })
    render(<ActivityStreamGadget config={{ projectKey: 'BTS' }} />)

    expect(screen.getByText(gadgetStateLabels.noActivity)).toBeInTheDocument()
    expect(screen.queryByTestId('activity-entry-list')).not.toBeInTheDocument()
  })

  it('조회 실패는 빈 상태로 흡수한다 — 대시보드가 죽지 않는다', () => {
    mockActivity({ data: undefined, isError: true })
    render(<ActivityStreamGadget config={{ projectKey: 'GONE' }} />)

    expect(screen.getByText(gadgetStateLabels.loadFailed)).toBeInTheDocument()
  })

  it('projectKey 가 없으면 설정 안내를 낸다', () => {
    mockActivity({})
    render(<ActivityStreamGadget config={{}} />)

    expect(screen.getByText(gadgetStateLabels.notConfigured)).toBeInTheDocument()
  })

  it('로딩 중에는 목록을 그리지 않는다', () => {
    mockActivity({ data: undefined, isPending: true })
    render(<ActivityStreamGadget config={{ projectKey: 'BTS' }} />)

    expect(screen.getByText(gadgetStateLabels.loading)).toBeInTheDocument()
    expect(screen.queryByTestId('activity-entry-list')).not.toBeInTheDocument()
  })
})
