// 프로젝트 뷰 탭바 E2E — 전 화면 공유 · 폭 실측 · 오버플로 · 활성 탭 핀 고정 (Jira 패리티 J5)
//
// 시나리오 개요.
//   S1. 전 화면 공유    — 요약·보드·백로그·설정 4화면에서 같은 탭바가 뜬다(옛날엔 2화면뿐이었다)
//   S2. 폭 실측         — 기본 뷰포트에서 가시 + 접힘 = 정본 10탭. 「더 보기」 유무가 모순되지 않는다
//   S3. 오버플로 도달성 — 창을 좁혀 접힌 탭을 「더 보기」로 눌러 이동한다
//   S4. 활성 탭 핀 고정 — 좁혀도 지금 보고 있는 화면의 탭은 밖에 남는다
//   S5. 프로젝트 밖     — /dashboards 에는 탭바가 없다(마운트 조건)
//
// 설계 결정.
//   - 폭 숫자를 스펙에 적지 않는다. 사이드바 폭·폰트·라벨이 바뀌면 그 숫자가 곧 거짓이 된다.
//     대신 **불변식**(가시 + 접힘 = 정본 탭 수 · 트리거는 접힘이 있을 때만)을 단언한다.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto/click 으로만 전환.
//   - 조회는 항상 nav 로 스코프하고 `exact: true` 를 쓴다 — 사이드바에 같은 이름 링크가 있다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { openTabIfOverflowed, projectViewNav } from './fixtures/project-view-tabs'

const PROJECT_KEY = 'ATLAS'

/**
 * 정본 10탭 — `src/components/project/project-view-tabs.ts` 와 같은 순서.
 *
 * 🛑 `리포트` 는 **맨 끝**이다(★리뷰 E-8). 순서가 곧 「무엇이 먼저 접히는가」라 위치를 바꾸면
 *    아래 S3·S4 가 고르는 탭의 의미가 달라진다.
 */
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
  '리포트',
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

  test('S2 기본 뷰포트 실측 — 가시 + 접힘 = 정본 10탭이고 트리거 유무가 모순되지 않는다', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/board`)

    const nav = projectViewNav(page)
    await expect(nav).toBeVisible()

    const visible = await nav.getByRole('link').count()
    const more = nav.getByRole('button', { name: '더 보기', exact: true })
    const hasMore = (await more.count()) > 0

    // 불변식 ① — 접힌 게 없으면 10개가 다 보이고, 있으면 그보다 적다.
    if (hasMore) {
      expect(visible).toBeLessThan(TAB_LABELS.length)
      expect(visible).toBeGreaterThanOrEqual(1) // 최소 1개 규칙
    } else {
      expect(visible).toBe(TAB_LABELS.length)
    }

    // 불변식 ② — 접힌 것을 펼치면 정확히 10개가 된다. 하나라도 새면 여기서 잡힌다.
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

    // 마지막 탭(리포트)은 이 폭에서 확실히 접힌다 — 헬퍼가 열어서 눌러야 한다.
    // 🛑 `exact: true` 는 헬퍼가 이미 건다. `리포트` 는 리포트 화면의 `리포트 전환` nav 이름과
    //    role 이 달라 탭 스코프 안에서는 하나만 잡힌다(`project-view-labels` 조회 규약 ④).
    const reports = await openTabIfOverflowed(page, '리포트')
    await reports.click()

    // 착지는 개별 차트가 아니라 4종 카드 목록이다(JR-2) — `/reports` 로 끝나야 한다.
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/reports(\\?.*)?$`))
  })

  test('S3-b 좁혔다 되돌리면 접힘이 그대로 복구된다 (히스테리시스 회귀 가드)', async ({ page }) => {
    // 🛑 이 PR 의 리뷰가 잡은 결함의 재현 스텝 그대로다.
    //
    //    측정 대상을 `<ul>`(트리거의 형제, `flex-1`)에 걸었더니 트리거가 렌더되는 순간 `<ul>` 이
    //    좁아지는데 예산에서 트리거 폭을 **또** 뺐다 — 같은 폭을 두 번 뺀 것이다. 그러면
    //    「트리거가 있는가」가 폭을 정하고 그 폭이 다시 트리거 유무를 정하는 되먹임 고리가 생겨,
    //    전량이 보이던 폭에서 한 단계 좁혔다 **되돌려도 6탭에 고정**됐다.
    //    창을 줄였다 되돌리는 평범한 조작으로 전 화면 탭바가 틀어진다.
    //
    //    지금은 탭과 트리거를 함께 담는 래퍼를 재므로 폭이 트리거 유무와 무관하다.
    //
    // 폭 3개는 **실측으로 고른 값**이다(2026-09-08 · Chromium · 사이드바 펼침 · 정본 10탭).
    // 1200 → 전량 · 900 → 접힘 · 1200 되돌림 → 다시 전량.
    // ★2026-09-11 에 960 → 1200 으로 올렸다. 한글 폰트를 Pretendard 로 번들하면서 라벨이
    //   넓어져 960 에서 **8개만** 남았다(실측 `expected 10, received 8`). 바로 아래 주석이
    //   예고한 그대로다 — 「라벨·폰트가 바뀌어 더는 안 접히면 먼저 red 가 되어 폭을 다시
    //   고르라고 알린다」. 조용히 통과하지 않았고, 그래서 다시 골랐다.
    // ★탭이 9종이던 2026-09-04 에는 880/860/880 이었다. 리포트 탭이 붙자 880 에서 7개만 남아
    //   **첫 단언이 먼저 red 가 됐고**(실측 `expected 10, received 7`) 그래서 폭을 다시 골랐다.
    //   임계는 900↔940 사이다(900 → 8+트리거 · 940 → 10). 960 은 그 위로 여유를 둔 값이다.
    // 🛑 데스크톱 구간을 벗어나지 않는 값이어야 한다 — `max-md`(768px) 아래로 내려가면 사이드바가
    //    오프캔버스로 빠져 본문이 **넓어지고** 오히려 접힘이 풀린다(실측: 700px 에서 전량 가시).
    //    그러면 「좁혔다」가 성립하지 않아 왕복 자체가 공허해진다.
    // 라벨·폰트가 바뀌어 860 에서 더는 안 접히면 아래 「접혔다」 단언이 **먼저 red** 가 되어
    // 폭을 다시 고르라고 알린다 — 조용히 통과하지 않는다.
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/board`)

    const nav = projectViewNav(page)
    await expect(nav).toBeVisible()

    await page.setViewportSize({ width: 1200, height: 720 })
    await expect(nav.getByRole('link')).toHaveCount(TAB_LABELS.length)

    // 좁혀서 접히게 만든다 — 접혔다는 것 자체를 먼저 확인해야 왕복이 의미를 갖는다.
    await page.setViewportSize({ width: 900, height: 720 })
    await expect(nav.getByRole('button', { name: '더 보기', exact: true })).toBeVisible()
    await expect(nav.getByRole('link')).not.toHaveCount(TAB_LABELS.length)

    // 되돌린다 — 원래 폭에서 보이던 것이 그대로 돌아와야 한다. **결함 시점에는 6탭에 고정됐다.**
    await page.setViewportSize({ width: 1200, height: 720 })
    await expect(nav.getByRole('link')).toHaveCount(TAB_LABELS.length)
    await expect(nav.getByRole('button', { name: '더 보기', exact: true })).toHaveCount(0)
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
