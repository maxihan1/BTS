// 프로젝트 목록 라우트 단위 테스트 — ProjectListPage(props/mock) + ProjectListRouteAdapter (FR-PJ PR-5 Task 4)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { ProjectListPage, ProjectListRouteAdapter } from '@/routes/projects.index'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router useNavigate (ProjectListPage는 이 모듈을 import하지 않으므로
// ProjectListPage 단위 테스트에는 영향이 없다. ProjectListRouteAdapter 테스트 전용)
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixture — 활성 2건(비알파벳 순서로 반환해 "재정렬 없음" 검증) + 아카이브 1건
// ─────────────────────────────────────────────────────────────────────────────

const ZETA_PROJECT = { id: 'b2c3d4e5-f6a7-4901-bcde-f12345678901', key: 'ZETA', name: 'Zeta 프로젝트', archived: false }
const ATLAS_PROJECT = { id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890', key: 'ATLAS', name: 'Atlas 프로젝트', archived: false }
const ARCHIVED_PROJECT = { id: 'c3d4e5f6-a7b8-4012-9def-123456789012', key: 'OLDONE', name: 'Old 프로젝트', archived: true }

/** archived 쿼리 파라미터에 따라 다른 목록을 반환하는 로컬 MSW 핸들러 — 백엔드 정렬 계약 재현 */
function useProjectsHandler(): void {
  server.use(
    http.get('/api/v1/projects', ({ request }) => {
      const url = new URL(request.url)
      const archived = url.searchParams.get('archived') === 'true'
      const data = archived ? [ARCHIVED_PROJECT] : [ZETA_PROJECT, ATLAS_PROJECT]
      return HttpResponse.json({ data })
    }),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderPage(onNavigateToProject = vi.fn()) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectListPage onNavigateToProject={onNavigateToProject} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <ProjectListRouteAdapter />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  useProjectsHandler()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  mockNavigate.mockClear()
})

// ─────────────────────────────────────────────────────────────────────────────
// h1 단일 소유 (PageHeader)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListPage — h1', () => {
  it('h1 "프로젝트"가 정확히 1개 렌더된다', async () => {
    renderPage()

    const headings = await screen.findAllByRole('heading', { level: 1, name: '프로젝트' })
    expect(headings).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 목록 렌더 + 정렬 신뢰 (S1)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListPage — 목록 렌더', () => {
  it('활성 프로젝트를 key·name과 함께 표시하고 백엔드 응답 순서를 재정렬하지 않는다', async () => {
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Zeta 프로젝트')).toBeInTheDocument()
    })
    expect(screen.getByText('Atlas 프로젝트')).toBeInTheDocument()

    // 응답 순서(ZETA→ATLAS)가 그대로 DOM 순서에 반영돼야 한다 — 알파벳 재정렬됐다면
    // ATLAS가 먼저 나타나 이 단언이 실패한다.
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Zeta 프로젝트'),
      expect.stringContaining('Atlas 프로젝트'),
    ])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// canCreateProject 게이팅 (S4, EC-6)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListPage — "새 프로젝트" 버튼 게이팅 (S4)', () => {
  it('canCreateProject=true면 "새 프로젝트" 버튼이 /projects/new 링크로 표시된다', async () => {
    useAuthStore.setState({ accessToken: 'test-token', user: makeWhoami({ canCreateProject: true }) })
    renderPage()

    await screen.findAllByRole('heading', { level: 1, name: '프로젝트' })
    expect(screen.getByRole('link', { name: '새 프로젝트' })).toHaveAttribute('href', '/projects/new')
    // 목록 조회까지 완전히 정착시켜 act() 경고 없이 테스트를 종료한다.
    await screen.findByText('Atlas 프로젝트')
  })

  it('canCreateProject=false면 "새 프로젝트" 버튼이 표시되지 않는다', async () => {
    useAuthStore.setState({ accessToken: 'test-token', user: makeWhoami({ canCreateProject: false }) })
    renderPage()

    await screen.findAllByRole('heading', { level: 1, name: '프로젝트' })
    expect(screen.queryByRole('link', { name: '새 프로젝트' })).not.toBeInTheDocument()
    await screen.findByText('Atlas 프로젝트')
  })

  it('canCreateProject 키 부재(undefined, EC-6 하위호환)면 "새 프로젝트" 버튼이 표시되지 않는다', async () => {
    useAuthStore.setState({ accessToken: 'test-token', user: makeWhoami() })
    renderPage()

    await screen.findAllByRole('heading', { level: 1, name: '프로젝트' })
    expect(screen.queryByRole('link', { name: '새 프로젝트' })).not.toBeInTheDocument()
    await screen.findByText('Atlas 프로젝트')
  })

  it('로그인하지 않은 상태(user:null)면 "새 프로젝트" 버튼이 표시되지 않는다', async () => {
    renderPage()

    await screen.findAllByRole('heading', { level: 1, name: '프로젝트' })
    expect(screen.queryByRole('link', { name: '새 프로젝트' })).not.toBeInTheDocument()
    await screen.findByText('Atlas 프로젝트')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 아카이브 토글 재조회 (S2)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListPage — 아카이브 토글 (S2)', () => {
  it('기본값은 활성 프로젝트만 표시하고, 토글 on 시 ?archived=true로 재조회해 아카이브 프로젝트만 표시한다', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Atlas 프로젝트')).toBeInTheDocument()
    })
    expect(screen.queryByText('Old 프로젝트')).not.toBeInTheDocument()

    await user.click(screen.getByRole('switch', { name: '아카이브된 프로젝트 표시' }))

    await waitFor(() => {
      expect(screen.getByText('Old 프로젝트')).toBeInTheDocument()
    })
    expect(screen.queryByText('Atlas 프로젝트')).not.toBeInTheDocument()
    expect(screen.queryByText('Zeta 프로젝트')).not.toBeInTheDocument()
  })

  it('아카이브 프로젝트 행에는 "아카이브" 배지가 표시된다', async () => {
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('Atlas 프로젝트')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('switch', { name: '아카이브된 프로젝트 표시' }))

    await waitFor(() => {
      expect(screen.getByText('Old 프로젝트')).toBeInTheDocument()
    })
    expect(screen.getByText('아카이브')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 빈 상태 (EC-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListPage — 빈 상태 (EC-1)', () => {
  it('접근 가능한 프로젝트가 0건이면 empty-state를 표시한다', async () => {
    server.use(http.get('/api/v1/projects', () => HttpResponse.json({ data: [] })))
    renderPage()

    await waitFor(() => {
      expect(screen.getByText('표시할 프로젝트가 없습니다')).toBeInTheDocument()
    })
  })

  it('빈 상태 + canCreateProject=true면 "새 프로젝트 만들기" CTA가 표시된다', async () => {
    server.use(http.get('/api/v1/projects', () => HttpResponse.json({ data: [] })))
    useAuthStore.setState({ accessToken: 'test-token', user: makeWhoami({ canCreateProject: true }) })
    renderPage()

    await waitFor(() => {
      expect(screen.getByRole('link', { name: '새 프로젝트 만들기' })).toHaveAttribute(
        'href',
        '/projects/new',
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 행 클릭 네비게이션 — 활성→board, 아카이브→settings/details (S1, G3)
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectListRouteAdapter — 행 클릭 네비게이션', () => {
  it('활성 프로젝트 행 클릭 → /projects/{key} 요약으로 navigate한다 (J4)', async () => {
    const user = userEvent.setup()
    renderAdapter()

    const atlasLink = await screen.findByRole('link', { name: 'ATLAS' })
    await user.click(atlasLink)

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/projects/ATLAS' })
  })

  it('아카이브 프로젝트 행 클릭 → /projects/{key}/settings/details로 navigate한다 (G3)', async () => {
    const user = userEvent.setup()
    renderAdapter()

    await screen.findByRole('link', { name: 'ATLAS' })
    await user.click(screen.getByRole('switch', { name: '아카이브된 프로젝트 표시' }))

    const archivedLink = await screen.findByRole('link', { name: 'OLDONE' })
    await user.click(archivedLink)

    expect(mockNavigate).toHaveBeenCalledWith({ to: '/projects/OLDONE/settings/details' })
  })
})
