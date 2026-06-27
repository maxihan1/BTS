// FavoritesMenu 컴포넌트 단위 테스트 — 트리거 버튼, 타입별 그룹 렌더, 링크, 빈 상태 (FR-UX-02 Task-5)
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { mockAccessToken } from '@/mocks/auth-fixtures'
import { seedFavorites, favoriteHandlers } from '@/mocks/favorite-handlers'
import { favoriteLabels } from '@/i18n/favorite-labels'
import { FavoritesMenu } from './FavoritesMenu'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router Link 모킹 — 라우터 컨텍스트 없이 단위 테스트 가능
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
}))

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper
// ─────────────────────────────────────────────────────────────────────────────

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderMenu() {
  return render(<FavoritesMenu />, { wrapper: makeWrapper() })
}

// ─────────────────────────────────────────────────────────────────────────────
// alice fixture 초기화
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

beforeEach(() => {
  useAuthStore.setState({
    accessToken: mockAccessToken('alice'),
    user: {
      username: 'alice',
      email: 'alice@bts.local',
      authMethod: 'local',
      userId: ALICE_USER_ID,
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    },
  })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// S1. 트리거 버튼 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S1 트리거 버튼', () => {
  it('S1a: 트리거 버튼이 렌더되고 aria-label이 있다', () => {
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ data: { items: [] } }),
      ),
    )
    renderMenu()

    const btn = screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel })
    expect(btn).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 빈 상태
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S2 빈 상태', () => {
  it('S2a: 즐겨찾기가 없을 때 드롭다운 열면 빈 상태 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/v1/favorites', () =>
        HttpResponse.json({ data: { items: [] } }),
      ),
    )
    renderMenu()

    const btn = screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel })
    await user.click(btn)

    expect(await screen.findByText(favoriteLabels.emptyMessage)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 타입별 그룹 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S3 타입별 그룹 렌더', () => {
  beforeEach(() => {
    // favoriteHandlers의 stateful GET 핸들러를 활성화 — seedFavorites 데이터 반환
    server.use(...favoriteHandlers)
    // created_at DESC: DASHBOARD가 가장 최근, ISSUE가 가장 오래됨
    seedFavorites(ALICE_USER_ID, [
      {
        id: 'fav-001',
        targetType: 'ISSUE',
        targetId: 'ATLAS-42',
        createdAt: '2024-01-01T00:00:00Z',
      },
      {
        id: 'fav-002',
        targetType: 'DASHBOARD',
        targetId: 'dash-uuid-001',
        createdAt: '2024-02-01T00:00:00Z',
      },
      {
        id: 'fav-003',
        targetType: 'PROJECT',
        targetId: 'ATLAS',
        createdAt: '2024-01-15T00:00:00Z',
      },
    ])
  })

  it('S3a: 드롭다운 열면 이슈 그룹명이 표시된다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText(favoriteLabels.groupIssue)).toBeInTheDocument()
  })

  it('S3b: 드롭다운 열면 대시보드 그룹명이 표시된다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText(favoriteLabels.groupDashboard)).toBeInTheDocument()
  })

  it('S3c: 드롭다운 열면 프로젝트 그룹명이 표시된다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText(favoriteLabels.groupProject)).toBeInTheDocument()
  })

  it('S3d: 이슈 항목이 targetId를 라벨로 표시한다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText('ATLAS-42')).toBeInTheDocument()
  })

  it('S3e: 대시보드 항목이 targetId를 라벨로 표시한다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText('dash-uuid-001')).toBeInTheDocument()
  })

  it('S3f: 프로젝트 항목이 targetId를 라벨로 표시한다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText('ATLAS')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 항목별 올바른 라우트 Link
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S4 항목 Link 라우트', () => {
  beforeEach(() => {
    server.use(...favoriteHandlers)
  })

  it('S4a: ISSUE 항목 링크가 /issues/{key} 경로를 갖는다', async () => {
    const user = userEvent.setup()
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-i', targetType: 'ISSUE', targetId: 'ATLAS-10', createdAt: '2024-01-01T00:00:00Z' },
    ])
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    const link = await screen.findByRole('link', { name: 'ATLAS-10' })
    expect(link).toHaveAttribute('href', '/issues/ATLAS-10')
  })

  it('S4b: DASHBOARD 항목 링크가 /dashboards/{id} 경로를 갖는다', async () => {
    const user = userEvent.setup()
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-d', targetType: 'DASHBOARD', targetId: 'dash-abc', createdAt: '2024-01-01T00:00:00Z' },
    ])
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    const link = await screen.findByRole('link', { name: 'dash-abc' })
    expect(link).toHaveAttribute('href', '/dashboards/dash-abc')
  })

  it('S4c: PROJECT 항목 링크가 /projects/{key}/board 경로를 갖는다', async () => {
    const user = userEvent.setup()
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-p', targetType: 'PROJECT', targetId: 'MYPROJ', createdAt: '2024-01-01T00:00:00Z' },
    ])
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    const link = await screen.findByRole('link', { name: 'MYPROJ' })
    expect(link).toHaveAttribute('href', '/projects/MYPROJ/board')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 그룹 순서 — 이슈 → 대시보드 → 프로젝트
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S5 그룹 순서', () => {
  beforeEach(() => {
    server.use(...favoriteHandlers)
  })

  it('S5a: 그룹이 이슈→대시보드→프로젝트 순서로 렌더된다', async () => {
    const user = userEvent.setup()
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-i', targetType: 'ISSUE', targetId: 'ATLAS-1', createdAt: '2024-01-01T00:00:00Z' },
      { id: 'fav-d', targetType: 'DASHBOARD', targetId: 'dash-1', createdAt: '2024-02-01T00:00:00Z' },
      { id: 'fav-p', targetType: 'PROJECT', targetId: 'PROJ1', createdAt: '2024-03-01T00:00:00Z' },
    ])
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    // 세 그룹 레이블이 모두 존재해야 한다
    const issueLabel = await screen.findByText(favoriteLabels.groupIssue)
    const dashLabel = await screen.findByText(favoriteLabels.groupDashboard)
    const projLabel = await screen.findByText(favoriteLabels.groupProject)

    // DOM 순서 검증: 이슈 그룹이 대시보드 그룹보다 앞에 위치
    expect(issueLabel.compareDocumentPosition(dashLabel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // 대시보드 그룹이 프로젝트 그룹보다 앞에 위치
    expect(dashLabel.compareDocumentPosition(projLabel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 공용 픽스처 — FILTER 테스트
// ─────────────────────────────────────────────────────────────────────────────

const FILTER_UUID = '550e8400-e29b-41d4-a716-446655440001'
const FILTER_NAME = '내 이슈 필터'

/** SavedFilterResponse 형태의 인라인 MSW 픽스처 */
const makeFilterFixture = (id: string, name: string) => ({
  id,
  ownerId: ALICE_USER_ID,
  name,
  aqlQuery: 'status = OPEN',
  projectKey: 'ATLAS',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: null,
  version: 1,
  isOwner: true,
  shares: [],
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. FILTER 타입 즐겨찾기 — 이름 비동기 조회 + /search?filterId=<id> Link
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S6 FILTER 그룹 렌더', () => {
  beforeEach(() => {
    server.use(...favoriteHandlers)
    seedFavorites(ALICE_USER_ID, [
      {
        id: 'fav-filter-001',
        targetType: 'FILTER',
        targetId: FILTER_UUID,
        createdAt: '2024-03-01T00:00:00Z',
      },
    ])
    server.use(
      http.get('/api/v1/filters/:id', () =>
        HttpResponse.json(makeFilterFixture(FILTER_UUID, FILTER_NAME)),
      ),
    )
  })

  it('S6a: 드롭다운 열면 "필터" 그룹 헤더가 표시된다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText('필터')).toBeInTheDocument()
  })

  it('S6b: 필터 항목이 이름으로 표시되고 /search?filterId=<id> 링크를 갖는다', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    const link = await screen.findByRole('link', { name: FILTER_NAME })
    expect(link).toHaveAttribute('href', `/search?filterId=${FILTER_UUID}`)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. FILTER 404 처리 — 항목 숨김 + 전체 404 시 헤더 비표시
// ─────────────────────────────────────────────────────────────────────────────

const FILTER_404_UUID = '550e8400-e29b-41d4-a716-446655440404'
const FILTER_200_UUID = '550e8400-e29b-41d4-a716-446655440200'
const FILTER_200_NAME = '살아있는 필터'

describe('FavoritesMenu — S7 FILTER 404 처리', () => {
  beforeEach(() => {
    server.use(...favoriteHandlers)
  })

  it('S7a: 404 필터 항목은 숨겨지고 200 필터 항목만 표시된다', async () => {
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-404', targetType: 'FILTER', targetId: FILTER_404_UUID, createdAt: '2024-03-01T00:00:00Z' },
      { id: 'fav-200', targetType: 'FILTER', targetId: FILTER_200_UUID, createdAt: '2024-03-02T00:00:00Z' },
    ])
    server.use(
      http.get('/api/v1/filters/:id', ({ params }) => {
        const id = params['id']
        if (id === FILTER_200_UUID) {
          return HttpResponse.json(makeFilterFixture(FILTER_200_UUID, FILTER_200_NAME))
        }
        return HttpResponse.json(
          { errorCode: 'SEARCH_FILTER_NOT_FOUND', message: '없음' },
          { status: 404 },
        )
      }),
    )

    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    // 200 필터는 표시
    const link = await screen.findByRole('link', { name: FILTER_200_NAME })
    expect(link).toBeInTheDocument()
    // 404 필터 링크는 없음 — 링크가 1개만 존재
    expect(screen.getAllByRole('link')).toHaveLength(1)
  })

  it('S7b: 모든 FILTER가 404이면 "필터" 그룹 헤더가 표시되지 않는다', async () => {
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-404', targetType: 'FILTER', targetId: FILTER_404_UUID, createdAt: '2024-03-01T00:00:00Z' },
    ])
    server.use(
      http.get('/api/v1/filters/:id', () =>
        HttpResponse.json(
          { errorCode: 'SEARCH_FILTER_NOT_FOUND', message: '없음' },
          { status: 404 },
        ),
      ),
    )

    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    // 드롭다운이 열린 상태 확인 (제목 표시)
    await screen.findByText(favoriteLabels.dropdownTitle)

    // 쿼리 settle 후에도 "필터" 헤더가 없어야 함
    await waitFor(() => {
      expect(screen.queryByText('필터')).not.toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 기존 ISSUE/DASHBOARD/PROJECT 그룹 회귀 없음
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoritesMenu — S8 FILTER 추가 후 기존 그룹 회귀 없음', () => {
  it('S8a: ISSUE/DASHBOARD/PROJECT 그룹은 FILTER 추가 후에도 정상 렌더된다', async () => {
    server.use(...favoriteHandlers)
    seedFavorites(ALICE_USER_ID, [
      { id: 'fav-i', targetType: 'ISSUE', targetId: 'ATLAS-42', createdAt: '2024-01-01T00:00:00Z' },
      { id: 'fav-d', targetType: 'DASHBOARD', targetId: 'dash-1', createdAt: '2024-02-01T00:00:00Z' },
      { id: 'fav-p', targetType: 'PROJECT', targetId: 'ATLAS', createdAt: '2024-01-15T00:00:00Z' },
    ])

    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }))

    expect(await screen.findByText(favoriteLabels.groupIssue)).toBeInTheDocument()
    expect(screen.getByText(favoriteLabels.groupDashboard)).toBeInTheDocument()
    expect(screen.getByText(favoriteLabels.groupProject)).toBeInTheDocument()
    expect(await screen.findByText('ATLAS-42')).toBeInTheDocument()
  })
})
