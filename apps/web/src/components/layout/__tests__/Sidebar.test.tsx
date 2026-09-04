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
import { ADMIN_HUB_LINKS } from '@/lib/admin-hub-links'
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
    const query = search === undefined ? '' : `?${new URLSearchParams(search).toString()}`
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

/**
 * 사이드바에 **없어야 할** 관리 링크 라벨 — 정본에서 파생한다(J9).
 *
 * 🛑 손으로 나열하지 마라. 사본을 두면 「9번째 링크를 허브에 넣으면서 사이드바에도
 *    되살리는」 조합을 아무도 못 본다 — 사본은 그 이름을 모르기 때문이다. 실제로 J9 이전
 *    두 목록은 한 칸 어긋나 있었다(`/admin/users/new` 가 허브에만). 정본에서 파생하면
 *    이후 추가되는 모든 허브 링크가 자동으로 이 부재 단언의 대상이 된다.
 */
const ADMIN_LINK_LABELS: readonly string[] = ADMIN_HUB_LINKS.map((link) => link.label)

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
  server.use(http.get('/api/v1/favorites', () => HttpResponse.json({ data: { items: [] } })))
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

  it('isSystemAdmin=true 여도 사이드바에 관리 메뉴 nav 가 없다 (J9 — 상단바 허브로 이관)', () => {
    // ★관리자일 때를 재는 것이 핵심이다. 비관리자에서는 이관 전에도 없었으므로 그 케이스만
    //   남기면 「옮겼다」를 증명하지 못한다 — 옮기기 전 코드에서도 초록이기 때문이다.
    useAuthStore.setState({
      accessToken: 'test-token',
      user: { ...BASE_USER, isSystemAdmin: true },
    })
    renderSidebar()

    expect(screen.queryByRole('navigation', { name: navLabels.adminNav })).not.toBeInTheDocument()
    for (const label of ADMIN_LINK_LABELS) {
      expect(
        screen.queryByRole('link', { name: label }),
        `사이드바에 관리 링크 「${label}」 가 남아 있다 — 진입점이 둘로 갈렸다`,
      ).not.toBeInTheDocument()
    }
  })

  it('isSystemAdmin=false 에서도 관리 링크가 없다 (게이팅 회귀 방지)', () => {
    renderSidebar()

    expect(screen.queryByRole('navigation', { name: navLabels.adminNav })).not.toBeInTheDocument()
    for (const label of ADMIN_LINK_LABELS) {
      expect(screen.queryByRole('link', { name: label })).not.toBeInTheDocument()
    }
  })

  it('관리 링크 라벨 파생이 비어 있지 않다 (0건 순회로 통과하는 것을 막는다)', () => {
    // ★`ADMIN_HUB_LINKS` 가 비거나 import 가 깨지면 위 두 루프가 **한 번도 돌지 않고** 통과한다.
    //   사본을 없앤 대가로 생긴 새 구멍이라 여기서 막는다.
    expect(ADMIN_LINK_LABELS.length).toBeGreaterThan(5)
    expect(ADMIN_LINK_LABELS).toContain('감사 로그')
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
      within(mainNav)
        .getByRole('link', { name: navLabels.issues })
        .querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
    expect(
      within(mainNav)
        .getByRole('link', { name: navLabels.dashboards })
        .querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
    expect(
      within(mainNav)
        .getByRole('link', { name: navLabels.calendar })
        .querySelector('svg[aria-hidden="true"]'),
    ).not.toBeNull()
  })

  it('프로젝트 nav(ProjectTree)가 렌더되고 메인 메뉴 nav보다 DOM 순서상 뒤에 위치한다 (캠페인 PR ⑩ · J2)', async () => {
    renderSidebar()

    const projectNav = await screen.findByRole('navigation', { name: navLabels.projectNav })
    const mainNav = screen.getByRole('navigation', { name: navLabels.mainNav })

    // ★ FR-UX-06 PR12 Task 3 은 반대(트리가 위)였고 J2 가 뒤집었다. 트리는 프로젝트 수만큼
    //   길어지므로 위에 두면 매일 여는 전역 링크가 스크롤 밖으로 밀린다.
    // compareDocumentPosition의 DOCUMENT_POSITION_FOLLOWING(4)는 "인자가 기준 노드보다 뒤"를 뜻한다.
    expect(
      mainNav.compareDocumentPosition(projectNav) & Node.DOCUMENT_POSITION_FOLLOWING,
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

      const navNames = screen.getAllByRole('navigation').map((el) => el.getAttribute('aria-label'))

      // 순서도 계약이다 — 메인 메뉴가 먼저고 스페이스 트리가 뒤다 (캠페인 PR ⑩ · J2)
      expect(navNames).toEqual([navLabels.mainNav, navLabels.projectNav])
    })
  })
})
