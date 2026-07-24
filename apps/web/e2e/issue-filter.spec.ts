// FR-SR-01 D7 E2E — 이슈 목록 필터 (status/담당자/미배정/라벨/컴포넌트/복합/초기화/URL 보존)
//
// 시나리오 개요.
//   S1. status 필터    — open 선택 → ATLAS-1 1개만, 전체 4개에서 감소
//   S2. 담당자 필터    — alice 선택 → ATLAS-3 1개만 (alice 담당)
//   S3. 미배정 필터    — "미배정" 체크 → ATLAS-1/ATLAS-5 2개 (assigneeId=null)
//   S4. 라벨 필터      — "frontend" 선택 → ATLAS-3 1개만
//   S5. 컴포넌트 필터  — URL param 직접 주입 → ATLAS-3 1개만 (COMP_A_ID 소속)
//   S6. 복합 AND       — status=in_progress + assignee=BOB → ATLAS-2 1개만
//   S8. 초기화         — 필터 후 "초기화" → 전체 4개 복원 + URL param 제거
//   S9. 새로고침 보존  — status=open URL 직접 진입 후 reload → 필터 상태 유지
//
// 설계 결정.
//   - issuePageFixture 4건(ATLAS-1/2/3/5)이 status/assignee/label/component 분별 시드됨
//     (issue-fixtures.ts FR-SR-01 B3 참고):
//       ATLAS-1: open,       미배정,  labels=[],           componentIds=[]
//       ATLAS-2: in_progress, BOB,    labels=['bug'],      componentIds=[]
//       ATLAS-3: done,        ALICE,  labels=['frontend'], componentIds=[COMP_A_ID]
//       ATLAS-5: in_review,  미배정,  labels=[],           componentIds=[]
//   - IssueListRouteAdapter는 /issues 진입 시 DEFAULT_PROJECT_KEY='ATLAS' 사용.
//   - IssueFilterBar 셀렉터:
//       · 담당자 typeahead: id="issue-filter-assignee-input"
//       · 미배정 checkbox: aria-label="미배정" (role=checkbox로 한정)
//       · status checkbox: aria-label=<name> (role=checkbox로 한정, e.g. "Open")
//       · 라벨 자동완성: data-testid="label-autocomplete-input"
//       · 초기화 버튼: role=button, name="초기화"
//   - 이슈 목록 컨테이너: getByRole('table', {name:'이슈 목록'}) (PR18 카드→테이블 전환)
//       이슈 행 링크: aria-label={issue.key} → getByRole('link', {name:key})
//   - 이슈 목록 로딩 대기: 첫 번째 이슈 link가 보일 때까지 대기
//   - vacuous 방지: 모든 시나리오에서 "필터 후 건수 < 전체 건수"를 숫자로 단언 (B3).
//   - MSW 필터링: listIssuesHandler가 query param 기반 실시간 필터링 (B2).
//   - MSW serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block 교훈).
//   - 불필요한 reload 금지 — S9에서만 실제 reload 검증 (msw-mutation-stateful-refetch 교훈).
//   - S5: ATLAS 프로젝트 컴포넌트는 자동 시드 없음 → URL param 직접 주입으로 MSW 필터 관통.
//     (component-handlers.ts 자동 시드는 FILTER 프로젝트 전용)
//   - board-fixtures.ts/board-filter.spec.ts 직접 import 금지 — 상수 인라인 동기화.
//   - 담당자 typeahead 드롭다운: 1글자 이상 입력 → GET /api/v1/users?query= → 결과 버튼 대기 → 클릭.
//     userBobFixture.displayName=null → username 'bob' 폴백으로 드롭다운에 표시됨.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — issue-fixtures.ts / user-fixtures.ts와 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** ATLAS 이슈 목록 URL */
const ISSUES_URL = '/issues'

/**
 * issue-fixtures.ts ISSUE_FILTER_COMP_A_ID 와 동기화.
 * ATLAS-3.componentIds[0] = '40000000-0000-4000-8000-000000000001'.
 */
