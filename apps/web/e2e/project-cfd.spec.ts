// 프로젝트 누적 흐름도(CFD) E2E — 백로그 뷰 전환 진입점 + 빈 상태 (FR-RP-03 D6/D7 Task-8)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 (playwright.config.ts 기본값 사용).
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 사용 (auth-fixtures userId 정합).
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 가능 셀렉터는 컨테이너 한정 또는 exact:true.
//   - fr-rp-01-d6-d7-burndown-ui-done / fr-tt-02-d6-d7-worklog-aggregate-ui-done / fr-rp-02-d6-d7-velocity-done:
//     recharts/SVG 시각화는 실브라우저 E2E로만 검증하되, 픽셀 좌표가 아닌 컨테이너(role="img") 가시성 위주로
//     단언한다.
//   - msw-derived-behavior-shared-store-e2e: cfd-handlers.ts가 모듈 로드 시 DEFAULT_CFD를 자동 시드하므로
//     (backlog-fixtures.ts / velocity-handlers.ts와 동일 projectKey='ATLAS') 별도 시드 스크립트가 불필요하다.
//
// 시나리오.
//   S1. 백로그 페이지 "프로젝트 뷰 전환" nav의 "누적 흐름도" 링크 클릭 → 라우트 이동(URL) + 차트 컨테이너 표시.
//   S2. PROJECT-EMPTY 프로젝트의 CFD 페이지 직접 진입 → 빈 상태 안내 문구 표시, 차트 컨테이너 미표시.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { openProjectReportFromSidebar, projectViewNav } from './fixtures/project-view-tabs'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — backlog-fixtures.ts DEFAULT_BACKLOG / cfd-handlers.ts DEFAULT_CFD와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그 페이지 URL — ATLAS 프로젝트 */
const BACKLOG_URL = '/projects/ATLAS/backlog'

/** DEFAULT_BACKLOG.projectKey / DEFAULT_CFD.projectKey 와 동기화 */
const PROJECT_KEY = 'ATLAS'

/** cfd-handlers.ts EMPTY_PROJECT_KEY 와 동기화 — 200이되 모든 point 카운트가 0인 빈 CFD 시나리오 트리거 */
const EMPTY_PROJECT_KEY = 'PROJECT-EMPTY'

/** 빈 CFD 프로젝트의 CFD 페이지 URL */
const EMPTY_CFD_URL = `/projects/${EMPTY_PROJECT_KEY}/reports/cfd`

/**
 * 사이드바 트리 `리포트` 그룹의 CFD 링크 라벨 (`ProjectTree.tsx` `REPORT_LINKS` 미러).
 *
 * 아래 `labels.nav.cfdLink`(`누적 흐름도`)와 **다른 문자열**이다 — 옛 백로그 인라인 nav 와
 * 사이드바가 각자 이름을 갖고 있었고, 인라인 쪽이 J5 로 없어지면서 사이드바 것만 남았다.
 * 둘을 하나로 합치는 것은 이 PR 범위 밖이라 차이를 여기에 명시해 둔다.
 */
const SIDEBAR_CFD_LABEL = '누적 흐름도(CFD)'

/** cfd-labels.ts cfdLabels / backlog-labels.ts backlogLabels 문자열 재노출 — E2E 셀렉터가 정본 참조 */
const labels = {
  page: {
    title: '누적 흐름도',
  },
  nav: {
    cfdLink: '누적 흐름도',
  },
  status: {
    empty: '아직 표시할 데이터가 없습니다.',
  },
  chart: {
    ariaLabel: '누적 흐름도 차트, 날짜별 할 일, 진행 중, 완료 이슈 수를 누적 영역으로 보여줍니다',
  },
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-RP-03 D6/D7 프로젝트 누적 흐름도(CFD)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 백로그 진입점
  //
  // Given  alice로 로그인, DEFAULT_BACKLOG 자동 시드(ATLAS 프로젝트)
  //        cfd-handlers.ts DEFAULT_CFD 자동 시드(동일 projectKey='ATLAS', 30일 시계열)
  // When   /projects/ATLAS/backlog 진입 → "프로젝트 뷰 전환" nav의 "누적 흐름도" 링크 클릭
  // Then   URL이 /projects/ATLAS/reports/cfd 로 이동
  //        CFD 페이지 헤더(h1) + 차트 컨테이너(role="img") 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 백로그 "프로젝트 뷰 전환" nav "누적 흐름도" 클릭 → 라우트 이동 + 차트 컨테이너 표시', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 리포트는 **탭이 아니다** (Jira 패리티 J5). 탭바가 정본 9탭으로 통합되면서 백로그
    //        인라인 nav 의 리포트 3링크가 사라졌고, 남은 UI 경로는 사이드바 트리의 `리포트`
    //        그룹 하나다. 탭바에 그 링크가 없다는 사실도 여기서 함께 못 박는다.
    const viewNav = projectViewNav(page)
    await expect(viewNav).toBeVisible()
    await expect(viewNav.getByRole('link', { name: labels.nav.cfdLink, exact: true })).toHaveCount(0)

    // When. 사이드바 리포트 그룹에서 링크 클릭 (SPA 내부 이동 — goto 금지, MSW store 리셋)
    // 🛑 사이드바 라벨은 `누적 흐름도(CFD)` 로 옛 인라인 nav 라벨(`누적 흐름도`)과 **다르다.**
    //    두 목록이 각자 이름을 갖고 있었고, 인라인 쪽이 없어지면서 사이드바 것만 남았다.
    const cfdLink = await openProjectReportFromSidebar(page, 'Atlas 프로젝트', SIDEBAR_CFD_LABEL)
    await cfdLink.click()

    // Then. URL이 CFD 라우트로 이동
    // SPA 클라이언트 라우팅(history.pushState)이라 waitForURL(glob) 기본 waitUntil='load' 이벤트가
    // 발생하지 않는다 — toHaveURL(polling 기반)로 URL 상태를 확인한다.
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/reports/cfd(\\?.*)?$`))

    // Then. CFD 페이지 헤더 표시
    await expect(page.getByRole('heading', { name: labels.page.title, level: 1 })).toBeVisible()

    // Then. 차트 컨테이너(role="img") 표시 — recharts 실렌더는 SVG bbox 좌표 대신
    // 컨테이너 가시성으로 검증한다 (SVG E2E 함정 선례).
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 빈 상태
  //
  // Given  alice로 로그인. cfd-handlers.ts EMPTY_PROJECT_KEY(PROJECT-EMPTY)는
  //        200이되 모든 point 카운트가 0인 빈 CFD를 반환한다.
  // When   /projects/PROJECT-EMPTY/reports/cfd 직접 진입
  // Then   빈 상태 안내 문구 표시, 차트 컨테이너(role="img") 미표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 표시할 데이터가 없는 프로젝트 → 빈 상태 안내 표시, 차트 미표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. 빈 CFD 프로젝트의 CFD 페이지 직접 진입
    await page.goto(EMPTY_CFD_URL)

    // Then. 페이지 헤더는 정상 표시 (조회 자체는 200 성공)
    await expect(page.getByRole('heading', { name: labels.page.title, level: 1 })).toBeVisible()

    // Then. 빈 상태 안내 문구 표시
    await expect(page.getByText(labels.status.empty, { exact: true })).toBeVisible()

    // Then. 차트 컨테이너(role="img")는 렌더되지 않음
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toHaveCount(0)
  })
})
