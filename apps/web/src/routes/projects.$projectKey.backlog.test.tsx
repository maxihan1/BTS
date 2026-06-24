// 백로그 라우트 페이지 단위 테스트 — RouteAdapter useParams 추출 + BacklogPage canManage 전달
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import {
  BacklogRouteAdapter,
  BacklogPage,
} from '@/routes/projects.$projectKey.backlog'

// TanStack Router useParams mock — RouteAdapter 단위 테스트용
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
  Link: ({
    to,
    params,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    children: React.ReactNode
    className?: string
  }) => {
    // params 치환: $projectKey → 실제 값 (TanStack Router 동작 모사)
    const resolvedTo = params !== undefined
      ? Object.entries(params).reduce(
          (acc, [key, val]) => acc.replace(`$${key}`, val),
          to,
        )
      : to
    return <a href={resolvedTo} className={className}>{children}</a>
  },
}))

// BacklogBoard는 별도 통합 테스트에서 검증하므로 라우트 단위 테스트에서 격리
vi.mock('@/components/backlog/BacklogBoard', () => ({
  BacklogBoard: ({
    projectKey,
    canManageSprint,
    canReorderIssue,
  }: {
    projectKey: string
    canManageSprint?: boolean
    canReorderIssue?: boolean
  }) => (
    <div
      data-testid="backlog-board"
      data-project-key={projectKey}
      data-can-manage-sprint={String(canManageSprint ?? true)}
      data-can-reorder-issue={String(canReorderIssue ?? true)}
    >
      backlog-board-mock
    </div>
  ),
}))

// useProjectPermissions mock — 기본값 CREATE=true, UPDATE=true (ADMIN 역할)
vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: () => ({
    data: {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
        UPDATE: true,
        MANAGE_COMPONENTS: true,
        MANAGE_VERSIONS: true,
        MANAGE_CUSTOM_FIELDS: true,
        MANAGE_FIELD_PERMISSIONS: true,
        MANAGE_TEMPLATES: true,
      },
    },
    isLoading: false,
    isError: false,
  }),
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
      <BacklogPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <BacklogRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('BacklogPage', () => {
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
   * T-BL-R1. BacklogPage가 BacklogBoard를 렌더하고 projectKey를 전달한다.
   */
  it('T-BL-R1: BacklogPage가 BacklogBoard에 projectKey를 전달한다', () => {
    renderPage('ATLAS')

    const board = screen.getByTestId('backlog-board')
    expect(board).toBeInTheDocument()
    expect(board).toHaveAttribute('data-project-key', 'ATLAS')
  })

  /**
   * T-BL-R2. CREATE 권한이 있으면 canManageSprint=true를 BacklogBoard에 전달한다.
   */
  it('T-BL-R2: CREATE 권한이 있으면 canManageSprint=true를 전달한다', () => {
    renderPage('ATLAS')

    const board = screen.getByTestId('backlog-board')
    expect(board).toHaveAttribute('data-can-manage-sprint', 'true')
  })

  /**
   * T-BL-R2b. UPDATE 권한이 있으면 canReorderIssue=true를 BacklogBoard에 전달한다.
   */
  it('T-BL-R2b: UPDATE 권한이 있으면 canReorderIssue=true를 전달한다', () => {
    renderPage('ATLAS')

    const board = screen.getByTestId('backlog-board')
    expect(board).toHaveAttribute('data-can-reorder-issue', 'true')
  })

  /**
   * T-BL-R3. Page가 "백로그" h1 헤더를 렌더한다.
   */
  it('T-BL-R3: BacklogPage가 "백로그" 헤더를 렌더한다', () => {
    renderPage('ATLAS')

    expect(screen.getByRole('heading', { level: 1, name: '백로그' })).toBeInTheDocument()
  })

  /**
   * T-BL-R4. 보드로 이동하는 링크가 존재한다.
   */
  it('T-BL-R4: 보드 링크가 존재한다', () => {
    renderPage('ATLAS')

    const boardLink = screen.getByRole('link', { name: '보드' })
    expect(boardLink).toBeInTheDocument()
    expect(boardLink).toHaveAttribute('href', '/projects/ATLAS/board')
  })
})

describe('BacklogRouteAdapter', () => {
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
   * T-BL-R5. RouteAdapter가 useParams에서 $projectKey를 추출해 BacklogPage에 전달한다.
   */
  it('T-BL-R5: RouteAdapter가 useParams $projectKey를 BacklogPage에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      const board = screen.getByTestId('backlog-board')
      expect(board).toBeInTheDocument()
      expect(board).toHaveAttribute('data-project-key', 'ATLAS')
    })
  })
})
