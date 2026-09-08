// ProjectSettingsNav 계약 테스트 — 정본 전수 렌더 · sticky 복귀 링크 · h1 부재 · 빈 그룹 생략 (JS-1·JS-2)
import type { ReactNode } from 'react'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { server } from '@/test/server'
import { projectHandlers } from '@/mocks/project-handlers'
import { useAuthStore } from '@/auth/authStore'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { navLabels } from '@/i18n/nav-labels'
import {
  ProjectSettingsNav,
  ProjectSettingsNavGroupSection,
} from '@/components/project/ProjectSettingsNav'
import {
  PROJECT_SETTINGS_NAV,
  type ProjectSettingsNavGroup,
} from '@/components/project/project-shell-mode'

/** 🔒 이 컴포넌트의 nav 이름 — `ProjectSettingsNav.tsx` 의 `SETTINGS_NAV_LABEL` 과 같아야 한다 */
const SETTINGS_NAV_NAME = '설정 메뉴'

/** 테스트에 쓰는 프로젝트 키 — MSW `project-handlers` 시드의 ATLAS */
const PROJECT_KEY = 'ATLAS'

/** ATLAS 시드의 표시 이름 — 복귀 링크의 접근가능 이름이 된다 */
const PROJECT_NAME = 'Atlas 프로젝트'

/** MSW 시드에 없는 키 — 단건 조회가 404 라 이름 폴백 경로가 **결정적으로** 재현된다 */
const MISSING_PROJECT_KEY = 'NOSUCH'

/** 설정 서브앱 안의 임의 경로 — 실제 사용 상황과 같게 둔다 */
const SETTINGS_PATHNAME = `/projects/${PROJECT_KEY}/settings/details`

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 격리된 최소 route tree(메모리 히스토리) + QueryClient 로 마운트한다.
//
// 목적지 라우트를 전부 등록하지는 않는다. `Link` 의 href 해석은 `to` + params 로 이뤄지고
// 활성 판정은 현재 location 과의 비교라, **지금 있는 위치**만 실재하면 된다
// (`ProjectReportsNav.test.tsx` 선례).
//
// 🛑 라우터 마운트는 **비동기**다 — 첫 tick 의 DOM 은 비어 있다. 동기 조회로 단언하면
//    「없다」가 항상 참이라 부재 단언이 통째로 가짜 그린이 된다. 아래 헬퍼가 마운트를 기다린다.
// ─────────────────────────────────────────────────────────────────────────────

function renderAt(pathname: string, node: ReactNode) {
  const rootRoute = createRootRoute()
  const catchAllRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '$',
    component: () => <>{node}</>,
  })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([catchAllRoute]),
    history: createMemoryHistory({ initialEntries: [pathname] }),
    defaultPreload: false,
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={testRouter} />
    </QueryClientProvider>,
  )
}

/** 설정 사이드바를 설정 경로에서 렌더하고, 마운트가 끝난 nav 랜드마크를 돌려준다 */
async function renderNav(projectKey: string = PROJECT_KEY): Promise<HTMLElement> {
  renderAt(`/projects/${projectKey}/settings/details`, <ProjectSettingsNav projectKey={projectKey} />)
  return screen.findByRole('navigation', { name: SETTINGS_NAV_NAME })
}

/** 정본에서 파생한 `$projectKey` 치환 경로 — 기대값을 손으로 적지 않는다 */
function hrefOf(to: string): string {
  return to.replace('$projectKey', PROJECT_KEY)
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'test-token', user: null })
  // useSidebarCollapsed 는 모듈 전역 zustand 싱글톤 — 이전 테스트가 남긴 접힘이 새지 않도록
  // 매 테스트 펼침(기본값)으로 리셋한다.
  useSidebarCollapsed.setState({ collapsed: false })
  // 복귀 링크가 `useProject` 로 프로젝트 이름을 읽는다 — MSW 는 `onUnhandledRequest: 'error'` 다.
  server.use(...projectHandlers)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  useSidebarCollapsed.setState({ collapsed: false })
})

