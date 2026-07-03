// CycleTimeReport useQuery 상태 분기 테스트 — 로딩/성공(Cycle→Lead 순서)/빈/403/기타에러 (FR-RP-04 D6/D7 Task-7)
import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { CycleTimeResponse } from '@/api/cycle-time'
import { ApiError } from '@/api/client'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// vi.mock — fetchProjectCycleTime(api/cycle-time.ts) 격리
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/cycle-time', async () => {
  const actual = await vi.importActual<typeof import('@/api/cycle-time')>('@/api/cycle-time')
  return {
    ...actual,
    fetchProjectCycleTime: vi.fn(),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// vi.mock — CycleTimeMetricSection 자식 스텁 (제목만 렌더해 순서 단언에 사용)
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('./CycleTimeMetricSection', () => ({
  CycleTimeMetricSection: ({ title }: { title: string }) => (
    <div data-testid="cycle-time-metric-section">{title}</div>
  ),
}))

import { fetchProjectCycleTime } from '@/api/cycle-time'
import { CycleTimeReport } from './CycleTimeReport'

const mockFetchProjectCycleTime = vi.mocked(fetchProjectCycleTime)

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return wrapper
}

/** CycleTimeReport를 wrapper와 함께 렌더한다 */
function renderReport(projectKey = 'BTS') {
  const wrapper = createWrapper()
  return render(<CycleTimeReport projectKey={projectKey} />, { wrapper })
}

/** 정상 응답 픽스처 — Cycle/Lead 두 지표 모두 표본이 있다 */
const mockData: CycleTimeResponse = {
  projectKey: 'BTS',
  from: '2026-06-01',
  to: '2026-06-30',
  cycleTime: {
    count: 3,
    min: 100,
    max: 500,
    avg: 300,
    p25: 150,
    p50: 300,
    p75: 450,
    p90: 480,
    samples: [{ issueKey: 'BTS-1', seconds: 300 }],
  },
  leadTime: {
    count: 3,
    min: 200,
    max: 600,
    avg: 400,
    p25: 250,
    p50: 400,
    p75: 550,
    p90: 580,
    samples: [{ issueKey: 'BTS-1', seconds: 400 }],
  },
}

/** 빈 응답 픽스처 — Cycle/Lead 두 지표 모두 count===0 (isCycleTimeEmpty=true) */
const mockEmptyData: CycleTimeResponse = {
  projectKey: 'BTS',
  from: '2026-06-01',
  to: '2026-06-30',
  cycleTime: {
    count: 0,
    min: null,
    max: null,
    avg: null,
    p25: null,
    p50: null,
    p75: null,
    p90: null,
    samples: [],
  },
  leadTime: {
    count: 0,
    min: null,
    max: null,
    avg: null,
    p25: null,
    p50: null,
    p75: null,
    p90: null,
    samples: [],
  },
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 로딩 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeReport — 로딩 상태', () => {
  it('조회가 pending일 때 role=status로 로딩 문구가 노출된다', () => {
    mockFetchProjectCycleTime.mockReturnValue(new Promise(() => {}))

    renderReport()

    expect(screen.getByRole('status')).toHaveTextContent(cycleTimeLabels.status.loading)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 성공 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeReport — 성공 상태', () => {
  it('Cycle 섹션이 Lead 섹션보다 먼저 렌더된다', async () => {
    mockFetchProjectCycleTime.mockResolvedValue(mockData)

    renderReport()

    await waitFor(() => {
      expect(screen.getAllByTestId('cycle-time-metric-section')).toHaveLength(2)
    })

    const sections = screen.getAllByTestId('cycle-time-metric-section')
    expect(sections).toHaveLength(2)
    expect(sections[0]).toHaveTextContent(cycleTimeLabels.metric.cycleTitle)
    expect(sections[1]).toHaveTextContent(cycleTimeLabels.metric.leadTitle)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeReport — 빈 상태', () => {
  it('isCycleTimeEmpty가 true면 빈 상태 문구가 노출되고 섹션은 렌더되지 않는다', async () => {
    mockFetchProjectCycleTime.mockResolvedValue(mockEmptyData)

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cycleTimeLabels.status.empty)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('cycle-time-metric-section')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 403 에러 (권한 없음)
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeReport — 403 에러', () => {
  it('ApiError(403) 발생 시 forbidden 문구가 노출되고 응답 데이터는 노출되지 않는다', async () => {
    mockFetchProjectCycleTime.mockRejectedValue(
      new ApiError(403, { code: 'forbidden', issueKey: 'BTS-999' }),
    )

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cycleTimeLabels.status.forbidden)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('cycle-time-metric-section')).not.toBeInTheDocument()
    expect(screen.queryByText('BTS-999')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 기타 에러 (폴백)
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeReport — 기타 에러', () => {
  it('ApiError(500) 발생 시 loadFailed 문구로 폴백한다', async () => {
    mockFetchProjectCycleTime.mockRejectedValue(new ApiError(500, { code: 'internal_error' }))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cycleTimeLabels.status.loadFailed)).toBeInTheDocument()
    })
  })

  it('네트워크 에러(ApiError 아님) 발생 시에도 loadFailed 문구로 폴백한다', async () => {
    mockFetchProjectCycleTime.mockRejectedValue(new Error('network down'))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cycleTimeLabels.status.loadFailed)).toBeInTheDocument()
    })
  })
})
