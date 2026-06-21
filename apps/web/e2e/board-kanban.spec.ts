// FR-BD-01 D7 E2E — 칸반 보드 실제 드래그 (PointerSensor 이동/resolution/충돌)
//
// 시나리오 개요.
//   S1. 보드 조회       — DEFAULT_BOARD 자동 시드 → 컬럼 3개 + 카드 확인
//   S2. 카드 이동       — PointerSensor로 ATLAS-1(TODO→IN PROGRESS) 실제 드래그
//   S3. DONE 이동       — ATLAS-4를 DONE으로 드래그 → ResolutionPickerModal 열림 → 확인
//   S4. 충돌 토스트     — addInitScript 409 플래그 → 드래그 후 충돌 토스트 + 카드 원위치
//   S5. 보드 생성       — 새 projectKey(NEWPROJ) 진입 → CreateBoardForm → 보드 생성 → 컬럼 표시
//
// 설계 결정.
//   - board-fixtures.ts 모듈 로드 시 seedBoard(DEFAULT_BOARD) 자동 호출(MODE!=='test').
//     loginAsAlice → page.goto('/projects/ATLAS/board?board=...') 진입 시 이미 카드가 있다.
//   - 드래그 구현: PointerSensor (activationConstraint: {distance:5}).
//     page.mouse.down → move(+6px) → move(대상 컬럼 중심, steps:20) → up.
//     KeyboardSensor ArrowRight는 headless에서 동일 컬럼 droppable로 떨어지는 문제가 확인됨
//     (over.id가 TODO columnId로 복귀 → resolveDropAction noop). PointerSensor로 채택.
//   - 컨테이너 한정 셀렉터(playwright-getbyrole-exact-strict-mode): strict-mode 위반 방지.
//   - MSW serviceWorkers:'block' 금지(e2e-msw-serviceworker-block).
//   - 409 토글: addInitScript + localStorage 플래그(e2e-msw-scenario-toggle-localstorage-flag).
//   - reload 금지(store 리셋 = 가짜그린). SPA goto로 보드 페이지 직접 진입.
//   - board-fixtures.ts 직접 import 금지 — import.meta.env.MODE 참조가 Node.js 런타임 오류 유발.
//     필요한 상수(boardId, LS키)를 인라인으로 동기화 정의.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts에서 import하지 않고 인라인 정의
//
// board-fixtures.ts는 모듈 레벨에서 import.meta.env.MODE를 참조하므로
// Playwright Node.js 런타임에서 직접 import하면 import.meta 접근 오류가 발생한다.
// 다른 핸들러 파일(issue-handlers 등)은 import.meta를 사용하지 않아 직접 import 가능.
// 이 spec은 필요한 상수를 board-fixtures.ts와 동기화해 인라인으로 정의한다.
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts의 LS_KEY_BOARD_CONFLICT와 동기화 */
const LS_KEY_BOARD_CONFLICT = '__bts_e2e_board_conflict'

/** board-fixtures.ts의 DEFAULT_BOARD.boardId와 동기화 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** board-fixtures.ts의 DEFAULT_BOARD.projectKey와 동기화 */
const DEFAULT_PROJECT_KEY = 'ATLAS'

/**
 * DEFAULT_BOARD를 직접 사용하는 보드 URL.
 * board-fixtures.ts 자동 시드 → 로그인 후 이 URL로 진입하면 카드가 있는 보드가 표시된다.
 */