describe('ProjectSettingsNav', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // 전수 렌더 — 정본을 순회한다. 하드코딩 목록을 두면 정본이 바뀔 때 조용히 갈린다.
  // ───────────────────────────────────────────────────────────────────────────

  it('비-공허: 정본이 비어 있지 않다 (0건 순회로 통과하는 것을 막는다)', () => {
    // ★아래 전수 단언들은 `PROJECT_SETTINGS_NAV` 가 비면 **한 번도 돌지 않고** 통과한다.
    const itemCount = PROJECT_SETTINGS_NAV.reduce((sum, group) => sum + group.items.length, 0)
    expect(PROJECT_SETTINGS_NAV.length).toBeGreaterThan(0)
    expect(itemCount).toBeGreaterThan(0)
  })

  it('정본의 모든 그룹 헤딩과 모든 항목 링크를 전수 렌더한다', async () => {
    const nav = await renderNav()

    for (const group of PROJECT_SETTINGS_NAV) {
      // 🛑 `getByText(group.label)` 이 아니라 **목록의 접근가능 이름**으로 조회한다. 이유 두 겹.
      //    ① 더 많은 것을 잰다 — 「헤딩 글자가 어딘가 있다」가 아니라 「헤딩이 자기 목록과
      //       `aria-labelledby` 로 실제로 묶였다」까지 한 번에 걸린다. 연결이 끊기면
      //       목록 앞을 지나가는 텍스트 한 줄일 뿐이라 구간 경계가 스크린리더에 들리지 않는다.
      //    ② 라벨 글자 충돌에 좌우되지 않는다. 한때 `general` 그룹('일반')과 그 첫 항목이
      //       **같은 글자**여서 `getByText` 가 2개를 잡았다. 그 항목은 Jira 원문(JI-1 "Details")에
      //       맞춰 '상세정보' 가 되어 지금은 충돌이 없지만, 설정은 항목이 계속 늘어나는 표면이라
      //       조회 방식이 라벨 충돌에 인질로 잡히지 않는 편이 낫다.
      expect(
        within(nav).getByRole('list', { name: group.label }),
        `그룹 「${group.label}」 헤딩 부재`,
      ).toBeInTheDocument()
      for (const item of group.items) {
        expect(
          within(nav).getByRole('link', { name: item.label }),
          `항목 「${item.label}」 링크 부재`,
        ).toHaveAttribute('href', hrefOf(item.to))
      }
    }
  })

  it('각 항목 링크가 aria-hidden 아이콘(svg)을 렌더한다 — 접힘 레일의 시각 앵커 (★D-1)', async () => {
    const nav = await renderNav()

    for (const group of PROJECT_SETTINGS_NAV) {
      for (const item of group.items) {
        expect(
          within(nav)
            .getByRole('link', { name: item.label })
            .querySelector('svg[aria-hidden="true"]'),
          `항목 「${item.label}」 에 아이콘이 없다 — 64px 레일에서 빈 행이 된다`,
        ).not.toBeNull()
      }
    }
  })

  it('그룹 헤딩이 aria-labelledby 로 자기 목록과 묶인다 (구간 경계가 스크린리더에 들린다)', async () => {
    const nav = await renderNav()

    for (const group of PROJECT_SETTINGS_NAV) {
      const list = within(nav).getByRole('list', { name: group.label })
      expect(within(list).getAllByRole('link')).toHaveLength(group.items.length)
    }
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 복귀 링크 (★리뷰 D-2)
  // ───────────────────────────────────────────────────────────────────────────

  it('최상단 복귀 링크가 프로젝트 이름으로 `/projects/$projectKey` 를 가리킨다', async () => {
    await renderNav()

    const back = await screen.findByRole('link', { name: PROJECT_NAME })
    expect(back).toHaveAttribute('href', `/projects/${PROJECT_KEY}`)
  })

  it('이름을 못 읽어도 키로 폴백한다 — 복귀 링크가 빈 채로 뜨지 않는다', async () => {
    const nav = await renderNav(MISSING_PROJECT_KEY)

    expect(within(nav).getByRole('link', { name: MISSING_PROJECT_KEY })).toHaveAttribute(
      'href',
      `/projects/${MISSING_PROJECT_KEY}`,
    )
  })

  it('복귀 링크는 sticky top 이다 — 스크롤해도 「돌아가는 길」이 화면에 남는다 (★D-2)', async () => {
    await renderNav()

    const wrapper = (await screen.findByRole('link', { name: PROJECT_NAME })).parentElement
    expect(wrapper).not.toBeNull()
    expect(wrapper?.className).toContain('sticky')
    expect(wrapper?.className).toContain('top-0')
  })

  it('복귀 링크가 nav 안에서 DOM 순서상 첫 링크다', async () => {
    const nav = await renderNav()

    expect(within(nav).getAllByRole('link')[0]).toHaveAttribute('href', `/projects/${PROJECT_KEY}`)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 즉사 계약 — h1 부재 · nav 이름
  // ───────────────────────────────────────────────────────────────────────────

  it('<h1> 이 없다 (C-3 — 프로젝트 하위 h1 은 ProjectViewHeader 단독 소유)', async () => {
    await renderNav()

    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it('그룹 헤딩이 heading role 이 아니다 — 설정 본문의 <h2> 조회와 섞이지 않는다', async () => {
    await renderNav()

    expect(screen.queryAllByRole('heading')).toHaveLength(0)
  })

  it('nav 이름이 navLabels 어떤 값과도 substring 관계가 아니다 (C-1 · FR15 동형)', () => {
    // ★`'프로젝트 설정'` 이었다면 `navLabels.projectNav`('프로젝트')를 통째로 품어
    //   `getByRole('navigation', { name: '프로젝트' })` 가 트리와 함께 잡힌다(Playwright 부분일치).
    //   `navLabels` 밖에 둔 이름이라 FR15 판별식이 안 보므로 같은 성질을 여기서 잰다.
    const violations = Object.entries(navLabels)
      .filter(([, value]) => value.includes(SETTINGS_NAV_NAME) || SETTINGS_NAV_NAME.includes(value))
      .map(([key, value]) => `${key}('${value}')`)

    expect(Object.keys(navLabels).length).toBeGreaterThan(0)
    expect(violations).toEqual([])
  })

  it('설정 사이드바가 만드는 nav 랜드마크는 1개다', async () => {
    await renderNav()

    const navNames = screen.getAllByRole('navigation').map((el) => el.getAttribute('aria-label'))
    expect(navNames).toEqual([SETTINGS_NAV_NAME])
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 접힘 레일(64px) — 라벨은 DOM 에 남고 시각적으로만 숨는다
  // ───────────────────────────────────────────────────────────────────────────

  it('접힘 시 항목 라벨이 sr-only 가 되고 접근가능 이름은 유지된다 (아이콘 레일)', async () => {
    useSidebarCollapsed.setState({ collapsed: true })
    const nav = await renderNav()

    for (const group of PROJECT_SETTINGS_NAV) {
      for (const item of group.items) {
        const link = within(nav).getByRole('link', { name: item.label })
        expect(link.querySelector('svg[aria-hidden="true"]')).not.toBeNull()
        expect(link.querySelector('span')?.className).toContain('sr-only')
      }
    }
  })

  it('접힘 시 그룹 헤딩도 sr-only 로만 내려간다 — DOM 에는 남는다', async () => {
    useSidebarCollapsed.setState({ collapsed: true })
    const nav = await renderNav()

    for (const group of PROJECT_SETTINGS_NAV) {
      // 헤딩 요소는 목록의 `aria-labelledby` 를 되짚어 집는다 — 위와 같은 이유(연결까지 함께
      // 재고, 라벨 충돌에 좌우되지 않는다)로 텍스트 조회를 쓰지 않는다.
      const headingId = within(nav)
        .getByRole('list', { name: group.label })
        .getAttribute('aria-labelledby')
      expect(headingId).not.toBeNull()
      expect(headingId === null ? null : document.getElementById(headingId)?.className).toContain(
        'sr-only',
      )
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// ★리뷰 D-5 — 항목 0개인 그룹은 헤딩도 그리지 않는다.
//
// 정본이 항상 10항목이라 부모(`ProjectSettingsNav`)만 렌더해서는 이 상태에 도달할 수 없다.
// 도달 불가 상태를 지키는 단언은 가짜 그린이므로(`unreachable-state-fixture-is-fake-green`)
// 구간 컴포넌트를 픽스처로 직접 흔든다. 짝(비-공허) 단언이 바로 아래 있다 — 「헤딩을 아예
// 안 그리는」 구현도 빈 그룹 단언만으로는 통과하기 때문이다.
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectSettingsNavGroupSection — 빈 그룹 계약 (★D-5)', () => {
  const EMPTY_GROUP: ProjectSettingsNavGroup = { key: 'access', label: '텅 빈 그룹', items: [] }

  /** 마운트 완료 마커 — 라우터가 비동기라 이것 없이는 부재 단언이 「아직 안 그려짐」과 구분되지 않는다 */
  const MOUNTED_MARKER = 'settings-nav-section-mounted'

  it('항목이 0개면 그룹 헤딩도 그리지 않는다 (빈 헤딩은 「고장났다」로 읽힌다)', async () => {
    renderAt(
      SETTINGS_PATHNAME,
      <>
        <div data-testid={MOUNTED_MARKER} />
        <ProjectSettingsNavGroupSection
          group={EMPTY_GROUP}
          projectKey={PROJECT_KEY}
          collapsed={false}
        />
      </>,
    )
    await screen.findByTestId(MOUNTED_MARKER)

    expect(screen.queryByText(EMPTY_GROUP.label)).not.toBeInTheDocument()
    expect(screen.queryAllByRole('list')).toHaveLength(0)
  })

  it('짝 단언: 항목이 1개 이상이면 헤딩과 링크를 그린다 (헤딩 전면 누락과 구분한다)', async () => {
    const group = PROJECT_SETTINGS_NAV[0]
    expect(group).toBeDefined()
    if (group === undefined) return

    renderAt(
      SETTINGS_PATHNAME,
      <ProjectSettingsNavGroupSection group={group} projectKey={PROJECT_KEY} collapsed={false} />,
    )

    expect(await screen.findByRole('list', { name: group.label })).toBeInTheDocument()
    expect(screen.getAllByRole('link')).toHaveLength(group.items.length)
  })
})
