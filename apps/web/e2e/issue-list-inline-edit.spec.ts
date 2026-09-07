// FR-UX-11 F9 E2E — 이슈 목록 셀 인라인 편집 (담당자·우선순위·상태)
//
// 시나리오
//   S1  담당자 셀 클릭 → 검색 → 선택 → 셀 값 반영
//   S2  우선순위 셀 클릭 → 선택 → 셀 값 반영
//   S3  상태 셀 클릭 → **그 이슈의 가용 전환만** 노출 (다른 이슈의 전환은 안 보인다)
//   S4  편집 대상이 아닌 셀(요약)·행 여백 클릭 → 기존대로 상세 이동 (회귀 0 의 증인)
//   S6  popover 를 Esc 로 닫아도 상세로 이동하지 않는다
//   E14 종료 전환(toCategory === 'DONE') → popover 가 닫히고 결의안 모달이 뜬다.
//       **결의안 없이 전환 요청이 나가지 않는다** (네트워크로 단정)
//   E10 popover 가 열린 동안 j/k 가 목록 커서를 움직이지 않는다
//   FR3 편집 셀 클릭이 상세를 열지 않는다
//   §시각검증7 편집 셀 텍스트를 드래그로 선택할 수 있다 (F8 `<button>` user-select 회귀면)
//
// 설계 메모
//   - 셀렉터는 `exact: true` 를 기본으로 쓴다. 우선순위 표기는 한국어이고 `가장 높음` 이
//     `높음` 을 부분 포함하므로 exact 없이는 strict mode 로 즉사한다.
//   - 편집 트리거의 접근성 이름은 이슈 키 접두다(`ATLAS-1 우선순위 변경`). 목록은 행이
//     여러 개라 키 접두가 없으면 역시 strict mode 위반이 난다.
//   - `미배정` 은 필터 체크박스 라벨과 같은 낱말이라 `getByText` 로 잡지 않는다. 담당자
//     셀은 트리거 버튼(`ATLAS-1 담당자 변경`)으로 한정해 읽는다.
//   - 전환 이름은 MSW 워크플로우 fixture(`softwareDefaultFixture`) 의 영문 정본이다
//     (`Start Work` / `Cancel`). 한국어 추정 문구를 쓰면 실패한다.
//   - MSW `serviceWorkers:'block'` 금지 — 앱 부팅이 깨진다.

import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueAtlas1Fixture, issueAtlas2Fixture } from '../src/mocks/issue-fixtures'
import { softwareDefaultFixture } from '../src/mocks/workflow-fixtures'
import { userCarolFixture } from '../src/mocks/user-fixtures'
import { issueDetailStrings } from '../src/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 전부 정본에서 파생한다 (fixture 가 바뀌면 이 spec 도 함께 움직인다)
// ─────────────────────────────────────────────────────────────────────────────

/** 편집 대상 이슈 — status=open · 미배정 · priority=3(보통) */
const TARGET_KEY = issueAtlas1Fixture.key

/** 다른 상태(in_progress)의 이슈 — S3 의 분별자 */
const OTHER_KEY = issueAtlas2Fixture.key

/** 상태 키 → 카테고리 (DONE 판별용) */
function categoryOf(stateKey: string): string | undefined {
  return softwareDefaultFixture.states.find((s) => s.key === stateKey)?.category
}

/**
 * 어떤 상태에서 나가는 전환 이름 하나를 fixture 에서 뽑는다.
 *
 * 상수를 손으로 적지 않는 이유 — fixture 가 바뀌면 이 spec 이 **조용히 공허해지는** 대신
 * 즉시 실패해야 한다. 못 찾으면 던진다.
 *
 * @param stateKey 출발 상태 키
 * @param wantDone true 면 종료(DONE) 전환, false 면 비종료 전환
 * @returns 전환 표시 이름
 */
function transitionNameFrom(stateKey: string, wantDone: boolean): string {
  const found = softwareDefaultFixture.transitions.find(
    (t) => t.fromStateKey === stateKey && (categoryOf(t.toStateKey) === 'DONE') === wantDone,
  )
  if (found === undefined) {
    throw new Error(`fixture 에 ${stateKey} → ${wantDone ? 'DONE' : '비DONE'} 전환이 없다`)
  }
  return found.name
}

/**
 * 상태 배지에 실제로 보이는 표기 — 워크플로우에서 해석한 **이름**이다.
 *
 * ★`currentStateKey`(원시 키 `open`)가 아니다. 배지는 이제 `useWorkflows()` 로 이름을
 * 풀어 `Open` 을 보인다 — 화면에 원시 키를 내지 않기 위한 의도된 변경이다.
 * 키를 그대로 단정하면 「사용자가 보는 글자」가 아니라 「서버가 준 식별자」를 재는 셈이라,
 * 표기를 고쳐도 테스트가 못 잡거나 지금처럼 무관하게 깨진다.
 */