const BOARD_URL = `/projects/${DEFAULT_PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** 드래그 대상 카드 issueKey — DEFAULT_BOARD.columns[0].cards[0] */
const DRAG_CARD_KEY = 'ATLAS-1'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 컬럼 locator (role=group, aria-label에 columnName 포함)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * role="group" + aria-label에 columnName이 포함된 첫 번째 요소를 반환한다.
 * BoardColumn: aria-label={`${column.name} 컬럼, ${column.cards.length}개 카드`}
 * 카드 이동 후 카운트가 변하므로 filter(hasText) 대신 getByRole + filter 사용.
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

/**
 * aria-roledescription="draggable card" 요소 중 issueKey가 aria-label에 포함된 카드 locator.
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
 * 카드를 PointerSensor로 드래그해 대상 컬럼 컨테이너로 이동한다.
 *
 * @dnd-kit PointerSensor activationConstraint: { distance: 5 } —
 * pointerdown 후 5px 초과 이동 시 드래그가 시작된다.
 *
 * 구현 시퀀스:
 *   1. 카드 중심에 pointerdown
 *   2. 6px 이동 (activation threshold 초과)
 *   3. 대상 컬럼 중심으로 점진적 이동 (steps: 20)
 *   4. pointerup
 *
 * @param page Playwright Page
 * @param issueKey 드래그할 카드 issueKey
 * @param targetColumnName 대상 컬럼 이름 (예: 'IN PROGRESS', 'DONE')
 */
async function dragCardToColumn(
  page: import('@playwright/test').Page,
  issueKey: string,
  targetColumnName: string,
): Promise<void> {
  const card = getCardLocator(page, issueKey)
  const targetColumn = getColumnLocator(page, targetColumnName)

  // 카드와 대상 컬럼의 bounding box
  const cardBox = await card.boundingBox()
  const colBox = await targetColumn.boundingBox()

  if (cardBox === null || colBox === null) {
    throw new Error(
      `dragCardToColumn: bounding box를 가져올 수 없습니다. issueKey=${issueKey}, targetColumn=${targetColumnName}`,
    )
  }

  // 카드 중심 좌표
  const cardCX = cardBox.x + cardBox.width / 2
  const cardCY = cardBox.y + cardBox.height / 2

  // 대상 컬럼 중심 좌표 (droppable div는 컬럼 내 하단 영역)
  const colCX = colBox.x + colBox.width / 2
  const colCY = colBox.y + colBox.height / 2

  // PointerSensor 시퀀스
  await page.mouse.move(cardCX, cardCY)
  await page.mouse.down()
  // activation constraint 초과: 6px 이동
  await page.mouse.move(cardCX + 6, cardCY)
  // 대상 컬럼으로 점진적 이동 (steps로 pointermove 이벤트 여러 번 발화)
  await page.mouse.move(colCX, colCY, { steps: 20 })
  await page.mouse.up()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-BD-01 칸반 보드 (보드 조회/카드 이동/resolution/충돌)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 보드 조회
  //
  // Given  alice 로그인 (ServiceWorker 활성)
  //        board-fixtures 자동 시드 → DEFAULT_BOARD가 boardStore에 존재
  // When   /projects/ATLAS/board?board={DEFAULT_BOARD.boardId} 진입
  // Then   TODO / IN PROGRESS / DONE 컬럼 표시
  //        TODO 컬럼에 ATLAS-1 카드 표시
  //        IN PROGRESS 컬럼에 ATLAS-2 카드 표시
  //        DONE 컬럼에 ATLAS-3 카드 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 보드 조회 — DEFAULT_BOARD 자동 시드 + 컬럼 3개 + 카드 렌더', async ({ page }) => {
    // Given. alice 로그인 → ServiceWorker 활성화
    await loginAsAlice(page)

    // When. 보드 페이지 직접 진입 (DEFAULT_BOARD.boardId 사용)
    await page.goto(BOARD_URL)

    // Then. 컬럼 3개 표시
    await expect(getColumnLocator(page, 'TODO')).toBeVisible()
    await expect(getColumnLocator(page, 'IN PROGRESS')).toBeVisible()
    await expect(getColumnLocator(page, 'DONE')).toBeVisible()

    // Then. 각 컬럼에 카드 표시 (컬럼 컨테이너 한정 — strict-mode 방지)
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    const inProgressColumn = getColumnLocator(page, 'IN PROGRESS')
    await expect(inProgressColumn.getByText('ATLAS-2')).toBeVisible()

    const doneColumn = getColumnLocator(page, 'DONE')
    await expect(doneColumn.getByText('ATLAS-3')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 카드 이동 — PointerSensor (TODO → IN PROGRESS)
  //
  // Given  보드 로드 완료, TODO 컬럼에 ATLAS-1 카드 존재
  // When   ATLAS-1 카드를 PointerSensor로 IN PROGRESS 컬럼으로 드래그
  //        (pointerdown → 6px 이동으로 activation → 대상 컬럼 중심으로 이동 → pointerup)
  // Then   낙관적 업데이트: ATLAS-1이 IN PROGRESS 컬럼 컨테이너에 나타남
  //        TODO 컬럼에서 ATLAS-1이 사라짐
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 카드 이동 — PointerSensor로 ATLAS-1을 TODO→IN PROGRESS 드래그', async ({ page }) => {
    // Given. alice 로그인 + 보드 진입
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    // Given. ATLAS-1이 TODO에 있는지 확인
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()

    // When. PointerSensor 드래그 — TODO → IN PROGRESS
    await dragCardToColumn(page, DRAG_CARD_KEY, 'IN PROGRESS')

    // Then. IN PROGRESS 컬럼 컨테이너 안에 ATLAS-1 나타남 (낙관적 이동)
    const inProgressColumn = getColumnLocator(page, 'IN PROGRESS')
    await expect(inProgressColumn.getByText('ATLAS-1')).toBeVisible()

    // Then. TODO 컬럼에서 ATLAS-1 사라짐
    await expect(todoColumn.getByText('ATLAS-1')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. DONE 이동 + ResolutionPickerModal
  //
  // Given  보드 로드 완료, TODO 컬럼에 ATLAS-4 카드 존재
  // When   ATLAS-4를 DONE 컬럼으로 PointerSensor 드래그
  // Then   ResolutionPickerModal 열림 — "해결 방안 선택" 타이틀 표시
  //        결의안 Select에서 'Fixed' 선택 → 확인 클릭
  //        ATLAS-4가 DONE 컬럼에 나타남
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 DONE 이동 — PointerSensor 드래그 → ResolutionPickerModal → 확인', async ({ page }) => {
    // Given. alice 로그인 + 보드 진입
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    // Given. ATLAS-4가 TODO에 있는지 확인
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-4를 DONE 컬럼으로 PointerSensor 드래그
    await dragCardToColumn(page, 'ATLAS-4', 'DONE')

    // Then. ResolutionPickerModal 열림 (radix-ui Dialog portal → document 전체에서 찾음)
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByText('해결 방안 선택')).toBeVisible()

    // Then. 결의안 Select 표시 확인
    const resolutionSelect = page.getByRole('combobox', { name: '결의안' })
    await expect(resolutionSelect).toBeVisible()

    // When. Select 열기 → Fixed 선택
    await resolutionSelect.click()
    await page.getByRole('option', { name: 'Fixed', exact: true }).click()

    // When. 확인 버튼 클릭
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. 모달 닫힘
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Then. ATLAS-4가 DONE 컬럼에 나타남 (낙관적 이동)
    const doneColumn = getColumnLocator(page, 'DONE')
    await expect(doneColumn.getByText('ATLAS-4')).toBeVisible()

    // Then. TODO 컬럼에서 ATLAS-4 사라짐
    await expect(todoColumn.getByText('ATLAS-4')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. 409 충돌 — 드래그 후 충돌 토스트 + 카드 원위치
  //
  // Given  addInitScript로 LS_KEY_BOARD_CONFLICT='true' 심기 (goto 전 등록)
  //        alice 로그인 + 보드 진입, ATLAS-1이 TODO에 존재
  // When   ATLAS-1을 IN PROGRESS로 PointerSensor 드래그
  //        → move API가 409 AGILE_CONFLICT 반환
  //        → useMoveCard onError: 스냅샷 롤백 + invalidateQueries
  //        → KanbanBoard onError: toast.error 호출
  // Then   "다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요." 토스트 표시
  //        ATLAS-1이 TODO 컬럼에 원위치
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 409 충돌 — 드래그 후 충돌 토스트 + 카드 원위치', async ({ page }) => {
    // Given. addInitScript로 충돌 플래그 심기 (goto 이전 등록 — 첫 로드부터 적용)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_BOARD_CONFLICT)

    // Given. alice 로그인 + 보드 진입
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    // Given. ATLAS-1이 TODO에 있는지 확인
    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()

    // When. PointerSensor 드래그 → move API 409 반환 예상
    await dragCardToColumn(page, DRAG_CARD_KEY, 'IN PROGRESS')

    // Then. 충돌 토스트 표시 (sonner Toaster — text로 단언)
    await expect(
      page.getByText('다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요.'),
    ).toBeVisible()

    // Then. ATLAS-1이 TODO 컬럼에 원위치 (낙관적 롤백 + invalidateQueries → DEFAULT_BOARD 재로드)
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()

    // Then. IN PROGRESS 컬럼에 ATLAS-1이 없음
    const inProgressColumn = getColumnLocator(page, 'IN PROGRESS')
    await expect(inProgressColumn.getByText('ATLAS-1')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. 보드 생성 — 빈 프로젝트 진입 → CreateBoardForm → 보드 생성 → 컬럼 표시
  //
  // Given  alice 로그인
  //        boardStore에 'NEWPROJ' 보드 없음 (DEFAULT_BOARD는 ATLAS만)
  // When   /projects/NEWPROJ/board 진입 → CreateBoardForm 표시
  //        보드 이름 입력 → "보드 만들기" 클릭
  // Then   KanbanBoard 렌더 — TODO / IN PROGRESS / DONE 컬럼 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 보드 생성 — 빈 프로젝트 진입 → 생성 폼 → 보드 생성 → 컬럼 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. NEWPROJ 보드 페이지 진입 — boardStore에 없으므로 CreateBoardForm 표시
    await page.goto('/projects/NEWPROJ/board')

    // Then. CreateBoardForm 표시 확인
    await expect(page.getByText('보드가 없습니다')).toBeVisible()
    await expect(page.getByRole('button', { name: '보드 만들기', exact: true })).toBeVisible()

    // When. 보드 이름 입력
    await page.getByLabel('보드 이름').fill('신규 보드')

    // When. 보드 생성 버튼 클릭
    await page.getByRole('button', { name: '보드 만들기', exact: true }).click()

    // Then. KanbanBoard 렌더 — 컬럼 3개
    await expect(getColumnLocator(page, 'TODO')).toBeVisible()
    await expect(getColumnLocator(page, 'IN PROGRESS')).toBeVisible()
    await expect(getColumnLocator(page, 'DONE')).toBeVisible()
  })
})
