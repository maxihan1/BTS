// FR-TT-02 D7 E2E — 워크로그 집계 보고 페이지 (WorklogAggregateReport)
//
// 교훈 반영.
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동으로 시나리오 전환
//     (page.reload 금지 — Service Worker store가 새 모듈로 재시작돼 가짜그린)
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: 셀렉터는 컨테이너 내 within 한정
//     또는 exact:true 사용 (텍스트 중복 버튼 strict mode violation 방지)
//   - msw-derived-behavior-shared-store-e2e: MSW fixture 응답은 핸들러별 고정 fixture
//     (stateful store 불필요 — 읽기 전용 집계 API)
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 사용 (auth-fixtures userId 정합)
//
// MSW 핵심 사항.
//   - worklog-aggregate-handlers.ts: project 쿼리파라미터별 fixture 분기
//     ATLAS → by 파라미터별 정상 집계 fixture 반환
//     EMPTY → 빈 buckets 반환 (빈 상태 시나리오)
//     FORBIDDEN → 403 반환 (권한 안내 시나리오)
//   - 각 테스트는 독립적인 Playwright context(새 ServiceWorker)를 사용하므로
//     store 상태 오염 없이 격리된다
//
// 시나리오.
//   S1. happy path — by=issue 기본 표 데이터 + 차원 전환 (user → "(알 수 없음)" + period → granularity 노출)
//   S2. 빈 상태 — project=EMPTY → 빈 상태 메시지 표시
//   S3. 403 권한 안내 — project=FORBIDDEN → ProjectNotFoundScreen "접근 권한이 없습니다"

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 정상 집계 데이터를 반환하는 프로젝트 키 */
const PROJECT_KEY = 'ATLAS'

/** 403을 반환하는 프로젝트 키 — worklog-aggregate-handlers.ts FORBIDDEN_PROJECT_KEY */
const FORBIDDEN_PROJECT_KEY = 'FORBIDDEN'

/** 빈 버킷을 반환하는 프로젝트 키 — worklog-aggregate-handlers.ts EMPTY_PROJECT_KEY */
const EMPTY_PROJECT_KEY = 'EMPTY'

/** 집계 보고 페이지 URL 생성 헬퍼 */
function reportUrl(projectKey: string): string {
  return `/projects/${projectKey}/reports/worklog`
}

// ─────────────────────────────────────────────────────────────────────────────
// i18n 라벨 — 라이브러리 import 없이 실제 컴포넌트에서 사용하는 문자열 상수로 관리
// (issue-fixtures.ts의 i18nLabels 패턴에서 착안 — 라벨 변경 시 단일 수정점)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  page: {
    title: '워크로그 집계 보고',
  },
  filter: {
    dimensionLabel: '집계 기준',
    dimensionUser: '사용자별',
    dimensionPeriod: '기간별',
    granularityLabel: '집계 단위',
  },
  table: {
    headerLabel: '레이블',
    headerTimeSpent: '소요 시간',
    headerCount: '건수',
    unknownDisplayName: '(알 수 없음)',
    totalRow: '합계',
  },
  chart: {
    ariaLabel: '워크로그 집계 막대 차트',
  },
  empty: {
    message: '기록된 워크로그가 없습니다.',
  },
  /** ProjectNotFoundScreen 텍스트 */
  notFound: {
    heading: '접근 권한이 없습니다',
  },
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 이동
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 SPA 컨텍스트 안에서 target URL로 내부 이동한다.
 *
 * page.goto 대신 history.pushState + popstate 를 사용해 Service Worker를
 * 재시작하지 않는다. (worktree-stale-base-rebase-and-e2e-msw-traps 교훈)
 * loginAsAlice 완료 후 ServiceWorker가 활성 상태일 때만 호출한다.
 */
