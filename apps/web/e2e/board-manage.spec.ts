// FR-BD-01-2 E2E — 보드 관리 한 바퀴 (생성 → 전환 → 이름 변경 → 삭제 → 빈 상태 복귀)
//
// 시나리오 개요 (plan `2026-08-31-board-crud-recovery.md` S1~S5).
//   S1. 두 번째 보드 생성 — 스위처 「새 보드」 → 종류 선택(스크럼) → 이름 입력 → 생성된 보드로 이동
//   S2. 보드 전환       — 스위처에서 첫 보드를 다시 고르면 그 보드의 카드가 보인다
//   S3. 이름 변경       — `⋯` → 「이름 변경」 → 새 이름 제출 → 스위처 표기가 갱신된다
//   S4. 삭제           — `⋯` → 「보드 삭제」 → 확인 → 남은 보드로 이동. **이슈는 남는다**
//   S5. 마지막 보드 삭제 — 보드 0개 → `CreateBoardForm` 빈 상태로 복귀
//
// 설계 결정.
//   - **한 test 안에서 S1~S5 를 잇는다.** MSW store 는 페이지 로드마다 픽스처로 되돌아가므로
//     test 를 쪼개면 앞 단계의 결과(생성된 보드)가 사라진다. 그래서 goto 는 처음 1회뿐이고
//     이후 이동은 전부 SPA 안에서 일어난다 (reload 금지 — store 리셋은 가짜그린이다).
//   - board-fixtures.ts 를 직접 import 하지 않는다 — 모듈 로드 시 `import.meta.env.MODE` 를
//     참조해 Playwright(Node) 런타임에서 깨진다. 필요한 상수는 인라인으로 동기화한다
//     (board-kanban.spec.ts 가 세운 관례).
//   - 다이얼로그/메뉴는 role+name 으로 컨테이너를 좁혀 잡는다. 생성 폼의 「보드 만들기」 버튼과
//     빈 상태의 같은 버튼이 화면에 함께 있을 수 있어 strict mode 위반이 나기 쉽다.
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { goToBoardNameStep, selectBoardType } from './fixtures/board-helpers'
import { boardLabels } from '../src/i18n/board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts 와 동기화 (직접 import 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts 의 DEFAULT_BOARD.boardId 와 동기화 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** board-fixtures.ts 의 DEFAULT_BOARD.projectKey 와 동기화 */
const DEFAULT_PROJECT_KEY = 'ATLAS'

/** board-fixtures.ts 의 DEFAULT_BOARD.name 과 동기화 */
const DEFAULT_BOARD_NAME = 'ATLAS 보드'

/** DEFAULT_BOARD 에 시드된 카드 — 「보드를 지워도 이슈는 남는다」의 관찰 지점 */
const SURVIVING_ISSUE_KEY = 'ATLAS-1'