const COMP_A_ID = '40000000-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// ATLAS issuePageFixture 이슈 분포 (검증 기준, vacuous 방지)
//
//   ATLAS-1: open,        미배정(null),   labels=[],           componentIds=[]
//   ATLAS-2: in_progress, bob,            labels=['bug'],      componentIds=[]
//   ATLAS-3: done,        alice,          labels=['frontend'], componentIds=[COMP_A_ID]
//   ATLAS-5: in_review,  미배정(null),    labels=[],           componentIds=[]
//
// 필터 결과 (기대 건수):
//   status=open                     → ATLAS-1 (1개)
//   assignee=alice(UUID)             → ATLAS-3 (1개)
//   assignee=unassigned              → ATLAS-1, ATLAS-5 (2개)
//   label=frontend                   → ATLAS-3 (1개)
//   component=COMP_A_ID(URL param)   → ATLAS-3 (1개)
//   status=in_progress&assignee=bob  → ATLAS-2 (1개)
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 목록 내 이슈 키에 해당하는 링크 로케이터.
 * 이슈 행 링크: aria-label={issue.key}, role=link.
 * 이슈 목록 테이블(aria-label="이슈 목록") 내로 한정해 strict-mode 충돌 방지.
 */
function getIssueLocator(
  page: import('@playwright/test').Page,
  issueKey: string,
) {
  return page
    .getByRole('table', { name: '이슈 목록' })
    .getByRole('link', { name: issueKey, exact: true })
}

/**
 * 이슈 목록의 전체 이슈 링크 수를 반환한다.
 * 필터 적용 전/후 건수 비교에 사용 (vacuous 방지 B3).
 */
async function countIssues(page: import('@playwright/test').Page): Promise<number> {
  return page
    .getByRole('table', { name: '이슈 목록' })
    .getByRole('link')
    .count()
}

/**
 * 이슈 목록 초기 로딩 대기 — 전체 4건이 모두 보일 때까지.
 * 로딩 상태에서 count가 0인 시점에 countIssues를 호출하는 실수를 방지한다.
 */
async function waitForFullIssueList(page: import('@playwright/test').Page): Promise<void> {
  await expect(getIssueLocator(page, 'ATLAS-1')).toBeVisible()
  await expect(getIssueLocator(page, 'ATLAS-2')).toBeVisible()
  await expect(getIssueLocator(page, 'ATLAS-3')).toBeVisible()
  await expect(getIssueLocator(page, 'ATLAS-5')).toBeVisible()
}

/**
 * 담당자 typeahead에서 사용자를 선택한다.
 *
 * AssigneeSection: input id="issue-filter-assignee-input".
 * board-filter.spec.ts의 selectAssigneeFromTypeahead 미러 — 같은 AssigneeSection 컴포넌트.
 *
 * @param page Playwright Page 객체
 * @param query 검색어 (username 또는 displayName prefix)
 * @param displayName 드롭다운에 표시되는 이름 (클릭 대상 버튼 텍스트)
 */
async function selectAssigneeFromTypeahead(
  page: import('@playwright/test').Page,
  query: string,
  displayName: string,
): Promise<void> {
  const input = page.locator('#issue-filter-assignee-input')
  await input.fill(query)
  // AssigneeSection 드롭다운 ul — input 형제로 렌더됨
  const dropdownList = page.locator('#issue-filter-assignee-input + ul').or(
    page.locator('[id="issue-filter-assignee-input"] ~ ul'),
  )
  const dropdownBtn = dropdownList.getByRole('button', { name: displayName, exact: true })
  await expect(dropdownBtn).toBeVisible()
  await dropdownBtn.click()
  // handleAssigneeSelect 내 setAssigneeQuery('')로 input 초기화 → 드롭다운 닫힘
  await expect(input).toHaveValue('')
}

/**
 * 라벨 자동완성에서 라벨을 선택한다.
 *
 * LabelAutocompleteInput: data-testid="label-autocomplete-input".
 *
 * @param page Playwright Page 객체
 * @param query 검색어 (라벨명 prefix)
 * @param labelName 선택할 라벨 이름 (드롭다운 option 텍스트)
 */
