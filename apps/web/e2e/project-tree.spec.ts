// FR-UX-06 PR12 D6 E2E — 사이드바 프로젝트 트리(ProjectTree) 목록/네비게이션/펼침/자동활성 시나리오
//
// 시나리오 개요.
//   S1. 목록 노출        — 사이드바 nav[aria-label="프로젝트"](exact) 존재 + fixture 3건 name 오름차순 노출
//   S2. 프로젝트명 클릭  — Atlas 프로젝트 링크 클릭 → /projects/ATLAS 요약 이동 (Jira 패리티 J4)
//   S3. 펼침 하위 전수     — 디스클로저 클릭 → 보드 목록 + 직접링크 2(백로그·타임라인)「만」, 리포트·설정 토글 0개
//   S4. 활성 프로젝트 자동 펼침 — /projects/ATLAS/board 직접 진입 시 Atlas만 자동 펼침 + aria-current="page"
//   S5. 프로젝트 컨텍스트 밖 — /dashboards 진입 시 프로젝트 목록은 보이되 전부 접힘
//
// 설계 결정.
//   - fixture는 src/mocks/project-list-handlers.ts의 projectListFixtures를 직접 import한다
//     (동일 원천, drift 방지). 이 파일은 import.meta.env를 참조하지 않아 Playwright Node
//     런타임에서 안전하다(board-fixtures.ts/dashboard-fixtures.ts와 달리 인라인 동기화 불필요).
//     project-list-handlers.ts는 project-handlers.ts(GET /api/v1/projects 정본, stateful)의
//     활성 시드(ATLAS·MIDDLE·ZETA)를 재노출하는 shim이다 — 정본 시드에는 아카이브 NOVA도 있지만
//     기본(archived 생략) 응답은 활성만 반환하므로 이 fixture 3건과 일치한다.
//   - 직접링크 실경로 계약(DIRECT)은 ProjectTree.tsx 내부 상수가 export되지 않으므로
//     ProjectTree.test.tsx(단위 테스트)와 동일하게 인라인 하드코딩한다.
//   - 🛑 **「죽은 링크 0」 계약의 소유자가 바뀌었다.** 종전 S3 는 리포트4+설정11 서브링크의
//     href 를 전수로 훑어 그 계약을 여기서 졌는데, 두 중첩그룹이 사라지면서(Jira JR-1·JR-3·
//     JS-1·JS-2) 훑을 대상 자체가 없어졌다. 계약은 폐기된 것이 아니라
//     `scripts/workflow/project-nav-reachability.test.ts` 로 **승계**됐다 — 그 판별식이 이 PR
//     직전 `ProjectTree.tsx` 의 16경로를 고정 픽스처로 들고 「전부 어딘가에서 닿는가」를
//     차집합으로 재고, 비-공허 짝(뮤테이션 red)까지 같은 파일에서 단언한다.
//     기록이 없으면 다음 사람이 「보증이 사라졌다」고 읽는다. 사라진 게 아니라 옮겼다.
//   - '프로젝트'는 '프로젝트 뷰 전환'(ProjectNavTabs)의 substring이므로 getByRole 조회는
//     항상 { exact: true }를 명시한다(playwright-getbyrole-exact-strict-mode).
//   - S1의 링크 개수 검증은 nav 최상단 "모든 프로젝트" 링크(G2, FR-PJ PR-5 Task 7)를 포함하지
//     않도록 `<ul>` 프로젝트 목록으로 스코핑한다(ProjectTree.test.tsx 동일 관례) — "모든 프로젝트"는
//     nav 직계 자식이고 `<ul>` 밖이므로 이 스코핑으로 자연히 제외된다.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto/click으로만 페이지 전환.
//   - serviceWorkers:'block' 금지(e2e-msw-serviceworker-block) — 기본 설정 그대로 사용.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { projectListFixtures } from '../src/mocks/project-list-handlers'

/** MSW 핸들러(project-list-handlers.ts)와 동일하게 name 오름차순 정렬 재현 */
const sortedProjects = [...projectListFixtures].sort((a, b) => a.name.localeCompare(b.name))

/** 활성 시나리오(S2~S4) 전용 프로젝트 — fixture 중 ATLAS */
const ATLAS_PROJECT_NAME = 'Atlas 프로젝트'

/**
 * 직접 링크 2종 — router.ts 실측 실 라우트 계약(ATLAS 기준).
 *
 * **「보드」가 여기 없다** — 캠페인 PR ⑩(J2)이 그 한 줄을 보드 **목록**(보드마다 한 줄,
 * `?board=<id>`)으로 갈랐다. 목록 자체의 계약은 유닛(`ProjectTree.test.tsx`)이 덮는다.
 */
const DIRECT_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['백로그', '/projects/ATLAS/backlog'],
  ['타임라인', '/projects/ATLAS/timeline'],
]

