// 부채 177 E2E — 보드 설정 Columns 탭 한 바퀴 (진입 → 매핑 → WIP → 추가 → 삭제 → 게이팅)
//
// 시나리오 개요 (spec `2026-09-04-board-settings-screen.md` S1~S8).
//   S1. 진입      — 보드 화면 `⋯` → 「보드 설정」 → Columns 탭이 열린다
//   S2. 매핑      — 미매핑 상태를 컬럼으로 (키보드로 한다)
//   S4. WIP 편집  — 최대 카드 수를 넣고 지운다
//   S5. 컬럼 추가 — 상태 0개 컬럼이 만들어지고 「상태 없음」으로 보인다
//   S6. 컬럼 삭제 — 폭발 반경을 먼저 보이고, 상태를 가진 컬럼을 지우면 그 상태가 미매핑으로 돌아온다
//   S8. 미지목    — `?board=` 없이 들어오면 보드를 지목하라고 안내한다
//   S9. 탭바      — 5탭이 다 있고 **전부 전환되며** 비활성 골격이 0개다 (J22)
//  S10. 카드 레이아웃 — 스크럼 보드에서 보드 뷰와 백로그 뷰가 **서로 다른** 구성을 갖는다 (J17·J18)
//
// **여기서 재지 않는 것 — S3(컬럼 → 미매핑 되돌리기).** 종전 이 머리말은 S3 를 잰다고 적었지만
// 실제 단언은 S2 방향 하나뿐이었다(리뷰 CONCERNS C7). 되돌리기를 키보드로 흉내 내려면 미매핑
// 패널까지 25px 씩 수십 번 눌러야 하고, 한 번이라도 지나치면 `over` 가 null 이 되어 조용히
// 아무 일도 일어나지 않는다 — 판정이 아니라 flake 가 된다. 그 방향의 판정은
// `use-column-settings-drag.test.tsx` 의 **T-DR-2** 가 진다(`UNMAPPED_DROP_ID` 로 드롭 →
// 상태 교체 호출). 커버리지가 없는 것이 아니라 **여기가 그 자리가 아니다.**
//
// 설계 결정.
//   - **드래그를 키보드로 한다.** @dnd-kit 의 KeyboardSensor 경로다(Space 로 집고 화살표로
//     옮기고 Space 로 놓는다). 포인터 드래그를 흉내 내는 것보다 안정적이고, 동시에
//     design 리뷰 BLOCKER-1 이 지목한 **키보드 접근 자체를 실제로 잰다** — 센서가 빠지면
//     이 spec 이 red 다. 유닛 테스트는 센서를 모르므로 여기서만 잡힌다.
//   - board-fixtures.ts 를 직접 import 하지 않는다 — 모듈 로드 시 `import.meta.env.MODE` 를
//     참조해 Playwright(Node) 런타임에서 깨진다(board-kanban.spec.ts 가 세운 관례).
//   - 한 test 안에서 잇는다. MSW store 는 페이지 로드마다 픽스처로 되돌아가므로 goto 는 1회뿐이다.
//   - **S10 은 스크럼 보드를 화면에서 만들어 쓴다.** 백로그 뷰는 스크럼에만 있는데(R3)
//     `board-fixtures.ts` 시드는 **전부 칸반**이다. 시드에 스크럼 보드를 하나 더 넣는 길도
//     있었지만 그러면 `backlog-handlers.ts:180`(「그 프로젝트의 **첫 스크럼 보드**가 백로그
//     기본 스코프」)의 판정이 ATLAS 에서 뒤집혀 백로그 계열 spec 이 통째로 흔들린다 —
//     폭발 반경이 이 spec 밖이라 화면에서 만드는 쪽을 골랐다(scrum-board.spec.ts S1 과 같은 길).
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import {
  boardActionsTrigger,
  boardHeader,
  boardSwitcherTrigger,
  goToBoardNameStep,
  selectBoardType,
} from './fixtures/board-helpers'
import { boardLabels } from '../src/i18n/board-labels'

/** board-fixtures.ts 의 DEFAULT_BOARD 와 동기화 (직접 import 금지) */
const BOARD_ID = '10000000-0000-4000-8000-000000000001'
const PROJECT_KEY = 'ATLAS'

const BOARD_URL = `/projects/${PROJECT_KEY}/board?board=${BOARD_ID}`
const SETTINGS_URL = `/projects/${PROJECT_KEY}/board/settings?board=${BOARD_ID}`

/** S10 이 화면에서 만드는 스크럼 보드 이름 — 시드 이름(`ATLAS 보드`)과 겹치지 않게 둔다 */
const SCRUM_BOARD_NAME = '설정용 스크럼 보드'