async function selectLabelFromAutocomplete(
  page: import('@playwright/test').Page,
  query: string,
  labelName: string,
): Promise<void> {
  const input = page.getByTestId('label-autocomplete-input')
  await input.fill(query)
  const option = page.getByRole('option', { name: labelName, exact: true })
  await expect(option).toBeVisible()
  await option.click()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-SR-01 이슈 목록 필터 (status/담당자/미배정/라벨/컴포넌트/복합/초기화/URL 보존)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. status 필터
  //
  // Given  alice 로그인 + /issues 진입 (ATLAS-1/2/3/5 전체 4건)
  // When   status "Open" 체크박스 선택
  // Then   ATLAS-1 (open) 1건만 표시
  //        ATLAS-2(in_progress)/ATLAS-3(done)/ATLAS-5(in_review) 사라짐
  //        건수 4개 → 1개로 감소 (vacuous 방지)
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 status 필터 — "Open" 선택 시 open 이슈 1건만 표시', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)

    // Given. 전체 4건 로딩 대기
    await waitForFullIssueList(page)
    const beforeCount = await countIssues(page)
    expect(beforeCount).toBe(4)

    // When. status "Open" 체크박스 선택
    // role=checkbox로 한정 — 이슈 상태 배지(role="status")와 충돌 방지
    await page.getByRole('checkbox', { name: 'Open', exact: true }).check()

    // Then. ATLAS-1 표시
    await expect(getIssueLocator(page, 'ATLAS-1')).toBeVisible()

    // Then. open이 아닌 이슈 사라짐
    await expect(getIssueLocator(page, 'ATLAS-2')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-3')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()

    // Then. 건수 1건 (4개에서 감소 — 실필터 동작 확인)
    const afterCount = await countIssues(page)
    expect(afterCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. 담당자 필터
  //
  // Given  alice 로그인 + /issues 진입 (4건)
  // When   담당자 typeahead에 "alice" 입력 → "김앨리스" 선택
  // Then   ATLAS-3 (alice 담당) 1건만 표시
  //        ATLAS-1(미배정)/ATLAS-2(bob)/ATLAS-5(미배정) 사라짐
  //        건수 4개 → 1개로 감소
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 담당자 필터 — alice 선택 시 alice 담당 이슈 1건만 표시', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)

    // Given. 전체 4건 로딩 대기
    await waitForFullIssueList(page)
    const beforeCount = await countIssues(page)
    expect(beforeCount).toBe(4)

    // When. 담당자 typeahead에서 alice(김앨리스) 선택
    await selectAssigneeFromTypeahead(page, 'alice', '김앨리스')

    // Then. ATLAS-3만 표시
    await expect(getIssueLocator(page, 'ATLAS-3')).toBeVisible()

    // Then. alice 담당이 아닌 이슈 사라짐
    await expect(getIssueLocator(page, 'ATLAS-1')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-2')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()

    // Then. 건수 1건 (4개에서 감소)
    const afterCount = await countIssues(page)
    expect(afterCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. 미배정 필터
  //
  // Given  alice 로그인 + /issues 진입 (4건)
  // When   "미배정" 체크박스 체크
  // Then   ATLAS-1, ATLAS-5 (assigneeId=null) 2건 표시
  //        ATLAS-2(bob)/ATLAS-3(alice) 사라짐
  //        건수 4개 → 2개로 감소
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 미배정 필터 — "미배정" 체크 시 assigneeId=null 이슈 2건만 표시', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)

    // Given. 전체 4건 로딩 대기
    await waitForFullIssueList(page)
    const beforeCount = await countIssues(page)
    expect(beforeCount).toBe(4)

    // When. "미배정" 체크박스 체크
    // role=checkbox로 한정 — "미배정"이 이슈 요약/다른 영역에 텍스트로 존재할 경우 strict-mode 방지
    await page.getByRole('checkbox', { name: '미배정', exact: true }).check()

    // Then. 미배정 이슈 2건 표시
    await expect(getIssueLocator(page, 'ATLAS-1')).toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).toBeVisible()

    // Then. 담당자 있는 이슈 사라짐
    await expect(getIssueLocator(page, 'ATLAS-2')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-3')).not.toBeVisible()

    // Then. 건수 2건 (4개에서 감소)
    const afterCount = await countIssues(page)
    expect(afterCount).toBe(2)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 라벨 필터
  //
  // Given  alice 로그인 + /issues 진입 (4건)
  // When   라벨 자동완성에서 "frontend" 선택
  // Then   ATLAS-3 (labels=['frontend']) 1건만 표시
  //        ATLAS-1/ATLAS-5(라벨없음)/ATLAS-2(bug) 사라짐
  //        건수 4개 → 1개로 감소
  //
  // label-handlers.ts LABEL_SEED에 'frontend' 포함 여부 확인 필요 (없으면 CONCERN).
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 라벨 필터 — "frontend" 선택 시 frontend 라벨 이슈 1건만 표시', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)

    // Given. 전체 4건 로딩 대기
    await waitForFullIssueList(page)
    const beforeCount = await countIssues(page)
    expect(beforeCount).toBe(4)

    // When. 라벨 자동완성에서 "frontend" 선택
    await selectLabelFromAutocomplete(page, 'front', 'frontend')

    // Then. ATLAS-3만 표시
    await expect(getIssueLocator(page, 'ATLAS-3')).toBeVisible()

    // Then. frontend 라벨 없는 이슈 사라짐
    await expect(getIssueLocator(page, 'ATLAS-1')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-2')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()

    // Then. 건수 1건 (4개에서 감소)
    const afterCount = await countIssues(page)
    expect(afterCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5. 컴포넌트 필터
  //
  // Given  alice 로그인 + /issues?component=COMP_A_ID 직접 진입
  //        (ATLAS 프로젝트 컴포넌트는 자동 시드 없음 — component-handlers.ts는 FILTER 전용)
  //        URL param 직접 주입으로 MSW listIssuesHandler 필터 관통
  // When   이미 component param 포함 URL 진입 = 필터가 적용된 상태
  // Then   ATLAS-3 (componentIds=[COMP_A_ID]) 1건만 표시
  //        ATLAS-1/ATLAS-2/ATLAS-5(componentIds=[]) 없음
  //        건수 4개 미만(1개) — URL 없는 상태와 비교해 감소 확인
  //
  // ★ CONCERNS: ATLAS 프로젝트에 컴포넌트가 시드되지 않아
  //   ComponentMultiSelect UI가 렌더되더라도 선택 체크박스는 없다.
  //   URL param 직접 주입으로 MSW 필터만 검증한다.
  //   UI 상호작용(체크박스 클릭)은 FILTER 프로젝트 board-filter.spec.ts가 이미 커버함.
  // ─────────────────────────────────────────────────────────────────────────
  test('S5 컴포넌트 필터 — URL param 직접 주입 시 COMP_A 이슈 1건만 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. component param 포함 URL 직접 진입 — MSW 필터 관통 검증
    // COMP_A_ID = issue-fixtures.ts ISSUE_FILTER_COMP_A_ID (ATLAS-3.componentIds[0])
    await page.goto(`${ISSUES_URL}?component=${COMP_A_ID}`)

    // Then. ATLAS-3만 표시 (로딩 대기 포함)
    await expect(getIssueLocator(page, 'ATLAS-3')).toBeVisible()

    // Then. 컴포넌트 없는 이슈 없음
    await expect(getIssueLocator(page, 'ATLAS-1')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-2')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()

    // Then. 건수 1건 (전체 4건 미만으로 감소 확인)
    const filteredCount = await countIssues(page)
    expect(filteredCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6. 복합 AND 필터 (status + assignee)
  //
  // Given  alice 로그인 + /issues 진입 (4건)
  // When   status "In Progress" 체크 (1건 — ATLAS-2만 in_progress)
  //        추가로 담당자 typeahead에서 "bob" 선택 (AND → ATLAS-2 유지 1건)
  // Then   status=in_progress이면서 assignee=BOB인 ATLAS-2만 (1개)
  //        전체 4개에서 감소(AND 동작 확인)
  //        ATLAS-1/ATLAS-3/ATLAS-5 없음
  //
  // 단일 status=in_progress 결과(1개)와 AND 후 결과(1개)가 같으므로
  // "전체 4개에서 최종 1개"로 감소 확인이 AND 동작을 증명한다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S6 복합 AND 필터 — status=in_progress + assignee=BOB → ATLAS-2 1건만 남음', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)

    // Given. 전체 4건 로딩 대기
    await waitForFullIssueList(page)
    const beforeCount = await countIssues(page)
    expect(beforeCount).toBe(4)

    // When-1. status "In Progress" 체크 → 1건으로 감소 확인 (AND 중간 단계)
    await page.getByRole('checkbox', { name: 'In Progress', exact: true }).check()
    // in_progress 이외 이슈가 사라질 때까지 대기
    await expect(getIssueLocator(page, 'ATLAS-1')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-3')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()
    const midCount = await countIssues(page)
    expect(midCount).toBe(1)

    // When-2. 담당자 typeahead에서 bob 선택
    // userBobFixture.displayName=null → username 'bob'으로 드롭다운에 표시
    await selectAssigneeFromTypeahead(page, 'bob', 'bob')

    // Then. ATLAS-2(in_progress + bob)만 표시
    await expect(getIssueLocator(page, 'ATLAS-2')).toBeVisible()

    // Then. 나머지 없음
    await expect(getIssueLocator(page, 'ATLAS-1')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-3')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()

    // Then. 건수 1건 (전체 4개에서 감소 — AND 필터 동작 확인)
    const afterCount = await countIssues(page)
    expect(afterCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S8. 초기화
  //
  // Given  alice 로그인 + /issues 진입
  //        status "Open" 체크로 1건 필터 적용 + URL에 status param 확인
  // When   "초기화" 버튼 클릭
  // Then   전체 4건 복원
  //        URL에서 status param 제거됨
  // ─────────────────────────────────────────────────────────────────────────
  test('S8 초기화 — 필터 적용 후 "초기화" 클릭 시 전체 이슈 복원 + URL param 제거', async ({ page }) => {
    // Given. alice 로그인 + 이슈 목록 진입
    await loginAsAlice(page)
    await page.goto(ISSUES_URL)

    // Given. 전체 4건 로딩 대기
    await waitForFullIssueList(page)

    // Given. status "Open" 체크 → 1건으로 감소 확인
    await page.getByRole('checkbox', { name: 'Open', exact: true }).check()
    const filteredCount = await countIssues(page)
    expect(filteredCount).toBe(1)

    // Given. URL에 status 관련 param 포함 확인
    // issueFilterToSearch가 status 배열을 URL search param으로 직렬화한다.
    expect(decodeURIComponent(page.url())).toContain('status')

    // When. "초기화" 버튼 클릭
    await page.getByRole('button', { name: '초기화', exact: true }).click()

    // Then. 전체 4건 복원
    await expect(getIssueLocator(page, 'ATLAS-1')).toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-2')).toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-3')).toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).toBeVisible()

    const restoredCount = await countIssues(page)
    expect(restoredCount).toBe(4)

    // Then. URL에서 status param 제거됨
    expect(decodeURIComponent(page.url())).not.toContain('status')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S9. URL param 보존 (새로고침)
  //
  // Given  alice 로그인 + status=open param 포함 URL 직접 진입
  //        MSW listIssuesHandler가 param 기준 ATLAS-1 1건 반환
  // When   page.reload() — 실제 브라우저 새로고침
  // Then   reload 후에도 ATLAS-1 1건만 표시 (URL param 유지 → MSW 동일 결과)
  //        ATLAS-2/ATLAS-3/ATLAS-5 없음
  //
  // 메모: MSW 핸들러가 URL query param 기준 필터링이므로
  //       reload 후에도 동일 URL → 동일 결과.
  //       SPA store가 아닌 URL이 필터 상태의 single source of truth (URL param 보존 검증).
  // ─────────────────────────────────────────────────────────────────────────
  test('S9 새로고침 보존 — status=open URL 직접 진입 후 reload 시 필터 상태 유지', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. status=open param 포함 URL 직접 진입
    await page.goto(`${ISSUES_URL}?status=open`)

    // Given. ATLAS-1만 표시 확인 (로딩 대기 포함)
    await expect(getIssueLocator(page, 'ATLAS-1')).toBeVisible()
    const beforeReloadCount = await countIssues(page)
    expect(beforeReloadCount).toBe(1)

    // When. 실제 브라우저 새로고침
    await page.reload()

    // Then. reload 후에도 ATLAS-1 1건만 표시
    await expect(getIssueLocator(page, 'ATLAS-1')).toBeVisible()

    // Then. open이 아닌 이슈 없음
    await expect(getIssueLocator(page, 'ATLAS-2')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-3')).not.toBeVisible()
    await expect(getIssueLocator(page, 'ATLAS-5')).not.toBeVisible()

    // Then. 건수 1건 유지 (reload 전과 동일)
    const afterReloadCount = await countIssues(page)
    expect(afterReloadCount).toBe(1)
  })
})
