// FR-BD-03 D7 E2E — WIP 초과 경고 + 스윔레인 그룹 전환 + 권한 게이팅
//
// 시나리오 개요.
//   S1. WIP 초과 경고    — wipExceeded=true 컬럼 → 헤더에 "3/2" + amber 경고 배지
//   S3. 스윔레인 ASSIGNEE — 셀렉터에서 "담당자" 선택 → PATCH 라운드트립 → 담당자 서브그룹 표시
//   S4. 스윔레인 PRIORITY — "우선순위" 선택 → priority별 서브그룹 표시
//   S6. 권한 게이팅       — CREATE=false 사용자 → 스윔레인 셀렉터 미노출
//
// 설계 결정.
//   - board-fixtures.ts WIP_BOARD / SWIMLANE_BOARD는 모듈 자동 시드(MODE!=='test') 시 등록됨.
//   - MSW serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block 교훈).
//   - board-fixtures.ts 직접 import 금지 — import.meta.env.MODE로 Node.js 런타임 오류.
//     필요한 상수를 인라인 동기화.
//   - SPA 내부 이동으로 검증 (page.reload() 금지 — store 리셋 가짜그린 방지).
//   - 스윔레인 셀렉터는 Radix Select(shadcn) — 로딩 대기 후 상호작용.
//   - strict-mode: 컬럼 컨테이너로 한정(playwright-getbyrole-exact-strict-mode 교훈).
//   - S6 CREATE 게이팅: E2E_FORCE_CREATE_FALSE_KEY localStorage 플래그 + addInitScript.
//   - CSRF 쿠키: PATCH는 apiFetch를 사용하고, MSW가 가로채므로 별도 수동 시드 불필요.
//   - alice whoami fixture userId = 00000000-0000-4000-8000-000000000001 (auth-fixtures.ts).
//     board-fixtures ALICE_USER_ID = c3d4e5f6-... (user-fixtures.ts).
//     두 UUID가 다른 것은 auth alice(whoami)와 board assignee alice가 다른 용도이기 때문.
//     SWIMLANE_BOARD 카드는 assigneeId=ALICE_USER_ID(user-fixtures)로 시드되어야
//     buildAssigneeNames에서 "김앨리스"로 해석됨 — board-fixtures.ts 동기화 확인 완료.
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts / project-permission-handlers.ts와 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts WIP_BOARD.boardId 와 동기화 */
const WIP_BOARD_ID = '10000000-0000-4000-8000-000000000003'

/** board-fixtures.ts WIP_BOARD.projectKey 와 동기화 */
const WIP_PROJECT_KEY = 'WIPTEST'

/** WIP_BOARD URL */
const WIP_BOARD_URL = `/projects/${WIP_PROJECT_KEY}/board?board=${WIP_BOARD_ID}`

/** board-fixtures.ts SWIMLANE_BOARD.boardId 와 동기화 */
const SWIMLANE_BOARD_ID = '10000000-0000-4000-8000-000000000004'

/** board-fixtures.ts SWIMLANE_BOARD.projectKey 와 동기화 */
const SWIMLANE_PROJECT_KEY = 'SWIMTEST'

/** SWIMLANE_BOARD URL */
const SWIMLANE_BOARD_URL = `/projects/${SWIMLANE_PROJECT_KEY}/board?board=${SWIMLANE_BOARD_ID}`

/**
 * project-permission-handlers.ts E2E_FORCE_CREATE_FALSE_KEY 와 동기화.
 * addInitScript로 이 플래그를 'true'로 심으면 권한 핸들러가 CREATE:false를 반환한다.
 */
const E2E_FORCE_CREATE_FALSE_KEY = '__bts_e2e_force_create_false'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 컬럼 locator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * role="group" + aria-label에 columnName이 포함된 첫 번째 컬럼 locator.
 * BoardColumn: aria-label="{name} 컬럼, {count}개 카드"
 * WIP 배지 카드 수가 바뀌므로 filter(hasText) 대신 startsWith 패턴 사용.
 */
