// 탭 정본 판별식 — 죽은 링크 0 · 실 라우트 전수에서 활성 탭 ≤ 1 · 요약 exact (Jira 패리티 J5)
import { describe, it, expect } from 'vitest'
import { router } from '@/router'
import {
  PROJECT_VIEW_TABS,
  resolveActiveTabIndex,
  resolveBoardScopeSearch,
  resolveTabHref,
  type ProjectViewTab,
} from '@/components/project/project-view-tabs'

/** 실 라우트를 흉내낼 때 쓰는 프로젝트 키 — 아무 값이어도 되지만 한 벌로 고정한다 */
const PROJECT_KEY = 'ATLAS'

/**
 * 라우트 id 접두사. `_shell` 은 pathless 레이아웃이라 자식 라우트 id 앞에 붙는다
 * (`router.admin-guards.test.tsx` 가 실측으로 남긴 형태).
 */
const SHELL = '/_shell'

/** 라우터가 아는 라우트 id 전량 */
const routeIds = Object.keys(router.routesById)

/**
 * 라우트 id → 실제 pathname. 동적 세그먼트를 고정 값으로 치환한다.
 *
 * `$projectKey` 만 특별 취급하고 나머지(`$sprintId`·`$key`…)는 아무 값이나 넣는다 —
 * 활성 판정은 프로젝트 경로 모양만 보기 때문이다.
 */
function toPathname(routeId: string): string {
  return routeId
    .slice(SHELL.length)
    .replace('$projectKey', PROJECT_KEY)
    .replace(/\$[A-Za-z]+/g, 'x')
}

/** `_shell` 하위의 실 라우트만 — 루트·로그인·pathless 자신은 활성 판정 대상이 아니다 */
const shellRouteIds = routeIds.filter((id) => id.startsWith(`${SHELL}/`))

/** 활성 탭이 될 수 있는 탭 — 전역 링크 3종(편차 X9)은 탭바가 사라지므로 제외된다 */
const projectScopedTabs = PROJECT_VIEW_TABS.filter((tab) => tab.usesProjectParam)

