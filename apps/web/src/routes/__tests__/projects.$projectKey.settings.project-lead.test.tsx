// 프로젝트 리드 설정 페이지 단위 테스트 — RouteAdapter + Page (FR-CM-04 D6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 훅/컴포넌트 mock — 라우터 비의존 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

const mockMutate = vi.fn()

vi.mock('@/hooks/use-project-lead', () => ({
  useProjectLead: vi.fn(),
  useChangeProjectLead: vi.fn(() => ({ mutate: mockMutate, isPending: false })),
}))

vi.mock('@/hooks/use-users', () => ({
  useUsers: vi.fn(() => ({ data: [] })),
  useUsersByIds: vi.fn(() => ({ data: [] })),
}))

vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: vi.fn(() => ({
    data: { permissions: { MANAGE_COMPONENTS: true } },
    isLoading: false,
    isError: false,
  })),
}))

// ─────────────────────────────────────────────────────────────────────────────
// import — mock 선언 후 실행
// ─────────────────────────────────────────────────────────────────────────────

import {
  ProjectLeadSettingsPage,
  ProjectLeadSettingsRouteAdapter,
} from '@/routes/projects.$projectKey.settings.project-lead'
import { useProjectLead } from '@/hooks/use-project-lead'
import { useProjectPermissions } from '@/hooks/use-project-permissions'
import { useUsers, useUsersByIds } from '@/hooks/use-users'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderPage(projectKey = 'ATLAS') {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectLeadSettingsPage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectLeadSettingsRouteAdapter />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectLeadSettingsRouteAdapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useProjectLead).mockReturnValue({
      data: { projectId: '00000000-0000-4000-8000-000000000001', leadUserId: null },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)
  })

  /**
   * T5-0. RouteAdapter가 useParams의 projectKey를 Page에 전달한다.
   * useParams mock은 projectKey='ATLAS' 반환 → 페이지가 'ATLAS' 프로젝트로 렌더된다.
   */
  it('T5-0: RouteAdapter가 useParams projectKey를 Page에 전달한다', () => {
    renderAdapter()
    // 페이지 heading이 렌더되면 RouteAdapter → Page 전달이 성공한 것
    expect(screen.getByRole('heading', { name: /프로젝트 리드/ })).toBeInTheDocument()
  })
})

