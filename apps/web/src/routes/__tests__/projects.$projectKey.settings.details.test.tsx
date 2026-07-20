// 프로젝트 일반 설정(details) 페이지 단위 테스트 — RouteAdapter + Page (FR-PJ PR-5 Task 6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import type { Project } from '@/api/projects'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 훅 mock — 라우터 비의존 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

vi.mock('@/hooks/use-project', () => ({
  useProject: vi.fn(),
}))

vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: vi.fn(),
}))

const mockUpdateNameMutate = vi.fn()
const mockUpdateNameReset = vi.fn()
const mockArchiveMutate = vi.fn()
const mockUnarchiveMutate = vi.fn()

vi.mock('@/hooks/use-project-mutations', () => ({
  useUpdateProjectName: vi.fn(() => ({
    mutate: mockUpdateNameMutate,
    reset: mockUpdateNameReset,
    isPending: false,
    isError: false,
  })),
  useArchiveProject: vi.fn(() => ({
    mutate: mockArchiveMutate,
    isPending: false,
    isError: false,
  })),
  useUnarchiveProject: vi.fn(() => ({
    mutate: mockUnarchiveMutate,
    isPending: false,
    isError: false,
  })),
}))

// ─────────────────────────────────────────────────────────────────────────────
// import — mock 선언 후 실행
// ─────────────────────────────────────────────────────────────────────────────

import {
  ProjectDetailsSettingsPage,
  ProjectDetailsSettingsRouteAdapter,
} from '@/routes/projects.$projectKey.settings.details'
import { useProject } from '@/hooks/use-project'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import {
  useUpdateProjectName,
  useArchiveProject,
  useUnarchiveProject,
} from '@/hooks/use-project-mutations'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const activeProject: Project = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  key: 'ATLAS',
  name: 'Atlas 프로젝트',
  archived: false,
}

const archivedProject: Project = {
  ...activeProject,
  archived: true,
}

function mockAdminPermissions(): void {
  vi.mocked(useProjectPermissions).mockReturnValue({
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
  } as ReturnType<typeof useProjectPermissions>)
}

function mockNonAdminPermissions(): void {
  vi.mocked(useProjectPermissions).mockReturnValue({
    data: {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
        UPDATE: true,
        MANAGE_COMPONENTS: false,
        MANAGE_VERSIONS: false,
        MANAGE_CUSTOM_FIELDS: false,
        MANAGE_FIELD_PERMISSIONS: false,
        MANAGE_TEMPLATES: false,
      },
    },
    isLoading: false,
    isError: false,
  } as ReturnType<typeof useProjectPermissions>)
}

function mockProject(project: Project | undefined, overrides: Partial<ReturnType<typeof useProject>> = {}): void {
  vi.mocked(useProject).mockReturnValue({
    data: project,
    isLoading: false,
    isError: false,
    error: null,
    ...overrides,
  } as ReturnType<typeof useProject>)
}

function renderPage(projectKey = 'ATLAS') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectDetailsSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectDetailsSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectDetailsSettingsRouteAdapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAdminPermissions()
    mockProject(activeProject)
  })

  it('T6-0: RouteAdapter가 useParams projectKey를 Page에 전달한다', () => {
    renderAdapter()
    expect(screen.getByRole('heading', { name: 'Atlas 프로젝트' })).toBeInTheDocument()
  })
})

