// FR-EP-01 D7 E2E — 보드 EPIC 스윔레인 검증
//
// 시나리오.
//   S3. 보드 EPIC 스윔레인 — 셀렉터에서 "에픽" 선택 → 카드가 에픽별 레인 그룹으로 표시
//       EPIC_SWIMLANE_BOARD fixture: EPICTEST-1(에픽A) + EPICTEST-2(에픽B) + EPICTEST-3(에픽 없음)
//       EPIC 스윔레인 선택 후 에픽A/B/없음 서브그룹 확인
//
// 설계 결정.
//   - serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block 교훈).
//   - EPIC_SWIMLANE_BOARD fixture는 board-fixtures.ts에서 자동 시드됨 (MODE!=='test').
//   - board-fixtures.ts 직접 import 금지 — import.meta.env.MODE Node.js 런타임 오류.
//     필요한 상수를 인라인 동기화.
//   - SPA 내부 이동으로 검증 (page.reload() 금지 — store 리셋 가짜그린 방지).
//   - 스윔레인 셀렉터는 Radix Select(shadcn) — 로딩 대기 후 상호작용.
//   - strict-mode: 컬럼 컨테이너 한정 (playwright-getbyrole-exact-strict-mode 교훈).
//   - EPIC 서브그룹 label = epicKey 문자열 (swimlane-group.ts groupByEpic: label=epicKey).
//     "에픽 없음" = LABEL_NO_EPIC 상수 ('에픽 없음').
//   - 드래그 회귀 확인: EPIC 스윔레인에서도 카드 이동 가능 (FR-BD-01 회귀 0).
//     Playwright drag-and-drop API — dnd-kit과 통합된 실제 드래그 검증.
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts / board-labels.ts 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts EPIC_SWIMLANE_BOARD.boardId 와 동기화 */
const EPIC_SWIMLANE_BOARD_ID = '10000000-0000-4000-8000-000000000005'

/** board-fixtures.ts EPIC_SWIMLANE_BOARD.projectKey 와 동기화 */
const EPIC_SWIMLANE_PROJECT_KEY = 'EPICTEST'

/** EPIC_SWIMLANE_BOARD URL */
const EPIC_SWIMLANE_BOARD_URL = `/projects/${EPIC_SWIMLANE_PROJECT_KEY}/board?board=${EPIC_SWIMLANE_BOARD_ID}`

/** EPIC 스윔레인 카드 이슈 키 — board-fixtures.ts 동기화 */
const EPIC_CARD_1 = 'EPICTEST-1'   // epicKey='EPICTEST-EP-1'
const EPIC_CARD_2 = 'EPICTEST-2'   // epicKey='EPICTEST-EP-2'
const EPIC_CARD_3 = 'EPICTEST-3'   // epicKey=null (에픽 없음)

/** swimlane-group.ts LABEL_NO_EPIC 상수 동기화 */
const LABEL_NO_EPIC = '에픽 없음'

/** board-labels.ts boardLabels.swimlane.options.EPIC 동기화 */
const SWIMLANE_OPTION_EPIC = '에픽'

/** board-labels.ts boardLabels.swimlane.selectorLabel 동기화 */
const SWIMLANE_SELECTOR_LABEL = '스윔레인'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 컬럼 locator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * role="group" + aria-label에 columnName이 포함된 첫 번째 컬럼 locator.
 * BoardColumn: aria-label="{name} 컬럼, {count}개 카드"
 * board-wip-swimlane.spec.ts와 동일 패턴 유지.
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

