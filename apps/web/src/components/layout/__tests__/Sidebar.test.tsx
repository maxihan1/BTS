// Sidebar 컴포넌트 단위 테스트 — 메인/관리 nav 렌더, isSystemAdmin 게이팅, h1/검색 부재, 접힘 시 접근가능 이름 보존 (FR-UX-06 PR11 Task 5)
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import type { WhoamiResponse } from '@/api/schemas'
import { favoriteLabels } from '@/i18n/favorite-labels'
import { navLabels } from '@/i18n/nav-labels'
import { projectListHandlers } from '@/mocks/project-list-handlers'
import { Sidebar } from '../Sidebar'

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Router Link/useParams 모킹 — 라우터 컨텍스트 없이 컴포넌트 isolation 렌더 (Header.test.tsx·
// FavoritesMenu.test.tsx 동일 패턴). `useParams`는 Sidebar가 배선하는 `ProjectTree`(FR-UX-06 PR12
// Task 2)가 활성 프로젝트 판별에 사용하므로 함께 모킹한다 — 항상 빈 객체를 반환해 "프로젝트 컨텍스트
// 밖"으로 취급된다(이 파일의 계약과 무관, ProjectTree 자체 활성 펼침 검증은 ProjectTree.test.tsx 소관).
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@tanstack/react-router', () => ({
  // `search`를 href 쿼리스트링으로 직렬화한다 — "내 작업"(FR12)이 `search={{ assignee }}`를
  // 싣는데, 직렬화하지 않으면 href 단언이 `projectKey` 미탑재(§1-A)를 검증할 수 없다.
  // `activeOptions`(E8)는 활성 표시 계산용이라 DOM에 영향이 없어 받기만 하고 버린다.
  Link: ({
    to,
    search,
    children,
    className,
  }: {
    to: string
    search?: Record<string, string>
    activeOptions?: { includeSearch?: boolean }
    children: React.ReactNode
    className?: string
  }) => {
    const query =
      search === undefined ? '' : `?${new URLSearchParams(search).toString()}`
    return (
      <a href={`${to}${query}`} className={className}>
        {children}
      </a>
    )
  },
  useParams: () => ({}),
  // ProjectTree가 검색 파라미터 `?projectKey=`도 활성 프로젝트 근거로 읽는다(FR-UX-08 FR7).
  // 이 파일의 계약(사이드바 랜드마크·라벨)과는 무관하므로 빈 검색 파라미터로 모킹한다.
  useSearch: () => ({}),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const BASE_USER: WhoamiResponse = {
  username: 'alice',
  email: 'alice@bts.local',
  authMethod: 'local',
  userId: 'u1',
  mustChangePassword: false,
  isSystemAdmin: false,
  mfaEnrollmentRequired: false,
}

/** 관리 nav 6링크 계약 — [라벨, href] */
const ADMIN_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['워크플로우 관리', '/admin/workflows'],
  ['워크플로우 스킴', '/admin/workflow-schemes'],
  ['감사 로그', '/admin/audit-logs'],
  ['전역 권한', '/admin/global-permissions'],
  ['알림 정책', '/admin/notification-policies'],
  ['Webhook', '/admin/webhooks'],
  ['Slack 연결', '/admin/slack'],
]

function makeWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderSidebar() {
  return render(<Sidebar />, { wrapper: makeWrapper() })
}