function getColumnLocator(
  page: import('@playwright/test').Page,
  columnName: string,
) {
  return page
    .getByRole('group')
    .filter({ hasText: new RegExp(`^${columnName}`) })
    .first()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-BD-03 WIP 초과 경고 + 스윔레인 그룹 + 권한 게이팅', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. WIP 초과 경고
  //
  // Given  alice 로그인 + WIP_BOARD 진입
  //        IN PROGRESS 컬럼: wipLimit=2, wipExceeded=true, 카드 3개
  // When   보드 화면 렌더
  // Then   IN PROGRESS 컬럼 헤더에 "3/2" 텍스트 표시
  //        aria-label="WIP 초과" 배지 표시 (amber 경고 톤)
  //        카드 이동은 차단 안 됨 (경고만)
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 WIP 초과 경고 — wipExceeded=true 컬럼 헤더에 "3/2" + WIP 초과 배지', async ({ page }) => {
    // Given. alice 로그인 + WIP_BOARD 진입
    await loginAsAlice(page)
    await page.goto(WIP_BOARD_URL)

    // Given. 보드 렌더 대기 — IN PROGRESS 컬럼의 카드 중 하나가 보일 때까지
    const inProgressColumn = getColumnLocator(page, 'IN PROGRESS')
    await expect(inProgressColumn.getByText('WIP-2')).toBeVisible()

    // Then. IN PROGRESS 컬럼 헤더에 "3/2" 텍스트 표시 (WipCountBadge countLabel)
    // 컬럼 컨테이너 내에서 한정 — strict-mode 방지
    await expect(inProgressColumn.getByText('3/2')).toBeVisible()

    // Then. aria-label="WIP 초과" 배지 표시 (boardLabels.wip.exceededAriaLabel)
    // WipCountBadge: <span aria-label="WIP 초과"> — span은 role=generic이지만
    // Playwright getByRole('generic') 매칭이 불안정하므로 aria-label attribute 셀렉터로 직접 한정한다.
    await expect(inProgressColumn.locator('[aria-label="WIP 초과"]')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. 스윔레인 ASSIGNEE 전환
  //
  // Given  alice 로그인 + SWIMLANE_BOARD 진입
  //        SWIMLANE_BOARD TODO: SWIM-1(alice 담당), SWIM-2(bob 담당)
  //        초기 swimlaneField=NONE → 단일 목록 표시
  // When   스윔레인 셀렉터에서 "담당자" 선택
  //        → PATCH /api/v1/boards/{id} { swimlaneField: 'ASSIGNEE' }
  //        → MSW store 변이 → invalidateQueries → GET 재조회
  // Then   TODO 컬럼 내에 "김앨리스" 서브그룹 표시 (alice 담당)
  //        "bob" 서브그룹 표시 (bob 담당 — displayName null → username 폴백)
  //        SPA 내부 상호작용으로 검증 (page.reload() 금지)
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 스윔레인 ASSIGNEE — "담당자" 선택 후 담당자 서브그룹 표시', async ({ page }) => {
    // Given. alice 로그인 + SWIMLANE_BOARD 진입
    await loginAsAlice(page)
    await page.goto(SWIMLANE_BOARD_URL)

    // Given. 보드 렌더 대기 — SWIM-1 카드가 보일 때까지
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('SWIM-1')).toBeVisible()
    await expect(todoColumn.getByText('SWIM-2')).toBeVisible()

    // Given. 초기 swimlaneField=NONE — 서브그룹 헤더 없음 확인
    await expect(page.getByText('김앨리스').first()).not.toBeVisible()

    // Given. 스윔레인 셀렉터 로딩 대기
    // boardLabels.swimlane.selectorLabel = '스윔레인'
    // SwimlaneSelector: aria-label="스윔레인"인 SelectTrigger
    const swimlaneSelect = page.getByRole('combobox', { name: '스윔레인', exact: true })
    await expect(swimlaneSelect).toBeVisible()

    // When. 스윔레인 셀렉터에서 "담당자" 선택 (boardLabels.swimlane.options.ASSIGNEE = '담당자')
    await swimlaneSelect.click()
    // Radix SelectContent가 팝업으로 열림 → role="option"으로 선택
    await page.getByRole('option', { name: '담당자', exact: true }).click()

    // Then. PATCH → invalidate → GET 재조회 → 스윔레인 서브그룹 렌더 대기
    // SwimlaneSection: role="group" aria-label="{그룹라벨}" (swimlane-group.ts)
    // alice displayName = '김앨리스' (user-fixtures.ts)
    const aliceGroup = page.getByRole('group', { name: '김앨리스', exact: true })
    await expect(aliceGroup).toBeVisible()

    // Then. bob displayName=null → username 'bob' 폴백으로 그룹 표시
    const bobGroup = page.getByRole('group', { name: 'bob', exact: true })
    await expect(bobGroup).toBeVisible()

    // Then. SWIM-1은 alice 그룹 안에, SWIM-2는 bob 그룹 안에 있음
    await expect(aliceGroup.getByText('SWIM-1')).toBeVisible()
    await expect(bobGroup.getByText('SWIM-2')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 스윔레인 PRIORITY 전환
  //
  // Given  alice 로그인 + SWIMLANE_BOARD 진입 (ASSIGNEE 이후 PRIORITY 전환)
  //        SWIM-1: priority=1, SWIM-2: priority=2
  // When   스윔레인 셀렉터에서 "우선순위" 선택
  // Then   "우선순위 1" 서브그룹 표시 + SWIM-1 포함
  //        "우선순위 2" 서브그룹 표시 + SWIM-2 포함
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 스윔레인 PRIORITY — "우선순위" 선택 후 priority별 서브그룹 표시', async ({ page }) => {
    // Given. alice 로그인 + SWIMLANE_BOARD 진입
    await loginAsAlice(page)
    await page.goto(SWIMLANE_BOARD_URL)

    // Given. 보드 렌더 대기
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('SWIM-1')).toBeVisible()

    // Given. 스윔레인 셀렉터 로딩 대기
    const swimlaneSelect = page.getByRole('combobox', { name: '스윔레인', exact: true })
    await expect(swimlaneSelect).toBeVisible()

    // When. "우선순위" 선택 (boardLabels.swimlane.options.PRIORITY = '우선순위')
    await swimlaneSelect.click()
    await page.getByRole('option', { name: '우선순위', exact: true }).click()

    // Then. PATCH → invalidate → GET 재조회 → PRIORITY 서브그룹 렌더 대기
    // swimlane-group.ts: label=`우선순위 ${p}` (groupByPriority)
    const priority1Group = page.getByRole('group', { name: '우선순위 1', exact: true })
    await expect(priority1Group).toBeVisible()

    const priority2Group = page.getByRole('group', { name: '우선순위 2', exact: true })
    await expect(priority2Group).toBeVisible()

    // Then. SWIM-1은 우선순위 1 그룹 안에, SWIM-2는 우선순위 2 그룹 안에
    await expect(priority1Group.getByText('SWIM-1')).toBeVisible()
    await expect(priority2Group.getByText('SWIM-2')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6. 권한 게이팅 — CREATE=false 사용자
  //
  // Given  addInitScript로 E2E_FORCE_CREATE_FALSE_KEY='true' 심기
  //        alice 로그인 + SWIMLANE_BOARD 진입
  //        권한 핸들러 → CREATE:false 반환 → canCreate=false
  // When   보드 렌더
  // Then   스윔레인 셀렉터 미노출 (canCreate=false 게이팅)
  //        보드 자체는 정상 표시 (카드 표시됨)
  // ─────────────────────────────────────────────────────────────────────────
  test('S6 권한 게이팅 — CREATE=false 사용자는 스윔레인 셀렉터 미노출', async ({ page }) => {
    // Given. addInitScript로 CREATE:false 강제 플래그 심기 (goto 전 등록 — e2e-msw-scenario-toggle-localstorage-flag)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, E2E_FORCE_CREATE_FALSE_KEY)

    // Given. alice 로그인 + SWIMLANE_BOARD 진입
    await loginAsAlice(page)
    await page.goto(SWIMLANE_BOARD_URL)

    // Given. 보드 카드 렌더 대기 (권한 핸들러 쿼리 완료 후 KanbanBoard가 렌더됨)
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('SWIM-1')).toBeVisible()

    // Then. 스윔레인 셀렉터 미노출 (canCreate=false → SwimlaneSelector 미렌더)
    const swimlaneSelect = page.getByRole('combobox', { name: '스윔레인', exact: true })
    await expect(swimlaneSelect).not.toBeVisible()

    // Then. 보드 자체는 정상 표시 — 카드가 보임 (권한 게이팅은 셀렉터만, 보드 접근 차단 아님)
    await expect(todoColumn.getByText('SWIM-2')).toBeVisible()
  })
})
