// FR-BD-02 D7 E2E — 보드 카드 필터 (담당자/미배정/라벨/컴포넌트/복합/초기화/URL 보존)
//
// 시나리오 개요.
//   S1. 담당자 필터   — alice 선택 → alice 카드 2개만, bob+미배정 카드 제거
//   S2. 미배정 필터   — "미배정" 체크 → assigneeId=null 카드 1개만
//   S3. 라벨 필터     — "bug" 선택 → bug 라벨 카드 2개만 (FILTER-1, FILTER-3)
//   S4. 컴포넌트 필터 — "컴포넌트A" 체크 → c1 카드 2개만 (FILTER-1, FILTER-2)
//   S5. 복합 AND      — alice + documentation 라벨 → FILTER-3 1개만 (alice 단독 2개보다 감소 확인)
//   S7. 초기화        — 필터 적용 후 "초기화" → 전체 4개 + URL param 제거
//   S9. 새로고침 보존 — URL ?assignee= 포함 상태에서 reload → 필터된 카드 유지
//
// 설계 결정.
//   - FILTER_BOARD(board-fixtures.ts)는 모듈 자동 시드(MODE!=='test') 시 seedBoardWithMeta로 등록됨.
//     E2E 진입 시 /projects/FILTER/board?board=FILTER_BOARD_ID URL에서 필터 가능한 카드 4개.
//   - FILTER_BOARD 카드의 assigneeId는 userAliceFixture.id / userBobFixture.id UUID와 동기화됨
//     (board-fixtures.ts 인라인 상수 ALICE_USER_ID / BOB_USER_ID 참조).
//   - FILTER-3의 labels=['bug', 'documentation'] — LABEL_SEED에 있는 값으로 동기화.
//     label-handlers.ts LABEL_SEED에 'docs'가 없고 'documentation'이 있으므로 일치시킨다.
//   - 담당자 typeahead: 1글자 이상 입력 → GET /api/v1/users?query= → 드롭다운 → 클릭.
//   - 라벨 자동완성: LabelAutocompleteInput — 입력 후 드롭다운 결과 클릭.
//   - 컴포넌트 필터: ComponentMultiSelect — GET /api/v1/projects/FILTER/components 체크박스.
//     component-handlers.ts 모듈 로드 시 MODE!=='test' 조건으로 컴포넌트A/컴포넌트B 자동 시드.
//     별도 fetch 시드 불필요. board-fixtures.ts componentIds도 동일 UUID로 동기화.
//   - 카드 수 단언: [aria-roledescription="draggable card"] 전체 카운트.
//     FILTER_BOARD는 컬럼 1개이므로 전체 = TODO 컬럼 내 카드 합계.
//   - MSW 필터가 URL query param 기준 실시간 필터링 → reload 후 동일 URL = 동일 결과.
//     S9에서 reload 가짜그린 없음.
//   - strict-mode: FILTER-N issueKey가 보드 내 유일 → 컨테이너 한정 불필요.
//   - MSW serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block 교훈).
//   - board-fixtures.ts 직접 import 금지 — import.meta.env.MODE로 Node.js 런타임 오류.
//     필요한 상수를 인라인 정의.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { openFilterDropdown } from './fixtures/filter-bar'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts / user-fixtures.ts와 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts FILTER_BOARD.boardId 와 동기화 */
const FILTER_BOARD_ID = '10000000-0000-4000-8000-000000000002'

/** board-fixtures.ts FILTER_BOARD.projectKey 와 동기화 */
const FILTER_PROJECT_KEY = 'FILTER'

/** FILTER_BOARD 보드 URL */
const FILTER_BOARD_URL = `/projects/${FILTER_PROJECT_KEY}/board?board=${FILTER_BOARD_ID}`

/**
 * user-fixtures.ts userAliceFixture.id 와 동기화.
 * board-fixtures.ts ALICE_USER_ID 인라인 상수와도 동기화.
 * FILTER_BOARD 카드 FILTER-1, FILTER-3의 assigneeId.
 */
const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

// COMPONENT_C1_ID / COMPONENT_C2_ID 상수는 E2E 스펙에서 직접 참조하지 않는다.
// component-handlers.ts가 모듈 로드 시 IIFE로 컴포넌트A/컴포넌트B를 자동 시드한다.
// board-fixtures.ts FILTER_BOARD 카드의 componentIds는 같은 UUID로 동기화되어 있다.
// 동기화 기준: component-handlers.ts 내 FILTER 자동 시드 블록 UUID 참조.

