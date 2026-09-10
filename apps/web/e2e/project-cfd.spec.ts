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
//   S1. 백로그 페이지 → 탭바 "리포트" 탭 → 착지 화면 "누적 흐름도(CFD)" 카드 클릭 → 라우트 이동(URL) + 차트 컨테이너 표시.
//   S2. PROJECT-EMPTY 프로젝트의 CFD 페이지 직접 진입 → 빈 상태 안내 문구 표시, 차트 컨테이너 미표시.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { clickProjectViewTab, projectViewNav } from './fixtures/project-view-tabs'

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

/** 정본 탭바의 리포트 탭 라벨 — `i18n/project-view-labels.ts` 의 `reports` 와 같은 값이어야 한다 */
const REPORTS_TAB_LABEL = '리포트'

/**
 * 리포트 착지 카드/서브내비의 CFD 라벨
 * (`components/project/project-report-links.ts` 의 `PROJECT_REPORT_LINKS` 미러).
 *
 * 아래 `labels.nav.cfdLink`(`누적 흐름도`)와 **다른 문자열**이다 — 옛 백로그 인라인 nav 와
 * 리포트 목록이 각자 이름을 갖고 있었고, 인라인 쪽이 J5 로 없어지면서 목록 것만 남았다.
 * 이 PR 로 그 목록의 집이 사이드바 트리에서 `project-report-links.ts` 로 옮겨졌을 뿐
 * **문자열은 그대로**다. 둘을 하나로 합치는 것은 여전히 범위 밖이라 차이를 여기 명시해 둔다.
 */
const REPORT_CARD_CFD_LABEL = '누적 흐름도(CFD)'

/**
 * 화면 문구 미러.
 *
 * ★`nav.cfdLink` 의 정본이 **바뀌었다.** 옛 정본 `backlogLabels.page.cfdLink` 는 Jira 패리티
 * J5 로 백로그 인라인 nav 가 사라지며 함께 지워졌다. 리포트 목록 라벨은 `누적 흐름도(CFD)` 로
 * 이 값과 **다르므로** 위 `REPORT_CARD_CFD_LABEL` 을 따로 둔다 — 이 값은 페이지 문구 전용이다.
 */
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
  // When   /projects/ATLAS/backlog 진입 → 탭바 "리포트" 탭 → 착지 화면 "누적 흐름도(CFD)" 카드 클릭
  // Then   URL이 /projects/ATLAS/reports/cfd 로 이동
  //        CFD 페이지 헤더(h1) + 차트 컨테이너(role="img") 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 백로그 → 탭바 "리포트" → 착지 화면 "누적 흐름도(CFD)" 카드 클릭 → 라우트 이동 + 차트 컨테이너 표시', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 리포트는 **이제 탭이다** (Jira JR-1·JR-3 — "Select Reports from the space
    //        navigation." · 신 내비게이션 사이드바 항목 열거에 Reports 부재). 종전 진입로였던
    //        사이드바 트리 `리포트` 그룹은 이 PR 로 사라졌다. 탭바가 떠 있는 것부터 확인한다.
    await expect(projectViewNav(page)).toBeVisible()

    // When ①. 탭바 `리포트` 탭 → 착지 화면 (SPA 내부 이동 — 🛑 goto 금지, MSW store 리셋)
    await clickProjectViewTab(page, REPORTS_TAB_LABEL)

    // When ②. 착지 화면의 CFD 카드 클릭 — 착지는 개별 차트가 아니라 목록이다(JR-2).
    // 🛑 카드 라벨은 `누적 흐름도(CFD)` 로 옛 인라인 nav 라벨(`누적 흐름도`)과 **다르다.**
    await page.getByRole('main').getByRole('link', { name: REPORT_CARD_CFD_LABEL }).click()

    // Then. URL이 CFD 라우트로 이동
    // SPA 클라이언트 라우팅(history.pushState)이라 waitForURL(glob) 기본 waitUntil='load' 이벤트가
    // 발생하지 않는다 — toHaveURL(polling 기반)로 URL 상태를 확인한다.
    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_KEY}/reports/cfd(\\?.*)?$`))

    // Then. CFD 페이지 헤더 표시
    await expect(page.getByRole('heading', { name: labels.page.title, level: 2 })).toBeVisible()

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
    await expect(page.getByRole('heading', { name: labels.page.title, level: 2 })).toBeVisible()

    // Then. 빈 상태 안내 문구 표시
    await expect(page.getByText(labels.status.empty, { exact: true })).toBeVisible()

    // Then. 차트 컨테이너(role="img")는 렌더되지 않음
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toHaveCount(0)
  })
})