describe('ProjectDetailsSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAdminPermissions()
  })

  it('T6-1: 로딩 중이면 로딩 텍스트가 표시된다', () => {
    mockProject(undefined, { isLoading: true })
    renderPage()
    expect(screen.getByText(/로딩 중/)).toBeInTheDocument()
  })

  it('T6-2: 404(ISSUE_PROJECT_NOT_FOUND) 에러이면 ProjectNotFoundScreen을 렌더한다', () => {
    mockProject(undefined, {
      isError: true,
      error: new ApiError(404, { errorCode: 'ISSUE_PROJECT_NOT_FOUND' }),
    })
    renderPage()
    expect(screen.getByText(/접근 권한이 없습니다/)).toBeInTheDocument()
  })

  it('T6-2b: 403(ISSUE_PROJECT_FORBIDDEN, 비멤버) 에러이면 ProjectNotFoundScreen을 렌더한다', () => {
    mockProject(undefined, {
      isError: true,
      error: new ApiError(403, { errorCode: 'ISSUE_PROJECT_FORBIDDEN' }),
    })
    renderPage()
    expect(screen.getByText(/접근 권한이 없습니다/)).toBeInTheDocument()
  })

  it('T6-3: 정상 로드 시 프로젝트 이름이 h1로 표시된다', () => {
    mockProject(activeProject)
    renderPage()
    expect(screen.getByRole('heading', { name: 'Atlas 프로젝트' })).toBeInTheDocument()
  })

  it('T6-4: 이름 입력 후 저장 클릭 시 updateName.mutate가 {idOrKey, name}으로 호출된다', async () => {
    mockProject(activeProject)
    const user = userEvent.setup()
    renderPage()

    const input = screen.getByLabelText(/프로젝트 이름/)
    await user.clear(input)
    await user.type(input, '새 이름')
    await user.click(screen.getByRole('button', { name: /저장/ }))

    expect(mockUpdateNameMutate).toHaveBeenCalledWith(
      { idOrKey: 'ATLAS', name: '새 이름' },
      expect.anything(),
    )
  })

  it('T6-5: MANAGE_COMPONENTS=true면 이름 입력이 활성 상태다', () => {
    mockAdminPermissions()
    mockProject(activeProject)
    renderPage()
    expect(screen.getByLabelText(/프로젝트 이름/)).not.toBeDisabled()
  })

  it('T6-6: MANAGE_COMPONENTS=false면 이름 입력이 disabled이고 읽기전용 안내가 표시된다', () => {
    mockNonAdminPermissions()
    mockProject(activeProject)
    renderPage()

    expect(screen.getByLabelText(/프로젝트 이름/)).toBeDisabled()
    expect(screen.getByText(/권한이 없습니다/)).toBeInTheDocument()
  })

  it('T6-7: 활성 프로젝트(archived:false)이면 "아카이브" 버튼이 표시된다', () => {
    mockProject(activeProject)
    renderPage()
    expect(screen.getByRole('button', { name: '아카이브' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '아카이브 해제' })).not.toBeInTheDocument()
  })

  it('T6-8: 아카이브된 프로젝트(archived:true)이면 "아카이브 해제" 버튼이 표시되고 클릭 시 unarchive.mutate(projectKey)가 호출된다', async () => {
    mockProject(archivedProject)
    const user = userEvent.setup()
    renderPage()

    expect(screen.queryByRole('button', { name: '아카이브' })).not.toBeInTheDocument()
    const unarchiveButton = screen.getByRole('button', { name: '아카이브 해제' })
    await user.click(unarchiveButton)

    expect(mockUnarchiveMutate).toHaveBeenCalledWith('ATLAS')
  })

  it('T6-9: 아카이브된 프로젝트이면 이름 폼이 비활성화되고 안내 문구가 표시된다', () => {
    mockProject(archivedProject)
    renderPage()

    expect(screen.getByLabelText(/프로젝트 이름/)).toBeDisabled()
    expect(
      screen.getByText('아카이브된 프로젝트는 설정을 변경할 수 없습니다'),
    ).toBeInTheDocument()
  })

  it('T6-10: 활성 프로젝트에서 "아카이브" 버튼 클릭 시 확인 단계를 거쳐 archive.mutate(projectKey)가 호출된다', async () => {
    mockProject(activeProject)
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '아카이브' }))
    // 인라인 확인 UI — 즉시 mutate 호출되지 않는다
    expect(mockArchiveMutate).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '확인' }))
    expect(mockArchiveMutate).toHaveBeenCalledWith('ATLAS', expect.anything())
  })

  it('T6-11: MANAGE_COMPONENTS=false면 아카이브 버튼도 disabled 상태다', () => {
    mockNonAdminPermissions()
    mockProject(activeProject)
    renderPage()

    expect(screen.getByRole('button', { name: '아카이브' })).toBeDisabled()
  })
})