// ─────────────────────────────────────────────────────────────────────────────
// FILTER_BOARD 카드 매핑 (검증 기준)
//
//   FILTER-1: alice,  bug,                    c1(컴포넌트A)
//   FILTER-2: bob,    feature,                c1(컴포넌트A) + c2(컴포넌트B)
//   FILTER-3: alice,  bug + documentation,    c2(컴포넌트B)
//   FILTER-4: null,   (없음),                 (없음)
//
// 필터 결과 (vacuous 방지 기준).
//   assignee=alice           → FILTER-1, FILTER-3 (2개)
//   assignee=unassigned      → FILTER-4 (1개)
//   label=bug                → FILTER-1, FILTER-3 (2개)
//   component=c1             → FILTER-1, FILTER-2 (2개)
//   assignee=alice&label=documentation → FILTER-3 (1개, alice 단독 2개에서 감소)
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * aria-roledescription="draggable card" 요소 중 issueKey가 포함된 카드 로케이터.
 * BoardCard: aria-label={`${card.issueKey} — ${card.summary}`}, aria-roledescription="draggable card"
 */
function getCardLocator(
  page: import('@playwright/test').Page,
  issueKey: string,
) {
  return page
    .locator('[aria-roledescription="draggable card"]')
    .filter({ hasText: issueKey })
    .first()
}

/**
 * 보드 내 전체 draggable card 수를 반환한다.
 * 필터 적용 전/후 카드 수 비교에 사용 (vacuous 방지).
 */
async function countCards(page: import('@playwright/test').Page): Promise<number> {
  return page.locator('[aria-roledescription="draggable card"]').count()
}


