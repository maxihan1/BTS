// VelocityReport useQuery 상태 분기 테스트 — 로딩/성공/빈/403/기타에러 (FR-RP-02 D6/D7 Task-3)
import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { VelocityResponse } from '@/api/velocity'
import { ApiError } from '@/api/client'
import { velocityLabels } from '@/i18n/velocity-labels'

// ─────────────────────────────────────────────────────────────────────────────
// vi.mock — fetchProjectVelocity(Task-1) 격리
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/velocity', () => ({
  fetchProjectVelocity: vi.fn(),
}))

import { fetchProjectVelocity } from '@/api/velocity'
import { VelocityReport } from './VelocityReport'

const mockFetchProjectVelocity = vi.mocked(fetchProjectVelocity)

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

/** VelocityReport를 wrapper와 함께 렌더한다 */
function renderReport(projectKey = 'BTS') {
  const wrapper = createWrapper()
  return render(<VelocityReport projectKey={projectKey} />, { wrapper })
}

/** 정상 응답 픽스처 — sprints 1건 */
const mockData: VelocityResponse = {
  projectKey: 'BTS',
  averageCommitmentSeconds: 30600,
  averageCompletedSeconds: 25200,
  sprints: [
    {
      sprintId: '11111111-1111-4111-8111-111111111111',
      name: 'Sprint 1',
      startDate: '2026-06-01',
      endDate: '2026-06-14',
      commitmentSeconds: 36000,
      completedSeconds: 28800,
    },
  ],
}

/** 빈 sprints 응답 픽스처 */
const mockEmptyData: VelocityResponse = {
  ...mockData,
  sprints: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 로딩 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('VelocityReport — 로딩 상태', () => {
  it('조회가 pending일 때 로딩 문구가 노출된다', () => {
    mockFetchProjectVelocity.mockReturnValue(new Promise(() => {}))

    renderReport()

    expect(screen.getByText(velocityLabels.status.loading)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 성공 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('VelocityReport — 성공 상태', () => {
  it('sprints가 1개 이상이면 차트 컨테이너(role=img)가 렌더된다', async () => {
    mockFetchProjectVelocity.mockResolvedValue(mockData)

    renderReport()

    await waitFor(() => {
      expect(
        screen.getByRole('img', { name: velocityLabels.chart.ariaLabel }),
      ).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('VelocityReport — 빈 상태', () => {
  it('sprints가 빈 배열이면 빈 상태 문구가 노출되고 차트는 렌더되지 않는다', async () => {
    mockFetchProjectVelocity.mockResolvedValue(mockEmptyData)

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(velocityLabels.status.empty)).toBeInTheDocument()
    })
    expect(
      screen.queryByRole('img', { name: velocityLabels.chart.ariaLabel }),
    ).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 403 에러 (권한 없음)
// ─────────────────────────────────────────────────────────────────────────────

describe('VelocityReport — 403 에러', () => {
  it('ApiError(403) 발생 시 forbidden 문구가 노출되고 데이터는 노출되지 않는다', async () => {
    mockFetchProjectVelocity.mockRejectedValue(new ApiError(403, { code: 'forbidden' }))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(velocityLabels.status.forbidden)).toBeInTheDocument()
    })
    expect(
      screen.queryByRole('img', { name: velocityLabels.chart.ariaLabel }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('Sprint 1')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 기타 에러 (폴백)
// ─────────────────────────────────────────────────────────────────────────────

describe('VelocityReport — 기타 에러', () => {
  it('ApiError(500) 발생 시 loadFailed 문구로 폴백한다', async () => {
    mockFetchProjectVelocity.mockRejectedValue(new ApiError(500, { code: 'internal_error' }))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(velocityLabels.status.loadFailed)).toBeInTheDocument()
    })
  })

  it('네트워크 에러(ApiError 아님) 발생 시에도 loadFailed 문구로 폴백한다', async () => {
    mockFetchProjectVelocity.mockRejectedValue(new Error('network down'))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(velocityLabels.status.loadFailed)).toBeInTheDocument()
    })
  })
})
