// 셸 모드 정본 판별식 — 설정 서브앱 경로 전수 settings · 탭 경로 전수 tree · 비-공허 하한 (JS-2 · A-3)
import { describe, it, expect } from 'vitest'
import {
  PROJECT_SETTINGS_NAV,
  resolveProjectShellMode,
} from '@/components/project/project-shell-mode'

/** 판정에 쓰는 프로젝트 키 — 아무 값이어도 되지만 한 벌로 고정한다 */
const PROJECT_KEY = 'ATLAS'

/** `$projectKey` 플레이스홀더를 실제 키로 치환 — 라우터가 만드는 pathname 과 같은 모양 */
function toPathname(to: string): string {
  return to.replace('$projectKey', PROJECT_KEY)
}

/** 정본을 평탄화한 항목 전량 — 하드코딩 목록을 두지 않는다(두 번째 목록 금지) */
const allItems = PROJECT_SETTINGS_NAV.flatMap((group) => group.items)

/**
 * 설정 서브앱에 **속하지 않는** 경로 전수.
 *
 * 앞 3개가 요점이다 — `컴포넌트`·`버전`·`보드 설정`은 경로에 `/settings/` 가 있는데도 `tree` 다.
 * `pathname.includes('/settings/')` 로 판정하면 여기서 즉사한다(스펙 D-1 · 편차 X-N2).
 */
const TREE_PATHNAMES: readonly string[] = [
  `/projects/${PROJECT_KEY}/settings/components`,
  `/projects/${PROJECT_KEY}/settings/versions`,
  `/projects/${PROJECT_KEY}/board/settings`,
  `/projects/${PROJECT_KEY}`,
  `/projects/${PROJECT_KEY}/board`,
  `/projects/${PROJECT_KEY}/backlog`,
  `/projects/${PROJECT_KEY}/timeline`,
  `/projects/${PROJECT_KEY}/reports`,
  `/projects/${PROJECT_KEY}/reports/velocity`,
  `/projects/${PROJECT_KEY}/reports/cfd`,
  `/projects/${PROJECT_KEY}/reports/cycle-time`,
  `/projects/${PROJECT_KEY}/reports/worklog`,
]

describe('resolveProjectShellMode — 셸 모드 판정', () => {
  it('PROJECT_SETTINGS_NAV 전 경로에서 settings 다', () => {
    for (const item of allItems) {
      expect(resolveProjectShellMode(toPathname(item.to), PROJECT_KEY)).toBe('settings')
    }
  })

  it('컴포넌트·버전·보드설정·요약·보드·백로그·타임라인·리포트4 에서 tree 다', () => {
    for (const pathname of TREE_PATHNAMES) {
      expect(resolveProjectShellMode(pathname, PROJECT_KEY)).toBe('tree')
    }
  })

  it('접두사만 겹치는 경로를 먹지 않는다 (세그먼트 경계)', () => {
    // `startsWith(href)` 만 쓰면 `/settings/details` 가 `/settings/details-v2` 를 먹는다.
    expect(resolveProjectShellMode(`/projects/${PROJECT_KEY}/settings/detailsx`, PROJECT_KEY)).toBe(
      'tree',
    )
  })

  it('projectKey 가 undefined 면 tree 다 (★리뷰 E-4)', () => {
    // Sidebar 는 /issues·/dashboards·/calendar 에서도 렌더되고 그때 projectKey 는 undefined 다.
    expect(resolveProjectShellMode(`/projects/${PROJECT_KEY}/settings/details`, undefined)).toBe(
      'tree',
    )
    expect(resolveProjectShellMode('/issues', undefined)).toBe('tree')
    expect(resolveProjectShellMode(`/projects/${PROJECT_KEY}/settings/details`, '')).toBe('tree')
  })
})

describe('PROJECT_SETTINGS_NAV — 정본 구성', () => {
  it('그룹은 4개이고 항목 합이 10이다 (비-공허 하한)', () => {
    expect(PROJECT_SETTINGS_NAV).toHaveLength(4)
    expect(allItems).toHaveLength(10)

    const groupKeys = PROJECT_SETTINGS_NAV.map((group) => group.key)
    expect(groupKeys).toEqual(['general', 'issue', 'access', 'integration'])

    const hrefs = allItems.map((item) => item.to)
    expect(new Set(hrefs).size).toBe(hrefs.length)
  })

  it('컴포넌트·버전은 설정 메뉴에 없다 (편차 X-N2 — 정본 탭으로만 유지)', () => {
    const hrefs = allItems.map((item) => item.to)
    expect(hrefs).not.toContain('/projects/$projectKey/settings/components')
    expect(hrefs).not.toContain('/projects/$projectKey/settings/versions')
  })
})
