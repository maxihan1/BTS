// FR-UX-06 Phase 5 PR20 Task 6 E2E — 이슈 목록 split view(좌 목록 + 우 상세 페인)
//
// 시나리오 개요.
//   S1  와이드 뷰포트에서 이슈 행 클릭 → ?selected=<KEY> + 우측 상세 페인(h2) 등장 + 선택 행 aria-current
//   S2  S1 상태에서 닫기 버튼(aria-label="닫기") 클릭 → selected 제거 + 상세 페인 사라짐
//   S3  /issues?selected=<KEY> 직접 진입(와이드) → 처음부터 좌 목록 + 우 상세 렌더
//   S4  split 상태에서 "키" 정렬 헤더 클릭 → selected 보존(상세 페인 유지) + 정렬도 반영
//   S5  좁은 뷰포트에서 행 클릭 → `/issues/<KEY>` 전체화면 이동(split 미표시)
//   H1  split 와이드에서 문서 h1은 목록 제목("이슈 목록") 1개뿐 — 상세는 h2로 강등
//
// 설계 결정.
//   - playwright.config.ts 기본 프로젝트(Desktop Chrome)의 기본 뷰포트는 1280x720으로
//     이미 split 분기 기준(min-width: 1024px)을 넘지만, 이 기능 자체가 뷰포트 경계에
//     의존하므로 두 describe 블록에서 test.use({ viewport })로 명시해 향후 config 기본값이
//     바뀌어도 시나리오 의도가 유지되게 한다.
//   - 대상 이슈는 ATLAS-1(issueAtlas1Fixture, 기본 4건 시드 중 하나)을 사용한다. summary는
//     mocks/issue-fixtures.ts 정본 문자열을 그대로 상수로 고정한다(변경 시 이 파일도 함께
//     실패해 drift를 즉시 드러낸다 — issue-changelog.spec.ts labels 상수 관례 미러).
//   - 셀렉터는 issue-table.spec.ts(PR18) 계약을 그대로 재사용한다 — 테이블 컨테이너
//     (`getByRole('table', {name:'이슈 목록'})`), 행 요약(`data-testid=issue-summary-{key}`),
//     "키" 정렬 헤더 버튼. 선택 행은 tr.filter({has: 요약 testid})로 한정해 aria-current를 검사한다.
//   - MSW/로그인은 기존 issue-table.spec.ts와 동일 패턴(loginAsAlice + 기본 4건 시드).
//   - playwright-getbyrole-exact-strict-mode 회피 — "닫기"/"키" 버튼은 exact:true 사용.
//
// 회귀 대조(기존 e2e, 이 태스크에서 직접 실행·확인만 — 별도 보고, 이 spec에는 포함하지 않음).
//   - issue-table.spec.ts S1(91행)이 이 PR의 split view 구현으로 인해 실패한다: 와이드 뷰포트에서
//     행(요약 셀) 클릭 시 기존 기대값 `/issues/ATLAS-1` 전체 이동 대신 `?selected=ATLAS-1`로만
//     URL이 바뀌어 `page.waitForURL(/\/issues\/ATLAS-1$/)`가 30초 타임아웃으로 실패한다
//     (S2~S5는 체크박스 selection 경유라 영향 없음, 4/5 green). 구현 코드는 이 태스크의 수정
//     대상이 아니므로 그대로 두고 controller에 보고한다.
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueAtlas1Fixture } from '../src/mocks/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** ATLAS 이슈 목록 URL */
const ISSUES_URL = '/issues'

/** split view 검증 대상 이슈 키 — 기본 4건 시드 중 하나 */
const TARGET_KEY = issueAtlas1Fixture.key

/** split view 검증 대상 이슈 요약(정본 fixture 참조 — 하드코딩 금지) */
const TARGET_SUMMARY = issueAtlas1Fixture.summary

/** split 분기 기준(min-width: 1024px)을 넘는 와이드 뷰포트 */
const WIDE_VIEWPORT = { width: 1280, height: 900 }

/** split 분기 기준 아래의 좁은 뷰포트 */
const NARROW_VIEWPORT = { width: 800, height: 900 }

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 목록 테이블 컨테이너 로케이터 — IssueTable: <table aria-label="이슈 목록"> */
function getTableLocator(page: Page) {
  return page.getByRole('table', { name: '이슈 목록' })
}

