// FavoritesMenu 컴포넌트 단위 테스트 — 트리거 버튼, 타입별 그룹 렌더, 링크, 빈 상태 (FR-UX-02 Task-5)
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { mockAccessToken } from '@/mocks/auth-fixtures'
import { seedFavorites } from '@/mocks/favorite-handlers'
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