/** 진입 URL — 카드가 있는 보드로 바로 들어간다 */
const BOARD_URL = `/projects/${DEFAULT_PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** S1 이 만드는 두 번째 보드 이름 */
const SECOND_BOARD_NAME = '버그 보드'

/** S3 이 바꿔 넣는 이름 */
const RENAMED_BOARD_NAME = '개선 보드'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 스위처 트리거 — 접근성 이름이 「보드 선택, 현재 …」이라 정규식으로 잡는다 */
function boardSwitcherTrigger(page: Page) {
  return page.getByRole('button', { name: /보드 선택/ })
}

/** 보드 관리 `⋯` 트리거 — 접근성 이름이 「보드 관리, …」 */
function boardActionsTrigger(page: Page) {
  return page.getByRole('button', { name: /보드 관리/ })
}

/** 스위처를 열어 다른 보드를 고른다 */
async function switchToBoard(page: Page, boardName: string): Promise<void> {
  await boardSwitcherTrigger(page).click()
  await page.getByRole('menuitemradio', { name: boardName }).click()
}

/** `⋯` 메뉴를 열어 항목 하나를 고른다 */
async function selectBoardAction(page: Page, itemName: string): Promise<void> {
  await boardActionsTrigger(page).click()
  await page.getByRole('menuitem', { name: itemName }).click()
}

/**
 * 삭제 확인 다이얼로그에서 확인을 눌러 현재 보드를 지운다.
 * 다이얼로그는 성공했을 때만 닫히므로 닫힘까지 기다린다 (S7 계약의 뒷면).
 */
async function deleteCurrentBoard(page: Page): Promise<void> {
  await selectBoardAction(page, boardLabels.actions.deleteItem)

  const dialog = page.getByRole('dialog', { name: boardLabels.actions.deleteDialogTitle })
  await expect(dialog).toBeVisible()
  // 「이슈는 삭제되지 않습니다」가 확인 창 안에 있어야 한다 — 소프트 삭제의 유일한 고지다 (J4)
  await expect(dialog).toContainText('이슈는 삭제되지 않습니다')

  await dialog.getByRole('button', { name: boardLabels.actions.deleteConfirm, exact: true }).click()
  await expect(dialog).toBeHidden()
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('보드 관리 — 생성·전환·이름 변경·삭제 (FR-BD-01-2)', () => {
  test('S1~S5 보드 한 바퀴 — 생성 → 전환 → 이름 변경 → 삭제 → 빈 상태', async ({ page }) => {
    // Given. alice 로그인 후 카드가 있는 ATLAS 보드로 진입
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await expect(boardSwitcherTrigger(page)).toContainText(DEFAULT_BOARD_NAME)

    // ── S1. 두 번째 보드 생성 ────────────────────────────────────────────────
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.switcher.createItem }).click()

    const createDialog = page.getByRole('dialog', {
      name: boardLabels.switcher.createDialogTitle,
    })
    await expect(createDialog).toBeVisible()
    // C1 — 보드가 있는데 「보드가 없습니다」가 뜨면 안 된다 (다이얼로그에서는 인트로를 끈다)
    await expect(createDialog).not.toContainText('보드가 없습니다')

    // 1단계 — 종류를 먼저 묻는다 (FR-BD-04 D6 · J1). 기본 선택이 칸반이라 그냥 넘기면 종류
    // 선택이 실화면에서 한 번도 밟히지 않는다 — 여기서 **스크럼을 명시적으로 고른다**.
    await selectBoardType(createDialog, 'SCRUM')
    await goToBoardNameStep(createDialog)

    // 2단계 — 이름 입력
    await createDialog.getByLabel('보드 이름').fill(SECOND_BOARD_NAME)
    await createDialog.getByRole('button', { name: '보드 만들기', exact: true }).click()

    // Then. 생성된 보드로 이동하고 스위처에 두 보드가 모두 있다
    await expect(boardSwitcherTrigger(page)).toContainText(SECOND_BOARD_NAME)
    await boardSwitcherTrigger(page).click()
    await expect(page.getByRole('menuitemradio', { name: DEFAULT_BOARD_NAME })).toBeVisible()
    await expect(page.getByRole('menuitemradio', { name: SECOND_BOARD_NAME })).toBeVisible()
    await page.keyboard.press('Escape')

    // ── S2. 보드 전환 ───────────────────────────────────────────────────────
    await switchToBoard(page, DEFAULT_BOARD_NAME)
    await expect(boardSwitcherTrigger(page)).toContainText(DEFAULT_BOARD_NAME)
    await expect(page.getByText(SURVIVING_ISSUE_KEY).first()).toBeVisible()

    // ── S3. 이름 변경 ───────────────────────────────────────────────────────
    await selectBoardAction(page, boardLabels.actions.renameItem)

    const renameDialog = page.getByRole('dialog', {
      name: boardLabels.actions.renameDialogTitle,
    })
    await expect(renameDialog).toBeVisible()
    await renameDialog.getByLabel(boardLabels.actions.renameNameLabel).fill(RENAMED_BOARD_NAME)
    await renameDialog
      .getByRole('button', { name: boardLabels.actions.renameSubmit, exact: true })
      .click()

    // Then. 창이 닫히고 스위처 표기가 새 이름으로 갱신된다
    await expect(renameDialog).toBeHidden()
    await expect(boardSwitcherTrigger(page)).toContainText(RENAMED_BOARD_NAME)

    // ── S4. 삭제 — 두 번째 보드를 지우면 남은 보드로 이동하고 이슈는 그대로다 ──
    await switchToBoard(page, SECOND_BOARD_NAME)
    await expect(boardSwitcherTrigger(page)).toContainText(SECOND_BOARD_NAME)

    await deleteCurrentBoard(page)

    await expect(boardSwitcherTrigger(page)).toContainText(RENAMED_BOARD_NAME)
    // ★이슈는 삭제되지 않는다 — 남은 보드의 카드가 그대로 보인다
    await expect(page.getByText(SURVIVING_ISSUE_KEY).first()).toBeVisible()
    await boardSwitcherTrigger(page).click()
    await expect(page.getByRole('menuitemradio', { name: SECOND_BOARD_NAME })).toHaveCount(0)
    await page.keyboard.press('Escape')

    // ── S5. 마지막 보드 삭제 — 빈 상태 복귀 ─────────────────────────────────
    await deleteCurrentBoard(page)

    await expect(page.getByText('보드가 없습니다', { exact: true })).toBeVisible()
    // 빈 상태도 생성 폼 **1단계**로 시작한다 — 그 화면의 진행 버튼은 「다음」이고
    // 「보드 만들기」는 종류를 고른 뒤 2단계에서야 나온다 (FR-BD-04 D6)
    await expect(
      page.getByRole('button', { name: boardLabels.createForm.next, exact: true }),
    ).toBeVisible()
    await expect(boardSwitcherTrigger(page)).toHaveCount(0)
  })
})