beforeEach(() => {
  window.localStorage.clear()
  useAuthStore.setState({ accessToken: 'test-token', user: BASE_USER })
  // useSidebarCollapsed는 모듈 전역 zustand 싱글톤 — 이전 테스트의 toggle()이 남긴 상태가
  // 누출되지 않도록 매 테스트 펼침(기본값)으로 리셋한다(테스트 간 격리)
  useSidebarCollapsed.setState({ collapsed: false })
  // FavoritesMenu(useFavorites)가 조회하는 엔드포인트 — 빈 목록으로 응답
  server.use(
    http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })),
  )
  // ProjectTree(useProjects)가 조회하는 엔드포인트 — mocks/handlers.ts 전역 등록에 더해 명시
  // 등록한다(use-projects.test.tsx·ProjectTree.test.tsx와 동일 관례)
  server.use(...projectListHandlers)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('Sidebar', () => {
  it('메인 메뉴 nav가 존재하고 이슈·대시보드·캘린더 링크를 포함한다 (FR3)', () => {
    renderSidebar()

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(within(mainNav).getByRole('link', { name: navLabels.issues })).toHaveAttribute(
      'href',
      '/issues',
    )
    expect(within(mainNav).getByRole('link', { name: navLabels.dashboards })).toHaveAttribute(
      'href',
      '/dashboards',
    )
    expect(within(mainNav).getByRole('link', { name: navLabels.calendar })).toHaveAttribute(
      'href',
      '/calendar',
    )
  })

  it('메인 메뉴 nav 안에 즐겨찾기(FavoritesMenu) 트리거가 존재한다 (FR3)', () => {
    renderSidebar()

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(
      within(mainNav).getByRole('button', { name: favoriteLabels.dropdownTriggerAriaLabel }),
    ).toBeInTheDocument()
  })

  it('isSystemAdmin=true이면 관리 메뉴 nav가 기본 펼침 상태로 7링크를 노출한다 (FR4)', () => {
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { ...BASE_USER, isSystemAdmin: true },
    })
    renderSidebar()

    const adminNav = screen.getByRole('navigation', { name: navLabels.adminNav })
    for (const [label, href] of ADMIN_LINK_CONTRACT) {
      const link = within(adminNav).getByRole('link', { name: label })
      expect(link).toBeVisible()
      expect(link).toHaveAttribute('href', href)
    }
  })

  it('isSystemAdmin=false이면 관리 메뉴 nav가 렌더되지 않는다 (FR4)', () => {
    renderSidebar()

    expect(screen.queryByRole('navigation', { name: navLabels.adminNav })).not.toBeInTheDocument()
    for (const [label] of ADMIN_LINK_CONTRACT) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument()
    }
  })

  it('사이드바에 <h1>이 없다', () => {
    renderSidebar()

    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it('검색 항목이 없다 — 상단바(Header) 단일 소유', () => {
    renderSidebar()

    expect(screen.queryByRole('button', { name: navLabels.search })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(navLabels.search)).not.toBeInTheDocument()
  })

  it('사이드바 접기 토글 클릭 시에도 메인 nav 링크의 접근가능 이름이 유지된다', async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.click(screen.getByRole('button', { name: navLabels.collapseSidebar }))

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(within(mainNav).getByRole('link', { name: navLabels.issues })).toBeInTheDocument()
    expect(within(mainNav).getByRole('link', { name: navLabels.dashboards })).toBeInTheDocument()
    expect(within(mainNav).getByRole('link', { name: navLabels.calendar })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: navLabels.expandSidebar })).toBeInTheDocument()
  })

  it('메인 nav 각 링크가 aria-hidden 아이콘(svg)을 렌더한다 (접힘=진짜 아이콘 레일, FR5)', () => {
    renderSidebar()

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    expect(
      within(mainNav).getByRole('link', { name: navLabels.issues }).querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
    expect(
      within(mainNav)
        .getByRole('link', { name: navLabels.dashboards })
        .querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
    expect(
      within(mainNav).getByRole('link', { name: navLabels.calendar }).querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
  })

  it('프로젝트 nav(ProjectTree)가 렌더되고 메인 메뉴 nav보다 DOM 순서상 앞에 위치한다 (FR-UX-06 PR12 Task 3)', async () => {
    renderSidebar()

    const projectNav = await screen.findByRole('navigation', { name: navLabels.projectNav })
    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })

    // projectNav → mainNav 순서면, mainNav 기준 projectNav는 "이전 형제"다.
    // compareDocumentPosition의 DOCUMENT_POSITION_PRECEDING(2)는 "인자가 기준 노드보다 앞선다"를 뜻한다.
    expect(
      mainNav.compareDocumentPosition(projectNav) & Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy()
  })

  it('사이드바 접힘 시 nav 링크의 텍스트 라벨이 시각적으로만 숨겨진다(sr-only) — 아이콘은 그대로 보인다', async () => {
    const user = userEvent.setup()
    renderSidebar()

    await user.click(screen.getByRole('button', { name: navLabels.collapseSidebar }))

    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
    const issuesLink = within(mainNav).getByRole('link', { name: navLabels.issues })
    expect(issuesLink.querySelector('svg[aria-hidden="true"]')).not.toBeNull()
    expect(issuesLink.querySelector('span')?.className).toContain('sr-only')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-08 PR-B — "내 작업" (FR12 · §8-A D-A/D-C · E8/E9/E10)
  // ───────────────────────────────────────────────────────────────────────────
  describe('"내 작업" 링크 (FR-UX-08 PR-B FR12)', () => {
    it('T-MW-1 (S7): userId가 있으면 /issues?assignee=<userId> 링크가 렌더된다', () => {
      renderSidebar()

      const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
      expect(within(mainNav).getByRole('link', { name: navLabels.myWork })).toHaveAttribute(
        'href',
        '/issues?assignee=u1',
      )
    })

    it('T-MW-2 (§1-A): href에 projectKey를 싣지 않는다 — 프로젝트는 라우트가 해소한다', () => {
      // 사이드바는 useProjects() 응답보다 먼저 렌더되므로 링크가 활성 프로젝트를 계산해
      // 붙이면 한 박자 늦게 바뀌고, 그 사이 클릭하면 빈 projectKey가 실린다(FR-UX-07 §1).
      renderSidebar()

      const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
      const href = within(mainNav)
        .getByRole('link', { name: navLabels.myWork })
        .getAttribute('href')

      expect(href).not.toBeNull()
      expect(href).not.toContain('projectKey')
    })

    it('T-MW-3 (E9): userId가 없으면 항목을 렌더하지 않는다 — 죽은 링크를 만들지 않는다', () => {
      useAuthStore.setState({ accessToken: 'test-token', user: null })
      renderSidebar()

      expect(screen.queryByRole('link', { name: navLabels.myWork })).toBeNull()
    })

    it('T-MW-4 (§8-A D-A): 메인 메뉴 nav 안에서 "이슈"보다 앞에 온다', () => {
      // 순서가 곧 중요도 신호다 — 매일 여는 진입점이 캘린더 밑에 묻히면 안 된다.
      renderSidebar()

      const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
      const linkNames = within(mainNav)
        .getAllByRole('link')
        .map((el) => el.textContent?.trim() ?? '')

      expect(linkNames.indexOf(navLabels.myWork)).toBeGreaterThanOrEqual(0)
      expect(linkNames.indexOf(navLabels.myWork)).toBeLessThan(linkNames.indexOf(navLabels.issues))
    })

    it('T-MW-5 (E10): 접힘 시 아이콘은 남고 텍스트만 sr-only가 된다 (DOM 유지)', async () => {
      renderSidebar()
      const toggle = screen.getByRole('button', { name: navLabels.collapseSidebar })
      await userEvent.click(toggle)

      const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })
      const myWorkLink = within(mainNav).getByRole('link', { name: navLabels.myWork })
      expect(myWorkLink.querySelector('svg[aria-hidden="true"]')).not.toBeNull()
      expect(myWorkLink.querySelector('span')?.className).toContain('sr-only')
    })

    it('T-MW-6 (FR13-b/NFR3): 새 nav 랜드마크를 만들지 않는다', () => {
      // ADR §D5 — `<nav>`를 늘리면 navigation-contract.test.tsx의 aria-label 4종 가드와
      // e2e `getByRole('navigation')` 계약이 동시에 깨진다.
      renderSidebar()

      const navNames = screen
        .getAllByRole('navigation')
        .map((el) => el.getAttribute('aria-label'))

      expect(navNames).toEqual([navLabels.projectNav, navLabels.mainNav])
    })
  })
})
