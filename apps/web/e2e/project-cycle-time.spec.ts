// 프로젝트 Cycle Time / Lead Time 분포 리포트 E2E — 백로그 뷰 전환 진입점 + 정상 렌더 + 빈 상태 + 403 (FR-RP-04 D6/D7 Task-10)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 (playwright.config.ts 기본값 사용).
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 사용 (auth-fixtures userId 정합).
//   - playwright-getbyrole-exact-strict-mode: 히스토그램/박스플롯 aria-label은 Cycle Time·Lead Time
//     두 섹션에서 동일 문자열을 공유해 페이지 전역 getByRole은 strict mode violation을 낸다 —
//     각 <section>을 h2 제목으로 스코핑한 뒤 그 안에서 img를 조회한다(metricSection 헬퍼).
//   - fr-rp-01-d6-d7-burndown-ui-done / fr-tt-02-d6-d7-worklog-aggregate-ui-done / fr-rp-02-d6-d7-velocity-done
//     / fr-rp-03-d6-d7-cfd-done: recharts/커스텀 SVG 시각화는 실브라우저 E2E로만 검증하되, 픽셀 좌표가
//     아닌 컨테이너(role="img") 가시성 위주로 단언한다.
//   - msw-derived-behavior-shared-store-e2e: cycle-time-handlers.ts가 모듈 로드 시 DEFAULT_CYCLE_TIME을
//     자동 시드하므로(backlog-fixtures.ts / cfd-handlers.ts / velocity-handlers.ts와 동일 projectKey='ATLAS')
//     별도 시드 스크립트가 불필요하다.
//
// 시나리오.
//   S1. 백로그 페이지 "프로젝트 뷰 전환" nav의 "사이클/리드 타임" 링크 클릭 → 라우트 이동(URL) + 페이지 헤더 표시.
//   S2. ATLAS 프로젝트 Cycle Time / Lead Time 페이지 직접 진입 → 두 섹션 모두 히스토그램·박스플롯 컨테이너
//       표시 + 요약 타일 수치 텍스트 존재.
//   S3. PROJECT-EMPTY 프로젝트 직접 진입 → 빈 상태 안내 문구 표시, 차트 컨테이너 미표시.
//   S4. PROJECT-FORBIDDEN 프로젝트 직접 진입 → 403 접근 거부 안내 표시, 차트·요약 타일 미표시.

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { openProjectReportFromSidebar, projectViewNav } from './fixtures/project-view-tabs'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — backlog-fixtures.ts DEFAULT_BACKLOG / cycle-time-handlers.ts DEFAULT_CYCLE_TIME와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** DEFAULT_BACKLOG.projectKey / DEFAULT_CYCLE_TIME.projectKey 와 동기화 */
const PROJECT_KEY = 'ATLAS'

/** 백로그 페이지 URL — ATLAS 프로젝트 */
const BACKLOG_URL = `/projects/${PROJECT_KEY}/backlog`

/** cycle-time-handlers.ts EMPTY_PROJECT_KEY 와 동기화 — 200이되 cycleTime/leadTime 모두 count=0인 빈 시나리오 트리거 */
const EMPTY_PROJECT_KEY = 'PROJECT-EMPTY'

/** cycle-time-handlers.ts FORBIDDEN_PROJECT_KEY 와 동기화 — 403(ISSUE_ACCESS_DENIED) 트리거 */
const FORBIDDEN_PROJECT_KEY = 'PROJECT-FORBIDDEN'

/** Cycle/Lead Time 리포트 페이지 URL 생성 헬퍼 */
function cycleTimeUrl(projectKey: string): string {
  return `/projects/${projectKey}/reports/cycle-time`
}

/**
 * cycle-time-labels.ts cycleTimeLabels / backlog-labels.ts backlogLabels 문자열 재노출 —
 * E2E 셀렉터가 정본을 참조하도록 값만 복사한다(로직 재구현 아님).
 */
const labels = {
  page: {
    title: 'Cycle / Lead Time 분포',
  },
  nav: {
    cycleTimeLink: '사이클/리드 타임',
  },
  metric: {
    cycleTitle: 'Cycle Time',
    leadTitle: 'Lead Time',
  },
  status: {
    empty: '최근 30일 내 완료된 이슈가 없습니다.',
    forbidden: '접근 권한이 없습니다.',
  },
  chart: {
    histogramAriaLabel: '소요 시간 구간별 이슈 수 분포 히스토그램',
    boxPlotAriaLabel: '최소, 25백분위, 중앙값, 75백분위, 최대값을 보여주는 박스플롯',
  },
} as const