describe('PROJECT_VIEW_TABS — 정본 구성', () => {
  it('비-공허: 탭이 9종이고 키가 중복되지 않는다', () => {
    expect(PROJECT_VIEW_TABS).toHaveLength(9)
    const keys = PROJECT_VIEW_TABS.map((tab) => tab.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('Maxi 확정 순서 그대로다 (요약·타임라인·보드·백로그·캘린더·대시보드·컴포넌트·이슈·버전)', () => {
    // 순서가 곧 계약이다 — 목업 v2 + 백로그 1건(2026-09-03 확정).
    expect(PROJECT_VIEW_TABS.map((tab) => tab.key)).toEqual([
      'summary',
      'timeline',
      'board',
      'backlog',
      'calendar',
      'dashboards',
      'components',
      'issues',
      'versions',
    ])
  })

  it('죽은 링크 0 — 모든 탭의 `to` 가 라우터에 등록된 라우트다', () => {
    // 비-공허 짝: 라우트 목록 자체가 비면 아래 단언이 조용히 통과한다.
    expect(routeIds.length).toBeGreaterThan(50)

    const dead = PROJECT_VIEW_TABS.filter((tab) => !routeIds.includes(`${SHELL}${tab.to}`)).map(
      (tab) => `${tab.key} → ${tab.to}`,
    )
    expect(dead).toEqual([])
  })

  it('요약만 exact 이다 — 나머지가 exact 가 되면 하위 경로에서 강조가 꺼진다', () => {
    const exactKeys = PROJECT_VIEW_TABS.filter((tab) => tab.exact).map((tab) => tab.key)
    expect(exactKeys).toEqual(['summary'])
  })

  it('전역 링크 3종만 `usesProjectParam: false` 다 (편차 X9 전수)', () => {
    const global = PROJECT_VIEW_TABS.filter((tab) => !tab.usesProjectParam).map((tab) => tab.key)
    expect(global).toEqual(['calendar', 'dashboards', 'issues'])
  })

  it('프로젝트 키를 search 로 싣는 탭은 이슈 하나뿐이고 라우트가 그 키를 검증한다', () => {
    const withSearch = PROJECT_VIEW_TABS.filter(
      (tab) => tab.projectKeySearchParam !== undefined,
    ).map((tab) => tab.key)
    expect(withSearch).toEqual(['issues'])

    // 짝 검사 — 라우트가 `projectKey` 를 실제로 파싱하지 않으면 링크가 조용히 무시된다.
    const issuesRoute = router.routesById[`${SHELL}/issues`]
    const parsed = issuesRoute?.options.validateSearch?.({ projectKey: PROJECT_KEY }) as
      | { projectKey?: string }
      | undefined
    expect(parsed?.projectKey).toBe(PROJECT_KEY)
  })
})

describe('resolveTabHref — 순수 함수와 라우터의 두 층이 같은 href 를 만든다', () => {
  it('비-공허: 대조 대상이 9건이다', () => {
    expect(PROJECT_VIEW_TABS).toHaveLength(9)
  })

  it('모든 탭에서 `resolveTabHref` 와 `router.buildLocation` 의 pathname 이 일치한다', () => {
    // ① 시각·ARIA 는 Link(=라우터)가 판정하고 ② 오버플로 인덱스는 순수 함수가 판정한다.
    // 두 층이 다른 경로를 만들면 「강조된 탭」과 「핀 고정된 탭」이 어긋난다.
    const mismatches = PROJECT_VIEW_TABS.filter((tab) => {
      const built = router.buildLocation({
        to: tab.to,
        ...(tab.usesProjectParam ? { params: { projectKey: PROJECT_KEY } } : {}),
      }).pathname
      return built !== resolveTabHref(tab, PROJECT_KEY)
    }).map((tab) => tab.key)

    expect(mismatches).toEqual([])
  })
})

describe('resolveActiveTabIndex — 실 라우트 전수', () => {
  it('비-공허: 훑는 실 라우트가 50건을 넘고 프로젝트 라우트를 포함한다', () => {
    expect(shellRouteIds.length).toBeGreaterThan(50)
    expect(shellRouteIds).toContain(`${SHELL}/projects/$projectKey`)
    expect(shellRouteIds).toContain(`${SHELL}/projects/$projectKey/board`)
  })

  it('어떤 실 라우트에서도 활성 탭이 2개 이상이 되지 않는다', () => {
    // 🛑 요약의 `exact` 가 빠지면 여기서 즉사한다 — `/projects/ATLAS` 는 모든 하위 경로의
    //    접두사라 전 화면에서 요약이 함께 걸린다.
    const multi = shellRouteIds
      .map((id) => ({ id, pathname: toPathname(id) }))
      .map(({ id, pathname }) => ({
        id,
        pathname,
        active: PROJECT_VIEW_TABS.filter((tab) => {
          const href = resolveTabHref(tab, PROJECT_KEY)
          return tab.exact ? pathname === href : pathname === href || pathname.startsWith(`${href}/`)
        }).map((tab) => tab.key),
      }))
      .filter(({ active }) => active.length > 1)
      .map(({ pathname, active }) => `${pathname} → ${active.join(', ')}`)

    expect(multi).toEqual([])
  })

  it('프로젝트 스코프 탭은 자기 목적지에서 정확히 자신이 활성이다', () => {
    expect(projectScopedTabs.length).toBeGreaterThan(0)

    for (const tab of projectScopedTabs) {
      const pathname = resolveTabHref(tab, PROJECT_KEY)
      const index = resolveActiveTabIndex(pathname, PROJECT_KEY)
      expect(PROJECT_VIEW_TABS[index]?.key, `${pathname} 에서 활성 탭이 ${tab.key} 가 아니다`).toBe(
        tab.key,
      )
    }
  })

  it('요약은 하위 경로에서 활성이 아니다 (exact 봉인)', () => {
    for (const sub of ['/projects/ATLAS/board', '/projects/ATLAS/settings/versions']) {
      const index = resolveActiveTabIndex(sub, PROJECT_KEY)
      expect(PROJECT_VIEW_TABS[index]?.key).not.toBe('summary')
    }
  })

  it('세그먼트 경계를 지킨다 — 키 접두가 같은 다른 프로젝트를 활성으로 만들지 않는다', () => {
    // `startsWith(href)` 만 쓰면 `/projects/ATLASX` 가 `/projects/ATLAS` 로 잡힌다.
    expect(resolveActiveTabIndex('/projects/ATLASX', PROJECT_KEY)).toBe(-1)
    expect(resolveActiveTabIndex('/projects/ATLASX/board', PROJECT_KEY)).toBe(-1)
  })

  it('탭이 없는 프로젝트 화면에서는 -1 이다 (리포트·다른 설정 화면)', () => {
    expect(resolveActiveTabIndex('/projects/ATLAS/reports/velocity', PROJECT_KEY)).toBe(-1)
    expect(resolveActiveTabIndex('/projects/ATLAS/settings/members', PROJECT_KEY)).toBe(-1)
  })

  it('전역 링크 3종(편차 X9)은 어떤 실 라우트에서도 활성이 되지 않는다', () => {
    // 탭바는 경로에 projectKey 가 있을 때만 마운트되므로 `/calendar` 등에서는 존재하지 않는다.
    // 「활성이 될 수 있다」고 적으면 그 화면에 탭바가 있다는 거짓 인상을 남긴다.
    const globalTabs: readonly ProjectViewTab[] = PROJECT_VIEW_TABS.filter(
      (tab) => !tab.usesProjectParam,
    )
    expect(globalTabs).toHaveLength(3)

    const reachable = shellRouteIds
      .map(toPathname)
      .filter((pathname) => pathname.startsWith('/projects/'))
      .flatMap((pathname) =>
        globalTabs
          .filter((tab) => {
            const href = resolveTabHref(tab, PROJECT_KEY)
            return pathname === href || pathname.startsWith(`${href}/`)
          })
          .map((tab) => `${pathname} → ${tab.key}`),
      )

    expect(reachable).toEqual([])
  })
})

describe('resolveBoardScopeSearch — 편차 X7 승계', () => {
  const SCRUM = { boardId: 'b-scrum', boardType: 'SCRUM' }
  const KANBAN = { boardId: 'b-kanban', boardType: 'KANBAN' }

  it('스크럼 보드를 보고 있으면 `?board=` 를 싣는다', () => {
    expect(resolveBoardScopeSearch([SCRUM, KANBAN], 'b-scrum')).toEqual({ board: 'b-scrum' })
  })

  it('칸반 보드면 싣지 않는다 — 스코프된 백로그의 스프린트는 어디에도 안 나타난다', () => {
    expect(resolveBoardScopeSearch([SCRUM, KANBAN], 'b-kanban')).toBeUndefined()
  })

  it('보드 목록이 아직 로딩 중이면 싣지 않는다 (종류를 모르는 채 실으면 폴백을 부른다)', () => {
    expect(resolveBoardScopeSearch(undefined, 'b-scrum')).toBeUndefined()
  })

  it('보고 있는 보드가 미확정이면 싣지 않는다', () => {
    expect(resolveBoardScopeSearch([SCRUM], undefined)).toBeUndefined()
  })

  it('목록에 없는 보드 id 면 싣지 않는다', () => {
    expect(resolveBoardScopeSearch([SCRUM], 'b-unknown')).toBeUndefined()
  })
})
