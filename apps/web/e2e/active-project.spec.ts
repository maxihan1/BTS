// 활성 프로젝트 컨텍스트 E2E — FR-UX-07 Task 10 (S1~S8 + NFR5 + B2)
//
// 관련 학습.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript 로 저장값 선주입
//   - frontend-nav-aria-label-e2e-contract: nav aria-label 4종은 계약이다 (신규 라벨 도입 금지)
//   - e2e-playwright-filter-arg-drop: 이 spec 단독 실행은 바이너리 직접 호출로
//
// ★ 판별자 선택 — "어느 프로젝트를 보고 있나"를 MSW 응답 내용이 아니라 **URL 과 localStorage**
//   로 판정한다. mock 이 projectKey 별로 다른 이슈를 주는지에 의존하지 않아야 이 spec 이
//   활성 프로젝트 해소 자체만 검증한다(mock 동작이 바뀌어도 의미가 유지된다).
import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { navLabels } from '../src/i18n/nav-labels'

/** `hooks/use-active-project.ts` 의 저장 키 — 값은 JSON 인코딩된 문자열이다 */
const ACTIVE_PROJECT_KEY = 'bts.active-project'

/** MSW 활성 시드는 name 오름차순 ATLAS·MIDDLE·ZETA — 첫 원소는 ATLAS */
const FIRST_PROJECT = 'ATLAS'

/** 저장된 활성 프로젝트 키를 읽는다 (JSON 디코딩) */
async function readActiveProject(page: Page): Promise<string | null> {
  return page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    if (raw === null) return null
    try {
      const parsed: unknown = JSON.parse(raw)
      return typeof parsed === 'string' ? parsed : null
    } catch {
      return null
    }
  }, ACTIVE_PROJECT_KEY)
}

/** 로그인 전에 활성 프로젝트 저장값을 심는다 */
async function seedActiveProject(page: Page, projectKey: string): Promise<void> {
  await page.addInitScript(
    ([key, value]: [string, string]) => {
      window.localStorage.setItem(key, JSON.stringify(value))
    },
    [ACTIVE_PROJECT_KEY, projectKey] as [string, string],
  )
}

/** 이슈 목록 표가 뜰 때까지 기다린다 — 해소가 끝났다는 신호 */
async function waitForIssueList(page: Page): Promise<void> {
  await expect(page.getByRole('table', { name: '이슈 목록' })).toBeVisible()
}

test.describe('활성 프로젝트 컨텍스트 (FR-UX-07)', () => {
  test('S1: ?projectKey=ZETA 로 진입하면 그 프로젝트가 활성이 되고 저장된다', async ({ page }) => {
    await seedActiveProject(page, 'MIDDLE')
    await loginAsAlice(page)

    await page.goto('/issues?projectKey=ZETA')
    await waitForIssueList(page)

    // 명시 지정이 저장값(MIDDLE)을 이긴다
    expect(await readActiveProject(page)).toBe('ZETA')
    expect(page.url()).toContain('projectKey=ZETA')
  })

  test('S2: URL 에 projectKey 가 없으면 저장값이 유지된다 (첫 프로젝트로 덮이지 않는다)', async ({
    page,
  }) => {
    await seedActiveProject(page, 'ZETA')
    await loginAsAlice(page)

    await page.goto('/issues')
    await waitForIssueList(page)

    expect(await readActiveProject(page)).toBe('ZETA')
  })

  test('S3: 저장값도 URL 도 없으면 목록의 첫 프로젝트가 선택되고 저장된다', async ({ page }) => {
    await loginAsAlice(page)
    // 로그인 과정에서 값이 심어졌을 수 있으므로 명시적으로 비운다
    await page.evaluate((key) => { window.localStorage.removeItem(key) }, ACTIVE_PROJECT_KEY)

    await page.goto('/issues')
    await waitForIssueList(page)

    expect(await readActiveProject(page)).toBe(FIRST_PROJECT)
  })

  /**
   * ★ S4 — plan 독립 리뷰 BLOCKER B1 이 없었으면 이 시나리오는 구현되지 않았다.
   * `/projects/$projectKey/*` 를 볼 때 그 키를 기록하는 지점이 어디에도 없었다.
   */
  test('S4: 프로젝트 보드를 보다 사이드바 "이슈"를 누르면 그 프로젝트의 이슈가 열린다', async ({
    page,
  }) => {
    await seedActiveProject(page, 'ATLAS')
    await loginAsAlice(page)

    await page.goto('/projects/ZETA/board')
    // 경로 파라미터가 저장값에 기록될 때까지 기다린다
    await expect.poll(() => readActiveProject(page)).toBe('ZETA')

    const mainNav = page.getByRole('navigation', { name: navLabels.mainNav })
    await mainNav.getByRole('link', { name: navLabels.issues, exact: true }).click()

    await page.waitForURL('**/issues*')
    await waitForIssueList(page)
    expect(await readActiveProject(page)).toBe('ZETA')
  })

  /**
   * ★ B2 — `handleFilterChange` 만 `...prev` 를 펼치지 않아 필터 한 번에 projectKey 가
   * URL 에서 증발하던 회귀. 타입 체크로도 안 잡히는 조용한 결함이었다.
   */
  test('B2: 필터를 바꿔도 URL 의 projectKey 가 유지된다', async ({ page }) => {
    await loginAsAlice(page)

    await page.goto('/issues?projectKey=ZETA')
    await waitForIssueList(page)

    // 필터 바의 "초기화" 가 handleFilterChange 를 태우는 가장 짧은 경로다
    await page.getByRole('button', { name: /초기화/ }).click()

    await expect.poll(() => page.url()).toContain('projectKey=ZETA')
    expect(await readActiveProject(page)).toBe('ZETA')
  })

  /**
   * NFR5 — 마운트 직후 URL 을 정규화하지 않는다. 이 저장소에는 transient URL 관측 race 로
   * e2e 가 90초 hang 한 선례가 있어(`saved-filters` SF-1/SF-3) 그 패턴을 새로 들이지 않는다.
   */
  test('NFR5: 진입 후 URL 이 저절로 바뀌지 않는다 (정규화 없음)', async ({ page }) => {
    await seedActiveProject(page, 'ZETA')
    await loginAsAlice(page)

    await page.goto('/issues')
    await waitForIssueList(page)
    const afterLoad = page.url()

    // 해소가 끝난 뒤에도 URL 에 projectKey 가 주입되지 않아야 한다
    await page.waitForTimeout(500)
    expect(page.url()).toBe(afterLoad)
    expect(page.url()).not.toContain('projectKey=')
  })

  test('S8: 검색 화면도 같은 활성 프로젝트를 따른다', async ({ page }) => {
    await seedActiveProject(page, 'ZETA')
    await loginAsAlice(page)

    await page.goto('/search')
    await expect(page.getByRole('heading', { name: 'AQL 검색' })).toBeVisible()

    // 검색 화면이 렌더됐다는 것은 활성 프로젝트가 해소됐다는 뜻이다
    // (0개/로딩/에러면 ActiveProjectGate 가 대신 렌더된다)
    expect(await readActiveProject(page)).toBe('ZETA')
  })
})
