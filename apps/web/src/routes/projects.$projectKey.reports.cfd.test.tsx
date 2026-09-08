// 누적 흐름도(CFD) 라우트 페이지 단위 테스트 — RouteAdapter useParams 추출 + Page 헤더/컴포넌트 렌더 (FR-RP-03 D6/D7 Task-5)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import {
  ProjectCfdReportRouteAdapter,
  CfdReportPage,
} from '@/routes/projects.$projectKey.reports.cfd'
import { cfdLabels } from '@/i18n/cfd-labels'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// 본문 상단 리포트 서브내비 격리 (Jira 패리티 JR-2).
// 🛑 이 파일은 `@tanstack/react-router` 를 **모듈 통째로** mock 하므로 `Link` 가 없다.
//    서브내비를 그대로 렌더하면 「No "Link" export is defined on the mock」으로 죽는다.
//    서브내비의 실제 계약(4링크 · aria-current · nav 이름)은 라우터가 실재하는
//    `components/project/__tests__/ProjectReportsNav.test.tsx` 가 전수로 지킨다 —
//    여기서 보는 것은 이 화면의 관심사(useParams 추출 · 헤더 · 본문 컴포넌트)뿐이다.
vi.mock('@/components/project/ProjectReportsNav', () => ({
  ProjectReportsNav: ({ projectKey }: { projectKey: string }) => (
    <div data-testid="project-reports-nav" data-project-key={projectKey} />
  ),
}))

// CfdReport는 별도 통합 테스트에서 검증하므로 단위 테스트에서 vi.mock으로 격리
vi.mock('@/components/cfd/CfdReport', () => ({
  CfdReport: ({ projectKey }: { projectKey: string }) => (
    <div data-testid="cfd-report" data-project-key={projectKey}>
      cfd-report-mock
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
      <CfdReportPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectCfdReportRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('CfdReportPage', () => {
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
   * T-RP-C1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 CfdReport에 projectKey="ATLAS"가 전달된다.
   */
  it('T-RP-C1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      const report = screen.getByTestId('cfd-report')
      expect(report).toBeInTheDocument()
      expect(report).toHaveAttribute('data-project-key', 'ATLAS')
    })
  })

  /**
   * T-RP-C2. Page가 cfdLabels.page.title h1 헤더를 렌더한다.
   */
  it('T-RP-C2: Page가 페이지 제목 h2 를 렌더한다 (셸이 h1 소유 · X-J5-14)', () => {
    renderPage('ATLAS')

    expect(
      screen.getByRole('heading', { level: 2, name: cfdLabels.page.title }),
    ).toBeInTheDocument()
  })

  /**
   * T-RP-C3. Page가 cfdLabels.page.description 문구를 렌더한다.
   */
  it('T-RP-C3: Page가 설명 문구를 렌더한다', () => {
    renderPage('ATLAS')

    expect(screen.getByText(cfdLabels.page.description)).toBeInTheDocument()
  })

  /**
   * T-RP-C4. Page가 CfdReport를 렌더하고 projectKey props를 올바르게 전달한다.
   */
  it('T-RP-C4: Page가 CfdReport에 projectKey를 전달한다', () => {
    renderPage('MYPROJECT')

    const report = screen.getByTestId('cfd-report')
    expect(report).toBeInTheDocument()
    expect(report).toHaveAttribute('data-project-key', 'MYPROJECT')
  })
})
