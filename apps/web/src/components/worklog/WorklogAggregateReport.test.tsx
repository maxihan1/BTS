// 워크로그 집계 보고 메인 컴포넌트 테스트 — 필터 상태·4가지 렌더 상태 (FR-TT-02 Task-6)
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { WorklogAggregateResponse } from '@/api/worklog-aggregate'
import { ApiError } from '@/api/client'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'
import { formatSeconds } from '@/lib/duration'

// ─────────────────────────────────────────────────────────────────────────────
// vi.mock — useWorklogAggregate 훅 격리
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/hooks/use-worklog-aggregate', () => ({
  useWorklogAggregate: vi.fn(),
}))

import { useWorklogAggregate } from '@/hooks/use-worklog-aggregate'
import { WorklogAggregateReport } from './WorklogAggregateReport'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const mockUseWorklogAggregate = vi.mocked(useWorklogAggregate)

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return wrapper
}

/** 정상 응답 픽스처 */
const mockData: WorklogAggregateResponse = {
  by: 'issue',
  buckets: [
    { key: 'BTS-1', label: 'BTS-1', timeSpentSeconds: 3600, worklogCount: 2 },
    { key: 'BTS-2', label: 'BTS-2', timeSpentSeconds: 7200, worklogCount: 4 },
  ],
  totalTimeSpentSeconds: 10800,
}

/** 빈 버킷 응답 픽스처 */
const mockEmptyData: WorklogAggregateResponse = {
  by: 'issue',
  buckets: [],
  totalTimeSpentSeconds: 0,
}

