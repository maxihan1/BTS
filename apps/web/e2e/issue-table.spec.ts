// FR-UX-06 Phase 5 PR18 Task 6 E2E — 이슈 목록 테이블 전환(정렬·컬럼 선택·필터+정렬)
//
// 시나리오 개요.
//   S1. 테이블 렌더       — <table> 시맨틱 + 행 클릭 상세 이동 + 체크박스 클릭 전파 차단
//   S2. 서버 정렬         — "키" 헤더 클릭 → ?sort=key,<dir> + 순서 변경, asc→desc→해제 3-state
//   S3. 정렬 유지 페이지 이동 — 정렬 상태로 "다음" 클릭 시 sort 유지 + 2페이지 표시
//   S4. 컬럼 선택         — "우선순위" 컬럼 토글 → 표시 변경 + reload 후에도 유지(localStorage)
//   S5. 필터 + 정렬 동시   — "미배정" 필터 적용 상태에서 "키" 정렬 → 둘 다 반영
//
// 설계 결정.
//   - 정렬 검증에 "우선순위" 대신 "키" 헤더를 사용한다. 기본 4건(ATLAS-1/2/3/5)의
//     priority 값이 전부 3(Medium)으로 동일해 우선순위 정렬은 순서 변화를 명확히
//     증명하지 못한다(vacuous 위험, [[spec-stated-count-becomes-blindfold]] 연장).
//     반면 key 값은 4건 모두 상이해 asc/desc 뒤집힘을 뚜렷하게 검증할 수 있다.
//   - S3(정렬 유지 페이지 이동)은 기본 4건만으로는 항상 1페이지(size=20>4)라 "다음"
//     버튼이 비활성 상태라 실제 클릭이 불가능하다. issue-handlers.ts의
//     LS_KEY_PAGINATION_EXTRA_ISSUES 플래그(addInitScript로 주입)를 켜면 추가 20건이
//     포함돼 총 24건(2페이지)이 되어 실제 페이지 이동을 검증할 수 있다. 플래그
//     미설정 시 다른 시나리오(S1/S2/S4/S5)는 기존 4건 그대로 영향받지 않는다.
//   - 이슈 목록 컨테이너 셀렉터가 role=list(구 <ul>)에서 role=table(신 <table>)로
//     바뀌었다(이번 PR18의 의도된 변경). 체크박스(data-testid=select-{key})·요약
//     (data-testid=issue-summary-{key})·행 링크(aria-label={key})·상태(role=status)는
//     기존 IssueCard 계약을 verbatim 보존한다(★e2e 셀렉터 보존, plan G2).
//   - MSW serviceWorkers:'block' 금지 ([[e2e-msw-serviceworker-block]]).
//   - localStorage 시나리오 플래그는 addInitScript로 loginAsAlice 이후·goto 이전에
//     주입한다 ([[e2e-msw-scenario-toggle-localstorage-flag]]).
//
// 회귀 대조(기존 e2e, 본 PR에서 직접 실행·확인만 — 별도 보고).
//   - issue-bulk-operations.spec.ts(선택/일괄작업) — data-testid 기반이라 무회귀, 5/5 green.
//   - issue-filter.spec.ts / issue-crud-happy.spec.ts — 카드→테이블 전환으로 컨테이너
//     role이 list→table로 바뀌어 getByRole('list',{name:'이슈 목록'}) 의존이 깨졌던 것을
//     같은 PR에서 getByRole('table',{name:'이슈 목록'})로 봉합함(filter 8 + crud 1 green).
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { LS_KEY_PAGINATION_EXTRA_ISSUES } from '../src/mocks/issue-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** ATLAS 이슈 목록 URL */
const ISSUES_URL = '/issues'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 목록 테이블 컨테이너 로케이터 — IssueTable: <table aria-label="이슈 목록"> */
function getTableLocator(page: Page) {
  return page.getByRole('table', { name: '이슈 목록' })
}

/**
 * 테이블에 현재 렌더된 이슈 키를 DOM 순서 그대로 반환한다.
 * 요약 셀 data-testid={issue-summary-<key>}에서 key를 역추출한다(정렬 순서 검증용).
 */
async function orderedIssueKeys(page: Page): Promise<string[]> {
  const testIds = await page
    .locator('[data-testid^="issue-summary-"]')
    .evaluateAll((elements) => elements.map((el) => el.getAttribute('data-testid') ?? ''))
  return testIds.map((id) => id.replace('issue-summary-', ''))
}