function stateNameFrom(stateKey: string): string {
  const found = softwareDefaultFixture.states.find((s) => s.key === stateKey)
  if (found === undefined) throw new Error(`fixture 에 ${stateKey} 상태가 없다`)
  return found.name
}

/** ATLAS-1(open) 상태 배지에 보이는 표기 — `Open` */
const OPEN_STATE_LABEL = stateNameFrom(issueAtlas1Fixture.currentStateKey)

/** ATLAS-1(open) 에서 나가는 비종료 전환 이름 — `Start Work` */
const OPEN_NON_DONE_TRANSITION = transitionNameFrom(issueAtlas1Fixture.currentStateKey, false)

/** ATLAS-1(open) 에서 나가는 **종료** 전환 이름 — `Cancel` (E14 의 방아쇠) */
const OPEN_DONE_TRANSITION = transitionNameFrom(issueAtlas1Fixture.currentStateKey, true)

/** ATLAS-2(in_progress) 에서만 나가는 전환 이름 — ATLAS-1 popover 에 보이면 안 된다 */
const OTHER_ONLY_TRANSITION = transitionNameFrom(issueAtlas2Fixture.currentStateKey, false)

/** 우선순위 3(보통) — ATLAS-1 의 초기 표기 */
const PRIORITY_INITIAL_LABEL = issueDetailStrings.priorityNames[3]

/** 우선순위 2(높음) — S2 가 고르는 값 */
const PRIORITY_TARGET_LABEL = issueDetailStrings.priorityNames[2]

/** 담당자 후보 표시 이름 — carol */
const CAROL_NAME = userCarolFixture.displayName ?? userCarolFixture.username

/** 결의안 모달 제목 (ResolutionModal DialogTitle — 비 export 라 하드코딩, keyboard-shortcuts.spec 선례) */
const RESOLUTION_DIALOG_TITLE = '종료 결의안 선택'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 편집 트리거 로케이터 — 접근성 이름은 `<이슈키> <필드> 변경` 형태다 */
function editTrigger(page: Page, issueKey: string, field: string): Locator {
  // ★접두 앵커 정규식이다. 접근성 이름에 **현재 값**이 함께 실리므로
  // (`ATLAS-1 우선순위 변경, 현재 보통` — 리뷰 C4: aria-label 이 자식 텍스트를 덮어
  // 화면낭독기에서 값이 사라지던 회귀를 봉합) 완전일치로 잡으면 값이 바뀌는 순간
  // 로케이터가 죽는다. S2 처럼 같은 로케이터로 변경 전후를 재는 테스트가 그 예다.
  //
  // `exact: true` 규율은 **선택지 버튼**에서 그대로 유지한다 — `가장 높음` 이 `높음` 을
  // 부분 포함하는 문제는 거기서 나온다. 트리거는 앵커 정규식이 완전일치보다 더 엄격하다
  // (접두 고정 + 이슈 키로 행 특정).
  return page.getByRole('button', { name: new RegExp(`^${issueKey} ${field} 변경`) })
}