/** WorklogAggregateReport를 wrapper와 함께 렌더한다 */
function renderReport(projectKey = 'BTS') {
  const wrapper = createWrapper()
  return render(<WorklogAggregateReport projectKey={projectKey} />, { wrapper })
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 기본 렌더 + 차원 선택
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — 기본 렌더 + 차원 선택', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('기본 by=issue로 렌더 시 useWorklogAggregate가 by=issue로 호출된다', () => {
    renderReport()
    expect(mockUseWorklogAggregate).toHaveBeenCalledWith(
      'BTS',
      expect.objectContaining({ by: 'issue' }),
    )
  })

  it('차원 셀렉터가 존재한다', () => {
    renderReport()
    expect(
      screen.getByLabelText(worklogAggregateLabels.filter.dimensionLabel),
    ).toBeInTheDocument()
  })

  it('차원 셀렉터에서 사용자별 선택 시 by=user로 재조회한다', async () => {
    const user = userEvent.setup()
    renderReport()

    const select = screen.getByLabelText(worklogAggregateLabels.filter.dimensionLabel)
    await user.selectOptions(select, 'user')

    await waitFor(() => {
      expect(mockUseWorklogAggregate).toHaveBeenCalledWith(
        'BTS',
        expect.objectContaining({ by: 'user' }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — granularity 셀렉터 노출/숨김
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — granularity 셀렉터 노출', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('by=issue일 때 granularity 셀렉터가 숨겨진다', () => {
    renderReport()
    expect(
      screen.queryByLabelText(worklogAggregateLabels.filter.granularityLabel),
    ).not.toBeInTheDocument()
  })

  it('by=user일 때 granularity 셀렉터가 숨겨진다', async () => {
    const user = userEvent.setup()
    renderReport()

    const dimSelect = screen.getByLabelText(worklogAggregateLabels.filter.dimensionLabel)
    await user.selectOptions(dimSelect, 'user')

    expect(
      screen.queryByLabelText(worklogAggregateLabels.filter.granularityLabel),
    ).not.toBeInTheDocument()
  })

  it('by=period일 때 granularity 셀렉터가 노출된다', async () => {
    const user = userEvent.setup()
    renderReport()

    const dimSelect = screen.getByLabelText(worklogAggregateLabels.filter.dimensionLabel)
    await user.selectOptions(dimSelect, 'period')

    expect(
      screen.getByLabelText(worklogAggregateLabels.filter.granularityLabel),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 차원 전환 시 granularity 상태 보존
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — 차원 전환 시 granularity 상태 보존', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('period에서 month 설정 → issue로 전환 → period 복귀 시 month가 유지된다', async () => {
    const user = userEvent.setup()
    renderReport()

    const dimSelect = screen.getByLabelText(worklogAggregateLabels.filter.dimensionLabel)

    // period로 전환
    await user.selectOptions(dimSelect, 'period')

    // granularity를 month로 변경
    const granSelect = screen.getByLabelText(worklogAggregateLabels.filter.granularityLabel)
    await user.selectOptions(granSelect, 'month')

    // issue로 전환 (granularity 셀렉터 숨겨짐)
    await user.selectOptions(dimSelect, 'issue')
    expect(
      screen.queryByLabelText(worklogAggregateLabels.filter.granularityLabel),
    ).not.toBeInTheDocument()

    // period로 복귀
    await user.selectOptions(dimSelect, 'period')

    // granularity가 month로 유지되어야 한다
    const granSelectRestored = screen.getByLabelText(worklogAggregateLabels.filter.granularityLabel)
    expect((granSelectRestored as HTMLSelectElement).value).toBe('month')

    // 훅 호출 시 granularity=month가 전달되어야 한다
    await waitFor(() => {
      expect(mockUseWorklogAggregate).toHaveBeenCalledWith(
        'BTS',
        expect.objectContaining({ by: 'period', granularity: 'month' }),
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — from/to 날짜 입력
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — from/to 날짜 입력', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('from 입력 시 params.from에 반영된다', async () => {
    const user = userEvent.setup()
    renderReport()

    const fromInput = screen.getByLabelText(worklogAggregateLabels.filter.fromLabel)
    await user.type(fromInput, '2026-01-01')

    await waitFor(() => {
      expect(mockUseWorklogAggregate).toHaveBeenCalledWith(
        'BTS',
        expect.objectContaining({ from: '2026-01-01' }),
      )
    })
  })

  it('to 입력 시 params.to에 반영된다', async () => {
    const user = userEvent.setup()
    renderReport()

    const toInput = screen.getByLabelText(worklogAggregateLabels.filter.toLabel)
    await user.type(toInput, '2026-12-31')

    await waitFor(() => {
      expect(mockUseWorklogAggregate).toHaveBeenCalledWith(
        'BTS',
        expect.objectContaining({ to: '2026-12-31' }),
      )
    })
  })

  it('from>to 입력 시 안내 메시지가 노출되고 from/to가 params에 포함되지 않는다', async () => {
    const user = userEvent.setup()
    renderReport()

    const fromInput = screen.getByLabelText(worklogAggregateLabels.filter.fromLabel)
    const toInput = screen.getByLabelText(worklogAggregateLabels.filter.toLabel)

    // to를 먼저 설정한 뒤 from을 더 나중 날짜로 설정
    await user.type(toInput, '2026-01-01')
    await user.type(fromInput, '2026-06-01')

    // 안내 메시지 노출
    expect(screen.getByRole('alert')).toBeInTheDocument()

    // from/to가 훅 파라미터에 포함되지 않아야 한다
    await waitFor(() => {
      const calls = mockUseWorklogAggregate.mock.calls
      const lastCall = calls[calls.length - 1]
      expect(lastCall?.[1]).not.toHaveProperty('from', '2026-06-01')
      expect(lastCall?.[1]).not.toHaveProperty('to', '2026-01-01')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 정상 상태 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — 정상 상태', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('총 소요 시간 요약이 표시된다', () => {
    renderReport()
    const formatted = formatSeconds(mockData.totalTimeSpentSeconds)
    // 요약 영역과 테이블 합계 행 양쪽에 동일 텍스트가 있을 수 있으므로 getAllByText 사용
    const cells = screen.getAllByText(formatted)
    expect(cells.length).toBeGreaterThanOrEqual(1)
    expect(
      screen.getByText(worklogAggregateLabels.summary.totalTimeLabel),
    ).toBeInTheDocument()
  })

  it('차트 컴포넌트가 렌더된다', () => {
    renderReport()
    // WorklogAggregateChart는 aria-label="워크로그 집계 막대 차트"를 가진다
    expect(
      screen.getByRole('img', { name: worklogAggregateLabels.chart.ariaLabel }),
    ).toBeInTheDocument()
  })

  it('테이블 헤더가 렌더된다', () => {
    renderReport()
    expect(
      screen.getByText(worklogAggregateLabels.table.headerLabel),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — 빈 상태', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockEmptyData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('빈 상태 메시지가 표시된다', () => {
    renderReport()
    expect(
      screen.getByText(worklogAggregateLabels.empty.message),
    ).toBeInTheDocument()
  })

  it('빈 상태일 때 차트가 표시되지 않는다', () => {
    renderReport()
    expect(
      screen.queryByRole('img', { name: worklogAggregateLabels.chart.ariaLabel }),
    ).not.toBeInTheDocument()
  })

  it('빈 상태일 때 테이블 헤더가 표시되지 않는다', () => {
    renderReport()
    expect(
      screen.queryByText(worklogAggregateLabels.table.headerLabel),
    ).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 로딩 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — 로딩 상태', () => {
  it('isPending=true일 때 로딩 표시가 렌더된다', () => {
    mockUseWorklogAggregate.mockReturnValue({
      data: undefined,
      isPending: true,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)

    renderReport()

    // 로딩 인디케이터는 role="status" 또는 특정 텍스트로 확인
    expect(screen.getByRole('status')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — C1 헤더 이중 렌더 금지 (codereview fix)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — C1 헤더 이중 렌더 금지', () => {
  beforeEach(() => {
    mockUseWorklogAggregate.mockReturnValue({
      data: mockData,
      isPending: false,
      isError: false,
      error: null,
    } as unknown as ReturnType<typeof useWorklogAggregate>)
  })

  it('리포트 컴포넌트는 h1(level-1 heading)을 렌더하지 않는다 — 헤더는 라우트 페이지 담당', () => {
    renderReport()
    // 라우트 페이지가 헤더를 담당하므로 WorklogAggregateReport 자체는 h1을 포함하면 안 된다
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 에러 상태 (403 vs 일반)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateReport — 에러 상태', () => {
  it('isError+ApiError(403)일 때 ProjectNotFoundScreen(접근 권한 안내)이 렌더된다', () => {
    const apiError = new ApiError(403, { code: 'forbidden' })
    mockUseWorklogAggregate.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError,
    } as unknown as ReturnType<typeof useWorklogAggregate>)

    renderReport()

    expect(screen.getByText('접근 권한이 없습니다')).toBeInTheDocument()
  })

  it('isError이지만 403이 아닌 일반 에러일 때 일반 에러 메시지가 표시된다', () => {
    const apiError = new ApiError(500, { code: 'internal_error' })
    mockUseWorklogAggregate.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError,
    } as unknown as ReturnType<typeof useWorklogAggregate>)

    renderReport()

    expect(
      screen.getByText(worklogAggregateLabels.error.generalMessage),
    ).toBeInTheDocument()
  })

  it('403 에러 시 일반 에러 메시지가 표시되지 않는다', () => {
    const apiError = new ApiError(403, { code: 'forbidden' })
    mockUseWorklogAggregate.mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: apiError,
    } as unknown as ReturnType<typeof useWorklogAggregate>)

    renderReport()

    expect(
      screen.queryByText(worklogAggregateLabels.error.generalMessage),
    ).not.toBeInTheDocument()
  })
})
