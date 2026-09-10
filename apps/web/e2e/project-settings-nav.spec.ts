// 프로젝트 설정 서브앱·리포트 탭 E2E — 탭바 부재/복귀 + 리포트 착지 (Jira 패리티 JS-1·JS-2·JR-1·JR-2)
//
// 시나리오 개요.
//   S1. 진입   — 사이드바 스페이스 `⋯` → 「프로젝트 설정」 → 탭바 **부재** + 설정 사이드바 존재
//   S2. 체류   — 설정 사이드바의 다른 항목으로 옮겨도 탭바가 **계속** 없다
//   S3. 복귀   — 설정 사이드바의 복귀 링크로 나오면 탭바·프로젝트 트리가 돌아온다
//   S4. 리포트 — 탭바 「리포트」 → 착지 4카드 → 하나 눌러 이동 → 탭바 **유지**
//
// 설계 결정.
//   - 🛑 **조회는 nav `aria-label` 스코프.** 이름 3종(`프로젝트`·`프로젝트 뷰 전환`·`설정 메뉴`)은
//     서로 substring 이 아니도록 고른 값이라(`ProjectSettingsNav.SETTINGS_NAV_LABEL` KDoc)
//     스코프만 지키면 strict mode 로 죽지 않는다.
//   - 🛑 **`보드`·`백로그`·`리포트` 는 `exact: true` 필수.** Playwright 의 `getByRole` 은 기본이
//     부분 일치라 `보드`가 `대시보드`를, `리포트`가 `리포트 전환`(리포트 서브내비 이름)을 함께
//     잡는다(`i18n/project-view-labels.ts` 조회 규약 ②③④). 탭 조회는 그 규약을 이미 지키는
//     `fixtures/project-view-tabs.ts` 헬퍼에 맡긴다.
//   - 🛑 **설정 부제(`프로젝트 설정`)는 `main` 스코프로 잡는다.** 문서 전역
//     `getByText('프로젝트 설정', { exact: true })` 는 SPA 전환 «중» 순간 2건이 된다 —
//     스페이스 `⋯` 드롭다운의 같은 문구가 전환 프레임에 남고 그 팝오버는 body 로 포털된다.
//     `main` 은 그 포털 밖이라 이 조회가 전환 타이밍에 흔들리지 않는다.
//   - 🛑 **시나리오 중간 전환은 전부 SPA 클릭이다.** `page.goto` 는 각 테스트의 최초 진입에만
//     쓴다 — hard navigation 은 MSW Service Worker 를 재초기화해 시드된 store 를 리셋한다.
//   - 리포트 4종 라벨·경로는 정본(`components/project/project-report-links.ts`)을 **직접 읽는다.**
//     베껴 두면 착지 화면과 이 spec 이 서로를 검사하지 않는 두 번째 목록이 된다.
import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { clickProjectViewTab, projectViewNav } from './fixtures/project-view-tabs'
import { PROJECT_REPORT_LINKS } from '../src/components/project/project-report-links'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 화면 문구 미러 (🔒 는 정본과 글자가 같아야 하는 계약 문자열)
// ─────────────────────────────────────────────────────────────────────────────

/** 대상 프로젝트 — `src/mocks/project-list-handlers.ts` 시드 */
const PROJECT_KEY = 'ATLAS'

/** 사이드바에 보이는 프로젝트 **이름**(키가 아니다) */
const PROJECT_NAME = 'Atlas 프로젝트'

/** 🔒 `layout/ProjectTree.spaceActionsTriggerLabel` 과 같은 값이어야 한다 */
const SPACE_ACTIONS_TRIGGER = `스페이스 관리, ${PROJECT_NAME}`

/** 🔒 `layout/ProjectTree.SETTINGS_GROUP_LABEL` — 트리에서 설정으로 가는 **유일한** 길 (JS-1) */
const SETTINGS_MENU_ITEM = '프로젝트 설정'

/** 🔒 `i18n/nav-labels.projectNav` — 사이드바 프로젝트 트리 nav 이름 */
const TREE_NAV_NAME = '프로젝트'

/** 🔒 `project/ProjectSettingsNav.SETTINGS_NAV_LABEL` — 설정 서브앱 사이드바 이름 */
const SETTINGS_NAV_NAME = '설정 메뉴'

/** 🔒 `project/ProjectViewHeader.PROJECT_SETTINGS_SUBTITLE` — 셸 헤더 부제 (★리뷰 D-3) */
const SETTINGS_SUBTITLE = '프로젝트 설정'

/** 🔒 `i18n/project-view-labels.reports` — 정본 10탭의 마지막 탭 */
const REPORTS_TAB_LABEL = '리포트'