/** alice 로 로그인하고 목록 테이블 + 대상 행이 뜰 때까지 기다린다 */
async function gotoIssueList(page: Page, url = '/issues'): Promise<void> {
  await loginAsAlice(page)
  await page.goto(url)
  await expect(page.getByRole('table', { name: '이슈 목록' })).toBeVisible()
  await expect(page.getByTestId(`issue-summary-${TARGET_KEY}`)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-11 F9 목록 셀 인라인 편집', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 담당자
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 담당자 셀에서 검색해 고르면 셀 값이 바뀐다', async ({ page }) => {
    // Given 미배정 이슈가 목록에 있다
    await gotoIssueList(page)
    const trigger = editTrigger(page, TARGET_KEY, '담당자')
    await expect(trigger).toHaveText('미배정')

    // When 담당자 셀을 열고 이름으로 검색해 후보를 고른다
    await trigger.click()
    await page.getByRole('textbox', { name: '담당자 검색', exact: true }).fill('carol')
    await page.getByRole('button', { name: CAROL_NAME, exact: true }).click()

    // Then 셀이 새 담당자로 바뀐다
    await expect(trigger).toHaveText(CAROL_NAME)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 우선순위
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 우선순위 셀에서 고르면 셀 값이 바뀐다', async ({ page }) => {
    // Given 우선순위가 `보통` 인 이슈가 목록에 있다
    await gotoIssueList(page)
    const trigger = editTrigger(page, TARGET_KEY, '우선순위')
    await expect(trigger).toHaveText(PRIORITY_INITIAL_LABEL)

    // When 우선순위 셀을 열고 `높음` 을 고른다
    await trigger.click()
    await page.getByRole('button', { name: PRIORITY_TARGET_LABEL, exact: true }).click()

    // Then 셀이 `높음` 으로 바뀐다 (`가장 높음` 이 아니다 — exact 비교)
    await expect(trigger).toHaveText(PRIORITY_TARGET_LABEL)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 상태 — 가용 전환만
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 상태 셀은 그 이슈의 가용 전환만 노출한다', async ({ page }) => {
    // Given open 이슈(ATLAS-1)와 in_progress 이슈(ATLAS-2)가 함께 목록에 있고,
    //       ATLAS-2 의 셀에는 in_progress 전환이 실제로 뜬다.
    //       ★이 대조가 없으면 아래 `toHaveCount(0)` 은 "그 문구가 앱 어디에도 없어서"
    //       통과하는 공허한 단정이 된다.
    await gotoIssueList(page)
    await editTrigger(page, OTHER_KEY, '상태').click()
    await expect(page.getByRole('button', { name: OTHER_ONLY_TRANSITION, exact: true })).toBeVisible()
    await page.keyboard.press('Escape')

    // When ATLAS-1 의 상태 셀을 연다
    await editTrigger(page, TARGET_KEY, '상태').click()

    // Then open 에서 나가는 전환만 보이고, 다른 이슈의 전환은 보이지 않는다
    await expect(
      page.getByRole('button', { name: OPEN_NON_DONE_TRANSITION, exact: true }),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: OTHER_ONLY_TRANSITION, exact: true }),
    ).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 회귀 0 — 비편집 영역 클릭은 기존대로 상세로 간다
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 요약 셀 클릭은 기존대로 상세를 연다', async ({ page }) => {
    // Given 목록이 보인다
    await gotoIssueList(page)

    // When 편집 대상이 아닌 요약 셀을 클릭한다
    await page.getByTestId(`issue-summary-${TARGET_KEY}`).click()

    // Then 기존대로 상세가 열린다 — 표시 방식 기본이 모달이라 URL 대신 dialog 로 도착지를 잰다(J1)
    await expect(page.getByRole('dialog', { name: `이슈 상세 ${TARGET_KEY}` })).toBeVisible()
  })

  test('S4 행 여백(수정일 셀) 클릭도 기존대로 상세를 연다', async ({ page }) => {
    // Given 목록이 보인다
    await gotoIssueList(page)

    // When 편집 대상이 아닌 행 영역(수정일 셀)을 클릭한다
    const row = page.getByRole('row').filter({ has: page.getByTestId(`issue-summary-${TARGET_KEY}`) })
    await row.getByRole('cell').last().click()

    // Then 기존대로 상세가 열린다 — 표시 방식 기본이 모달이라 URL 대신 dialog 로 도착지를 잰다(J1)
    await expect(page.getByRole('dialog', { name: `이슈 상세 ${TARGET_KEY}` })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR3 편집 셀 클릭은 상세를 열지 않는다
  // ───────────────────────────────────────────────────────────────────────────
  test('FR3 편집 셀 클릭은 상세를 열지 않는다', async ({ page }) => {
    // Given 목록이 보인다
    await gotoIssueList(page)
    const before = page.url()

    // When 편집 셀(우선순위)을 클릭한다
    await editTrigger(page, TARGET_KEY, '우선순위').click()

    // Then popover 만 열리고 URL 은 그대로다 (행 클릭으로 전파되지 않았다)
    await expect(page.getByRole('button', { name: PRIORITY_TARGET_LABEL, exact: true })).toBeVisible()
    await expect(page).toHaveURL(before)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6 Esc
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 Esc 로 popover 를 닫아도 상세로 이동하지 않는다', async ({ page }) => {
    // Given 우선순위 popover 가 열려 있다
    await gotoIssueList(page)
    const before = page.url()
    await editTrigger(page, TARGET_KEY, '우선순위').click()
    const option = page.getByRole('button', { name: PRIORITY_TARGET_LABEL, exact: true })
    await expect(option).toBeVisible()

    // When Esc 를 누른다
    await page.keyboard.press('Escape')

    // Then 닫히기만 하고 상세로 튀지 않는다
    await expect(option).toHaveCount(0)
    await expect(page).toHaveURL(before)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E14 종료 전환 — 결의안 없이 전환이 나가지 않는다
  // ───────────────────────────────────────────────────────────────────────────
  test('E14 종료 전환을 고르면 popover 가 닫히고 결의안 모달이 뜬다', async ({ page }) => {
    // Given 목록이 보인다
    await gotoIssueList(page)

    // When 종료(DONE) 전환을 고른다
    await editTrigger(page, TARGET_KEY, '상태').click()
    await page.getByRole('button', { name: OPEN_DONE_TRANSITION, exact: true }).click()

    // Then popover 는 닫히고 결의안 모달이 뜬다
    await expect(page.getByRole('dialog', { name: RESOLUTION_DIALOG_TITLE })).toBeVisible()
    await expect(page.getByRole('button', { name: OPEN_DONE_TRANSITION, exact: true })).toHaveCount(0)
  })

  test('E14 결의안을 고르지 않으면 전환 요청이 나가지 않는다', async ({ page }) => {
    // Given 전환 요청을 세는 관찰자를 붙이고 목록을 연다
    let transitionCalls = 0
    await page.route(`**/api/v1/issues/${TARGET_KEY}/transition`, async (route) => {
      transitionCalls += 1
      await route.continue()
    })
    await gotoIssueList(page)
    const statusTrigger = editTrigger(page, TARGET_KEY, '상태')
    await expect(statusTrigger).toHaveText(OPEN_STATE_LABEL)

    // When 종료 전환을 고른 뒤 결의안을 고르지 않고 모달을 닫는다
    await statusTrigger.click()
    await page.getByRole('button', { name: OPEN_DONE_TRANSITION, exact: true }).click()
    const dialog = page.getByRole('dialog', { name: RESOLUTION_DIALOG_TITLE })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '취소', exact: true }).click()

    // Then 상태는 그대로고 전환 요청은 한 번도 나가지 않았다
    await expect(statusTrigger).toHaveText(OPEN_STATE_LABEL)
    expect(transitionCalls).toBe(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E10 popover 가 열린 동안 목록 커서 단축키가 죽는다
  // ───────────────────────────────────────────────────────────────────────────
  test('E10 popover 가 열린 동안 j/k 는 목록 커서를 움직이지 않는다', async ({ page }) => {
    // Given 이 화면에서 커서 단축키가 **살아 있다**. ★대조 없이 아래 단정만 두면 j 가
    //       통째로 죽은 상황에서도 통과하는 공허한 테스트가 된다 (F10 자산 확인 겸용).
    await gotoIssueList(page, `/issues?selected=${TARGET_KEY}`)
    await page.keyboard.press('j')
    await expect(page).not.toHaveURL(new RegExp(`selected=${TARGET_KEY}$`))
    const cursorKey = new URL(page.url()).searchParams.get('selected') ?? ''
    expect(cursorKey).not.toBe('')

    // When 그 행의 우선순위 popover 를 연 채로 j·k 를 누른다
    await editTrigger(page, cursorKey, '우선순위').click()
    await expect(page.getByRole('button', { name: PRIORITY_TARGET_LABEL, exact: true })).toBeVisible()
    const before = page.url()
    await page.keyboard.press('j')
    await page.keyboard.press('k')

    // Then 커서(=?selected) 가 움직이지 않는다
    await expect(page).toHaveURL(before)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // §시각검증7 드래그 복사 — F8 이 `<button>` 감싸기로 죽였던 회귀면
  // ───────────────────────────────────────────────────────────────────────────
  test('편집 셀 텍스트를 드래그로 선택할 수 있다 (복사 회귀 차단)', async ({ page }) => {
    // Given 우선순위 셀에 `보통` 텍스트가 보인다
    await gotoIssueList(page)
    const trigger = editTrigger(page, TARGET_KEY, '우선순위')
    const box = await trigger.boundingBox()
    expect(box).not.toBeNull()
    if (box === null) return

    // When 셀 텍스트 위를 실제로 드래그한다.
    // ★시작점은 **우선순위 아이콘 바로 뒤**다. 아이콘이 셀 첫 내용이라 셀 왼쪽 패딩에서
    //   시작하면 텍스트 앵커가 잡히지 않는다 — `user-select` 와 무관한 기하 문제이고,
    //   이 테스트가 지키려는 것은 「`<button>` 으로 감싸도 텍스트를 복사할 수 있는가」다.
    //   좌표를 박지 않고 아이콘 상자에서 계산해, 아이콘 크기가 바뀌어도 살아남게 한다.
    const iconBox = await trigger.locator('[data-testid^="priority-icon-"]').boundingBox()
    const startX = iconBox === null ? box.x + 2 : iconBox.x + iconBox.width + 1
    const y = box.y + box.height / 2
    await page.mouse.move(startX, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width - 2, y, { steps: 8 })
    await page.mouse.up()

    // Then 선택이 잡힌다 (`user-select: none` 이면 빈 문자열이 된다)
    const selected = await page.evaluate(() => window.getSelection()?.toString() ?? '')
    expect(selected).toContain(PRIORITY_INITIAL_LABEL)
  })
})
