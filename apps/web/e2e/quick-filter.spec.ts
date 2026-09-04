// FR-UX-01 D7 E2E — 보드 퀵필터 저장→적용→해제→삭제 생명주기 + 권한 게이팅
//
// 시나리오 개요.
//   S1. 저장       — 필터 적용(담당자 alice) → 이름 입력 저장 → 칩 목록에 표시
//   S2. 칩 적용     — 새로고침 없이(SPA 내부 이동) 칩 클릭 시 카드 필터링 + 칩 활성 표시(FR6)
//   S3. 칩 재클릭   — 활성 칩 재클릭 시 필터 해제 + 칩 비활성
//   S4. 수동 변경   — 칩 활성 상태에서 필터바를 수동 조작하면 칩이 비활성화된다(리뷰 C3-b)
//   S5. 보드 전환   — 다른 보드로 이동 후 복귀 시 activeQuickFilterId 초기화(리뷰 C3-d)
//   S6. 삭제       — 삭제 버튼 클릭 시 칩이 목록에서 사라짐
//   S7. 권한 게이팅 — CREATE 권한 없는(BROWSE만) 사용자는 칩을 보고 클릭할 수 있으나
//                    "필터 저장"/편집/삭제 버튼은 노출되지 않는다(FR5)
//
// 설계 결정.
//   - FILTER_BOARD(board-fixtures.ts)를 S1~S6에 재사용한다. 모듈 자동 시드(MODE!=='test')로
//     퀵필터 없이(quickFilters: [] 기본) 시작하므로 매 테스트가 스스로 퀵필터를 생성한다
//     (데이터 격리 — 전역 fixture로 미리 채워둔 퀵필터를 공유하지 않는다).
//   - alice는 adminProjectPermissions(CREATE:true) — canManage=true로 저장/편집/삭제 버튼 노출.
//   - S7은 board-handlers.ts에 이 작업(qa-engineer Task 10) 전용으로 새로 시드한 QFPERM 보드를
//     사용한다. FILTER_BOARD에 퀵필터를 영구 시드하면 board-filter.spec.ts(FR-BD-02)와
//     S1~S6(빈 칩 목록에서 출발해야 함)에 교차 오염되므로 별도 보드로 격리했다.
//   - S2/S3/S4/S5는 "SPA 내부 이동" 원칙 — page.reload() 금지(memory: MSW 영속 E2E는
//     reload=가짜그린). S5의 보드 전환은 page.goto 대신 history.pushState + popstate 이벤트로
//     구현한다(epic-children.spec.ts 선례와 동일 패턴) — MSW boardStore가 페이지 JS 모듈
//     상태이므로 실제 reload 시 방금 생성한 퀵필터가 유실된다.
//   - S7의 CREATE:false는 project-permission-handlers.ts의 E2E_FORCE_CREATE_FALSE_KEY
//     localStorage 플래그 + addInitScript로 심는다(memory: e2e-msw-scenario-toggle-localstorage-flag).
//     이 플래그는 페이지의 첫 네비게이션 전에 설정해야 첫 권한 조회부터 적용된다
//     (staleTime 30초 — 세션 중간에 플래그를 바꿔도 캐시된 권한 쿼리는 갱신되지 않는다).
//   - getByRole exact:true + 컨테이너(role=list aria-label="퀵필터 목록") 한정
//     (memory: playwright-getbyrole-exact-strict-mode) — 카드 summary에 "미배정" 등 텍스트가
//     포함되므로 role 기반 정밀 매칭을 사용한다(board-filter.spec.ts 선례).
//   - board-fixtures.ts / board-handlers.ts 직접 import 금지 — import.meta.env.MODE로
//     Node.js(E2E) 런타임 오류. 필요한 상수는 인라인 동기화한다.
//   - MSW serviceWorkers:'block' 금지(e2e-msw-serviceworker-block 교훈).
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { quickFilterLabels } from '../src/i18n/quick-filter-labels'
import { openFilterDropdown } from './fixtures/filter-bar'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts / board-handlers.ts / project-permission-handlers.ts와 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts FILTER_BOARD.boardId 와 동기화 */
const FILTER_BOARD_ID = '10000000-0000-4000-8000-000000000002'
/** board-fixtures.ts FILTER_BOARD.projectKey 와 동기화 */
const FILTER_PROJECT_KEY = 'FILTER'
/** FILTER_BOARD 보드 URL */
const FILTER_BOARD_URL = `/projects/${FILTER_PROJECT_KEY}/board?board=${FILTER_BOARD_ID}`