/**
 * `CardLayoutPanel.tsx` 의 지역 문구 미러 (`labels.heading` · `labels.viewGroupLabel` · 뷰 라벨).
 *
 * ★그 파일은 문구를 `board-labels.ts` 가 아니라 **자기가** 소유한다(Task 16 의 files 제약).
 * 컴포넌트를 import 하면 `import.meta.env` 를 거쳐 Playwright(Node) 런타임에서 깨지므로
 * 이 spec 의 다른 미러 상수(BOARD_ID 등)와 같은 규약으로 값만 옮겨 적고 출처를 남긴다.
 */
const cardLayoutMirror = {
  heading: '카드에 표시할 필드',
  viewGroupLabel: '카드 레이아웃 뷰',
  viewBoard: '보드',
  viewBacklog: '백로그',
  fieldEpic: '에픽',
  fieldPriority: '우선순위',
  fieldAssignee: '담당자',
  fieldLabels: '라벨',
} as const

/**
 * 「준비 중」 류 비활성 골격 문구.
 *
 * `board-labels.ts:309` 가 「[settings.tabs] 의 5탭에 비활성 골격이 하나도 없다. 「준비 중」 류
 * 문구도 두지 않는다」를 계약으로 적어 뒀다. 그 계약을 **화면에서** 재는 자리가 S9 다 —
 * 4탭이 다 구현됐으므로 스텁이 보이면 그것은 회귀다.
 */
const PENDING_STUB_TEXT = '준비 중'

/** 보드 상세 조회 경로 — `useBoard` 의 재조회를 기다릴 때 쓴다 */
const BOARD_DETAIL_PATH_RE = /^\/api\/v1\/boards\/[0-9a-f-]{36}$/

/** 탭 하나 = 라벨 + 그 탭에서만 보이는 표식(h2). 표식이 없으면 「전환됐다」를 못 잰다. */
const TAB_STEPS: readonly { label: string; marker: (panel: Locator) => Locator }[] = [
  {
    label: boardLabels.settings.tabs.columns,
    marker: (panel) =>
      panel.getByRole('heading', { name: boardLabels.settings.unmappedHeading, exact: true }),
  },
  {
    label: boardLabels.settings.tabs.cardLayout,
    marker: (panel) => panel.getByRole('heading', { name: cardLayoutMirror.heading, exact: true }),
  },
  {
    label: boardLabels.settings.tabs.estimation,
    marker: (panel) =>
      panel.getByRole('heading', {
        name: boardLabels.settings.estimation.estimationHeading,
        exact: true,
      }),
  },
  {
    label: boardLabels.settings.tabs.workingDays,
    marker: (panel) =>
      panel.getByRole('heading', {
        name: boardLabels.settings.workingDays.standardHeading,
        exact: true,
      }),
  },
  {
    label: boardLabels.settings.tabs.detailView,
    marker: (panel) =>
      panel.getByRole('heading', { name: boardLabels.settings.detailView.heading, exact: true }),
  },
]

/** 설정 탭바 — 이 화면의 tablist 는 하나뿐이다(`ProjectNavTabs` 는 nav+Link 라 tab 이 아니다). */
function settingsTabBar(page: Page): Locator {
  return page.getByRole('tablist', { name: boardLabels.settings.tabs.ariaLabel })
}

/** 활성 탭 본문. Radix 는 비활성 탭을 마운트하지 않으므로 tabpanel 은 항상 하나다. */
function activeTabPanel(page: Page): Locator {
  return page.getByRole('tabpanel')
}

/**
 * 카드 레이아웃 후보 하나를 토글하고 **저장이 정착할 때까지** 기다린다.
 *
 * ★`waitForTimeout` 을 쓰지 않는다. 그리고 체크 상태만 보고 넘어가서도 안 된다 — 낙관 반영이라
 * 요청이 나가기 전에도 체크는 켜진다. 저장(PATCH)과 그 뒤 보드 **재조회**(GET)를 둘 다 기다려야
 * 다음 재마운트가 서버 값에서 시작한다(`CardLayoutPanel` 의 `onSettled` 무효화).
 */
async function toggleCardLayoutField(page: Page, fieldLabel: string): Promise<void> {
  const patched = page.waitForResponse(
    (res) => res.request().method() === 'PATCH' && res.url().includes('/card-layout'),
  )
  const refetched = page.waitForResponse(
    (res) =>
      res.request().method() === 'GET' && BOARD_DETAIL_PATH_RE.test(new URL(res.url()).pathname),
  )
  await activeTabPanel(page)
    .getByRole('checkbox', { name: fieldLabel, exact: true })
    .click()
  await patched
  await refetched
}

