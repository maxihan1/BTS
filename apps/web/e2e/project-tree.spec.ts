// FR-UX-06 PR12 D6 E2E — 사이드바 프로젝트 트리(ProjectTree) 목록/네비게이션/펼침/자동활성 시나리오
//
// 시나리오 개요.
//   S1. 목록 노출        — 사이드바 nav[aria-label="프로젝트"](exact) 존재 + fixture 3건 name 오름차순 노출
//   S2. 프로젝트명 클릭  — Atlas 프로젝트 링크 클릭 → /projects/ATLAS/board 이동
//   S3. 펼침 + 죽은 링크 0 — 디스클로저 클릭 → 직접링크3 + 리포트(4)·설정(11) 그룹 펼침, 전 서브링크 href 실 라우트 확인
//   S4. 활성 프로젝트 자동 펼침 — /projects/ATLAS/board 직접 진입 시 Atlas만 자동 펼침 + aria-current="page"
//   S5. 프로젝트 컨텍스트 밖 — /dashboards 진입 시 프로젝트 목록은 보이되 전부 접힘
//
// 설계 결정.
//   - fixture는 src/mocks/project-list-handlers.ts의 projectListFixtures를 직접 import한다
//     (동일 원천, drift 방지). 이 파일은 import.meta.env를 참조하지 않아 Playwright Node
//     런타임에서 안전하다(board-fixtures.ts/dashboard-fixtures.ts와 달리 인라인 동기화 불필요).
//   - 서브링크 실경로 계약(DIRECT/REPORT/SETTINGS)은 ProjectTree.tsx 내부 상수가 export되지
//     않으므로 ProjectTree.test.tsx(단위 테스트)와 동일하게 인라인 하드코딩한다.
//   - '프로젝트'는 '프로젝트 뷰 전환'(ProjectNavTabs)의 substring이므로 getByRole 조회는
//     항상 { exact: true }를 명시한다(playwright-getbyrole-exact-strict-mode).
//   - reload 금지(store 리셋 = 가짜그린). SPA goto/click으로만 페이지 전환.
//   - serviceWorkers:'block' 금지(e2e-msw-serviceworker-block) — 기본 설정 그대로 사용.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { projectListFixtures } from '../src/mocks/project-list-handlers'

/** MSW 핸들러(project-list-handlers.ts)와 동일하게 name 오름차순 정렬 재현 */
const sortedProjects = [...projectListFixtures].sort((a, b) => a.name.localeCompare(b.name))

/** 활성 시나리오(S2~S4) 전용 프로젝트 — fixture 중 ATLAS */
const ATLAS_PROJECT_NAME = 'Atlas 프로젝트'

/** 직접 링크 3종 — router.ts 실측 실 라우트 계약(ATLAS 기준) */
const DIRECT_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['보드', '/projects/ATLAS/board'],
  ['백로그', '/projects/ATLAS/backlog'],
  ['타임라인', '/projects/ATLAS/timeline'],
]

/** 리포트 그룹 서브링크 4종 — router.ts 실측 실 라우트 계약(ATLAS 기준) */
const REPORT_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['벨로시티', '/projects/ATLAS/reports/velocity'],
  ['누적 흐름도(CFD)', '/projects/ATLAS/reports/cfd'],
  ['사이클/리드 타임', '/projects/ATLAS/reports/cycle-time'],
  ['작업 로그', '/projects/ATLAS/reports/worklog'],
]

/** 프로젝트 설정 그룹 서브링크 11종 — router.ts 실측 실 라우트 계약(ATLAS 기준) */
const SETTINGS_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['워크플로우 스킴', '/projects/ATLAS/settings/workflow-scheme'],
  ['멤버', '/projects/ATLAS/settings/members'],
  ['컴포넌트', '/projects/ATLAS/settings/components'],
  ['버전', '/projects/ATLAS/settings/versions'],
  ['커스텀 필드', '/projects/ATLAS/settings/custom-fields'],
  ['이슈 템플릿', '/projects/ATLAS/settings/issue-templates'],
  ['필드 권한', '/projects/ATLAS/settings/field-permissions'],
  ['자동화', '/projects/ATLAS/settings/automation'],
  ['Slack 채널', '/projects/ATLAS/settings/slack-channels'],
  ['프로젝트 리드', '/projects/ATLAS/settings/project-lead'],
  ['가져오기', '/projects/ATLAS/settings/import'],
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
    const projectLinks = nav.getByRole('link')
    await expect(projectLinks).toHaveCount(sortedProjects.length)
    for (const [index, project] of sortedProjects.entries()) {
      await expect(projectLinks.nth(index)).toHaveText(project.name)
    }
  })

  test('S2 Given alice 로그인 When Atlas 프로젝트 링크 클릭 Then /projects/ATLAS/board 이동', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    await nav.getByRole('link', { name: ATLAS_PROJECT_NAME, exact: true }).click()

    await page.waitForURL('**/projects/ATLAS/board')
  })

  test('S3 Given alice 로그인 When Atlas 디스클로저 펼침 Then 직접링크3+리포트4+설정11 전 서브링크가 실 라우트 href로 노출(죽은 링크 0)', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    const nav = page.getByRole('navigation', { name: '프로젝트', exact: true })
    const atlasToggle = nav.getByRole('button', { name: `${ATLAS_PROJECT_NAME} 하위 메뉴`, exact: true })
    await atlasToggle.click()
    await expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')

    for (const [label, href] of DIRECT_LINK_CONTRACT) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveAttribute('href', href)
    }

    const reportsToggle = nav.getByRole('button', { name: '리포트', exact: true })
    await expect(reportsToggle).toHaveAttribute('aria-expanded', 'false')
    await reportsToggle.click()
    await expect(reportsToggle).toHaveAttribute('aria-expanded', 'true')
    for (const [label, href] of REPORT_LINK_CONTRACT) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveAttribute('href', href)
    }

    const settingsToggle = nav.getByRole('button', { name: '프로젝트 설정', exact: true })
    await expect(settingsToggle).toHaveAttribute('aria-expanded', 'false')
    await settingsToggle.click()
    await expect(settingsToggle).toHaveAttribute('aria-expanded', 'true')
    for (const [label, href] of SETTINGS_LINK_CONTRACT) {
      await expect(nav.getByRole('link', { name: label, exact: true })).toHaveAttribute('href', href)
    }
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
