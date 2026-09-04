// 부채 177 E2E — 보드 설정 Columns 탭 한 바퀴 (진입 → 매핑 → WIP → 추가 → 삭제 → 게이팅)
//
// 시나리오 개요 (spec `2026-09-04-board-settings-screen.md` S1~S8).
//   S1. 진입      — 보드 화면 `⋯` → 「보드 설정」 → Columns 탭이 열린다
//   S2/S3. 매핑   — 미매핑 상태를 컬럼으로, 컬럼에서 미매핑으로 (키보드로 한다)
//   S4. WIP 편집  — 최대 카드 수를 넣고 지운다
//   S5. 컬럼 추가 — 상태 0개 컬럼이 만들어지고 「상태 없음」으로 보인다
//   S6. 컬럼 삭제 — 폭발 반경을 먼저 보이고, 지운 상태는 미매핑으로 돌아온다
//   S8. 미지목    — `?board=` 없이 들어오면 보드를 지목하라고 안내한다
//
// 설계 결정.
//   - **드래그를 키보드로 한다.** @dnd-kit 의 KeyboardSensor 경로다(Space 로 집고 화살표로
//     옮기고 Space 로 놓는다). 포인터 드래그를 흉내 내는 것보다 안정적이고, 동시에
//     design 리뷰 BLOCKER-1 이 지목한 **키보드 접근 자체를 실제로 잰다** — 센서가 빠지면
//     이 spec 이 red 다. 유닛 테스트는 센서를 모르므로 여기서만 잡힌다.
//   - board-fixtures.ts 를 직접 import 하지 않는다 — 모듈 로드 시 `import.meta.env.MODE` 를
//     참조해 Playwright(Node) 런타임에서 깨진다(board-kanban.spec.ts 가 세운 관례).
//   - 한 test 안에서 잇는다. MSW store 는 페이지 로드마다 픽스처로 되돌아가므로 goto 는 1회뿐이다.
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { boardLabels } from '../src/i18n/board-labels'

/** board-fixtures.ts 의 DEFAULT_BOARD 와 동기화 (직접 import 금지) */
const BOARD_ID = '10000000-0000-4000-8000-000000000001'
const PROJECT_KEY = 'ATLAS'

const BOARD_URL = `/projects/${PROJECT_KEY}/board?board=${BOARD_ID}`
const SETTINGS_URL = `/projects/${PROJECT_KEY}/board/settings?board=${BOARD_ID}`

/** 보드 관리 `⋯` 트리거 — 접근성 이름이 「보드 관리, …」 */
function boardActionsTrigger(page: Page) {
  return page.getByRole('button', { name: /보드 관리/ })
}

