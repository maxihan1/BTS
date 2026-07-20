// 프로젝트 생성 폼 라우트 단위 테스트 — key 정규식 검증·name required·제출 성공 navigate·409 중복key·400 검증·403 EC-5 안내 (FR-PJ PR-5 Task 5)
import type { ReactNode } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectHandlers, resetProjectStore } from '@/mocks/project-handlers'
import { ProjectCreatePage, ProjectCreateRouteAdapter } from '../projects.new'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router(useNavigate/Link), authStore(useAuthUser) — dashboards.test.tsx 동형 패턴
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  // Link mock — Breadcrumb이 소비하는 <Link to>를 라우터 컨텍스트 없이 <a>로 렌더
  Link: ({ to, children, className }: { to: string; children: ReactNode; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
}))

// authStore의 useAuthStore(accessToken)는 실 client.ts(apiFetch)가 그대로 소비하므로 보존하고,
// useAuthUser만 오버라이드한다(전체 교체 시 useAuthStore undefined로 apiFetch가 즉시 throw).
const mockUseAuthUser = vi.fn()
vi.mock('@/auth/authStore', async () => {
  const actual = await vi.importActual<typeof import('@/auth/authStore')>('@/auth/authStore')
  return {
    ...actual,
    useAuthUser: () => mockUseAuthUser(),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** canCreateProject=true인 기본 whoami fixture — EC-5 테스트가 개별 override */
const CAN_CREATE_USER = { canCreateProject: true }

function renderPage(onSuccess?: (key: string) => void) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectCreatePage onSuccess={onSuccess} />
    </QueryClientProvider>,
  )
}

function renderRouteAdapter() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ProjectCreateRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('ProjectCreatePage', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
    mockNavigate.mockReset()
    mockUseAuthUser.mockReturnValue(CAN_CREATE_USER)
  })

  it('T1: PageHeader h1 "새 프로젝트"와 Breadcrumb "프로젝트 > 새 프로젝트"를 렌더한다', () => {
    renderPage()

    expect(screen.getByRole('heading', { level: 1, name: '새 프로젝트' })).toBeInTheDocument()

    const breadcrumbNav = screen.getByRole('navigation', { name: '탐색 경로' })
    expect(within(breadcrumbNav).getByRole('link', { name: '프로젝트' })).toHaveAttribute(
      'href',
      '/projects',
    )
    expect(within(breadcrumbNav).getByText('새 프로젝트')).toBeInTheDocument()
  })

  it('T2: 소문자를 포함한 key를 입력하면 형식 검증 메시지가 표시되고 서버에 도달하지 않는다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('프로젝트 키'), 'atlas')
    await user.type(screen.getByLabelText('프로젝트 이름'), '아틀라스')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() =>
      expect(
        screen.getByText('프로젝트 키는 대문자로 시작하는 대문자+숫자 2~10자여야 합니다.'),
      ).toBeInTheDocument(),
    )
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('T3: 숫자로 시작하는 key를 입력하면 형식 검증 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('프로젝트 키'), '1ABC')
    await user.type(screen.getByLabelText('프로젝트 이름'), '숫자 시작')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() =>
      expect(
        screen.getByText('프로젝트 키는 대문자로 시작하는 대문자+숫자 2~10자여야 합니다.'),
      ).toBeInTheDocument(),
    )
  })

  it('T4: 빈 key로 제출하면 필수 입력 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('프로젝트 이름'), '이름만 입력')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() => expect(screen.getByText('프로젝트 키를 입력하세요.')).toBeInTheDocument())
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('T5: 빈 name으로 제출하면 필수 입력 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('프로젝트 키'), 'NEWPRJ')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() => expect(screen.getByText('프로젝트 이름을 입력하세요.')).toBeInTheDocument())
  })

  it('T6: 유효한 입력 제출 성공 시 onSuccess(key)가 호출된다', async () => {
    const onSuccess = vi.fn()
    const user = userEvent.setup()
    renderPage(onSuccess)

    await user.type(screen.getByLabelText('프로젝트 키'), 'NEWPRJ')
    await user.type(screen.getByLabelText('프로젝트 이름'), '새 프로젝트 이름')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith('NEWPRJ'))
  })

  it('T7: 이미 사용 중인 key(409, ATLAS)로 제출하면 "이미 사용 중인 키입니다" 메시지가 표면된다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')
    await user.type(screen.getByLabelText('프로젝트 이름'), '중복 시도')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('이미 사용 중인 키입니다.'),
    )
  })

  it('T8: 서버가 400(형식 검증 실패)을 반환하면 검증 안내 메시지가 표면된다', async () => {
    server.use(
      http.post('/api/v1/projects', () =>
        HttpResponse.json(
          {
            type: 'https://bts.example.com/problems/project-validation-failed',
            title: 'Validation Failed',
            status: 400,
            detail: 'key는 대문자로 시작하는 대문자+숫자 2~10자여야 합니다.',
            errorCode: 'ISSUE_PROJECT_VALIDATION_FAILED',
            timestamp: new Date().toISOString(),
          },
          { status: 400 },
        ),
      ),
    )

    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText('프로젝트 키'), 'NEWPRJ')
    await user.type(screen.getByLabelText('프로젝트 이름'), '검증 실패')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('입력값을 확인해주세요.'))
  })

  it('T9 (EC-5): canCreateProject=false로 진입하면 생성 권한 안내가 표시되고 폼이 노출되지 않는다', () => {
    mockUseAuthUser.mockReturnValue({ canCreateProject: false })
    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent('새 프로젝트를 생성할 권한이 없습니다.')
    expect(screen.queryByLabelText('프로젝트 키')).not.toBeInTheDocument()
  })

  it('T10 (EC-6): whoami canCreateProject 키가 부재해도(undefined) 권한 없음으로 안전하게 취급된다', () => {
    mockUseAuthUser.mockReturnValue({})
    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent('새 프로젝트를 생성할 권한이 없습니다.')
    expect(screen.queryByLabelText('프로젝트 키')).not.toBeInTheDocument()
  })
})

describe('ProjectCreateRouteAdapter', () => {
  beforeEach(() => {
    resetProjectStore()
    server.use(...projectHandlers)
    mockNavigate.mockReset()
    mockUseAuthUser.mockReturnValue(CAN_CREATE_USER)
  })

  it('T11 (S3): 제출 성공 시 /projects/{key}/board로 navigate가 호출된다', async () => {
    const user = userEvent.setup()
    renderRouteAdapter()

    await user.type(screen.getByLabelText('프로젝트 키'), 'NEWPRJ')
    await user.type(screen.getByLabelText('프로젝트 이름'), '새 프로젝트')
    await user.click(screen.getByRole('button', { name: '프로젝트 생성' }))

    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/projects/$projectKey/board',
        params: { projectKey: 'NEWPRJ' },
      }),
    )
  })
})
