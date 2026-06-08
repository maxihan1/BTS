// 프로젝트 버전 설정 페이지 단위 테스트 — RouteAdapter props 전달 + 목록 렌더 + PROJECT_NOT_FOUND 접근 불가 화면
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { versionHandlers, resetVersionStore } from '@/mocks/version-handlers'
import { useAuthStore } from '@/auth/authStore'
import {
  ProjectVersionsSettingsRouteAdapter,
  ProjectVersionsSettingsPage,
} from '@/routes/projects.$projectKey.settings.versions'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
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
      <ProjectVersionsSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectVersionsSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectVersionsSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetVersionStore()
    server.use(...versionHandlers)
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        userId: '10000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-VR-R1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 ATLAS 프로젝트 버전 설정 heading이 렌더된다.
   */
  it('T-VR-R1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /버전 관리/i })).toBeInTheDocument()
    })
  })

  /**
   * T-VR-R2. 정상 프로젝트 키 → 페이지 헤더와 VersionList가 렌더된다.
   */
  it('T-VR-R2: 정상 프로젝트 키이면 페이지 헤더와 VersionList가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /버전 관리/i })).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('버전 추가')).toBeInTheDocument()
    })
  })

  /**
   * T-VR-R3. PROJECT_NOT_FOUND 404 → 페이지 레벨에서 "접근 권한이 없습니다" 화면이 표시된다.
   * C2: server.use per-test 오버라이드로 주입 (versionHandlers 전역은 200 유지).
   */
  it('T-VR-R3: PROJECT_NOT_FOUND 404 → 접근 권한이 없습니다 안내 화면이 표시된다', async () => {
    // UNKNOWN-PROJECT에 대해 404 PROJECT_NOT_FOUND 반환
    server.use(
      http.get('/api/v1/projects/UNKNOWN-PROJECT/versions', () => {
        return HttpResponse.json(
          {
            type: 'https://bts.example.com/problems/project-not-found',
            title: 'Project Not Found',
            status: 404,
            detail: '프로젝트를 찾을 수 없습니다.',
            errorCode: 'PROJECT_NOT_FOUND',
            timestamp: new Date().toISOString(),
          },
          { status: 404 },
        )
      }),
    )

    renderPage('UNKNOWN-PROJECT')

    await waitFor(() => {
      const elements = screen.getAllByText(/접근 권한이 없습니다/)
      expect(elements.length).toBeGreaterThanOrEqual(1)
    })

    // 일반 목록 UI는 보이지 않아야 한다
    expect(screen.queryByText('버전 추가')).not.toBeInTheDocument()
  })

  /**
   * T-VR-R4. 페이지 헤더(h1)와 설명 문구가 렌더된다.
   */
  it('T-VR-R4: 페이지 헤더와 설명 문구가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /버전 관리/i })).toBeInTheDocument()
    })

    expect(screen.getByText(/이 프로젝트의 버전을 관리합니다/)).toBeInTheDocument()
  })
})