/** 🔒 `PROJECT_SETTINGS_NAV` 의 `settings/members` 라벨 — 설정 안에서 옮겨 갈 다른 항목 */
const MEMBERS_NAV_LABEL = '멤버'

/** 멤버 설정 화면 본문 헤딩 — 이동이 «실제로» 일어났음을 본문에서 확인한다 */
const MEMBERS_PAGE_HEADING = '멤버 설정'

/**
 * 착지에서 눌러 볼 카드 — 🔒 `PROJECT_REPORT_LINKS` 의 벨로시티 라벨과 같은 값이어야 한다.
 *
 * 넷 중 어느 것이든 계약(「카드를 누르면 그 리포트로 가고 탭바가 남는다」)은 같다. 벨로시티를
 * 고른 이유는 MSW 가 `ATLAS` 에 완료 스프린트를 시드해 두어 빈 상태 분기로 새지 않기 때문이다
 * (`mocks/velocity-handlers.ts` DEFAULT_VELOCITY).
 */
const TARGET_REPORT_LABEL = '벨로시티'

/** 위 카드의 목적지 — 정본 `to` 를 `$projectKey` 로 치환한 값 */
const TARGET_REPORT_PATH = `/projects/${PROJECT_KEY}/reports/velocity`

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 두 사이드바를 이름으로 스코프한다
// ─────────────────────────────────────────────────────────────────────────────

/** 사이드바 프로젝트 트리 nav */
function projectTreeNav(page: Page): Locator {
  return page.getByRole('navigation', { name: TREE_NAV_NAME, exact: true })
}