/**
 * Cycle Time / Lead Time 지표 섹션(<section>)을 h2 제목으로 스코핑해 반환한다.
 *
 * 히스토그램/박스플롯 aria-label이 Cycle Time·Lead Time 두 섹션에서 동일하므로,
 * 페이지 전역에서 role="img"를 조회하면 strict mode violation이 난다 — 섹션 단위로
 * 한정해 각 지표의 차트/요약 타일을 독립적으로 검증한다.
 *
 * @param page Playwright Page 객체
 * @param headingName 섹션 내 h2 제목 텍스트 (labels.metric.cycleTitle | labels.metric.leadTitle)
 */
function metricSection(page: Page, headingName: string) {
  return page.locator('section').filter({
    has: page.getByRole('heading', { name: headingName, level: 2 }),
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-RP-04 D6/D7 프로젝트 Cycle Time / Lead Time 분포', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 백로그 진입점
  //
  // Given  alice로 로그인, DEFAULT_BACKLOG 자동 시드(ATLAS 프로젝트)
  //        cycle-time-handlers.ts DEFAULT_CYCLE_TIME 자동 시드(동일 projectKey='ATLAS')
  // When   /projects/ATLAS/backlog 진입 → "프로젝트 뷰 전환" nav의 "사이클/리드 타임" 링크 클릭
  // Then   URL이 /projects/ATLAS/reports/cycle-time 로 이동
  //        Cycle/Lead Time 페이지 헤더(h1) 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 백로그 "프로젝트 뷰 전환" nav "사이클/리드 타임" 클릭 → 라우트 이동 + 페이지 헤더 표시', async ({
    page,
  }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 리포트는 **탭이 아니다** (Jira 패리티 J5). 탭바가 정본 9탭으로 통합되면서 백로그
    //        인라인 nav 의 리포트 3링크가 사라졌고, 남은 UI 경로는 사이드바 트리의 `리포트`
    //        그룹 하나다. 탭바에 그 링크가 없다는 사실도 여기서 함께 못 박는다.
    const viewNav = projectViewNav(page)
    await expect(viewNav).toBeVisible()
    await expect(
      viewNav.getByRole('link', { name: labels.nav.cycleTimeLink, exact: true }),
    ).toHaveCount(0)

    // When. 사이드바 리포트 그룹에서 링크 클릭 (SPA 내부 이동 — goto 금지, MSW store 리셋)
    const cycleTimeLink = await openProjectReportFromSidebar(
      page,
      'Atlas 프로젝트',
      labels.nav.cycleTimeLink,
    )
    await cycleTimeLink.click()

    // Then. URL이 Cycle Time 라우트로 이동
    // SPA 클라이언트 라우팅(history.pushState)이라 waitForURL(glob) 기본 waitUntil='load' 이벤트가
    // 발생하지 않는다 — toHaveURL(polling 기반)로 URL 상태를 확인한다. page.reload는 사용하지 않는다
    // (MSW store가 페이지 리로드로 초기화되는 것을 방지 — msw-derived-behavior-shared-store-e2e 교훈).
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/reports/cycle-time(\\?.*)?$`))

    // Then. Cycle/Lead Time 페이지 헤더 표시
    await expect(page.getByRole('heading', { name: labels.page.title, level: 1 })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 정상 렌더
  //
  // Given  alice로 로그인. cycle-time-handlers.ts DEFAULT_CYCLE_TIME(ATLAS, cycle=15/lead=20 표본)이
  //        모듈 로드 시 자동 시드되어 있다.
  // When   /projects/ATLAS/reports/cycle-time 직접 진입
  // Then   Cycle Time / Lead Time 두 섹션 모두 히스토그램(role="img")·박스플롯(role="img") 표시,
  //        요약 타일(중앙값) 수치 텍스트 존재
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 정상 렌더 — Cycle/Lead 두 섹션 모두 히스토그램·박스플롯·요약 타일 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. ATLAS 프로젝트 Cycle/Lead Time 페이지 직접 진입
    await page.goto(cycleTimeUrl(PROJECT_KEY))

    // Then. 페이지 헤더 표시
    await expect(page.getByRole('heading', { name: labels.page.title, level: 1 })).toBeVisible()

    // Then. Cycle Time 섹션 — 히스토그램·박스플롯 컨테이너 표시 + 중앙값 타일 텍스트 존재
    // (픽셀/bbox 단언 금지 — recharts/SVG는 실브라우저에서도 bbox 폭 0 함정이 있어 컨테이너
    // 가시성 위주로만 검증한다)
    const cycleSection = metricSection(page, labels.metric.cycleTitle)
    await expect(cycleSection.getByRole('img', { name: labels.chart.histogramAriaLabel })).toBeVisible()
    await expect(cycleSection.getByRole('img', { name: labels.chart.boxPlotAriaLabel })).toBeVisible()
    await expect(cycleSection.getByTestId('cycle-time-stat-p50')).toHaveText(/\S/)

    // Then. Lead Time 섹션 — 동일 검증
    const leadSection = metricSection(page, labels.metric.leadTitle)
    await expect(leadSection.getByRole('img', { name: labels.chart.histogramAriaLabel })).toBeVisible()
    await expect(leadSection.getByRole('img', { name: labels.chart.boxPlotAriaLabel })).toBeVisible()
    await expect(leadSection.getByTestId('cycle-time-stat-p50')).toHaveText(/\S/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 — 빈 상태
  //
  // Given  alice로 로그인. cycle-time-handlers.ts EMPTY_PROJECT_KEY(PROJECT-EMPTY)는
  //        200이되 cycleTime/leadTime 모두 count=0인 빈 응답을 반환한다.
  // When   /projects/PROJECT-EMPTY/reports/cycle-time 직접 진입
  // Then   빈 상태 안내 문구 표시, 차트 컨테이너(role="img") 미표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 표시할 데이터가 없는 프로젝트 → 빈 상태 안내 표시, 차트 미표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. 빈 Cycle/Lead Time 프로젝트 페이지 직접 진입
    await page.goto(cycleTimeUrl(EMPTY_PROJECT_KEY))

    // Then. 페이지 헤더는 정상 표시 (조회 자체는 200 성공)
    await expect(page.getByRole('heading', { name: labels.page.title, level: 1 })).toBeVisible()

    // Then. 빈 상태 안내 문구 표시
    await expect(page.getByText(labels.status.empty, { exact: true })).toBeVisible()

    // Then. 차트 컨테이너(role="img")는 렌더되지 않음
    await expect(page.getByRole('img', { name: labels.chart.histogramAriaLabel })).toHaveCount(0)
    await expect(page.getByRole('img', { name: labels.chart.boxPlotAriaLabel })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 — 403 접근 거부
  //
  // Given  alice로 로그인. cycle-time-handlers.ts FORBIDDEN_PROJECT_KEY(PROJECT-FORBIDDEN)는
  //        403(ISSUE_ACCESS_DENIED)을 반환한다.
  // When   /projects/PROJECT-FORBIDDEN/reports/cycle-time 직접 진입
  // Then   접근 거부 안내 문구 표시, 차트·요약 타일(이슈 표본 데이터) 미노출
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 접근 권한이 없는 프로젝트 → 403 안내 표시, 차트·요약 타일 데이터 미노출', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. 접근 거부되는 프로젝트 Cycle/Lead Time 페이지 직접 진입
    await page.goto(cycleTimeUrl(FORBIDDEN_PROJECT_KEY))

    // Then. 페이지 헤더는 정상 표시 (헤더는 CycleTimeReportPage 레벨, 403은 하위 CycleTimeReport에서만 처리)
    await expect(page.getByRole('heading', { name: labels.page.title, level: 1 })).toBeVisible()

    // Then. 403 접근 거부 안내 문구 표시 (exact:true — backlogLabels.page.accessDenied는
    // 마침표 없는 동일 어두 문자열이라 substring 매칭 시 혼동 가능성을 차단)
    await expect(page.getByText(labels.status.forbidden, { exact: true })).toBeVisible()

    // Then. 차트 컨테이너·요약 타일(이슈 표본 데이터) 미노출 — 403 에러 분기는 응답 데이터를 렌더하지 않는다
    await expect(page.getByRole('img', { name: labels.chart.histogramAriaLabel })).toHaveCount(0)
    await expect(page.getByRole('img', { name: labels.chart.boxPlotAriaLabel })).toHaveCount(0)
    await expect(page.getByTestId('cycle-time-stat-p50')).toHaveCount(0)
  })
})