/** 기본 4건(ATLAS-1/2/3/5)이 모두 로딩될 때까지 대기한다(issue-table.spec.ts 관례 미러). */
async function waitForDefaultFourIssues(page: Page): Promise<void> {
  await expect(page.getByTestId('issue-summary-ATLAS-1')).toBeVisible()
  await expect(page.getByTestId('issue-summary-ATLAS-2')).toBeVisible()
  await expect(page.getByTestId('issue-summary-ATLAS-3')).toBeVisible()
  await expect(page.getByTestId('issue-summary-ATLAS-5')).toBeVisible()
}

/** "키" 정렬 헤더 버튼 로케이터 — 테이블 컨테이너로 한정(strict mode 방지). */
function getKeySortHeaderButton(page: Page) {
  return getTableLocator(page).getByRole('button', { name: '키', exact: true })
}

/**
 * 지정한 key의 이슈 행(<tr>) 로케이터 — 요약 셀 testid를 포함하는 행으로 한정한다.
 * aria-current 강조 검증(getCurrentRowAttrs, IssueTable.tsx)에 사용한다.
 */
function getRowByKey(page: Page, key: string) {
  return getTableLocator(page)
    .locator('tr')
    .filter({ has: page.getByTestId(`issue-summary-${key}`) })
}

/** 상세 페인 제목(h2) 로케이터 — variant='pane'일 때만 렌더된다(IssueDetailPage). */
function getPaneHeading(page: Page) {
  return page.getByRole('heading', { level: 2, name: TARGET_SUMMARY })
}

