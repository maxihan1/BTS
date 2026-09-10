// 탭 정본 판별식 — 죽은 링크 0 · 실 라우트 전수에서 활성 탭 ≤ 1 · 요약 exact (Jira 패리티 J5)
import { describe, it, expect } from 'vitest'
import { router } from '@/router'
import {
  PROJECT_VIEW_TABS,
  isTabActive,
  resolveActiveTabIndex,
  resolveBacklogTabSearch,
  resolveBoardTabSearch,
  resolveTabHref,
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

/** 활성 탭이 될 수 있는 탭 — 편차 X9 폐기(2026-09-07) 이후 10탭 전부다 */
const projectScopedTabs = PROJECT_VIEW_TABS.filter((tab) => tab.usesProjectParam)

describe('PROJECT_VIEW_TABS — 정본 구성', () => {
  it('비-공허: 탭이 10종이고 키가 중복되지 않는다', () => {
    expect(PROJECT_VIEW_TABS).toHaveLength(10)
    const keys = PROJECT_VIEW_TABS.map((tab) => tab.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('Maxi 확정 순서 그대로다 (요약·타임라인·보드·백로그·캘린더·대시보드·컴포넌트·이슈·버전·리포트)', () => {
    // 순서가 곧 계약이다 — 목업 v2 + 백로그 1건(2026-09-03 확정) + 리포트 1건(2026-09-08).
    // 🛑 리포트는 **맨 끝**이다. 앞에 끼우면 기존 9탭의 인덱스가 밀려 `resolveActiveTabIndex`
    //    를 재료로 쓰는 오버플로 핀 고정의 회귀 표면이 통째로 늘어난다 (★리뷰 E-8).
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
      'reports',
    ])
  })

  it('리포트 탭이 정본에 있고 착지가 개별 차트가 아니다 (JR-1·JR-2)', () => {
    // 위 순서 단언이 이미 키를 보지만, 「무엇이 왜 필요한지」가 코드에서 사라지지 않게 못박는다.
    const reports = PROJECT_VIEW_TABS.find((tab) => tab.key === 'reports')
    expect(reports).toBeDefined()
    // 🛑 `/reports/velocity` 로 두면 탭이 4종 중 하나를 임의로 고른 셈이 된다 — Jira 의 착지는
    //    개별 차트가 아니라 목록/인사이트 화면이다(JR-2).
    expect(reports?.to).toBe('/projects/$projectKey/reports')
    // 🛑 `exact: true` 로 바뀌면 리포트 4화면에서 탭 강조가 꺼진다(채택 A-3).
    expect(reports?.exact).toBe(false)
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

  it('10탭 전부가 `$projectKey` 를 받는다 (편차 X9 폐기 · J5-12)', () => {
    // 🛑 하나라도 전역 경로로 돌아가면 그 탭을 누르는 순간 헤더·탭바가 사라진다 —
    //    `ProjectViewChrome` 의 마운트 조건이 `params.projectKey` 이기 때문이다.
    const global = PROJECT_VIEW_TABS.filter((tab) => !tab.usesProjectParam).map((tab) => tab.key)
    expect(global).toEqual([])
  })

  it('10탭의 `to` 가 전부 `/projects/$projectKey` 로 시작한다', () => {
    // 위 단언은 플래그만 본다. 플래그를 true 로 둔 채 `to` 만 전역 경로로 적으면
    // `resolveTabHref` 가 치환할 것이 없어 그대로 전역으로 나가는데 플래그 검사는 통과한다.
    const escaping = PROJECT_VIEW_TABS.filter(
      (tab) => !tab.to.startsWith('/projects/$projectKey'),
    ).map((tab) => `${tab.key} → ${tab.to}`)
    expect(escaping).toEqual([])
  })

  it('스코프 라우트 3종이 실제로 등록돼 있다 (편차 X9 폐기의 짝 검사)', () => {
    // 탭 정의만 바꾸고 라우트를 안 만들면 「죽은 링크 0」 단언이 red 가 되지만, 그 단언 하나에
    // 기대면 무엇이 왜 필요한지가 코드에서 사라진다. 신설 3종을 이름으로 못박는다.
    for (const path of ['/projects/$projectKey/issues', '/projects/$projectKey/calendar', '/projects/$projectKey/dashboards']) {
      expect(routeIds).toContain(`${SHELL}${path}`)
    }
  })
})

describe('resolveTabHref — 순수 함수와 라우터의 두 층이 같은 href 를 만든다', () => {
  it('비-공허: 대조 대상이 10건이다', () => {
    expect(PROJECT_VIEW_TABS).toHaveLength(10)
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

describe('isTabActive — 판정 규칙 직접 단언', () => {
  /**
   * 🛑 이 블록이 없으면 접두 분기가 **무보증**이다.
   *
   * 실측(2026-09-07) — `isTabActive` 의 비-exact 분기를 완전 일치로 바꿔도 아래 「실 라우트
   * 전수」 판별식이 통과했다. 당시 정본 9탭의 목적지가 전부 말단 경로였기 때문이다.
   *
   * ★2026-09-08 부터는 **리포트 탭이 그 관측 표면**이다 — `/projects/$key/reports` 아래에
   * 실 라우트 4개(`velocity`·`cfd`·`cycle-time`·`worklog`)가 있어 분기를 완전 일치로 바꾸면
   * 아래 「리포트 하위 4화면에서 리포트 탭이 활성이다」가 red 가 된다. 그래도 이 블록은
   * 남긴다 — 규칙 자체를 직접 재는 자리는 여기 하나이고, 리포트 라우트가 없어지는 날
   * 다시 무보증이 되기 때문이다.
   */
  const boardTab = PROJECT_VIEW_TABS.find((tab) => tab.key === 'board')
  const summaryTab = PROJECT_VIEW_TABS.find((tab) => tab.key === 'summary')

  it('비-공허: 대상 탭 2종을 정본에서 찾았다', () => {
    expect(boardTab).toBeDefined()
    expect(summaryTab).toBeDefined()
    expect(boardTab?.exact).toBe(false)
    expect(summaryTab?.exact).toBe(true)
  })

  it('비-exact 탭은 하위 경로에서도 활성이다 (접두 분기)', () => {
    if (boardTab === undefined) throw new Error('board 탭이 정본에 없다')
    expect(isTabActive(boardTab, '/projects/ATLAS/board', PROJECT_KEY)).toBe(true)
    // 아직 이런 라우트는 없지만 규칙은 지켜야 한다 — 생기는 날 이 단언이 계약이 된다.
    expect(isTabActive(boardTab, '/projects/ATLAS/board/settings', PROJECT_KEY)).toBe(true)
    expect(isTabActive(boardTab, '/projects/ATLAS/board/a/b', PROJECT_KEY)).toBe(true)
  })

  it('비-exact 탭도 세그먼트 경계를 넘지 않는다', () => {
    if (boardTab === undefined) throw new Error('board 탭이 정본에 없다')
    // `startsWith(href)` 만 쓰면 여기가 true 가 된다.
    expect(isTabActive(boardTab, '/projects/ATLAS/boardroom', PROJECT_KEY)).toBe(false)
    expect(isTabActive(boardTab, '/projects/ATLAS/backlog', PROJECT_KEY)).toBe(false)
  })

  it('exact 탭은 하위 경로에서 활성이 아니다', () => {
    if (summaryTab === undefined) throw new Error('summary 탭이 정본에 없다')
    expect(isTabActive(summaryTab, '/projects/ATLAS', PROJECT_KEY)).toBe(true)
    expect(isTabActive(summaryTab, '/projects/ATLAS/board', PROJECT_KEY)).toBe(false)
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
        // 🛑 판정식을 여기 베끼지 않는다 — 베끼면 `exact` 데이터만 지키고 함수의 규칙을
        //    바꿔도 red 가 안 난다(「두 목록이 서로를 검사하지 않는다」 양식).
        active: PROJECT_VIEW_TABS.filter((tab) =>
          isTabActive(tab, pathname, PROJECT_KEY),
        ).map((tab) => tab.key),
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

  it('탭이 없는 프로젝트 화면에서는 -1 이다 (설정 서브앱 화면)', () => {
    // 설정 서브앱 경로는 어느 탭에도 안 걸린다 — 그리고 거기서는 `ProjectViewChrome` 이
    // 탭바 자체를 렌더하지 않는다(편차 X-N1 · `ProjectViewChrome.test.tsx`).
    expect(resolveActiveTabIndex('/projects/ATLAS/settings/members', PROJECT_KEY)).toBe(-1)
    expect(resolveActiveTabIndex('/projects/ATLAS/settings/automation', PROJECT_KEY)).toBe(-1)
  })

  it('리포트 하위 4화면 전수에서 리포트 탭이 활성이다 (채택 A-3 · JR-1)', () => {
    // 🛑 여기가 `exact: false` 의 계약이다. 리포트는 «탭»이므로 자기 하위 화면에서 자기를
    //    지우면 안 된다 — 지우면 4화면에서 「내가 어디 있나」가 사라진다.
    //    2026-09-08 이전에는 이 경로들이 **-1** 이었다(탭이 없었다).
    const subPaths = [
      '/projects/ATLAS/reports/velocity',
      '/projects/ATLAS/reports/cfd',
      '/projects/ATLAS/reports/cycle-time',
      '/projects/ATLAS/reports/worklog',
    ]
    expect(subPaths).toHaveLength(4) // 비-공허 짝 — 목록이 비면 아래 순회가 조용히 통과한다

    for (const pathname of subPaths) {
      const index = resolveActiveTabIndex(pathname, PROJECT_KEY)
      expect(PROJECT_VIEW_TABS[index]?.key, `${pathname} 에서 리포트 탭이 활성이 아니다`).toBe(
        'reports',
      )
    }

    // 착지 화면 자신도 물론 활성이다.
    const landing = resolveActiveTabIndex('/projects/ATLAS/reports', PROJECT_KEY)
    expect(PROJECT_VIEW_TABS[landing]?.key).toBe('reports')
  })

  it('스코프 라우트 3종에서 각자의 탭이 활성이 된다 (편차 X9 폐기 · J5-12)', () => {
    // 편차 X9 시절 이 자리에는 「전역 3탭은 어떤 라우트에서도 활성이 안 된다」가 있었다.
    // 이제 반대가 계약이다 — 캘린더·대시보드·이슈 화면에서도 탭바가 남고 자기 탭이 강조된다.
    const expectations: readonly (readonly [string, string])[] = [
      ['/projects/ATLAS/issues', 'issues'],
      ['/projects/ATLAS/calendar', 'calendar'],
      ['/projects/ATLAS/dashboards', 'dashboards'],
    ]

    for (const [pathname, key] of expectations) {
      const index = resolveActiveTabIndex(pathname, PROJECT_KEY)
      expect(PROJECT_VIEW_TABS[index]?.key).toBe(key)
    }
  })

  it('10탭 어디서도 활성 탭이 2개 이상이 되지 않는다 (전수)', () => {
    // 🛑 스코프 라우트 3종이 늘면서 접두 충돌 위험도 늘었다. 실 라우트 전수로 다시 확인한다.
    expect(shellRouteIds.length).toBeGreaterThan(50)

    const conflicts = shellRouteIds
      .map(toPathname)
      .filter((pathname) => pathname.startsWith('/projects/'))
      .map((pathname) => ({
        pathname,
        active: PROJECT_VIEW_TABS.filter((tab) => isTabActive(tab, pathname, PROJECT_KEY)).map(
          (tab) => tab.key,
        ),
      }))
      .filter((row) => row.active.length > 1)

    expect(conflicts).toEqual([])
  })
})

describe('편차 X7 승계 — 보드 탭과 백로그 탭의 규칙이 다르다', () => {
  const SCRUM = { boardId: 'b-scrum', boardType: 'SCRUM' }
  const KANBAN = { boardId: 'b-kanban', boardType: 'KANBAN' }

  describe('resolveBoardTabSearch — 보드 탭은 종류를 가리지 않는다', () => {
    it('스크럼이든 칸반이든 보고 있던 보드로 되돌아간다', () => {
      // 보드 화면은 두 종류를 다 연다. 가리면 칸반을 보다 탭을 다녀올 때 boards[0] 로 튄다.
      expect(resolveBoardTabSearch('b-scrum')).toEqual({ board: 'b-scrum' })
      expect(resolveBoardTabSearch('b-kanban')).toEqual({ board: 'b-kanban' })
    })

    it('보고 있는 보드가 미확정이면 싣지 않는다', () => {
      expect(resolveBoardTabSearch(undefined)).toBeUndefined()
    })
  })

  describe('resolveBacklogTabSearch — 백로그 탭은 스크럼일 때만 받는다', () => {
    it('스크럼 보드를 보고 있으면 `?board=` 를 싣는다', () => {
      expect(resolveBacklogTabSearch([SCRUM, KANBAN], 'b-scrum')).toEqual({ board: 'b-scrum' })
    })

    it('칸반 보드면 싣지 않는다 — 스코프된 백로그의 스프린트는 어디에도 안 나타난다', () => {
      expect(resolveBacklogTabSearch([SCRUM, KANBAN], 'b-kanban')).toBeUndefined()
    })

    it('보드 목록이 아직 로딩 중이면 싣지 않는다 (종류를 모르는 채 실으면 폴백을 부른다)', () => {
      expect(resolveBacklogTabSearch(undefined, 'b-scrum')).toBeUndefined()
    })

    it('보고 있는 보드가 미확정이면 싣지 않는다', () => {
      expect(resolveBacklogTabSearch([SCRUM], undefined)).toBeUndefined()
    })

    it('목록에 없는 보드 id 면 싣지 않는다', () => {
      expect(resolveBacklogTabSearch([SCRUM], 'b-unknown')).toBeUndefined()
    })

    it('두 함수는 칸반에서 갈린다 — 같은 판정이면 한쪽이 무의미하다', () => {
      // 비-공허 짝. 둘이 늘 같은 값을 주면 분리한 이유가 사라진다.
      expect(resolveBoardTabSearch('b-kanban')).not.toEqual(
        resolveBacklogTabSearch([SCRUM, KANBAN], 'b-kanban'),
      )
    })
  })
})