/**
 * 담당자 typeahead에서 사용자를 선택한다.
 *
 * AssigneeSection: input id="board-filter-assignee-input".
 * getByLabel('담당자') 사용 금지 — 카드의 aria-label="담당자: 김앨리스" 등이 strict-mode 위반 유발.
 * id 셀렉터로 직접 한정한다.
 *
 * 1글자 이상 입력 → GET /api/v1/users?query= → 드롭다운 렌더 → 결과 버튼 대기 → 클릭.
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
  await openFilterDropdown(page, '담당자')
  // id 직접 한정 — getByLabel('담당자')는 카드 aria-label="담당자: ..." 포함 요소와 strict-mode 충돌
  const input = page.locator('#board-filter-assignee-input')
  await input.fill(query)
  // AssigneeSection 드롭다운: sibling ul — id=board-filter-assignee-input 다음 ul로 한정
  const dropdownList = page.locator('#board-filter-assignee-input + ul').or(
    page.locator('[id="board-filter-assignee-input"] ~ ul'),
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
 * getByLabel('라벨') 사용 금지 — 카드의 aria-label="... bug 라벨, ..." 등이 strict-mode 위반 유발.
 * data-testid로 직접 한정한다.
 *
 * 입력 → GET /api/v1/labels?q= → 드롭다운 option 대기 → 클릭.
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
  await openFilterDropdown(page, '라벨')
  // data-testid 직접 한정 — getByLabel('라벨')는 카드 aria-label="... 라벨 ..." 포함 요소와 충돌
  const input = page.getByTestId('label-autocomplete-input')
  await input.fill(query)
  const option = page.getByRole('option', { name: labelName, exact: true })
  await expect(option).toBeVisible()
  await option.click()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-BD-02 보드 카드 필터 (담당자/미배정/라벨/컴포넌트/복합/초기화/URL 보존)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. 담당자 필터
  //
  // Given  alice 로그인 + FILTER_BOARD 진입 (카드 4개)
  // When   담당자 typeahead에 "alice" 입력 → "김앨리스" 선택
  // Then   FILTER-1, FILTER-3 카드 표시 (alice 담당 2개)
  //        FILTER-2(bob), FILTER-4(미배정) 사라짐
  //        카드 총 수 4개 → 2개로 감소 (vacuous 방지)
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 담당자 필터 — alice 선택 시 alice 카드 2개만 표시', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)

    // Given. 보드 카드 렌더 대기 — useBoard 쿼리 완료 후 KanbanBoard가 카드를 렌더한다.
    //        첫 번째 카드가 보일 때까지 대기 후 전체 수 확인 (로딩 중 countCards=0 방지).
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-2')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()
    const beforeCount = await countCards(page)
    expect(beforeCount).toBe(4)

    // When. 담당자 typeahead에서 alice(김앨리스) 선택
    await selectAssigneeFromTypeahead(page, 'alice', '김앨리스')

    // Then. alice 카드 2개 표시
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()

    // Then. bob 카드, 미배정 카드 사라짐
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()

    // Then. 카드 총 수 2개 (4개에서 감소 — 실제 필터 동작 확인)
    const afterCount = await countCards(page)
    expect(afterCount).toBe(2)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. 미배정 필터
  //
  // Given  alice 로그인 + FILTER_BOARD 진입 (카드 4개)
  // When   "미배정" 체크박스 체크
  // Then   FILTER-4 카드만 표시 (assigneeId=null 1개)
  //        FILTER-1, FILTER-2, FILTER-3 사라짐
  //        카드 총 수 4개 → 1개로 감소
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 미배정 필터 — "미배정" 체크 시 assigneeId=null 카드 1개만 표시', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)

    // Given. 보드 카드 렌더 대기 후 전체 4개 확인
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()
    const beforeCount = await countCards(page)
    expect(beforeCount).toBe(4)

    // When. "미배정" 체크박스 체크
    // aria-label="미배정"이 카드 summary에도 포함되므로 role=checkbox로 한정 (strict-mode 방지)
    await openFilterDropdown(page, '담당자')
    await page.getByRole('checkbox', { name: '미배정', exact: true }).check()

    // Then. FILTER-4만 표시
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()

    // Then. 담당자 있는 카드 3개 사라짐
    await expect(getCardLocator(page, 'FILTER-1')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).not.toBeVisible()

    // Then. 카드 총 수 1개 (4개에서 감소)
    const afterCount = await countCards(page)
    expect(afterCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. 라벨 필터
  //
  // Given  alice 로그인 + FILTER_BOARD 진입 (카드 4개)
  // When   라벨 자동완성에서 "bug" 선택
  // Then   FILTER-1(bug), FILTER-3(bug+documentation) 표시
  //        FILTER-2(feature), FILTER-4(라벨없음) 사라짐
  //        카드 총 수 4개 → 2개로 감소
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 라벨 필터 — "bug" 선택 시 bug 라벨 카드 2개만 표시', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)

    // Given. 보드 카드 렌더 대기 후 전체 4개 확인
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const beforeCount = await countCards(page)
    expect(beforeCount).toBe(4)

    // When. 라벨 자동완성에서 "bug" 선택
    await selectLabelFromAutocomplete(page, 'bug', 'bug')

    // Then. bug 라벨 카드 2개 표시
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()

    // Then. bug 라벨 없는 카드 사라짐
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()

    // Then. 카드 총 수 2개 (4개에서 감소)
    const afterCount = await countCards(page)
    expect(afterCount).toBe(2)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 컴포넌트 필터
  //
  // Given  alice 로그인 + FILTER_BOARD 진입 (카드 4개)
  //        컴포넌트 시드: 컴포넌트A(COMPONENT_C1_ID), 컴포넌트B(COMPONENT_C2_ID)
  // When   "컴포넌트A" 체크박스 체크
  // Then   FILTER-1(c1), FILTER-2(c1+c2) 표시
  //        FILTER-3(c2만), FILTER-4(없음) 사라짐
  //        카드 총 수 4개 → 2개로 감소
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 컴포넌트 필터 — "컴포넌트A" 체크 시 c1 카드 2개만 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. FILTER_BOARD 진입
    await page.goto(FILTER_BOARD_URL)

    // Given. 보드 카드 렌더 대기 후 전체 4개 확인
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const beforeCount = await countCards(page)
    expect(beforeCount).toBe(4)

    // Given. 컴포넌트 체크박스 렌더 대기 (useComponents 쿼리 완료 후)
    await openFilterDropdown(page, '컴포넌트')
    const componentACheckbox = page.getByRole('checkbox', { name: '컴포넌트A', exact: true })
    await expect(componentACheckbox).toBeVisible()

    // When. 컴포넌트A 체크박스 체크
    await componentACheckbox.check()

    // Then. c1 포함 카드 2개 표시
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-2')).toBeVisible()

    // Then. c1 미포함 카드 사라짐
    await expect(getCardLocator(page, 'FILTER-3')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()

    // Then. 카드 총 수 2개 (4개에서 감소)
    const afterCount = await countCards(page)
    expect(afterCount).toBe(2)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5. 복합 AND 필터
  //
  // Given  alice 로그인 + FILTER_BOARD 진입 (카드 4개)
  // When   담당자 "alice" 선택 (2개로 감소)
  //        추가로 라벨 "documentation" 선택
  // Then   alice이면서 documentation 라벨인 카드 → FILTER-3만 (1개)
  //        alice 단독 필터(2개)보다 더 줄어들어 AND 동작 확인
  //        FILTER-1(alice+bug, documentation 없음), FILTER-2, FILTER-4 사라짐
  //
  // 라벨 선택: label-handlers.ts LABEL_SEED에 'documentation'(freq=40)이 있으므로
  //            'doc' prefix 입력 시 드롭다운에 표시됨.
  //            FILTER-3의 labels=['bug', 'documentation']으로 동기화됨(board-fixtures.ts 수정).
  // ─────────────────────────────────────────────────────────────────────────
  test('S5 복합 AND 필터 — alice + documentation 라벨 → FILTER-3 1개만 남음', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)

    // Given. 보드 카드 렌더 대기 후 전체 4개 확인
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const beforeCount = await countCards(page)
    expect(beforeCount).toBe(4)

    // When-1. 담당자 alice 선택 → 2개로 감소 확인 (AND 중간 단계 검증)
    await selectAssigneeFromTypeahead(page, 'alice', '김앨리스')
    // 필터 후 FILTER-2(bob), FILTER-4(미배정) 사라질 때까지 대기
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()
    const midCount = await countCards(page)
    expect(midCount).toBe(2)

    // When-2. 추가로 라벨 "documentation" 선택 (AND 적용 → 1개로 더 감소)
    await selectLabelFromAutocomplete(page, 'doc', 'documentation')

    // Then. alice이면서 documentation 라벨인 FILTER-3만 표시
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()

    // Then. 나머지 카드 사라짐
    await expect(getCardLocator(page, 'FILTER-1')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()

    // Then. 카드 총 수 1개 (alice 단독 2개보다 더 줄어든 AND 확인)
    const afterCount = await countCards(page)
    expect(afterCount).toBe(1)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S7. 초기화
  //
  // Given  alice 로그인 + FILTER_BOARD 진입
  //        "미배정" 체크로 1개 필터 적용 상태 + URL에 assignee=unassigned 확인
  // When   "초기화" 버튼 클릭
  // Then   전체 카드 4개 복원
  //        URL에 assignee param 없음
  // ─────────────────────────────────────────────────────────────────────────
  test('S7 초기화 — 필터 적용 후 "초기화" 클릭 시 전체 카드 복원 + URL param 제거', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)

    // Given. 보드 카드 렌더 대기
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()

    // Given. 미배정 필터 적용 → 1개로 감소 확인
    // role=checkbox로 한정 — card summary "미배정" + aria-label "담당자 미배정" strict-mode 방지
    await openFilterDropdown(page, '담당자')
    await page.getByRole('checkbox', { name: '미배정', exact: true }).check()
    const filteredCount = await countCards(page)
    expect(filteredCount).toBe(1)

    // Given. URL에 assignee 관련 param 포함 확인.
    //        filterToSearch가 배열을 반환하므로 TanStack Router가 JSON 직렬화한다.
    //        실제 URL: assignee=%5B%22unassigned%22%5D (encodeURIComponent('["unassigned"]'))
    //        decodeURIComponent로 원본 URL을 확인한다.
    expect(decodeURIComponent(page.url())).toContain('unassigned')

    // When. "초기화" 버튼 클릭
    await page.getByRole('button', { name: '초기화', exact: true }).click()

    // Then. 전체 카드 4개 복원
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-2')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()

    const restoredCount = await countCards(page)
    expect(restoredCount).toBe(4)

    // Then. URL에서 assignee/unassigned 관련 param 제거됨
    expect(decodeURIComponent(page.url())).not.toContain('unassigned')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S9. URL param 보존 (새로고침)
  //
  // Given  alice 로그인 + assignee=ALICE_USER_ID param 포함 URL 직접 진입
  //        MSW 핸들러가 param 기준 alice 카드 2개 반환
  // When   page.reload() — 실제 브라우저 새로고침
  // Then   reload 후에도 alice 카드 2개만 표시 (URL param 유지 → MSW 동일 결과)
  //        FILTER-2(bob), FILTER-4(미배정) 없음
  //
  // 메모: SPA 내부 이동이 아닌 실제 reload — URL state 보존 검증.
  //       MSW 핸들러가 query param 기준 필터링이므로 store reset 무관하게 동일 결과.
  // ─────────────────────────────────────────────────────────────────────────
  test('S9 새로고침 보존 — 필터 param URL 직접 진입 후 reload 시 필터 상태 유지', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. assignee=ALICE_USER_ID param 포함 URL 직접 진입
    const filteredUrl = `${FILTER_BOARD_URL}&assignee=${ALICE_USER_ID}`
    await page.goto(filteredUrl)

    // Given. alice 카드 2개만 표시 확인
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()
    const beforeReloadCount = await countCards(page)
    expect(beforeReloadCount).toBe(2)

    // When. 실제 브라우저 새로고침
    await page.reload()

    // Then. reload 후에도 alice 카드 2개만 표시
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()

    // Then. 다른 카드 없음
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()

    // Then. 카드 총 수 2개 유지 (reload 전과 동일)
    const afterReloadCount = await countCards(page)
    expect(afterReloadCount).toBe(2)
  })
})
