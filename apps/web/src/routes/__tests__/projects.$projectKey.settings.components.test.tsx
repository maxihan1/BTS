// 프로젝트 컴포넌트 설정 페이지 단위 테스트 — RouteAdapter props 전달 + 목록 렌더 + PROJECT_NOT_FOUND 접근 불가 화면
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { componentHandlers, resetComponentStore } from '@/mocks/component-handlers'
import { useAuthStore } from '@/auth/authStore'
import {
  ProjectComponentsSettingsRouteAdapter,
  ProjectComponentsSettingsPage,
} from '@/routes/projects.$projectKey.settings.components'

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
      <ProjectComponentsSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectComponentsSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectComponentsSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetComponentStore()
    server.use(...componentHandlers)
    useAuthStore.setState({
      accessToken: 'test-token',
      user: {
        userId: '10000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-F9-1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 ATLAS 프로젝트 컴포넌트 설정 heading이 렌더된다.
   */
  it('T-F9-1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /컴포넌트 설정/i })).toBeInTheDocument()
    })
  })

  /**
   * T-F9-2. 정상 프로젝트 키 → 페이지 헤더와 ComponentList가 렌더된다.
   * ComponentList는 빈 상태를 포함해 렌더되어야 하고, 헤더 h1이 존재해야 한다.
   */
  it('T-F9-2: 정상 프로젝트 키이면 페이지 헤더와 ComponentList가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /컴포넌트 설정/i })).toBeInTheDocument()
    })

    // ComponentList 내부의 "컴포넌트 추가" 버튼 또는 빈 상태 메시지로 렌더 확인
    await waitFor(() => {
      expect(screen.getByText('컴포넌트 추가')).toBeInTheDocument()
    })
  })

  /**
   * T-F9-3. PROJECT_NOT_FOUND 404 → 페이지 레벨에서 "접근 권한이 없습니다" 화면이 표시된다.
   * 미존재/비멤버 프로젝트 키에 대해 MSW가 404 PROJECT_NOT_FOUND를 반환한다.
   */
  it('T-F9-3: PROJECT_NOT_FOUND 404 → 접근 권한이 없습니다 안내 화면이 표시된다', async () => {
    // UNKNOWN-PROJECT에 대해 404 PROJECT_NOT_FOUND 반환
    server.use(
      http.get('/api/v1/projects/UNKNOWN-PROJECT/components', () => {
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
    expect(screen.queryByText('컴포넌트 추가')).not.toBeInTheDocument()
  })

  /**
   * T-F9-4. PROJECT_NOT_FOUND 이외의 에러(예: 500)는 ComponentList 에러 분기에 위임된다.
   * 페이지 레벨 "접근 권한이 없습니다" 화면은 표시되지 않는다.
   */
  it('T-F9-4: PROJECT_NOT_FOUND 이외 에러는 ComponentList 에러 분기에 위임된다', async () => {
    server.use(
      http.get('/api/v1/projects/ERR-PROJECT/components', () => {
        return HttpResponse.json({ error: 'internal_server_error' }, { status: 500 })
      }),
    )

    renderPage('ERR-PROJECT')

    // ComponentList 에러 메시지가 표시된다
    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })

    // 페이지 레벨 접근 불가 화면은 보이지 않는다
    expect(screen.queryByText(/접근 권한이 없습니다/)).not.toBeInTheDocument()
  })

  /**
   * T-F9-5. 페이지 헤더(h1)와 설명 문구가 렌더된다.
   */
  it('T-F9-5: 페이지 헤더와 설명 문구가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /컴포넌트 설정/i })).toBeInTheDocument()
    })

    expect(screen.getByText(/이 프로젝트의 컴포넌트를 관리합니다/)).toBeInTheDocument()
  })
})