describe('ProjectLeadSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useProjectPermissions).mockReturnValue({
      data: { permissions: { MANAGE_COMPONENTS: true } },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectPermissions>)
    vi.mocked(useUsers).mockReturnValue({ data: [] } as unknown as ReturnType<typeof useUsers>)
    vi.mocked(useUsersByIds).mockReturnValue(
      { data: [] } as unknown as ReturnType<typeof useUsersByIds>,
    )
  })

  /**
   * T5-1. 로딩 중 → "로딩 중" 텍스트가 표시된다.
   */
  it('T5-1: isLoading이면 로딩 중 텍스트가 표시된다', () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)

    renderPage()
    expect(screen.getByText(/로딩 중/)).toBeInTheDocument()
  })

  /**
   * T5-2. 프로젝트 404 에러 → ProjectNotFoundScreen ("접근 권한이 없습니다")을 렌더한다.
   */
  it('T5-2: 프로젝트 404 에러이면 ProjectNotFoundScreen을 렌더한다', () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: { status: 404 },
    } as unknown as ReturnType<typeof useProjectLead>)

    renderPage()
    const found = screen.getAllByText(/접근 권한이 없습니다/)
    expect(found.length).toBeGreaterThanOrEqual(1)
  })

  /**
   * T5-3. 현재 리드가 지정된 경우 — 리드 이름이 표시된다.
   * useUsersByIds가 현재 리드 사용자를 반환하면 ProjectLeadSelect의 currentLead에 전달된다.
   */
  it('T5-3: 현재 리드가 있으면 리드 이름이 표시된다', () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: {
        projectId: '00000000-0000-4000-8000-000000000001',
        leadUserId: '00000000-0000-4000-8000-000000000002',
      },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)
    vi.mocked(useUsersByIds).mockReturnValue({
      data: [
        {
          id: '00000000-0000-4000-8000-000000000002',
          username: 'alice',
          displayName: 'Alice Kim',
        },
      ],
    } as ReturnType<typeof useUsersByIds>)

    renderPage()
    expect(screen.getByTestId('lead-current-name')).toHaveTextContent('Alice Kim')
  })

  /**
   * T5-4. 현재 리드가 미지정인 경우 — "미지정" 텍스트가 표시된다.
   */
  it('T5-4: 리드가 미지정이면 미지정 텍스트가 표시된다', () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: { projectId: '00000000-0000-4000-8000-000000000001', leadUserId: null },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)

    renderPage()
    expect(screen.getByTestId('lead-current-name')).toHaveTextContent('미지정')
  })

  /**
   * T5-5. 검색어 입력 후 300ms 경과하면 useUsers가 새 쿼리로 호출된다.
   * 실제 타이머를 사용하며 waitFor로 debounce 완료를 기다린다 (8s timeout).
   */
  it(
    'T5-5: 검색어 입력 시 debounce 후 useUsers가 새 쿼리로 호출된다',
    async () => {
      vi.mocked(useProjectLead).mockReturnValue({
        data: { projectId: '00000000-0000-4000-8000-000000000001', leadUserId: null },
        isLoading: false,
        isError: false,
        error: null,
      } as ReturnType<typeof useProjectLead>)

      const user = userEvent.setup({ delay: null })
      renderPage()

      const input = screen.getByPlaceholderText(/리드 검색/)
      await user.type(input, 'bob')

      // 타이핑 직후에는 debouncedQuery가 아직 '' → useUsers('')
      expect(vi.mocked(useUsers)).toHaveBeenLastCalledWith('')

      // 300ms debounce 완료 후 useUsers('bob')가 호출된다
      await waitFor(
        () => {
          expect(vi.mocked(useUsers)).toHaveBeenLastCalledWith('bob')
        },
        { timeout: 1000 },
      )
    },
    8_000,
  )

  /**
   * T5-6. 검색 결과 클릭 → mutate(userId) 호출.
   */
  it('T5-6: 검색 결과 사용자 클릭 시 mutate(userId)가 호출된다', async () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: { projectId: '00000000-0000-4000-8000-000000000001', leadUserId: null },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)
    vi.mocked(useUsers).mockReturnValue({
      data: [
        {
          id: '00000000-0000-4000-8000-000000000003',
          username: 'charlie',
          displayName: 'Charlie Lee',
        },
      ],
    } as ReturnType<typeof useUsers>)

    const user = userEvent.setup()
    renderPage()

    // 검색 결과 버튼 클릭
    const btn = screen.getByRole('button', { name: 'Charlie Lee' })
    await user.click(btn)

    expect(mockMutate).toHaveBeenCalledWith('00000000-0000-4000-8000-000000000003')
  })

  /**
   * T5-7. 권한 없는 경우(canManage=false) — ProjectLeadSelect의 input과 버튼이 disabled이다.
   * fail-closed 원칙: 로딩/에러/미인가 모두 disabled.
   */
  it('T5-7: 권한이 없으면 컨트롤이 disabled 상태가 된다', () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: { projectId: '00000000-0000-4000-8000-000000000001', leadUserId: null },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)
    vi.mocked(useProjectPermissions).mockReturnValue({
      data: { permissions: { MANAGE_COMPONENTS: false } },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectPermissions>)

    renderPage()

    const input = screen.getByPlaceholderText(/리드 검색/)
    expect(input).toBeDisabled()
  })

  /**
   * T5-8. 권한 로딩 중(isLoading=true) — fail-closed: 검색 input이 disabled이다.
   */
  it('T5-8: 권한 로딩 중이면 컨트롤이 disabled 상태가 된다', () => {
    vi.mocked(useProjectLead).mockReturnValue({
      data: { projectId: '00000000-0000-4000-8000-000000000001', leadUserId: null },
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectLead>)
    vi.mocked(useProjectPermissions).mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      error: null,
    } as ReturnType<typeof useProjectPermissions>)

    renderPage()

    const input = screen.getByPlaceholderText(/리드 검색/)
    expect(input).toBeDisabled()
  })
})
