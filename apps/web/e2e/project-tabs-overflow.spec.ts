// 프로젝트 뷰 탭바 E2E — 전 화면 공유 · 폭 실측 · 오버플로 · 활성 탭 핀 고정 (Jira 패리티 J5)
//
// 시나리오 개요.
//   S1. 전 화면 공유    — 요약·보드·백로그·설정 4화면에서 같은 탭바가 뜬다(옛날엔 2화면뿐이었다)
//   S2. 폭 실측         — 기본 뷰포트에서 가시 + 접힘 = 정본 9탭. 「더 보기」 유무가 모순되지 않는다
//   S3. 오버플로 도달성 — 창을 좁혀 접힌 탭을 「더 보기」로 눌러 이동한다
//   S4. 활성 탭 핀 고정 — 좁혀도 지금 보고 있는 화면의 탭은 밖에 남는다
//   S5. 프로젝트 밖     — /dashboards 에는 탭바가 없다(마운트 조건)
//
// 설계 결정.
//   - 폭 숫자를 스펙에 적지 않는다. 사이드바 폭·폰트·라벨이 바뀌면 그 숫자가 곧 거짓이 된다.
//     대신 **불변식**(가시 + 접힘 = 9 · 트리거는 접힘이 있을 때만)을 단언한다.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto/click 으로만 전환.
//   - 조회는 항상 nav 로 스코프하고 `exact: true` 를 쓴다 — 사이드바에 같은 이름 링크가 있다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { openTabIfOverflowed, projectViewNav } from './fixtures/project-view-tabs'

const PROJECT_KEY = 'ATLAS'

/** 정본 9탭 — `src/components/project/project-view-tabs.ts` 와 같은 순서 */
const TAB_LABELS = [
  '요약',
  '타임라인',
  '보드',
  '백로그',
  '캘린더',
  '대시보드',
  '컴포넌트',
  '이슈',
  '버전',
] as const

/** 탭바가 뜨는지 볼 프로젝트 화면들 — 옛 인라인 nav 는 앞의 둘에만 있었다 */
const PROJECT_SCREENS: ReadonlyArray<readonly [name: string, path: string]> = [
  ['요약', `/projects/${PROJECT_KEY}`],
  ['보드', `/projects/${PROJECT_KEY}/board`],
  ['백로그', `/projects/${PROJECT_KEY}/backlog`],
  ['설정(컴포넌트)', `/projects/${PROJECT_KEY}/settings/components`],
]

test.describe('Jira 패리티 J5 — 프로젝트 뷰 탭바', () => {
  test('S1 프로젝트 하위 4화면 전부에서 같은 탭바가 뜬다', async ({ page }) => {
    await loginAsAlice(page)

    for (const [name, path] of PROJECT_SCREENS) {
      await page.goto(path)
      const nav = projectViewNav(page)
      await expect(nav, `${name} 화면에 탭바가 없다`).toBeVisible()

      // nav 는 문서에 하나뿐이어야 한다 — 페이지가 인라인으로 다시 심으면 두 개가 된다.
      await expect(nav).toHaveCount(1)
    }
  })

  test('S2 기본 뷰포트 실측 — 가시 + 접힘 = 정본 9탭이고 트리거 유무가 모순되지 않는다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/board`)

    const nav = projectViewNav(page)
    await expect(nav).toBeVisible()

    const visible = await nav.getByRole('link').count()
    const more = nav.getByRole('button', { name: '더 보기', exact: true })
    const hasMore = (await more.count()) > 0

    // 불변식 ① — 접힌 게 없으면 9개가 다 보이고, 있으면 그보다 적다.
    if (hasMore) {
      expect(visible).toBeLessThan(TAB_LABELS.length)
      expect(visible).toBeGreaterThanOrEqual(1) // 최소 1개 규칙
    } else {
      expect(visible).toBe(TAB_LABELS.length)
    }

    // 불변식 ② — 접힌 것을 펼치면 정확히 9개가 된다. 하나라도 새면 여기서 잡힌다.
    if (hasMore) await more.click()
    await expect(nav.getByRole('link')).toHaveCount(TAB_LABELS.length)
  })

  test('S3 좁은 폭에서 접힌 탭도 「더 보기」로 눌러 이동한다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}`)

    // 확실히 접히도록 좁힌다. 숫자는 「접히게 만드는 값」이지 계약이 아니다.
    await page.setViewportSize({ width: 600, height: 720 })

    const nav = projectViewNav(page)
    await expect(nav).toBeVisible()
    await expect(nav.getByRole('button', { name: '더 보기', exact: true })).toBeVisible()

    // 마지막 탭(버전)은 이 폭에서 확실히 접힌다 — 헬퍼가 열어서 눌러야 한다.
    const versions = await openTabIfOverflowed(page, '버전')
    await versions.click()

    await expect(page).toHaveURL(
      new RegExp(`/projects/${PROJECT_KEY}/settings/versions(\\?.*)?$`),
    )
  })

  test('S4 활성 탭은 좁혀도 탭바 밖에 남는다 (핀 고정)', async ({ page }) => {
    await loginAsAlice(page)
    // 뒤쪽 탭인 「버전」 화면으로 직접 들어간다 — 좁히면 원래는 접힐 자리다.
    await page.goto(`/projects/${PROJECT_KEY}/settings/versions`)
    await page.setViewportSize({ width: 600, height: 720 })

    const nav = projectViewNav(page)
    await expect(nav).toBeVisible()

    // 🛑 「더 보기」를 열지 않은 상태에서 보여야 한다. 지금 보고 있는 화면이 탭바에서
    //    사라지는 것이 이 기능의 가장 나쁜 상태다.
    const activeTab = nav.getByRole('link', { name: '버전', exact: true })
    await expect(activeTab).toBeVisible()
    await expect(activeTab).toHaveAttribute('aria-current', 'page')
  })

  test('S5 프로젝트 밖(/dashboards)에는 탭바가 없다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    // 사이드바 프로젝트 트리는 그대로 있고 탭바만 없다 — 마운트 조건이 경로의 projectKey 다.
    await expect(page.getByRole('navigation', { name: '프로젝트', exact: true })).toBeVisible()
    await expect(projectViewNav(page)).toHaveCount(0)
  })
})
