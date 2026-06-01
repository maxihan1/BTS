// 프로젝트 멤버 설정 페이지 단위 테스트 — RouteAdapter props 전달 + 목록 렌더 + project_not_found 접근 불가 화면
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectMemberHandlers } from '@/mocks/project-member-handlers'
import {
  ProjectMembersSettingsRouteAdapter,
  ProjectMembersSettingsPage,
} from '@/routes/projects.$projectKey.settings.members'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// useAuthStore mock — 로그인 사용자 = alice (PROJECT_ADMIN)
vi.mock('@/auth/authStore', () => ({
  useAuthStore: (selector: (s: { user: { userId: string } | null }) => unknown) =>
    selector({ user: { userId: 'fixture-alice-uuid' } }),
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
      <ProjectMembersSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectMembersSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectMembersSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    server.use(...projectMemberHandlers)
  })

  /**
   * T-F5-1. RouteAdapter가 useParams에서 $projectKey를 추출해 Page에 전달한다.
   * useParams mock이 ATLAS를 반환하므로 ATLAS 프로젝트 멤버 목록이 렌더된다.
   */
  it('T-F5-1: RouteAdapter가 useParams $projectKey를 Page에 전달한다', async () => {
    renderAdapter()

    // ATLAS 프로젝트 멤버 heading이 렌더되면 props 전달이 성공한 것이다
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /멤버 설정/i })).toBeInTheDocument()
    })
  })

  /**
   * T-F5-2. 정상 프로젝트 키 → 멤버 목록이 렌더된다.
   * ATLAS 프로젝트는 fixture에서 앨리스(PROJECT_ADMIN), 밥(MEMBER) 두 명이다.
   */
  it('T-F5-2: 정상 프로젝트 키이면 멤버 목록이 렌더된다', async () => {
    renderPage('ATLAS')

    // MemberList 내부의 "프로젝트 멤버" 카드 제목이 보여야 한다
    await waitFor(() => {
      expect(screen.getByText('프로젝트 멤버')).toBeInTheDocument()
    })

    // 멤버 이름 확인
    expect(screen.getByText('앨리스')).toBeInTheDocument()
    expect(screen.getByText('밥')).toBeInTheDocument()
  })

  /**
   * T-F5-3. project_not_found 404 → 페이지 레벨에서 "접근 권한이 없습니다" 화면이 표시된다.
   * 미존재/비멤버 프로젝트 키에 대해 MSW가 404 project_not_found를 반환한다.
   */
  it('T-F5-3: project_not_found 404 → 접근 권한이 없습니다 안내 화면이 표시된다', async () => {
    // UNKNOWN-PROJECT는 KNOWN_PROJECT_KEYS에 없으므로 404 project_not_found 반환
    renderPage('UNKNOWN-PROJECT')

    await waitFor(() => {
      expect(screen.getByText(/접근 권한이 없습니다/)).toBeInTheDocument()
    })

    // 일반 목록 UI는 보이지 않아야 한다
    expect(screen.queryByText('프로젝트 멤버')).not.toBeInTheDocument()
  })

  /**
   * T-F5-4. project_not_found 이외의 에러(예: 500)는 MemberList 에러 분기에 위임된다.
   * 페이지 레벨 "접근 권한이 없습니다" 화면은 표시되지 않는다.
   */
  it('T-F5-4: project_not_found 이외 에러는 MemberList 에러 분기에 위임된다', async () => {
    server.use(
      http.get('/api/v1/projects/ERR-PROJECT/members', () => {
        return HttpResponse.json({ error: 'internal_server_error' }, { status: 500 })
      }),
    )

    renderPage('ERR-PROJECT')

    // MemberList 에러 메시지가 표시된다
    await waitFor(() => {
      expect(
        screen.getByText(/멤버 목록을 불러오지 못했습니다/),
      ).toBeInTheDocument()
    })

    // 페이지 레벨 접근 불가 화면은 보이지 않는다
    expect(screen.queryByText(/접근 권한이 없습니다/)).not.toBeInTheDocument()
  })

  /**
   * T-F5-5. 페이지 헤더(h1)와 max-w 컨테이너가 렌더된다.
   * workflow-scheme 페이지 레이아웃과 일관성을 위해 헤더 영역이 존재해야 한다.
   */
  it('T-F5-5: 페이지 헤더가 렌더된다', async () => {
    renderPage('ATLAS')

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /멤버 설정/i })).toBeInTheDocument()
    })
  })
})