async function navigateTo(page: import('@playwright/test').Page, url: string): Promise<void> {
  await page.evaluate((targetUrl: string) => {
    window.history.pushState({}, '', targetUrl)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, url)
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-TT-02 워크로그 집계 보고 (WorklogAggregateReport)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice로 로그인 → /dashboard 진입 → ServiceWorker 기동 완료
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 — happy path: 표 데이터 + 차원 전환
  //
  // Given   alice로 로그인 후 ATLAS 프로젝트 집계 보고 페이지 SPA 내부 이동
  // When    by=issue(기본) 로 집계 표 로드
  // Then    표에 버킷 데이터 표시 (ATLAS-1, ATLAS-2, ATLAS-3 행)
  //         차트 컨테이너(role="img", aria-label=worklogAggregateLabels.chart.ariaLabel) 표시
  //
  // When    차원 셀렉터로 "사용자별" 선택 → SPA 내부 필터 조작 (reload 금지)
  // Then    표에 "(알 수 없음)" 항목 표시 (빈 displayName 버킷 대체)
  //
  // When    차원 셀렉터로 "기간별" 선택
  // Then    granularity 셀렉터 노출 (id="worklog-granularity")
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 — by=issue 기본 표 데이터 표시 + 차원 전환(user·period) 검증', async ({ page }) => {
    // Given. ATLAS 집계 보고 페이지 SPA 내부 이동
    await navigateTo(page, reportUrl(PROJECT_KEY))

    // 페이지 헤더 로드 대기 (h1 렌더 = 컴포넌트 마운트 신호)
    await expect(
      page.getByRole('heading', { name: labels.page.title, level: 1 }),
    ).toBeVisible()

    // Then. by=issue 기본 — 표 헤더 확인
    const table = page.locator('table')
    await expect(table).toBeVisible()
    await expect(table.getByRole('columnheader', { name: labels.table.headerLabel })).toBeVisible()
    await expect(
      table.getByRole('columnheader', { name: labels.table.headerTimeSpent }),
    ).toBeVisible()

    // Then. 버킷 데이터 행 확인 (ATLAS-1 fixture 첫 번째 버킷)
    await expect(table.getByRole('cell', { name: 'ATLAS-1', exact: true })).toBeVisible()
    await expect(table.getByRole('cell', { name: 'ATLAS-2', exact: true })).toBeVisible()
    await expect(table.getByRole('cell', { name: 'ATLAS-3', exact: true })).toBeVisible()

    // Then. 합계 행 확인
    await expect(table.getByText(labels.table.totalRow, { exact: true })).toBeVisible()

    // Then. 차트 컨테이너 표시 확인
    // recharts는 SVG를 렌더한다. role="img" + aria-label로 컨테이너 존재 확인 (WorklogAggregateChart)
    // 정확한 막대 수 단언은 표로 수행하고, 차트는 컨테이너 가시성만 검증한다.
    // (fr-lk-02-d6-d7-graph-ui-done: 시각화는 실렌더 E2E 필수 — 컨테이너 존재 최소 기준)
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toBeVisible()

    // When. 차원 셀렉터로 "사용자별" 선택 — SPA 내부 필터 조작 (reload 금지)
    // WorklogFilterBar의 select#worklog-by (aria-label="집계 기준")
    const dimensionSelect = page.locator('#worklog-by')
    await expect(dimensionSelect).toBeVisible()
    await dimensionSelect.selectOption('user')

    // Then. by=user 전환 후 "(알 수 없음)" 항목 확인
    // USER_AGGREGATE_FIXTURE에 label='' 버킷 1개 포함 → WorklogAggregateTable에서 unknownDisplayName 대체
    await expect(
      table.getByRole('cell', { name: labels.table.unknownDisplayName, exact: true }),
    ).toBeVisible()

    // Then. alice / bob 버킷도 표시
    await expect(table.getByRole('cell', { name: 'alice', exact: true })).toBeVisible()
    await expect(table.getByRole('cell', { name: 'bob', exact: true })).toBeVisible()

    // When. 차원 셀렉터로 "기간별" 선택
    await dimensionSelect.selectOption('period')

    // Then. granularity 셀렉터 노출 확인 (by=period 시에만 렌더됨 — WorklogFilterBar 조건부)
    const granularitySelect = page.locator('#worklog-granularity')
    await expect(granularitySelect).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 빈 상태
  //
  // Given   alice로 로그인 후 EMPTY 프로젝트 집계 보고 페이지 SPA 내부 이동
  //         worklog-aggregate-handlers.ts: project=EMPTY → buckets=[] 반환
  // When    페이지 로드
  // Then    빈 상태 메시지(worklogAggregateLabels.empty.message) 표시
  //         차트 / 표 미노출
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 — buckets=[] 빈 상태 메시지 표시', async ({ page }) => {
    // Given. EMPTY 프로젝트 집계 보고 페이지 SPA 내부 이동
    await navigateTo(page, reportUrl(EMPTY_PROJECT_KEY))

    // 페이지 헤더 로드 대기
    await expect(
      page.getByRole('heading', { name: labels.page.title, level: 1 }),
    ).toBeVisible()

    // Then. 빈 상태 메시지 표시
    await expect(page.getByText(labels.empty.message)).toBeVisible()

    // Then. 차트 컨테이너 미노출 (buckets=[] 시 WorklogAggregateChart는 null 반환)
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toHaveCount(0)

    // Then. 테이블 헤더 미노출 (buckets=[] 시 WorklogAggregateReport가 빈 상태 분기로 렌더)
    await expect(page.locator('table')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 — 403 권한 안내
  //
  // Given   alice로 로그인 후 FORBIDDEN 프로젝트 집계 보고 페이지 직접 진입
  //         worklog-aggregate-handlers.ts: project=FORBIDDEN → 403 반환
  //         WorklogAggregateReport: ApiError(403) → ProjectNotFoundScreen 렌더
  // When    페이지 로드
  // Then    "접근 권한이 없습니다" 안내 문구 표시 (ProjectNotFoundScreen)
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 — 403 시 ProjectNotFoundScreen "접근 권한이 없습니다" 표시', async ({ page }) => {
    // Given. FORBIDDEN 프로젝트 집계 보고 페이지 SPA 내부 이동
    // 403 시나리오는 최초 진입 자체가 403이므로 직접 navigateTo로 진입
    await navigateTo(page, reportUrl(FORBIDDEN_PROJECT_KEY))

    // Then. ProjectNotFoundScreen 렌더 확인
    // "접근 권한이 없습니다" 텍스트 — projects.$projectKey.settings.members.tsx ProjectNotFoundScreen
    // exact:true 필수 — 하위 문자열을 포함하는 p.text-sm도 매칭돼 strict mode violation 발생
    await expect(page.getByText(labels.notFound.heading, { exact: true })).toBeVisible()

    // Then. 집계 차트 미노출 (403 에러 분기에서 WorklogAggregateReport가 ProjectNotFoundScreen으로 교체됨)
    // 참고: 라우트 Page 레벨의 h1(워크로그 집계 보고)은 403에서도 남아 있으므로 h1 미노출 단언은 하지 않는다.
    // ProjectWorklogReportPage가 h1을 항상 렌더하고 WorklogAggregateReport를 자식으로 포함하는 구조.
    await expect(page.getByRole('img', { name: labels.chart.ariaLabel })).toHaveCount(0)
    await expect(page.locator('table')).toHaveCount(0)
  })
})