test.describe('FR-EP-01 보드 EPIC 스윔레인', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S3. EPIC 스윔레인 전환
  //
  // Given  alice 로그인 + EPIC_SWIMLANE_BOARD 진입
  //        TODO 컬럼: EPICTEST-1(에픽A), EPICTEST-2(에픽B), EPICTEST-3(에픽 없음)
  //        초기 swimlaneField=NONE → 서브그룹 없음
  // When   스윔레인 셀렉터에서 "에픽" 선택
  //        → PATCH /api/v1/boards/{id} { swimlaneField: 'EPIC' }
  //        → MSW store 변이 → invalidateQueries → GET 재조회
  // Then   TODO 컬럼 내에 "EPICTEST-EP-1" 서브그룹 표시 + EPICTEST-1 카드 포함
  //        "EPICTEST-EP-2" 서브그룹 표시 + EPICTEST-2 카드 포함
  //        "에픽 없음" 서브그룹 표시 + EPICTEST-3 카드 포함
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 EPIC 스윔레인 — "에픽" 선택 후 에픽별 서브그룹 표시', async ({ page }) => {
    // Given. alice 로그인 + EPIC_SWIMLANE_BOARD 진입
    await loginAsAlice(page)
    await page.goto(EPIC_SWIMLANE_BOARD_URL)

    // Given. 보드 렌더 대기 — TODO 컬럼 카드들이 보일 때까지
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText(EPIC_CARD_1)).toBeVisible()
    await expect(todoColumn.getByText(EPIC_CARD_2)).toBeVisible()
    await expect(todoColumn.getByText(EPIC_CARD_3)).toBeVisible()

    // Given. 초기 swimlaneField=NONE — 서브그룹 없음 확인
    // EPICTEST-EP-1 그룹 aria-label로 된 role="group"이 없어야 함
    await expect(
      page.getByRole('group', { name: 'EPICTEST-EP-1', exact: true })
    ).toHaveCount(0)

    // Given. 스윔레인 셀렉터 로딩 대기
    const swimlaneSelect = page.getByRole('combobox', { name: SWIMLANE_SELECTOR_LABEL, exact: true })
    await expect(swimlaneSelect).toBeVisible()

    // When. 스윔레인 셀렉터에서 "에픽" 선택
    await swimlaneSelect.click()
    await page.getByRole('option', { name: SWIMLANE_OPTION_EPIC, exact: true }).click()

    // Then. PATCH → invalidate → GET 재조회 → EPIC 서브그룹 렌더 대기
    // SwimlaneSection: role="group" aria-label="{그룹라벨}" (swimlane-group.ts groupByEpic)
    // 에픽A 그룹 label = 'EPICTEST-EP-1' (epicKey 그대로)
    const epicAGroup = page.getByRole('group', { name: 'EPICTEST-EP-1', exact: true })
    await expect(epicAGroup).toBeVisible()

    // 에픽B 그룹 label = 'EPICTEST-EP-2'
    const epicBGroup = page.getByRole('group', { name: 'EPICTEST-EP-2', exact: true })
    await expect(epicBGroup).toBeVisible()

    // 에픽 없음 그룹 label = '에픽 없음' (LABEL_NO_EPIC)
    const noEpicGroup = page.getByRole('group', { name: LABEL_NO_EPIC, exact: true })
    await expect(noEpicGroup).toBeVisible()

    // Then. 각 카드가 올바른 그룹 안에 있음
    await expect(epicAGroup.getByText(EPIC_CARD_1)).toBeVisible()
    await expect(epicBGroup.getByText(EPIC_CARD_2)).toBeVisible()
    await expect(noEpicGroup.getByText(EPIC_CARD_3)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3b. EPIC → NONE 전환 후 그룹 사라짐 (SPA 내부 이동)
  //
  // Given  EPIC 스윔레인이 활성화된 상태 (S3 직후)
  // When   스윔레인 셀렉터에서 "없음" 선택
  //        → PATCH { swimlaneField: 'NONE' } → store 변이 → GET 재조회
  // Then   에픽 서브그룹이 사라지고 단일 TODO 컬럼에 카드 3개 다시 표시
  // ─────────────────────────────────────────────────────────────────────────
  test('S3b EPIC 스윔레인 → NONE 전환 — 서브그룹 사라짐', async ({ page }) => {
    // Given. alice 로그인 + EPIC 스윔레인 활성
    await loginAsAlice(page)
    await page.goto(EPIC_SWIMLANE_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText(EPIC_CARD_1)).toBeVisible()

    const swimlaneSelect = page.getByRole('combobox', { name: SWIMLANE_SELECTOR_LABEL, exact: true })
    await expect(swimlaneSelect).toBeVisible()

    // EPIC 선택
    await swimlaneSelect.click()
    await page.getByRole('option', { name: SWIMLANE_OPTION_EPIC, exact: true }).click()

    // EPIC 서브그룹 대기
    await expect(page.getByRole('group', { name: 'EPICTEST-EP-1', exact: true })).toBeVisible()

    // When. "없음" 선택
    await swimlaneSelect.click()
    await page.getByRole('option', { name: '없음', exact: true }).click()

    // Then. 에픽 서브그룹 사라짐
    await expect(
      page.getByRole('group', { name: 'EPICTEST-EP-1', exact: true })
    ).toHaveCount(0)
    await expect(
      page.getByRole('group', { name: LABEL_NO_EPIC, exact: true })
    ).toHaveCount(0)

    // Then. TODO 컬럼에 카드 3개 다시 표시 (단일 컬럼 뷰로 복원)
    const todoColumnAfter = getColumnLocator(page, 'TODO')
    await expect(todoColumnAfter.getByText(EPIC_CARD_1)).toBeVisible()
    await expect(todoColumnAfter.getByText(EPIC_CARD_2)).toBeVisible()
    await expect(todoColumnAfter.getByText(EPIC_CARD_3)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3c. 드래그 회귀 확인 — EPIC 스윔레인에서 카드 이동
  //
  // Given  EPIC 스윔레인 활성 상태
  //        EPICTEST-1 카드가 에픽A 그룹(TODO) 안에 있음
  // When   EPICTEST-1 카드를 TODO → IN PROGRESS 컬럼으로 드래그
  //        → MSW moveCardHandler: 200 + store 변이 (version 증가)
  //        → invalidate → GET 재조회
  // Then   EPICTEST-1이 IN PROGRESS 컬럼에 표시됨
  //        TODO 컬럼 에픽A 그룹에서 사라짐
  //
  // 주의.
  //   dnd-kit은 pointer 이벤트 기반 — Playwright mouse.down → move → up 사용.
  //   보드 E2E 드래그는 board-kanban.spec.ts 패턴 참조.
  // ─────────────────────────────────────────────────────────────────────────
  test('S3c 드래그 회귀 — EPIC 스윔레인에서 카드 이동 (FR-BD-01 회귀 0)', async ({ page }) => {
    // Given. alice 로그인 + EPIC 스윔레인 활성
    await loginAsAlice(page)
    await page.goto(EPIC_SWIMLANE_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText(EPIC_CARD_1)).toBeVisible()

    const swimlaneSelect = page.getByRole('combobox', { name: SWIMLANE_SELECTOR_LABEL, exact: true })
    await expect(swimlaneSelect).toBeVisible()
    await swimlaneSelect.click()
    await page.getByRole('option', { name: SWIMLANE_OPTION_EPIC, exact: true }).click()

    // 에픽A 그룹 렌더 대기
    const epicAGroup = page.getByRole('group', { name: 'EPICTEST-EP-1', exact: true })
    await expect(epicAGroup).toBeVisible()
    await expect(epicAGroup.getByText(EPIC_CARD_1)).toBeVisible()

    // When. EPICTEST-1 카드 드래그 (dnd-kit: pointerdown → pointermove → pointerup)
    const card = epicAGroup.getByText(EPIC_CARD_1).first()
    const inProgressColumn = getColumnLocator(page, 'IN PROGRESS')
    await expect(inProgressColumn).toBeVisible()

    // card bounding box
    const cardBox = await card.boundingBox()
    const colBox = await inProgressColumn.boundingBox()

    if (cardBox !== null && colBox !== null) {
      const startX = cardBox.x + cardBox.width / 2
      const startY = cardBox.y + cardBox.height / 2
      const endX = colBox.x + colBox.width / 2
      const endY = colBox.y + colBox.height / 2

      await page.mouse.move(startX, startY)
      await page.mouse.down()
      // 중간 지점 경유 — dnd-kit 드래그 감지
      await page.mouse.move(startX + (endX - startX) / 2, startY + (endY - startY) / 2, { steps: 5 })
      await page.mouse.move(endX, endY, { steps: 5 })
      await page.mouse.up()
    }

    // Then. EPICTEST-1이 IN PROGRESS 컬럼에 표시됨 (카드 이동 + store 변이 + invalidate refetch)
    const inProgressColumnAfter = getColumnLocator(page, 'IN PROGRESS')
    await expect(inProgressColumnAfter.getByText(EPIC_CARD_1)).toBeVisible()
  })
})
