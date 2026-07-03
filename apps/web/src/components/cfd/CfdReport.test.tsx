// CfdReport useQuery 상태 분기 테스트 — 로딩/성공/빈/403/기타에러 (FR-RP-03 D6/D7 Task-4)
import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { CfdResponse } from '@/api/cfd'
import { ApiError } from '@/api/client'
import { cfdLabels } from '@/i18n/cfd-labels'

// ─────────────────────────────────────────────────────────────────────────────
// vi.mock — fetchProjectCfd(api/cfd.ts) 격리
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/cfd', async () => {
  const actual = await vi.importActual<typeof import('@/api/cfd')>('@/api/cfd')
  return {
    ...actual,
    fetchProjectCfd: vi.fn(),
  }
})

import { fetchProjectCfd } from '@/api/cfd'
import { CfdReport } from './CfdReport'

const mockFetchProjectCfd = vi.mocked(fetchProjectCfd)

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

/** CfdReport를 wrapper와 함께 렌더한다 */
function renderReport(projectKey = 'BTS') {
  const wrapper = createWrapper()
  return render(<CfdReport projectKey={projectKey} />, { wrapper })
}

/** 정상 응답 픽스처 — 카운트가 있는 포인트 1건 */
const mockData: CfdResponse = {
  projectKey: 'BTS',
  from: '2026-06-01',
  to: '2026-06-30',
  points: [
    { date: '2026-06-01', todoCount: 3, inProgressCount: 2, doneCount: 1 },
  ],
}

/** 빈 응답 픽스처 — 모든 카운트 합이 0 (isCfdEmpty=true) */
const mockEmptyData: CfdResponse = {
  ...mockData,
  points: [{ date: '2026-06-01', todoCount: 0, inProgressCount: 0, doneCount: 0 }],
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 로딩 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CfdReport — 로딩 상태', () => {
  it('조회가 pending일 때 로딩 문구가 노출된다', () => {
    mockFetchProjectCfd.mockReturnValue(new Promise(() => {}))

    renderReport()

    expect(screen.getByText(cfdLabels.status.loading)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 성공 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CfdReport — 성공 상태', () => {
  it('카운트가 있는 데이터면 차트 컨테이너(role=img)가 렌더된다', async () => {
    mockFetchProjectCfd.mockResolvedValue(mockData)

    renderReport()

    await waitFor(() => {
      expect(screen.getByRole('img', { name: cfdLabels.chart.ariaLabel })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('CfdReport — 빈 상태', () => {
  it('isCfdEmpty가 true면 빈 상태 문구가 노출되고 차트는 렌더되지 않는다', async () => {
    mockFetchProjectCfd.mockResolvedValue(mockEmptyData)

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cfdLabels.status.empty)).toBeInTheDocument()
    })
    expect(screen.queryByRole('img', { name: cfdLabels.chart.ariaLabel })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 403 에러 (권한 없음)
// ─────────────────────────────────────────────────────────────────────────────

describe('CfdReport — 403 에러', () => {
  it('ApiError(403) 발생 시 forbidden 문구가 노출되고 데이터는 노출되지 않는다', async () => {
    mockFetchProjectCfd.mockRejectedValue(new ApiError(403, { code: 'forbidden' }))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cfdLabels.status.forbidden)).toBeInTheDocument()
    })
    expect(screen.queryByRole('img', { name: cfdLabels.chart.ariaLabel })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 — 기타 에러 (폴백)
// ─────────────────────────────────────────────────────────────────────────────

describe('CfdReport — 기타 에러', () => {
  it('ApiError(500) 발생 시 loadFailed 문구로 폴백한다', async () => {
    mockFetchProjectCfd.mockRejectedValue(new ApiError(500, { code: 'internal_error' }))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cfdLabels.status.loadFailed)).toBeInTheDocument()
    })
  })

  it('네트워크 에러(ApiError 아님) 발생 시에도 loadFailed 문구로 폴백한다', async () => {
    mockFetchProjectCfd.mockRejectedValue(new Error('network down'))

    renderReport()

    await waitFor(() => {
      expect(screen.getByText(cfdLabels.status.loadFailed)).toBeInTheDocument()
    })
  })
})
