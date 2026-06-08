// 커스텀 필드 설정 페이지 단위 테스트 — RouteAdapter props 전달 + 목록 렌더 + PROJECT_NOT_FOUND 접근 불가 화면 (FR-IS-10 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { customFieldHandlers, resetCustomFieldStore } from '@/mocks/custom-field-handlers'
import { projectPermissionHandlers } from '@/mocks/project-permission-handlers'
import { useAuthStore } from '@/auth/authStore'
import {
  ProjectCustomFieldsSettingsRouteAdapter,
  ProjectCustomFieldsSettingsPage,
} from '@/routes/projects.$projectKey.settings.custom-fields'

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
      <ProjectCustomFieldsSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectCustomFieldsSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectCustomFieldsSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetCustomFieldStore()
    server.use(...customFieldHandlers, ...projectPermissionHandlers)
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
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
   * T-CFP-1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 ATLAS 프로젝트 커스텀 필드 설정 heading이 렌더된다.
   */
  it('T-CFP-1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /커스텀 필드 설정/i })).toBeInTheDocument()
    })
  })

  /**
   * T-CFP-2. 정상 프로젝트 키 → 페이지 헤더와 CustomFieldList가 렌더된다.
   */
  it('T-CFP-2: 정상 프로젝트 키이면 페이지 헤더와 CustomFieldList가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /커스텀 필드 설정/i })).toBeInTheDocument()
    })

    // CustomFieldList 내부의 "필드 추가" 버튼 또는 빈 상태 메시지로 렌더 확인
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '필드 추가' })).toBeInTheDocument()
    })
  })

  /**
   * T-CFP-3. PROJECT_NOT_FOUND 404 → 페이지 레벨에서 "접근 권한이 없습니다" 화면이 표시된다.
   */
  it('T-CFP-3: PROJECT_NOT_FOUND 404 → 접근 권한이 없습니다 안내 화면이 표시된다', async () => {
    server.use(
      http.get('/api/v1/projects/UNKNOWN-PROJECT/custom-fields', () =>
        HttpResponse.json(
          {
            type: 'https://bts.example.com/problems/project-not-found',
            title: 'Project Not Found',
            status: 404,
            detail: '프로젝트를 찾을 수 없습니다.',
            errorCode: 'PROJECT_NOT_FOUND',
            timestamp: new Date().toISOString(),
          },
          { status: 404 },
        ),
      ),
    )

    renderPage('UNKNOWN-PROJECT')

    await waitFor(() => {
      const elements = screen.getAllByText(/접근 권한이 없습니다/)
      expect(elements.length).toBeGreaterThanOrEqual(1)
    })

    // 일반 목록 UI는 보이지 않아야 한다
    expect(screen.queryByRole('button', { name: '필드 추가' })).not.toBeInTheDocument()
  })

  /**
   * T-CFP-4. PROJECT_NOT_FOUND 이외의 에러(예: 500)는 CustomFieldList 에러 분기에 위임된다.
   * 페이지 레벨 "접근 권한이 없습니다" 화면은 표시되지 않는다.
   */
  it('T-CFP-4: PROJECT_NOT_FOUND 이외 에러는 CustomFieldList 에러 분기에 위임된다', async () => {
    server.use(
      http.get('/api/v1/projects/ERR-PROJECT/custom-fields', () =>
        HttpResponse.json({ error: 'internal_server_error' }, { status: 500 }),
      ),
    )

    renderPage('ERR-PROJECT')

    // CustomFieldList 에러 메시지가 표시된다
    await waitFor(() => {
      expect(screen.getByText(/요청을 처리하지 못했습니다/)).toBeInTheDocument()
    })

    // 페이지 레벨 접근 불가 화면은 보이지 않는다
    expect(screen.queryByText(/접근 권한이 없습니다/)).not.toBeInTheDocument()
  })

  /**
   * T-CFP-5. 페이지 헤더(h1)와 설명 문구가 렌더된다.
   */
  it('T-CFP-5: 페이지 헤더와 설명 문구가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /커스텀 필드 설정/i })).toBeInTheDocument()
    })

    expect(screen.getByText(/이 프로젝트의 커스텀 필드를 관리합니다/)).toBeInTheDocument()
  })
})
