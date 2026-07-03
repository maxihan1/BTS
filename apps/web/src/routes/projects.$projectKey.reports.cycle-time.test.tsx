// Cycle Time / Lead Time 분포 라우트 페이지 단위 테스트 — RouteAdapter useParams 추출 + Page 헤더/컴포넌트 렌더 (FR-RP-04 D6/D7 Task-9)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import {
  ProjectCycleTimeReportRouteAdapter,
  CycleTimeReportPage,
} from '@/routes/projects.$projectKey.reports.cycle-time'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'
import { backlogLabels } from '@/i18n/backlog-labels'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// CycleTimeReport는 별도 통합 테스트에서 검증하므로 단위 테스트에서 vi.mock으로 격리
vi.mock('@/components/cycle-time/CycleTimeReport', () => ({
  CycleTimeReport: ({ projectKey }: { projectKey: string }) => (
    <div data-testid="cycle-time-report" data-project-key={projectKey}>
      cycle-time-report-mock
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
      <CycleTimeReportPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectCycleTimeReportRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('CycleTimeReportPage', () => {
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
   * T-RP-D1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 CycleTimeReport에 projectKey="ATLAS"가 전달된다.
   */
  it('T-RP-D1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      const report = screen.getByTestId('cycle-time-report')
      expect(report).toBeInTheDocument()
      expect(report).toHaveAttribute('data-project-key', 'ATLAS')
    })
  })

  /**
   * T-RP-D2. Page가 cycleTimeLabels.page.title h1 헤더를 렌더한다.
   */
  it('T-RP-D2: Page가 페이지 제목 h1을 렌더한다', () => {
    renderPage('ATLAS')

    expect(
      screen.getByRole('heading', { level: 1, name: cycleTimeLabels.page.title }),
    ).toBeInTheDocument()
  })

  /**
   * T-RP-D3. Page가 cycleTimeLabels.page.description 문구를 렌더한다.
   */
  it('T-RP-D3: Page가 설명 문구를 렌더한다', () => {
    renderPage('ATLAS')

    expect(screen.getByText(cycleTimeLabels.page.description)).toBeInTheDocument()
  })

  /**
   * T-RP-D4. Page가 CycleTimeReport를 렌더하고 projectKey props를 올바르게 전달한다.
   */
  it('T-RP-D4: Page가 CycleTimeReport에 projectKey를 전달한다', () => {
    renderPage('MYPROJECT')

    const report = screen.getByTestId('cycle-time-report')
    expect(report).toBeInTheDocument()
    expect(report).toHaveAttribute('data-project-key', 'MYPROJECT')
  })

  /**
   * T-RP-D5. 백로그 nav 링크 라벨(cycleTimeLink)이 정의되어 있다 — backlog.tsx 링크 추가의 최소 커버.
   * backlog.tsx 자체 렌더 테스트는 기존 projects.$projectKey.backlog.test.tsx 범위(파일 스코프 외)이므로
   * 라벨 존재만 이 파일에서 검증한다.
   */
  it('T-RP-D5: backlogLabels.page.cycleTimeLink 라벨이 정의되어 있다', () => {
    expect(backlogLabels.page.cycleTimeLink).toBeTruthy()
  })
})