/** 상세 페인 닫기 버튼 — /issues 페이지 내에서 유일한 aria-label="닫기" 요소. */
function getCloseButton(page: Page) {
  return page.getByRole('button', { name: '닫기', exact: true })
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite — 와이드 뷰포트(≥1024px, split view 활성)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-06 Phase 5 PR20 이슈 목록 split view — 와이드 뷰포트', () => {
  test.use({ viewport: WIDE_VIEWPORT })

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1. 행 클릭 → split view 오픈
  //
  // Given  alice 로그인 + /issues 진입(와이드 뷰포트)
  // When   ATLAS-1 행(요약 셀)을 클릭하면
  // Then   URL이 ?selected=ATLAS-1을 포함하고, 우측에 상세 페인(제목이 h2)이 표시되며,
  //        선택된 행에 aria-current="true"가 부여된다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 이슈 행 클릭 → ?selected=KEY + 우측 상세 페인(h2) + 선택 행 aria-current', async ({ page }) => {
    // Given. /issues 진입 + 기본 4건 로딩 대기
    await page.goto(ISSUES_URL)
    await waitForDefaultFourIssues(page)

    // When. ATLAS-1 행(요약 셀) 클릭
    await page.getByTestId(`issue-summary-${TARGET_KEY}`).click()

    // Then. URL에 selected 파라미터 반영
    await expect(page).toHaveURL(new RegExp(`[?&]selected=${TARGET_KEY}`))

    // Then. 우측 상세 페인 — 제목이 h2로 표시(문서 h1 단일 계약)
    await expect(getPaneHeading(page)).toBeVisible()

    // Then. 선택된 행 aria-current 강조
    await expect(getRowByKey(page, TARGET_KEY)).toHaveAttribute('aria-current', 'true')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 닫기 버튼 → split view 종료
  //
  // Given  /issues?selected=ATLAS-1 상태(상세 페인 열림)에서
  // When   닫기 버튼(aria-label="닫기")을 클릭하면
  // Then   URL에서 selected 파라미터가 제거되고, 상세 페인(h2)이 사라진다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 닫기 버튼 클릭 → selected 제거 + 상세 페인 사라짐', async ({ page }) => {
    // Given. selected 상태로 진입 — 상세 페인 열림 확인
    await page.goto(`${ISSUES_URL}?selected=${TARGET_KEY}`)
    await expect(getPaneHeading(page)).toBeVisible()

    // When. 닫기 버튼 클릭
    await getCloseButton(page).click()

    // Then. URL에서 selected 파라미터 제거
    await expect(page).not.toHaveURL(/[?&]selected=/)

    // Then. 상세 페인(h2) 사라짐
    await expect(getPaneHeading(page)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. 직접 진입 시 초기 split 렌더
  //
  // Given  /issues?selected=ATLAS-1 URL로 직접 진입하면(와이드 뷰포트)
  // Then   처음부터 좌측 목록(테이블) + 우측 상세 페인이 함께 렌더된다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 /issues?selected=KEY 직접 진입 → 처음부터 좌 목록 + 우 상세 렌더', async ({ page }) => {
    // Given/When. selected 파라미터 포함 URL로 직접 진입
    await page.goto(`${ISSUES_URL}?selected=${TARGET_KEY}`)

    // Then. 좌측 목록(테이블) 렌더
    await expect(getTableLocator(page)).toBeVisible()

    // Then. 우측 상세 페인 렌더(h2)
    await expect(getPaneHeading(page)).toBeVisible()

    // Then. 선택 행 aria-current 강조
    await expect(getRowByKey(page, TARGET_KEY)).toHaveAttribute('aria-current', 'true')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. split 상태 유지 — 정렬 헤더 클릭
  //
  // Given  /issues?selected=ATLAS-1 상태(상세 페인 열림)에서
  // When   "키" 정렬 헤더를 클릭하면
  // Then   URL의 sort 파라미터가 반영되면서도 selected 파라미터가 그대로 보존되고,
  //        상세 페인이 계속 표시된다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 split 상태에서 "키" 정렬 헤더 클릭 → selected 보존(상세 페인 유지)', async ({ page }) => {
    // Given. selected 상태로 진입 + 테이블 로딩 대기
    await page.goto(`${ISSUES_URL}?selected=${TARGET_KEY}`)
    await waitForDefaultFourIssues(page)
    await expect(getPaneHeading(page)).toBeVisible()

    // When. "키" 정렬 헤더 클릭
    await getKeySortHeaderButton(page).click()

    // Then. sort 파라미터 반영 + selected 파라미터 보존
    await expect(page).toHaveURL(/[?&]sort=key%2Casc/)
    await expect(page).toHaveURL(new RegExp(`[?&]selected=${TARGET_KEY}`))

    // Then. 상세 페인 계속 표시
    await expect(getPaneHeading(page)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // H1. 문서 h1 단일 계약
  //
  // Given  split view가 열린 와이드 상태(목록 h1 + 상세 h2 공존)에서
  // Then   문서 전체에서 heading level=1은 정확히 1개("이슈 목록", 목록 제목)뿐이다.
  //        상세 제목은 h2로 강등되어 있어 h1 중복이 발생하지 않는다.
  // ───────────────────────────────────────────────────────────────────────────
  test('H1 split 와이드에서 문서 h1은 목록 제목 1개뿐(상세는 h2)', async ({ page }) => {
    // Given. selected 상태로 진입 — 상세 페인(h2) 렌더 확인
    await page.goto(`${ISSUES_URL}?selected=${TARGET_KEY}`)
    await expect(getPaneHeading(page)).toBeVisible()

    // Then. 문서 전체 h1 정확히 1개
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)

    // Then. 그 h1은 목록 제목("이슈 목록")이다
    await expect(page.getByRole('heading', { level: 1, name: '이슈 목록', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Suite — 좁은 뷰포트(<1024px, split view 비활성)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-06 Phase 5 PR20 이슈 목록 split view — 좁은 뷰포트', () => {
  test.use({ viewport: NARROW_VIEWPORT })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. 좁은 뷰포트 — 전체화면 이동(split 미표시)
  //
  // Given  alice 로그인 + /issues 진입(좁은 뷰포트, <1024px)
  // When   ATLAS-1 행(요약 셀)을 클릭하면
  // Then   split view가 아니라 `/issues/ATLAS-1` 전체화면 상세로 이동한다.
  //        URL에 ?selected 쿼리는 남지 않는다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 좁은 뷰포트에서 행 클릭 → /issues/<KEY> 전체화면 이동(split 미표시)', async ({ page }) => {
    // Given. alice 로그인 + /issues 진입 + 기본 4건 로딩 대기
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)
    await waitForDefaultFourIssues(page)

    // When. ATLAS-1 행(요약 셀) 클릭
    await page.getByTestId(`issue-summary-${TARGET_KEY}`).click()

    // Then. 전체화면 상세 경로로 이동(split view 아님)
    await page.waitForURL(new RegExp(`/issues/${TARGET_KEY}$`))
    expect(page.url()).not.toContain('selected=')

    // Then. 전체화면 상세는 제목이 h1(variant='page')
    await expect(page.getByRole('heading', { level: 1, name: TARGET_SUMMARY })).toBeVisible()
  })
})