test.describe('FR-UX-06 PR12 사이드바 프로젝트 트리', () => {
  test('S1 Given alice 로그인 When /dashboards 진입 Then 프로젝트 nav + fixture 3건 name 오름차순 노출', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    await expect(nav).toBeVisible()

    // 로딩 스켈레톤 → 실 목록 전환을 기다린 뒤(개수 고정) 순서를 검증한다.
    // <ul> 프로젝트 목록으로 스코핑 — nav 최상단 "모든 프로젝트" 링크(G2)는 <ul> 밖이라 미포함.
    const projectList = nav.getByRole('list')
    const projectLinks = projectList.getByRole('link')
    await expect(projectLinks).toHaveCount(sortedProjects.length)
    for (const [index, project] of sortedProjects.entries()) {
      await expect(projectLinks.nth(index)).toHaveText(project.name)
    }
  })

  test('S2 Given alice 로그인 When Atlas 프로젝트 링크 클릭 Then /projects/ATLAS 요약 이동', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    await nav.getByRole('link', { name: ATLAS_PROJECT_NAME, exact: true }).click()

    // 목적지만 보면 「URL 은 맞는데 화면이 안 떴다」를 놓친다 — 요약 화면의 실 콘텐츠로 확정한다.
    await page.waitForURL('**/projects/ATLAS')
    expect(new URL(page.url()).pathname).toBe('/projects/ATLAS')
    await expect(page.getByText('최근 7일 활동과 현재 작업 분포입니다.', { exact: true })).toBeVisible()
  })

  // ★ 이 S3 는 종전 「직접링크3+리포트4+설정11 전 서브링크 href」의 **재작성**이다. 머지된
  //   초록을 끈 것(skip/only)이 아니라, 그 테스트가 훑던 대상(중첩그룹 2개)이 화면에서
  //   사라져 같은 자리를 새 사실로 다시 쓴 것이다. 부분 수정이 성립하지 않는 이유가 여기
  //   있다 — 테스트의 **존재 이유**가 삭제 대상이었다.
  //   🛑 「죽은 링크 0」 계약은 파일 머리에 적은 대로
  //   `scripts/workflow/project-nav-reachability.test.ts` 가 승계했다.
  //   ⚠️ 종전 제목의 「직접링크3」은 실제 `DIRECT_LINK_CONTRACT` **2** 와 이미 어긋나 있었다
  //   (선재 drift · 「보드」가 목록으로 갈릴 때 제목만 안 고쳤다). 재작성하며 바로잡는다.
  test('S3 Given alice 로그인 When Atlas 디스클로저 펼침 Then 하위는 보드 목록 + 백로그·타임라인 2「만」이고 리포트·설정 토글은 0개', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    const atlasToggle = nav.getByRole('button', { name: `${ATLAS_PROJECT_NAME} 하위 메뉴`, exact: true })
    await atlasToggle.click()
    await expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')

    // Then ①. 남는 직접 링크 2종이 실 라우트 href 로 노출된다
    for (const [label, href] of DIRECT_LINK_CONTRACT) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveAttribute('href', href)
    }

    // Then ②. 리포트·설정 디스클로저가 **0개**다 (A-1)
    await expect(nav.getByRole('button', { name: '리포트', exact: true })).toHaveCount(0)
    await expect(nav.getByRole('button', { name: '프로젝트 설정', exact: true })).toHaveCount(0)

    // Then ③. 「만」을 전수로 잰다 — 부정 단언 2개만으로는 **다른 이름의** 그룹이 새로
    //   끼어드는 것을 못 본다. 하위 목록의 모든 href 가 「보드 링크」이거나 직접 링크 2 중
    //   하나여야 한다. 보드 이름은 시드에 달려 있으므로 이름이 아니라 href 형태로 잰다.
    //   스코프는 `nav ul ul a` — 트리 구조가 `nav > ul(그룹) > li(행) > ul(하위) > li > a` 라
    //   중첩 `ul` 안의 앵커가 곧 하위 목록이다. S3 는 ATLAS 하나만 펼치므로 이 조회에 다른
    //   프로젝트의 하위가 섞이지 않는다. 종전 리포트·설정 그룹은 한 단 더 깊은 `ul` 이었고
    //   후손 셀렉터라 **되살아나도 이 조회에 걸린다**(그래야 판별식이 된다).
    const subHrefs = await nav
      .locator('ul ul a')
      .evaluateAll((anchors) => anchors.map((anchor) => anchor.getAttribute('href') ?? ''))
    const directHrefs = DIRECT_LINK_CONTRACT.map(([, href]) => href)
    for (const href of subHrefs) {
      const isBoardLink = href.startsWith('/projects/ATLAS/board?board=')
      expect(isBoardLink || directHrefs.includes(href)).toBe(true)
    }
    expect(subHrefs).toEqual(expect.arrayContaining(directHrefs))
  })

  test('S4 Given alice 로그인 When /projects/ATLAS/board 직접 진입 Then Atlas만 자동 펼침 + aria-current="page", 나머지는 접힘', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/projects/ATLAS/board')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    const atlasToggle = nav.getByRole('button', { name: `${ATLAS_PROJECT_NAME} 하위 메뉴`, exact: true })
    await expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')

    const atlasLink = nav.getByRole('link', { name: ATLAS_PROJECT_NAME, exact: true })
    await expect(atlasLink).toHaveAttribute('aria-current', 'page')

    const otherProjects = sortedProjects.filter((project) => project.name !== ATLAS_PROJECT_NAME)
    for (const project of otherProjects) {
      const toggle = nav.getByRole('button', { name: `${project.name} 하위 메뉴`, exact: true })
      await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    }
  })

  test('S5 Given alice 로그인 When /dashboards(프로젝트 컨텍스트 밖) 진입 Then 프로젝트 목록은 보이되 전부 접힘', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    for (const project of sortedProjects) {
      const toggle = nav.getByRole('button', { name: `${project.name} 하위 메뉴`, exact: true })
      await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    }
  })
})