/** 기본 4건(ATLAS-1/2/3/5)이 모두 로딩될 때까지 대기한다. */
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

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-06 Phase 5 PR18 이슈 목록 테이블(정렬·컬럼 선택·필터+정렬)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 테이블 렌더
  //
  // Given  alice 로그인 + /issues 진입 (ATLAS-1/2/3/5 4건)
  // When   테이블이 렌더되면
  // Then   <table> 시맨틱으로 렌더되고, 행(요약 셀) 클릭 시 상세 페이지로 이동한다.
  //        체크박스 클릭은 이벤트 전파를 차단해 상세 이동이 발생하지 않는다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 테이블 렌더 — <table> 시맨틱 + 행 클릭 상세 이동 + 체크박스 클릭 전파 차단', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)
    await waitForDefaultFourIssues(page)

    // Then. <table> 시맨틱(role=table) + 접근 가능한 이름
    await expect(getTableLocator(page)).toBeVisible()

    // When. 체크박스 클릭 (요약 셀 클릭보다 먼저 검증 — 전파 차단 확인)
    await page.getByTestId('select-ATLAS-1').click()

    // Then. 체크박스는 토글됐지만(선택 상태 반영) 상세 페이지로 이동하지 않음
    await expect(page.getByTestId('select-ATLAS-1')).toBeChecked()
    await expect(page).toHaveURL(new RegExp(`${ISSUES_URL}$`))

    // When. 요약 셀(행 영역) 클릭 → 행 전체 클릭 네비게이션
    await page.getByTestId('issue-summary-ATLAS-1').click()

    // Then. 이슈 상세 페이지로 이동
    await page.waitForURL(/\/issues\/ATLAS-1$/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 서버 정렬
  //
  // Given  테이블이 렌더된 상태(기본 순서 ATLAS-1,2,3,5)에서
  // When   "키" 헤더를 클릭하면(asc) → 다시 클릭하면(desc) → 세 번째 클릭하면(해제)
  // Then   URL의 sort 파라미터가 각 단계마다 반영되고, desc 단계에서 목록 순서가
  //        뚜렷하게 뒤집히며, 해제 시 sort 파라미터가 URL에서 사라진다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 서버 정렬 — "키" 헤더 클릭 시 asc→desc 순서 변경 + 3번째 클릭 시 해제', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)
    await waitForDefaultFourIssues(page)

    // When-1. "키" 헤더 첫 클릭 → asc
    await getKeySortHeaderButton(page).click()

    // Then-1. URL에 sort=key,asc 반영 + aria-sort=ascending
    await expect(page).toHaveURL(/[?&]sort=key%2Casc/)
    await expect(page.getByRole('columnheader', { name: '키', exact: true })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )

    // When-2. "키" 헤더 두 번째 클릭 → desc
    await getKeySortHeaderButton(page).click()

    // Then-2. URL에 sort=key,desc 반영 + 순서가 뚜렷하게 뒤집힘(ATLAS-5,3,2,1)
    await expect(page).toHaveURL(/[?&]sort=key%2Cdesc/)
    await expect(page.getByRole('columnheader', { name: '키', exact: true })).toHaveAttribute(
      'aria-sort',
      'descending',
    )
    await expect.poll(() => orderedIssueKeys(page)).toEqual(['ATLAS-5', 'ATLAS-3', 'ATLAS-2', 'ATLAS-1'])

    // When-3. "키" 헤더 세 번째 클릭 → 해제
    await getKeySortHeaderButton(page).click()

    // Then-3. sort 파라미터가 URL에서 제거되고 aria-sort=none으로 복귀
    await expect(page).not.toHaveURL(/[?&]sort=/)
    await expect(page.getByRole('columnheader', { name: '키', exact: true })).toHaveAttribute('aria-sort', 'none')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. 정렬 유지 페이지 이동
  //
  // Given  LS_KEY_PAGINATION_EXTRA_ISSUES 플래그로 총 24건(2페이지) + sort=key,asc 상태에서
  // When   "다음 페이지" 버튼을 클릭하면
  // Then   정렬이 유지된 채(URL sort 파라미터·aria-sort 그대로) 2페이지 내용이 표시된다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 정렬 유지 페이지 이동 — "다음" 클릭 후에도 sort 파라미터·정렬 순서 유지', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 페이지네이션 검증용 추가 이슈 20건 포함 플래그 주입 (loginAsAlice 이후, goto 이전)
    await page.addInitScript(
      (flagKey: string) => {
        window.localStorage.setItem(flagKey, 'true')
      },
      LS_KEY_PAGINATION_EXTRA_ISSUES,
    )

    // Given. sort=key,asc 상태로 진입 → 총 24건 1페이지(20건) 표시
    await page.goto(`${ISSUES_URL}?sort=key,asc`)
    await expect(page.getByTestId('issue-summary-ATLAS-1')).toBeVisible()

    // Given. 1페이지 마지막 항목 확인(정렬 asc: ATLAS-1..5 다음 ATLAS-P01..P16)
    await expect(page.getByTestId('issue-summary-ATLAS-P16')).toBeVisible()
    await expect(page.getByRole('navigation', { name: '페이지 탐색' }).getByText('1 / 2')).toBeVisible()

    // When. "다음 페이지" 버튼 클릭
    await page.getByRole('button', { name: '다음 페이지' }).click()

    // Then. URL에 page=1 + sort=key,asc 모두 유지
    await expect(page).toHaveURL(/[?&]page=1/)
    await expect(page).toHaveURL(/[?&]sort=key%2Casc/)

    // Then. 2페이지(나머지 4건: ATLAS-P17~P20) 표시 + 정렬 상태(aria-sort) 유지
    await expect(page.getByRole('navigation', { name: '페이지 탐색' }).getByText('2 / 2')).toBeVisible()
    await expect(page.getByTestId('issue-summary-ATLAS-P17')).toBeVisible()
    await expect(page.getByTestId('issue-summary-ATLAS-P20')).toBeVisible()
    await expect(page.getByRole('columnheader', { name: '키', exact: true })).toHaveAttribute(
      'aria-sort',
      'ascending',
    )
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. 컬럼 선택
  //
  // Given  테이블이 렌더된 상태(기본 전체 컬럼 표시)에서
  // When   "컬럼" 드롭다운에서 "우선순위" 컬럼을 끄면
  // Then   우선순위 컬럼이 테이블에서 사라지고, 새로고침 후에도 숨김 상태가 유지된다
  //        (localStorage persist). 필수 컬럼("키")은 비활성 처리되어 끌 수 없다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 컬럼 선택 — "우선순위" 컬럼 토글 시 표시 변경 + 새로고침 후 유지', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)
    await waitForDefaultFourIssues(page)

    // Given. 기본 상태 — 우선순위 컬럼 헤더 노출
    await expect(page.getByRole('columnheader', { name: '우선순위', exact: true })).toBeVisible()

    // When. "컬럼" 드롭다운 열기
    await page.getByRole('button', { name: '컬럼', exact: true }).click()

    // Then. 필수 컬럼("키")은 비활성 체크박스 — 숨김 불가(F4)
    await expect(page.getByRole('menuitemcheckbox', { name: '키', exact: true })).toBeDisabled()

    // When. "우선순위" 항목 토글(끄기)
    await page.getByRole('menuitemcheckbox', { name: '우선순위', exact: true }).click()

    // 드롭다운 닫기
    await page.keyboard.press('Escape')

    // Then. 우선순위 컬럼 헤더가 테이블에서 사라짐
    await expect(page.getByRole('columnheader', { name: '우선순위', exact: true })).toHaveCount(0)

    // When. 새로고침
    await page.reload()
    await waitForDefaultFourIssues(page)

    // Then. 새로고침 후에도 우선순위 컬럼 숨김 유지(localStorage persist)
    await expect(page.getByRole('columnheader', { name: '우선순위', exact: true })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. 필터 + 정렬 동시
  //
  // Given  "미배정" 필터 적용 상태(ATLAS-1, ATLAS-5 2건)에서
  // When   "키" 헤더로 정렬하면(asc → desc)
  // Then   필터된 2건 결과 집합 내에서 정렬이 적용되고, 필터가 유지된 채(2건) 순서만 바뀐다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 필터 + 정렬 동시 — "미배정" 필터 적용 상태에서 "키" 정렬 시 둘 다 반영', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)
    await waitForDefaultFourIssues(page)

    // Given. "미배정" 필터 적용 → ATLAS-1, ATLAS-5 2건만 남음
    await page.getByRole('checkbox', { name: '미배정', exact: true }).check()
    await expect(page.getByTestId('issue-summary-ATLAS-1')).toBeVisible()
    await expect(page.getByTestId('issue-summary-ATLAS-5')).toBeVisible()
    await expect(page.getByTestId('issue-summary-ATLAS-2')).not.toBeVisible()
    await expect(page.getByTestId('issue-summary-ATLAS-3')).not.toBeVisible()
    await expect.poll(() => orderedIssueKeys(page)).toEqual(['ATLAS-1', 'ATLAS-5'])

    // When. "키" 헤더 클릭 → asc (필터 유지 상태에서 정렬 시작)
    await getKeySortHeaderButton(page).click()
    await expect(page).toHaveURL(/[?&]sort=key%2Casc/)
    // 필터 param도 URL에 그대로 유지됨(assignee=["unassigned"] — TanStack Router 배열 JSON 직렬화)
    expect(decodeURIComponent(page.url())).toContain('unassigned')

    // When. "키" 헤더 재클릭 → desc
    await getKeySortHeaderButton(page).click()

    // Then. 필터된 2건 그대로(개수 불변) + 순서만 뒤집힘(desc: ATLAS-5, ATLAS-1)
    await expect(page).toHaveURL(/[?&]sort=key%2Cdesc/)
    expect(decodeURIComponent(page.url())).toContain('unassigned')
    await expect.poll(() => orderedIssueKeys(page)).toEqual(['ATLAS-5', 'ATLAS-1'])
  })
})