/** board-fixtures.ts DEFAULT_BOARD.boardId 와 동기화 — S5 보드 전환 대상 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'
/** board-fixtures.ts DEFAULT_BOARD.projectKey 와 동기화 */
const DEFAULT_PROJECT_KEY = 'ATLAS'
/** DEFAULT_BOARD 보드 URL — S5에서 일시적으로 전환할 다른 보드 */
const DEFAULT_BOARD_URL = `/projects/${DEFAULT_PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** board-handlers.ts QUICK_FILTER_PERM_BOARD_ID 와 동기화 — S7 권한 게이팅 전용 보드 */
const QF_PERM_BOARD_ID = '10000000-0000-4000-8000-000000000006'
/** board-handlers.ts QUICK_FILTER_PERM_PROJECT_KEY 와 동기화 */
const QF_PERM_PROJECT_KEY = 'QFPERM'
/** QFPERM 보드 URL */
const QF_PERM_BOARD_URL = `/projects/${QF_PERM_PROJECT_KEY}/board?board=${QF_PERM_BOARD_ID}`
/** board-handlers.ts QUICK_FILTER_PERM_SEED.quickFilters[0].name 와 동기화 */
const QF_PERM_FILTER_NAME = '버그만'

/** project-permission-handlers.ts E2E_FORCE_CREATE_FALSE_KEY 와 동기화 */
const E2E_FORCE_CREATE_FALSE_KEY = '__bts_e2e_force_create_false'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 카드 locator (board-filter.spec.ts 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * aria-roledescription="draggable card" 요소 중 issueKey가 포함된 카드 로케이터.
 * BoardCard: aria-label={`${card.issueKey} — ${card.summary}`}, aria-roledescription="draggable card"
 */
function getCardLocator(page: import('@playwright/test').Page, issueKey: string) {
  return page
    .locator('[aria-roledescription="draggable card"]')
    .filter({ hasText: issueKey })
    .first()
}

/** 보드 내 전체 draggable card 수를 반환한다(필터 전/후 비교용, vacuous 방지). */
async function countCards(page: import('@playwright/test').Page): Promise<number> {
  return page.locator('[aria-roledescription="draggable card"]').count()
}

/**
 * 담당자 typeahead에서 사용자를 선택한다 (board-filter.spec.ts와 동일 패턴).
 * id 셀렉터로 직접 한정 — getByLabel('담당자')는 카드 aria-label과 strict-mode 충돌.
 */
async function selectAssigneeFromTypeahead(
  page: import('@playwright/test').Page,
  query: string,
  displayName: string,
): Promise<void> {
  await openFilterDropdown(page, '담당자')
  const input = page.locator('#board-filter-assignee-input')
  await input.fill(query)
  const dropdownList = page.locator('#board-filter-assignee-input + ul').or(
    page.locator('[id="board-filter-assignee-input"] ~ ul'),
  )
  const dropdownBtn = dropdownList.getByRole('button', { name: displayName, exact: true })
  await expect(dropdownBtn).toBeVisible()
  await dropdownBtn.click()
  await expect(input).toHaveValue('')
}

/**
 * 퀵필터 칩 목록(role="list" aria-label="퀵필터 목록") 내에서 이름으로 칩 적용 버튼을 찾는다.
 * BoardFilterBar의 "적용된 필터" role=list와 aria-label이 달라 충돌하지 않는다.
 */
function getChipButton(page: import('@playwright/test').Page, name: string) {
  return page
    .getByRole('list', { name: quickFilterLabels.list.ariaLabel })
    .getByRole('button', { name, exact: true })
}

/**
 * FILTER_BOARD에서 담당자 alice로 필터를 적용한 뒤 "필터 저장" 다이얼로그로 퀵필터를 생성한다.
 * S1~S6가 공유하는 Given 단계 — 매 테스트가 자기 데이터를 직접 생성한다(데이터 격리).
 *
 * @param page Playwright Page 객체 (FILTER_BOARD_URL 진입 + 로그인 완료 상태 전제)
 * @param name 저장할 퀵필터 이름
 */
async function createQuickFilterViaUI(page: import('@playwright/test').Page, name: string): Promise<void> {
  // Given. 담당자 alice 필터 적용 — 4개→2개로 감소해야 저장 버튼이 활성화된다(EC1).
  await selectAssigneeFromTypeahead(page, 'alice', '김앨리스')
  await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()

  // Given. "필터 저장" 클릭 → 다이얼로그에 이름 입력 → 저장
  await page.getByRole('button', { name: quickFilterLabels.list.saveCurrentButton, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByLabel(quickFilterLabels.form.nameLabel).fill(name)
  await dialog.getByRole('button', { name: quickFilterLabels.form.saveButton, exact: true }).click()
  await expect(dialog).not.toBeVisible()
}

/**
 * SPA 내부 이동(popstate) — 실제 브라우저 reload 없이 다른 라우트로 전환한다.
 * MSW boardStore는 페이지 JS 모듈 상태이므로 page.goto()(전체 reload)를 쓰면
 * 방금 생성한 퀵필터가 유실된다. epic-children.spec.ts와 동일한 선례 패턴.
 *
 * @param page Playwright Page 객체
 * @param url 이동할 경로(쿼리스트링 포함)
 */
async function navigateSpaTo(page: import('@playwright/test').Page, url: string): Promise<void> {
  await page.evaluate((targetUrl: string) => {
    window.history.pushState({}, '', targetUrl)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, url)
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-01 보드 퀵필터 (저장·적용·해제·삭제·권한 게이팅)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. 저장
  //
  // Given  alice 로그인 + FILTER_BOARD 진입(카드 4개)
  // When   담당자 alice 필터 적용 → "필터 저장" → 이름 "내 이슈" 입력 → 저장
  // Then   퀵필터 칩 목록에 "내 이슈" 표시
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 저장 — 필터 적용 후 이름 입력 저장 시 칩 목록에 표시', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()

    // When. 담당자 필터 적용 후 "내 이슈"로 저장
    const filterName = '내 이슈'
    await createQuickFilterViaUI(page, filterName)

    // Then. 칩 목록에 "내 이슈" 표시
    await expect(getChipButton(page, filterName)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. 칩 적용
  //
  // Given  alice 로그인 + FILTER_BOARD 진입 + "내 이슈" 퀵필터 생성 + 필터 초기화(전체 4개)
  // When   새로고침 없이(SPA 내부 이동) "내 이슈" 칩 클릭
  // Then   alice 카드 2개만 표시(FILTER-1, FILTER-3) + 칩 활성(aria-pressed=true)
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 칩 적용 — 새로고침 없이 칩 클릭 시 카드 필터링 + 칩 활성 표시', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입 + 퀵필터 생성
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const filterName = '내 이슈'
    await createQuickFilterViaUI(page, filterName)

    // Given. 필터 초기화 → 전체 4개 복원(칩 클릭 효과를 명확히 관찰하기 위한 기준선)
    await page.getByRole('button', { name: '초기화', exact: true }).click()
    await expect(getCardLocator(page, 'FILTER-2')).toBeVisible()
    const beforeCount = await countCards(page)
    expect(beforeCount).toBe(4)

    // When. "내 이슈" 칩 클릭 (SPA 내부 navigate — reload 없음)
    const chipButton = getChipButton(page, filterName)
    await chipButton.click()

    // Then. alice 카드 2개만 표시
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-3')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-2')).not.toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).not.toBeVisible()
    const afterCount = await countCards(page)
    expect(afterCount).toBe(2)

    // Then. 칩 활성 표시(aria-pressed=true)
    await expect(chipButton).toHaveAttribute('aria-pressed', 'true')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. 칩 재클릭
  //
  // Given  "내 이슈" 칩 클릭으로 활성 상태(카드 2개)
  // When   같은 칩 재클릭
  // Then   필터 해제 — 전체 4개 복원 + 칩 비활성(aria-pressed=false)
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 칩 재클릭 — 활성 칩 재클릭 시 필터 해제', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입 + 퀵필터 생성 + 칩 클릭으로 활성화
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const filterName = '내 이슈'
    await createQuickFilterViaUI(page, filterName)
    await page.getByRole('button', { name: '초기화', exact: true }).click()
    const chipButton = getChipButton(page, filterName)
    await chipButton.click()
    await expect(chipButton).toHaveAttribute('aria-pressed', 'true')
    const activeCount = await countCards(page)
    expect(activeCount).toBe(2)

    // When. 같은 칩 재클릭
    await chipButton.click()

    // Then. 필터 해제 — 전체 4개 복원
    await expect(getCardLocator(page, 'FILTER-2')).toBeVisible()
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()
    const restoredCount = await countCards(page)
    expect(restoredCount).toBe(4)

    // Then. 칩 비활성 표시(aria-pressed=false)
    await expect(chipButton).toHaveAttribute('aria-pressed', 'false')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 수동 변경 (리뷰 C3-b — 단위테스트로 못 커버한 부분)
  //
  // Given  "내 이슈" 칩 클릭으로 활성 상태(카드 2개)
  // When   필터바에서 "미배정" 체크박스를 수동으로 체크
  // Then   칩 비활성화(aria-pressed=false) + 카드 구성 변경(3개 — vacuous 방지)
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 필터바 수동 변경 — 칩 활성 상태에서 필터바 조작 시 칩 비활성화', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입 + 퀵필터 생성 + 칩 클릭으로 활성화
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const filterName = '내 이슈'
    await createQuickFilterViaUI(page, filterName)
    await page.getByRole('button', { name: '초기화', exact: true }).click()
    const chipButton = getChipButton(page, filterName)
    await chipButton.click()
    await expect(chipButton).toHaveAttribute('aria-pressed', 'true')
    const activeCount = await countCards(page)
    expect(activeCount).toBe(2)

    // When. 필터바에서 "미배정" 체크박스를 수동으로 체크(칩과 무관한 직접 조작)
    // role=checkbox로 한정 — 카드 summary에 "미배정" 텍스트가 포함되어 strict-mode 방지 필요.
    await openFilterDropdown(page, '담당자')
    await page.getByRole('checkbox', { name: '미배정', exact: true }).check()

    // Then. 칩 비활성화 — 더 이상 그 퀵필터의 조건과 일치한다는 보장이 없으므로 해제된다.
    await expect(chipButton).toHaveAttribute('aria-pressed', 'false')

    // Then. 카드 구성 실제로 변경됨(alice 담당 2개 + 미배정 1개 = 3개, vacuous 방지)
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()
    const afterManualChange = await countCards(page)
    expect(afterManualChange).toBe(3)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5. 보드 전환 (리뷰 C3-d, 가능하면 검증)
  //
  // Given  "내 이슈" 칩 클릭으로 활성 상태(카드 2개)
  // When   SPA 내부 이동으로 다른 보드(ATLAS DEFAULT_BOARD)로 전환 후 FILTER_BOARD로 복귀
  // Then   필터 해제 상태(전체 4개) + 칩 비활성(activeQuickFilterId 초기화)
  // ─────────────────────────────────────────────────────────────────────────
  test('S5 보드 전환 — 다른 보드로 이동 후 복귀 시 활성 퀵필터 초기화', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입 + 퀵필터 생성 + 칩 클릭으로 활성화
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const filterName = '내 이슈'
    await createQuickFilterViaUI(page, filterName)
    await page.getByRole('button', { name: '초기화', exact: true }).click()
    const chipButton = getChipButton(page, filterName)
    await chipButton.click()
    await expect(chipButton).toHaveAttribute('aria-pressed', 'true')

    // When. SPA 내부 이동(popstate)으로 다른 보드(ATLAS)로 전환
    await navigateSpaTo(page, DEFAULT_BOARD_URL)
    await expect(getCardLocator(page, 'ATLAS-1')).toBeVisible()

    // When. 다시 FILTER_BOARD로 복귀(필터 파라미터 없는 bare URL)
    await navigateSpaTo(page, FILTER_BOARD_URL)

    // Then. 필터 해제 상태 — 전체 4개 복원
    await expect(getCardLocator(page, 'FILTER-4')).toBeVisible()
    const restoredCount = await countCards(page)
    expect(restoredCount).toBe(4)

    // Then. 칩 비활성 — activeQuickFilterId가 보드 전환 시 초기화됨
    await expect(getChipButton(page, filterName)).toHaveAttribute('aria-pressed', 'false')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6. 삭제
  //
  // Given  "내 이슈" 퀵필터 생성됨(칩 표시)
  // When   삭제(✕) 버튼 클릭
  // Then   칩이 목록에서 사라짐
  // ─────────────────────────────────────────────────────────────────────────
  test('S6 삭제 — 삭제 버튼 클릭 시 칩이 목록에서 사라짐', async ({ page }) => {
    // Given. alice 로그인 + FILTER_BOARD 진입 + 퀵필터 생성
    await loginAsAlice(page)
    await page.goto(FILTER_BOARD_URL)
    await expect(getCardLocator(page, 'FILTER-1')).toBeVisible()
    const filterName = '내 이슈'
    await createQuickFilterViaUI(page, filterName)
    const chipButton = getChipButton(page, filterName)
    await expect(chipButton).toBeVisible()

    // When. 삭제 버튼 클릭
    const deleteButton = page.getByRole('button', {
      name: quickFilterLabels.list.deleteAriaLabel(filterName),
      exact: true,
    })
    await deleteButton.click()

    // Then. 칩이 목록에서 사라짐
    await expect(chipButton).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S7. 권한 게이팅 (FR5, 가능하면 검증)
  //
  // Given  addInitScript로 CREATE:false 강제(E2E_FORCE_CREATE_FALSE_KEY)
  //        alice 로그인 + QFPERM 보드 진입 — "버그만" 퀵필터 1건 사전 시드됨
  // When   보드 렌더
  // Then   칩은 표시되지만 "필터 저장"/편집/삭제 버튼은 없음(canManage=false)
  // When   칩 클릭
  // Then   권한 없어도 필터 적용은 가능(FR6) — bug 라벨 카드만 남음(2개→1개)
  // ─────────────────────────────────────────────────────────────────────────
  test('S7 권한 게이팅 — CREATE 없는 사용자는 칩을 보고 클릭할 수 있으나 관리 버튼은 없음', async ({ page }) => {
    // Given. CREATE:false 강제 플래그(goto 전 addInitScript — e2e-msw-scenario-toggle-localstorage-flag)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, E2E_FORCE_CREATE_FALSE_KEY)

    // Given. alice 로그인 + QFPERM 보드 진입(퀵필터 "버그만" 사전 시드)
    await loginAsAlice(page)
    await page.goto(QF_PERM_BOARD_URL)
    await expect(getCardLocator(page, 'QFPERM-1')).toBeVisible()
    await expect(getCardLocator(page, 'QFPERM-2')).toBeVisible()

    // Then. 칩은 표시됨
    const chipButton = getChipButton(page, QF_PERM_FILTER_NAME)
    await expect(chipButton).toBeVisible()

    // Then. "필터 저장" 버튼은 없음(canManage=false)
    await expect(
      page.getByRole('button', { name: quickFilterLabels.list.saveCurrentButton, exact: true }),
    ).not.toBeVisible()

    // Then. 편집/삭제 아이콘 버튼도 없음
    await expect(
      page.getByRole('button', {
        name: quickFilterLabels.list.editAriaLabel(QF_PERM_FILTER_NAME),
        exact: true,
      }),
    ).not.toBeVisible()
    await expect(
      page.getByRole('button', {
        name: quickFilterLabels.list.deleteAriaLabel(QF_PERM_FILTER_NAME),
        exact: true,
      }),
    ).not.toBeVisible()

    // When. 칩 클릭 — 권한 없어도 적용은 가능(FR6)
    await chipButton.click()

    // Then. label=bug 카드만 남음(QFPERM-1), feature 카드는 사라짐(2개→1개)
    await expect(getCardLocator(page, 'QFPERM-1')).toBeVisible()
    await expect(getCardLocator(page, 'QFPERM-2')).not.toBeVisible()
    await expect(chipButton).toHaveAttribute('aria-pressed', 'true')
  })
})
