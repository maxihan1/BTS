// 워크로그 집계 보고 라우트 페이지 단위 테스트 — RouteAdapter useParams 추출 + Page 헤더/컴포넌트 렌더
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import {
  ProjectWorklogReportRouteAdapter,
  ProjectWorklogReportPage,
} from '@/routes/projects.$projectKey.reports.worklog'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// WorklogAggregateReport는 별도 통합 테스트에서 검증하므로 단위 테스트에서 vi.mock으로 격리
vi.mock('@/components/worklog/WorklogAggregateReport', () => ({
  WorklogAggregateReport: ({ projectKey }: { projectKey: string }) => (
    <div data-testid="worklog-aggregate-report" data-project-key={projectKey}>
      worklog-aggregate-report-mock
    </div>
  ),
}))

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderPage(projectKey = 'ATLAS') {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectWorklogReportPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectWorklogReportRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectWorklogReportPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '00000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-TT-W1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 WorklogAggregateReport에 projectKey="ATLAS"가 전달된다.
   */
  it('T-TT-W1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      const report = screen.getByTestId('worklog-aggregate-report')
      expect(report).toBeInTheDocument()
      expect(report).toHaveAttribute('data-project-key', 'ATLAS')
    })
  })

  /**
   * T-TT-W2. Page가 worklogAggregateLabels.page.title h1 헤더를 렌더한다.
   */
  it('T-TT-W2: Page가 페이지 제목 h1을 렌더한다', () => {
    renderPage('ATLAS')

    expect(
      screen.getByRole('heading', { level: 1, name: worklogAggregateLabels.page.title }),
    ).toBeInTheDocument()
  })

  /**
   * T-TT-W3. Page가 worklogAggregateLabels.page.description 문구를 렌더한다.
   */
  it('T-TT-W3: Page가 설명 문구를 렌더한다', () => {
    renderPage('ATLAS')

    expect(screen.getByText(worklogAggregateLabels.page.description)).toBeInTheDocument()
  })

  /**
   * T-TT-W4. Page가 WorklogAggregateReport를 렌더하고 projectKey props를 올바르게 전달한다.
   */
  it('T-TT-W4: Page가 WorklogAggregateReport에 projectKey를 전달한다', () => {
    renderPage('MYPROJECT')

    const report = screen.getByTestId('worklog-aggregate-report')
    expect(report).toBeInTheDocument()
    expect(report).toHaveAttribute('data-project-key', 'MYPROJECT')
  })
})
