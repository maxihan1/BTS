// 스프린트 번다운/번업 차트 E2E — 백로그 진입점 + 뷰 토글 (FR-RP-01 D6/D7 Task-6)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 (playwright.config.ts 기본값 사용).
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 사용 (auth-fixtures userId 정합).
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 가능 셀렉터는 컨테이너 한정 또는 exact:true.
//   - fr-tt-02-d6-d7-worklog-aggregate-ui-done / fr-lk-02-d6-d7-graph-ui-done: recharts/SVG 시각화는
//     실브라우저 E2E로만 검증하되, 픽셀 좌표가 아닌 컨테이너(role="img") 가시성 위주로 단언한다.
//   - msw-derived-behavior-shared-store-e2e: burndown-handlers.ts가 모듈 로드 시 DEFAULT_BURNDOWN을
//     자동 시드하므로(backlog-fixtures.ts의 스프린트 1과 동일 sprintId) 별도 시드 스크립트가 불필요하다.
//
// 시나리오.
//   S1. 백로그 스프린트 칸 "번다운" 링크 클릭 → 라우트 이동(URL) + 차트 컨테이너 표시.
//   S2. 번다운 페이지에서 "번업" 토글 클릭 → URL에 ?view=burnup 반영 + tablist aria-selected 전환 + 차트 유지.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — backlog-fixtures.ts DEFAULT_BACKLOG / burndown-handlers.ts DEFAULT_BURNDOWN과 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그 페이지 URL — ATLAS 프로젝트 */
const BACKLOG_URL = '/projects/ATLAS/backlog'

/** DEFAULT_BACKLOG.projectKey / DEFAULT_BURNDOWN.projectKey 와 동기화 */
const PROJECT_KEY = 'ATLAS'

/**
 * DEFAULT_BACKLOG.sprints[0].sprint.sprintId / DEFAULT_BURNDOWN.sprintId 와 동기화.
 * 두 fixture가 같은 값을 사용하므로 백로그의 스프린트 1이 곧 번다운 시드 데이터의 대상이다.
 */
const DEFAULT_SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

/** DEFAULT_BACKLOG.sprints[0].sprint.name 와 동기화 — 스프린트 칸 헤더 텍스트 */
const DEFAULT_SPRINT_NAME = '스프린트 1'

/** 번다운 페이지 URL — 위 상수 조합 */
const BURNDOWN_URL = `/projects/${PROJECT_KEY}/sprints/${DEFAULT_SPRINT_ID}/burndown`

/** burndown-labels.ts burndownLabels 문자열 재노출 — E2E 셀렉터가 정본 참조 (하드코딩 대신) */
const labels = {
  page: {
    title: '번다운 / 번업 차트',
  },
  toggle: {
    burndown: '번다운',
    burnup: '번업',
  },
  chart: {
    ariaLabel:
      '번다운 차트, 스프린트의 잔여 작업량과 이상적인 소진 추이, 범위 변화를 선으로 보여줍니다',
  },
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 칸 locator (backlog.spec.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * role="region" + aria-label에 columnName이 포함된 첫 번째 요소를 반환한다.
 * SprintColumn: aria-label="{name} 칸, {count}개 이슈"
 */
function getSprintColumn(page: import('@playwright/test').Page) {
  return page
    .getByRole('region')
    .filter({ hasText: new RegExp(`^${DEFAULT_SPRINT_NAME}`) })
    .first()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-RP-01 D6/D7 스프린트 번다운/번업 차트', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 백로그 진입점
  //
  // Given  alice로 로그인, DEFAULT_BACKLOG 자동 시드(스프린트 1: ATLAS, sprintId=DEFAULT_SPRINT_ID)
  //        burndown-handlers.ts DEFAULT_BURNDOWN 자동 시드(동일 sprintId)
  // When   /projects/ATLAS/backlog 진입 → 스프린트 1 칸의 "번다운" 링크 클릭
  // Then   URL이 /projects/ATLAS/sprints/{sprintId}/burndown 로 이동
  //        번다운 페이지 헤더(h1) + 차트 컨테이너(role="img") 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 백로그 스프린트 칸 "번다운" 클릭 → 라우트 이동 + 차트 컨테이너 표시', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 스프린트 1 칸 표시 확인
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn).toBeVisible()

    // When. 스프린트 1 칸의 "번다운" 링크 클릭
    // SprintColumn: <Link aria-label={`${sprint.name} ${burndownLabels.toggle.burndown} 보기`}>
    const burndownLink = sprintColumn.getByRole('link', {
      name: `${DEFAULT_SPRINT_NAME} ${labels.toggle.burndown} 보기`,
      exact: true,
    })
    await expect(burndownLink).toBeVisible()
    await burndownLink.click()

    // Then. URL이 번다운 라우트로 이동
    // SPA 클라이언트 라우팅(history.pushState)이라 waitForURL(glob) 기본 waitUntil='load' 이벤트가
    // 발생하지 않는다 — toHaveURL(polling 기반)로 URL 상태를 확인한다.
    // validateSearch가 기본값 view=burndown을 URL에 반영하므로 뒤에 쿼리스트링이 붙을 수 있다.
    await expect(page).toHaveURL(
      new RegExp(`/projects/${PROJECT_KEY}/sprints/${DEFAULT_SPRINT_ID}/burndown(\\?.*)?$`),
    )

    // Then. 번다운 페이지 헤더 표시
    await expect(page.getByRole('heading', { name: labels.page.title, level: 2 })).toBeVisible()

    // Then. 차트 컨테이너(role="img") 표시 — recharts 실렌더는 SVG bbox 좌표 대신
    // 컨테이너 가시성으로 검증한다 (SVG E2E 함정 선례).
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 번업 토글
  //
  // Given  alice로 로그인 후 번다운 페이지 직접 진입(기본 뷰=번다운, tablist role="tablist")
  // When   "번업" tab 클릭
  // Then   URL에 ?view=burnup 반영
  //        "번업" tab의 aria-selected=true 전환("번다운" tab은 false)
  //        차트 컨테이너 유지(visible)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 "번업" 토글 클릭 → URL ?view=burnup 반영 + tablist 전환 + 차트 유지', async ({ page }) => {
    // Given. alice 로그인 + 번다운 페이지 직접 진입
    await loginAsAlice(page)
    await page.goto(BURNDOWN_URL)

    // Given. 기본 뷰=번다운 — 차트 컨테이너 표시 확인
    await expect(page.getByRole('heading', { name: labels.page.title, level: 2 })).toBeVisible()
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toBeVisible()

    const burndownTab = page.getByRole('tab', { name: labels.toggle.burndown, exact: true })
    const burnupTab = page.getByRole('tab', { name: labels.toggle.burnup, exact: true })
    await expect(burndownTab).toHaveAttribute('aria-selected', 'true')
    await expect(burnupTab).toHaveAttribute('aria-selected', 'false')

    // When. "번업" tab 클릭
    await burnupTab.click()

    // Then. URL에 ?view=burnup 반영
    await expect(page).toHaveURL(/[?&]view=burnup/)

    // Then. tablist aria-selected 전환
    await expect(burnupTab).toHaveAttribute('aria-selected', 'true')
    await expect(burndownTab).toHaveAttribute('aria-selected', 'false')

    // Then. 차트 컨테이너 유지(번업 뷰도 동일 aria-label의 role="img" 컨테이너를 렌더)
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toBeVisible()
  })
})