test.describe('보드 설정 — Columns 탭 (부채 177)', () => {
  test('S1~S6 한 바퀴 — 진입 → 매핑 → WIP → 추가 → 삭제', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    // ── S1. 보드 화면 ⋯ 메뉴로 설정에 들어간다 (J7 · 편차 X4) ──────────────────
    //
    // ★**여기가 스코프 없는 셀렉터의 실측 근거다.** 이 화면에서 「보드 관리」에 매치되는 버튼은
    //   **2개**다 — 사이드바 「최근 방문」 목록의 ATLAS 보드 `⋯`(`#454`)와 보드 헤더 `⋯`.
    //   접근성 이름이 `boardLabels.actions.triggerAriaLabel` 한 생산자에서 나와 바이트 단위로
    //   같으므로 전역 `getByRole` 은 strict mode 로 즉사한다. 좁히는 축은 이름이 아니라
    //   **컨테이너**여야 하고, 그것이 `board-helpers.ts` 의 헬퍼가 존재하는 이유다.
    await expect(page.getByRole('button', { name: /보드 관리/ })).toHaveCount(2)
    await expect(boardActionsTrigger(page)).toHaveCount(1)

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

    // ── S6 후단. 상태를 가진 컬럼을 지우면 그 상태가 미매핑으로 돌아온다 (J28) ──
    // ★위에서 지운 「검수」는 **상태 0개** 컬럼이라 돌아올 상태가 없다 — 그 삭제만으로는
    //   J28 을 잰 것이 아니다(리뷰 CONCERNS C7). 상태를 가진 컬럼으로 한 번 더 잰다.
    const todoColumn = page.getByRole('article', { name: 'TODO' })
    await todoColumn.getByRole('button', { name: /컬럼 삭제/ }).click()
    await page
      .getByRole('dialog', { name: boardLabels.settings.deleteColumnTitle('TODO') })
      .getByRole('button', { name: boardLabels.settings.deleteColumnConfirm })
      .click()

    await expect(page.getByRole('article', { name: 'TODO' })).toHaveCount(0)
    // 담겨 있던 상태가 미매핑 패널에 나타난다 — 이슈는 그대로이고 매핑만 풀린다.
    await expect(unmappedPanel.getByText('TODO')).toBeVisible()
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

  /**
   * S9 — 탭바 5탭 (J22).
   *
   * **Given** alice 로 보드가 지목된 설정 화면에 들어와 있다.
   * **When**  탭 5개를 차례로 누른다.
   * **Then**  누른 탭이 선택되고, **그 탭에서만 보이는 표식**이 본문에 뜨며,
   *           「준비 중」 류 비활성 골격이 한 개도 없다.
   *
   * ★**개수만 세면 안 된다.** 5탭을 다 그리되 전환이 안 되는 구현이 개수 단언은 통과한다 —
   *   그래서 탭마다 `aria-selected` 와 **그 탭 고유의 h2** 를 함께 잰다.
   */
  test('S9 — 탭바 5탭이 전부 전환되고 비활성 골격이 하나도 없다 (J22)', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    const tabBar = settingsTabBar(page)
    await expect(tabBar).toBeVisible()
    // ★리터럴 5 다. `TAB_STEPS.length` 로 적으면 탭과 단계를 함께 지운 날 조용히 통과한다.
    await expect(tabBar.getByRole('tab')).toHaveCount(5)

    for (const step of TAB_STEPS) {
      const trigger = tabBar.getByRole('tab', { name: step.label, exact: true })
      await trigger.click()
      await expect(trigger).toHaveAttribute('aria-selected', 'true')

      const panel = activeTabPanel(page)
      await expect(step.marker(panel)).toBeVisible()
      // 4탭이 다 구현됐다 — 스텁이 남아 있으면 그것은 회귀다(board-labels.ts:309 계약).
      await expect(panel.getByText(PENDING_STUB_TEXT)).toHaveCount(0)
    }
  })

  /**
   * S10 — 카드 레이아웃 뷰별 저장 (J17 · J18).
   *
   * **Given** 스크럼 보드(백로그 뷰가 있는 유일한 종류)의 카드 레이아웃 탭에 있다.
   * **When**  보드 뷰에 필드 하나를, 백로그 뷰에 **다른** 필드 둘을 고른 뒤
   *           다른 탭에 갔다 돌아와 패널을 **재마운트**시킨다.
   * **Then**  보드 뷰는 자기 하나만, 백로그 뷰는 자기 둘만 갖는다.
   *
   * ★**일부러 다르게 넣는다**(완료 기준 3). 같은 값을 넣으면 「구성을 한 벌만 저장하는」
   *   구현도 통과한다.
   * ★**재마운트가 판정의 핵심이다.** 재마운트 없이 재면 로컬 state 위에서만 참인 가짜 초록이
   *   된다 — Radix 가 비활성 탭 본문을 마운트하지 않으므로, 돌아온 패널의 초기값은 보드 조회가
   *   실어 온 **서버 값**이다.
   */
  test('S10 — 카드 레이아웃이 보드 뷰와 백로그 뷰에 따로 저장된다 (J17·J18)', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    // ── 스크럼 보드를 화면에서 만든다. 이후 goto 는 없다 — reload 하면 MSW store 가 픽스처로
    //    되돌아가 방금 만든 보드가 증발한다.
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.switcher.createItem }).click()
    const createDialog = page.getByRole('dialog', { name: boardLabels.switcher.createDialogTitle })
    await expect(createDialog).toBeVisible()
    await selectBoardType(createDialog, 'SCRUM')
    await goToBoardNameStep(createDialog)
    // 「보드 이름」·「보드 만들기」는 `CreateBoardForm.tsx` 안의 리터럴이라 `boardLabels` 에 없다.
    await createDialog.getByLabel('보드 이름').fill(SCRUM_BOARD_NAME)
    await createDialog.getByRole('button', { name: '보드 만들기', exact: true }).click()
    await expect(boardSwitcherTrigger(page)).toContainText(SCRUM_BOARD_NAME)

    // ── 헤더 ⋯ → 「보드 설정」 (SPA 이동)
    // 🛑 `board-helpers.ts` 가 요구하는 선단언 — testid 가 어긋나면 하위 조회가 조용히 count 0 이
    //    되고, 이 spec 의 `not.toBeChecked()` 류 부재 단언들이 **그대로 통과**한다.
    await expect(boardHeader(page)).toBeVisible()
    await boardActionsTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.actions.settingsItem }).click()
    await expect(
      page.getByRole('heading', { level: 1, name: boardLabels.settings.pageHeading }),
    ).toBeVisible()

    const tabBar = settingsTabBar(page)
    const cardLayoutTab = tabBar.getByRole('tab', {
      name: boardLabels.settings.tabs.cardLayout,
      exact: true,
    })
    await cardLayoutTab.click()

    const panel = activeTabPanel(page)
    const viewToggle = panel.getByRole('radiogroup', { name: cardLayoutMirror.viewGroupLabel })
    // 뷰 토글은 스크럼에만 있다(R3). 이것이 없으면 J18 을 잴 자리 자체가 없다.
    await expect(viewToggle).toBeVisible()

    // ── 보드 뷰에 에픽 하나 ────────────────────────────────────────────────
    await viewToggle.getByRole('radio', { name: cardLayoutMirror.viewBoard, exact: true }).click()
    await toggleCardLayoutField(page, cardLayoutMirror.fieldEpic)

    // ── 백로그 뷰에 **다른** 둘 ────────────────────────────────────────────
    await viewToggle
      .getByRole('radio', { name: cardLayoutMirror.viewBacklog, exact: true })
      .click()
    // 뷰를 바꾼 순간 앞 뷰의 선택이 따라오지 않는다 — 여기서 이미 「한 벌 공유」가 죽는다.
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldEpic, exact: true }),
    ).not.toBeChecked()
    await toggleCardLayoutField(page, cardLayoutMirror.fieldPriority)
    await toggleCardLayoutField(page, cardLayoutMirror.fieldLabels)

    // ── ★재마운트. 컬럼 탭에 갔다 돌아온다.
    await tabBar.getByRole('tab', { name: boardLabels.settings.tabs.columns, exact: true }).click()
    await expect(
      activeTabPanel(page).getByRole('heading', {
        name: boardLabels.settings.unmappedHeading,
        exact: true,
      }),
    ).toBeVisible()
    await cardLayoutTab.click()
    await expect(viewToggle).toBeVisible()

    // 기본 뷰는 보드다 — 저장한 그 하나만 켜져 있다.
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldEpic, exact: true }),
    ).toBeChecked()
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldPriority, exact: true }),
    ).not.toBeChecked()
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldLabels, exact: true }),
    ).not.toBeChecked()

    // 백로그 뷰는 **자기 둘만** 갖는다 — 보드 뷰의 에픽이 새어 들어오지 않는다.
    await viewToggle
      .getByRole('radio', { name: cardLayoutMirror.viewBacklog, exact: true })
      .click()
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldPriority, exact: true }),
    ).toBeChecked()
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldLabels, exact: true }),
    ).toBeChecked()
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldEpic, exact: true }),
    ).not.toBeChecked()

    // ── J17 상한 3 — 셋째를 채우면 **안 고른** 후보가 잠기고 **고른** 것은 안 잠긴다.
    //    후자가 없으면 「저장 중이라 전부 잠긴 상태」와 구별되지 않는다.
    await toggleCardLayoutField(page, cardLayoutMirror.fieldAssignee)
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldEpic, exact: true }),
    ).toBeDisabled()
    await expect(
      panel.getByRole('checkbox', { name: cardLayoutMirror.fieldPriority, exact: true }),
    ).toBeEnabled()
  })
})