test.describe('보드 설정 — Columns 탭 (부채 177)', () => {
  test('S1~S6 한 바퀴 — 진입 → 매핑 → WIP → 추가 → 삭제', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    // ── S1. 보드 화면 ⋯ 메뉴로 설정에 들어간다 (J7 · 편차 X4) ──────────────────
    await boardActionsTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.actions.settingsItem }).click()

    await expect(
      page.getByRole('heading', { level: 1, name: boardLabels.settings.pageHeading }),
    ).toBeVisible()
    // 보드가 URL 로 실려 왔다 — 설정 화면은 기본 보드를 스스로 고르지 않는다(S8 의 짝).
    await expect(page).toHaveURL(new RegExp(`board=${BOARD_ID}`))

    // ── S2. 미매핑 상태를 컬럼으로 끌어다 놓는다 — **키보드로** (J27) ───────────
    // mock 카탈로그에 REVIEW 가 있고 어느 컬럼에도 없으므로 미매핑 패널에 있다.
    const unmappedPanel = page.getByRole('region', { name: boardLabels.settings.unmappedHeading })
    await expect(unmappedPanel.getByText('REVIEW')).toBeVisible()

    const reviewBadge = unmappedPanel.getByText('REVIEW')
    await reviewBadge.focus()
    await page.keyboard.press('Space') // 집는다

    // ★한 번으로는 못 건넌다. dnd-kit 기본 KeyboardSensor 는 **한 번에 25px** 만 옮기는데
    //   미매핑 패널이 256px 이라 컬럼에 닿으려면 여러 번 눌러야 한다. 실측으로 확인한 값이고,
    //   그 자체가 키보드 사용자에게 나쁜 조작감이다 — 후속 개선 대상으로 등재했다(부채 181).
    for (let i = 0; i < 25; i += 1) {
      await page.keyboard.press('ArrowLeft')
    }
    await page.keyboard.press('Space') // 놓는다

    // 미매핑 패널에서 사라진다 — 어느 컬럼이 받았는지는 dnd 충돌 판정에 달렸으므로
    // 「패널을 떠났다」만 단언한다. 그것이 이 시나리오의 관찰 지점이다.
    await expect(unmappedPanel.getByText('REVIEW')).toHaveCount(0)

    // ── S4. WIP 제한을 넣고 지운다 (J29 · 편차 X2) ────────────────────────────
    const wipInput = page.getByLabel(boardLabels.settings.wipLimitInputLabel('TODO'))
    await wipInput.fill('3')
    await wipInput.press('Enter')
    await expect(wipInput).toHaveValue('3')

    // 빈 값이 **해제**다 — 0 이 아니다.
    await wipInput.fill('')
    await wipInput.press('Enter')
    await expect(wipInput).toHaveValue('')

    // ★최소치 입력은 없다(편차 X2). 있으면 spinbutton 이 컬럼 수보다 많아진다.
    const columnCount = await page.getByRole('article').count()
    await expect(page.getByRole('spinbutton')).toHaveCount(columnCount)

    // ── S5. 컬럼을 추가하면 상태 0개 컬럼이 생긴다 (J23 · E1) ──────────────────
    await page.getByRole('button', { name: boardLabels.settings.addColumn }).click()
    const addDialog = page.getByRole('dialog', { name: boardLabels.settings.addColumn })
    await addDialog.getByLabel(boardLabels.settings.columnNameLabel).fill('검수')
    await addDialog.getByRole('button', { name: boardLabels.settings.addColumnSubmit }).click()

    const newColumn = page.getByRole('article', { name: '검수' })
    await expect(newColumn).toBeVisible()
    // ★그냥 비워 두지 않는다 — 미완성임이 글자로 보여야 한다.
    await expect(newColumn.getByText(boardLabels.settings.columnNoStates)).toBeVisible()

    // ── S6. 컬럼을 지우면 폭발 반경을 먼저 보인다 (J26 · J28) ──────────────────
    await newColumn
      .getByRole('button', { name: boardLabels.settings.deleteColumnTitle('검수') })
      .click()
    const confirmDialog = page.getByRole('dialog', {
      name: boardLabels.settings.deleteColumnTitle('검수'),
    })
    // 사라지는 카드 수를 **삭제 전에** 보인다.
    await expect(confirmDialog.getByText(/카드/)).toBeVisible()
    await confirmDialog
      .getByRole('button', { name: boardLabels.settings.deleteColumnConfirm })
      .click()

    await expect(page.getByRole('article', { name: '검수' })).toHaveCount(0)
  })

  test('S8 — ?board= 없이 들어오면 보드를 지목하라고 안내한다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(`/projects/${PROJECT_KEY}/board/settings`)

    // ★기본 보드를 스스로 고르지 않는다. 「기본 보드」 규칙이 이미 세 곳에서 서로 다르고
    //   (부채 164) 여기서 네 번째를 만들지 않는다.
    await expect(page.getByText(boardLabels.settings.boardNotSelected)).toBeVisible()
    await expect(page.getByRole('article')).toHaveCount(0)
  })

  test('마지막 컬럼은 지울 수 없다 — 부채 179 도달 차단 (Sanity G1)', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    const columns = page.getByRole('article')
    // count() 는 대기하지 않는다 — 보드 조회가 끝난 뒤에 세야 한다.
    await expect(columns.first()).toBeVisible()
    const initial = await columns.count()
    expect(initial).toBeGreaterThan(1)

    // 하나만 남을 때까지 지운다.
    for (let remaining = initial; remaining > 1; remaining -= 1) {
      const first = columns.first()
      const name = await first.getAttribute('aria-label')
      await first.getByRole('button', { name: /컬럼 삭제/ }).click()
      await page
        .getByRole('dialog', { name: boardLabels.settings.deleteColumnTitle(name ?? '') })
        .getByRole('button', { name: boardLabels.settings.deleteColumnConfirm })
        .click()
      await expect(columns).toHaveCount(remaining - 1)
    }

    // ★마지막 하나는 잠긴다. 이 판정이 없으면 컬럼 0개 보드로 갈 수 있고, 그 보드는
    //   조회가 자가 치유 경합으로 500 이 된다(부채 179).
    await expect(columns.first().getByRole('button', { name: /컬럼 삭제/ })).toBeDisabled()
  })
})
