// 번다운/번업 차트 라우트 페이지 테스트 — MSW 시드 기반 상태별 화면 + 토글 (FR-RP-01 D6/D7 Task-4)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import {
  burndownHandlers,
  resetBurndownStore,
  seedBurndown,
  DEFAULT_BURNDOWN,
  DEFAULT_SPRINT_ID,
  FORBIDDEN_SPRINT_ID,
  DATES_REQUIRED_SPRINT_ID,
} from '@/mocks/burndown-handlers'
import { burndownLabels } from '@/i18n/burndown-labels'
import {
  SprintBurndownPage,
  SprintBurndownRouteAdapter,
} from '@/routes/projects.$projectKey.sprints.$sprintId.burndown'

// ─────────────────────────────────────────────────────────────────────────────
// mock — @tanstack/react-router (RouteAdapter 단위 테스트용)
// DEFAULT_SPRINT_ID와 동일 문자열 하드코딩 — vi.mock factory는 호이스팅되어 모듈 상단 import를 참조할 수 없다.
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS', sprintId: 'a0000000-0000-4000-8000-000000000001' }),
  useSearch: () => ({}),
  useNavigate: () => mockNavigate,
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

function renderPage(sprintId: string, view: 'burndown' | 'burnup' = 'burndown', onViewChange = vi.fn()) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <SprintBurndownPage sprintId={sprintId} view={view} onViewChange={onViewChange} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <SprintBurndownRouteAdapter />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  resetBurndownStore()
  seedBurndown(DEFAULT_BURNDOWN)
  server.use(...burndownHandlers)
  mockNavigate.mockClear()
})

// ─────────────────────────────────────────────────────────────────────────────
// SprintBurndownPage
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintBurndownPage', () => {
  it('T-RP-BD-1: 200 응답(view=burndown) → 차트가 렌더되고 번다운 탭이 선택됨', async () => {
    renderPage(DEFAULT_SPRINT_ID, 'burndown')

    await waitFor(() => {
      expect(screen.getByRole('img', { name: burndownLabels.chart.ariaLabel })).toBeInTheDocument()
    })

    expect(screen.getByRole('tab', { name: burndownLabels.toggle.burndown })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByRole('tab', { name: burndownLabels.toggle.burnup })).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  it('T-RP-BD-2: view=burnup → 차트가 렌더되고 번업 탭이 선택됨', async () => {
    renderPage(DEFAULT_SPRINT_ID, 'burnup')

    await waitFor(() => {
      expect(screen.getByRole('img', { name: burndownLabels.chart.ariaLabel })).toBeInTheDocument()
    })

    expect(screen.getByRole('tab', { name: burndownLabels.toggle.burnup })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('T-RP-BD-3: 로딩 중 스켈레톤(role=status)이 표시된다', () => {
    renderPage(DEFAULT_SPRINT_ID)

    expect(screen.getByRole('status', { name: burndownLabels.status.loading })).toBeInTheDocument()
  })

  it('T-RP-BD-4: 422(AGILE_SPRINT_DATES_REQUIRED) → 날짜필요 안내, 차트 미렌더', async () => {
    renderPage(DATES_REQUIRED_SPRINT_ID)

    await waitFor(() => {
      expect(screen.getByText(burndownLabels.status.datesRequired)).toBeInTheDocument()
    })
    expect(screen.queryByRole('img', { name: burndownLabels.chart.ariaLabel })).not.toBeInTheDocument()
  })

  it('T-RP-BD-5: 403(AGILE_ACCESS_DENIED) → 권한없음 안내', async () => {
    renderPage(FORBIDDEN_SPRINT_ID)

    await waitFor(() => {
      expect(screen.getByText(burndownLabels.status.forbidden)).toBeInTheDocument()
    })
  })

  it('T-RP-BD-6: 404(미시드 sprintId) → 스프린트없음 안내', async () => {
    renderPage('sprint-does-not-exist')

    await waitFor(() => {
      expect(screen.getByText(burndownLabels.status.sprintNotFound)).toBeInTheDocument()
    })
  })

  it('T-RP-BD-7: points가 빈 배열이면 warm 빈 상태 안내', async () => {
    seedBurndown({ ...DEFAULT_BURNDOWN, sprintId: 'sprint-empty', points: [] })

    renderPage('sprint-empty')

    await waitFor(() => {
      expect(screen.getByText(burndownLabels.status.empty)).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SprintBurndownRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

describe('SprintBurndownRouteAdapter', () => {
  it('T-RP-BD-8: useParams의 sprintId로 조회해 페이지 제목과 차트를 렌더한다', async () => {
    renderAdapter()

    expect(
      screen.getByRole('heading', { level: 2, name: burndownLabels.page.title }),
    ).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByRole('img', { name: burndownLabels.chart.ariaLabel })).toBeInTheDocument()
    })
  })

  it('T-RP-BD-9: 번업 탭 클릭 → navigate가 올바른 params와 search(view=burnup)로 호출된다', async () => {
    const user = userEvent.setup()
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByRole('img', { name: burndownLabels.chart.ariaLabel })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('tab', { name: burndownLabels.toggle.burnup }))

    expect(mockNavigate).toHaveBeenCalledTimes(1)
    const call = mockNavigate.mock.calls[0]?.[0] as {
      to: string
      params: { projectKey: string; sprintId: string }
      search: (prev: Record<string, unknown>) => Record<string, unknown>
    }
    expect(call.to).toBe('/projects/$projectKey/sprints/$sprintId/burndown')
    expect(call.params).toEqual({ projectKey: 'ATLAS', sprintId: 'a0000000-0000-4000-8000-000000000001' })
    expect(call.search({})).toEqual({ view: 'burnup' })
  })
})