/** 설정 서브앱 사이드바 nav */
function projectSettingsNav(page: Page): Locator {
  return page.getByRole('navigation', { name: SETTINGS_NAV_NAME, exact: true })
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('Jira 패리티 JS-1·JS-2·JR-2 — 프로젝트 설정 서브앱 / 리포트 탭', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 진입
  //
  // Given  alice 로그인 후 /projects/ATLAS/board — 탭바와 프로젝트 트리가 떠 있다
  // When   사이드바 스페이스 `⋯` → 「프로젝트 설정」 (SPA 내부 이동)
  // Then   설정 사이드바가 트리를 «대체»하고, 탭바는 0개이며, 헤더에 설정 부제가 붙는다
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 스페이스 `⋯` → 프로젝트 설정 → 탭바 부재 + 설정 사이드바 존재', async ({ page }) => {
    // Given. 보드 화면 — 탭바와 트리가 둘 다 있는 상태에서 출발해야 「사라졌다」가 성립한다.
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/board`)
    await expect(projectViewNav(page)).toBeVisible()

    const tree = projectTreeNav(page)
    await expect(tree).toBeVisible()

    // When. 트리의 유일한 설정 진입로 — 스페이스 `⋯` 의 「프로젝트 설정」 (JS-1).
    //   드롭다운 콘텐츠는 body 로 포털되므로 항목 조회는 트리 스코프 밖에서 한다.
    await tree.getByRole('button', { name: SPACE_ACTIONS_TRIGGER, exact: true }).click()
    await page.getByRole('menuitem', { name: SETTINGS_MENU_ITEM, exact: true }).click()

    // Then ①. 설정 서브앱의 기본 착지로 이동한다.
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/settings/details(\\?.*)?$`))

    // Then ②. 설정 사이드바가 서고 프로젝트 트리는 «대체»된다 (JS-2 · 나란히 서지 않는다).
    await expect(projectSettingsNav(page)).toBeVisible()
    await expect(tree).toHaveCount(0)

    // Then ③. 탭바가 없다 (편차 X-N1) — 이 PR 의 핵심 계약이다.
    await expect(projectViewNav(page)).toHaveCount(0)

    // Then ④. 대신 헤더가 「어느 서브앱에 있는가」를 한 줄로 말한다 (★리뷰 D-3).
    //   `main` 스코프 — 위 드롭다운의 같은 문구는 body 포털이라 여기 걸리지 않는다.
    await expect(page.getByRole('main').getByText(SETTINGS_SUBTITLE, { exact: true })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 체류
  //
  // Given  alice 로그인 후 /projects/ATLAS/settings/details — 설정 서브앱 안이다
  // When   설정 사이드바의 다른 항목(멤버)을 눌러 SPA 내부 이동
  // Then   본문이 바뀌어도 탭바는 계속 0개이고 설정 사이드바는 그대로 선다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 설정 사이드바의 다른 항목으로 옮겨도 탭바가 계속 없다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/settings/details`)

    const settingsNav = projectSettingsNav(page)
    await expect(settingsNav).toBeVisible()
    await expect(projectViewNav(page)).toHaveCount(0)

    // When. 설정 서브앱 «안»에서의 이동 — 여기서 탭바가 되살아나면 서브앱 경계가 깨진 것이다.
    await settingsNav.getByRole('link', { name: MEMBERS_NAV_LABEL, exact: true }).click()

    // Then ①. 이동이 실제로 일어났다 — URL 과 본문 헤딩 둘 다로 확인한다.
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/settings/members(\\?.*)?$`))
    await expect(page.getByRole('heading', { name: MEMBERS_PAGE_HEADING, level: 2 })).toBeVisible()

    // Then ②. 탭바는 계속 없고 설정 사이드바는 계속 있다.
    await expect(projectViewNav(page)).toHaveCount(0)
    await expect(settingsNav).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 — 복귀
  //
  // Given  alice 로그인 후 /projects/ATLAS/settings/details — 설정 서브앱 안이다
  // When   설정 사이드바 맨 위의 복귀 링크(← 프로젝트명)를 누른다
  // Then   프로젝트 홈으로 나오고 탭바·프로젝트 트리가 돌아오며 설정 사이드바는 사라진다
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 복귀 링크로 나오면 탭바와 프로젝트 트리가 돌아온다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/settings/details`)

    const settingsNav = projectSettingsNav(page)
    await expect(settingsNav).toBeVisible()

    // When. 복귀 링크의 접근가능 이름은 프로젝트 **이름**이다(화살표 아이콘은 aria-hidden).
    //   응답 전에는 키로 폴백하므로 이름으로 조회하면 로드 완료를 기다리는 효과도 함께 얻는다.
    await settingsNav.getByRole('link', { name: PROJECT_NAME, exact: true }).click()

    // Then ①. 프로젝트 기본 착지로 나온다.
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}(\\?.*)?$`))

    // Then ②. 탭바와 트리가 «둘 다» 돌아온다 — 한쪽만 보면 절반의 복귀를 통과시킨다.
    await expect(projectViewNav(page)).toBeVisible()
    await expect(projectTreeNav(page)).toBeVisible()

    // Then ③. 설정 사이드바는 사라진다.
    await expect(settingsNav).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 — 리포트
  //
  // Given  alice 로그인 후 /projects/ATLAS/board — 탭바가 떠 있다
  // When   탭바 「리포트」 → 착지 화면에서 카드 하나를 누른다 (둘 다 SPA 내부 이동)
  // Then   착지에 정본 4종이 전부 있고, 카드로 이동한 뒤에도 탭바가 그대로 남는다
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 탭바 「리포트」 → 착지 4카드 → 카드 클릭 → 이동 후에도 탭바 유지', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/board`)
    await expect(projectViewNav(page)).toBeVisible()

    // When ①. 리포트는 «이제 탭이다» (JR-1·JR-3). 접혔으면 헬퍼가 「더 보기」를 열어 준다.
    await clickProjectViewTab(page, REPORTS_TAB_LABEL)

    // Then ①. 착지는 개별 차트가 아니라 4종 카드 목록이다 (JR-2).
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/reports(\\?.*)?$`))

    // 🛑 카드 링크의 접근가능 이름은 **`라벨 + 질문 한 줄`** 이다 — `<Link>` 가 `CardTitle` 과
    //    `CardDescription` 을 함께 감싸고 있어서다(실측 `벨로시티 스프린트마다 얼마나 끝냈나`).
    //    그래서 라벨만으로 `exact: true` 를 걸면 **element not found** 다. 둘을 이어 붙여
    //    exact 로 재면 「질문 줄이 실제로 함께 나온다」(★리뷰 D-4)까지 같은 단언이 지킨다.
    const main = page.getByRole('main')
    for (const link of PROJECT_REPORT_LINKS) {
      await expect(
        main.getByRole('link', { name: `${link.label} ${link.question}`, exact: true }),
      ).toHaveAttribute('href', link.to.replace('$projectKey', PROJECT_KEY))
    }

    // When ②. 카드 하나를 눌러 개별 리포트로 들어간다.
    //   여기서는 라벨 부분일치로 잡는다(위와 같은 이유로 라벨 단독 exact 는 성립하지 않는다).
    //   `벨로시티` 를 이름에 품는 요소는 이 화면의 카드 하나뿐이다.
    await main.getByRole('link', { name: TARGET_REPORT_LABEL }).click()

    // Then ②. 이동했고 **탭바가 그대로 남는다** — 리포트는 설정과 달리 서브앱이 아니다.
    await expect(page).toHaveURL(new RegExp(`${TARGET_REPORT_PATH}(\\?.*)?$`))
    await expect(projectViewNav(page)).toBeVisible()
  })
})
