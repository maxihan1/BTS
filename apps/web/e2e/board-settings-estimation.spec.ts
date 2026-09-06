// 보드 설정 — 추정 · 작업일 탭 E2E (부채 177 Task 23 · J36·J37·J38·J39·J40)
//
// ## 이 spec 이 지는 판정 — 각 축이 무엇과 무엇을 가르나
//
// | 뮤테이션 | 죽는 테스트 | 가르는 것 |
// |---|---|---|
// | ① `EstimationPanel.locked` 에서 `!isScrum` 을 뺀다 | S1 | 「칸반에서도 열린다」 ↔ 스크럼 전용 (J37) |
// | ② `locked = true` 로 고정한다 (항상 잠김) | S2 | 「항상 잠긴 구현」 ↔ 스크럼에서 열린다 — ①의 **대조군** |
// | ③ `toSettingsPayload` 가 `timeTracking` 을 응답에서 뺀다 (**배선 절단**) | S2 | 저장이 서버에 닿았나 ↔ 화면 상태만 바뀌었나 |
// | ④ `WorkingDaysPanel` 초기값을 `stored?.standardDays ?? null` → `null` 고정 (**배선 절단**) | S3 | 서버 값에서 시작 ↔ 매번 빈 화면에서 시작 |
// | ⑤ `toSettingsPayload` 가 `workingDays` 를 응답에서 뺀다 (**배선 절단**) | S3·S4 | 위와 같은 축을 **응답 쪽에서** 끊은 것 |
// | ⑥ 타임존 저장에서 `standardDays`/`nonWorkingDates` 를 요청에서 뺀다 | S4 | 「타임존만 바꿨는데 근무일이 날아간다」 ↔ PUT 3축 동시 전송 |
// | ⑦ `WorkingDaysPanel` 의 `days === null` 분기를 지우고 항상 체크박스를 그린다 (**항상 좁힌다**) | S1·S4 대조군 | 미설정을 「근무일 0개」로 뭉갬 ↔ 두 상태를 가름 (R6) |
// | ⑧ 저장을 보드 단위가 아니라 프로젝트 단위로 흘린다 | S4 대조군 | 옆 보드까지 바뀜 ↔ 보드 단위 (편차 X7) |
// | ⑨ 서비스가 근무일을 `BurndownCalculator` 에 넘기지 않는다 (**배선 절단**) | — **이 spec 은 못 잡는다** (아래 ★) | — |
// | ⑩ 타임존을 무시하고 UTC 로 고정한다 | — **이 spec 은 못 잡는다** (아래 ★) | — |
//
// ★★**⑨·⑩ 은 이 spec 이 잡지 못한다 — 그 사실 자체가 이 파일의 산출물이다.**
//   프론트 E2E 는 MSW 목 위에서 돈다. 번다운 응답의 정본은 `src/mocks/burndown-handlers.ts` 의
//   **정적 store** 이고, 그 store 는 보드 작업일 설정(`board-handlers.ts` 의 `boardSettingsStore`)
//   에서 **아무것도 파생하지 않는다.** 즉 화면에서 근무일을 아무리 바꿔도 차트는 바뀔 수가 없다.
//   `BurndownChart` 도 클라이언트 축소를 하지 않는다(응답 `points` 를 그대로 그린다) —
//   좁히는 주체는 백엔드 `BurndownCalculator` 하나뿐이다.
//   ★**이 RED 커밋은 그 예상을 실측한다** — S5·S6 을 마커 없이 두고 실제 실패 원문을 받는다.
//   ⑨·⑩ 의 실제 판정자는 백엔드다 — `BurndownWorkingDaysTest`(Task 11) ·
//   `BurndownTimezoneTest` · `SprintBurndownIntegrationTest`(Task 12).
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: `serviceWorkers:'block'` 금지 — playwright.config.ts 기본값 사용.
//   - **`page.route()` 를 쓰지 않는다.** MSW Service Worker 가 먼저 응답해 가로채기가 무효임이
//     이 저장소에서 실측돼 있다(`backlog.spec.ts:125` · `import.spec.ts` S3 히스토리).
//     그래서 「응답을 spec 에서 덮어쓴다」는 선택지가 이 파일에는 존재하지 않는다.
//   - **reload 금지** — MSW store 는 모듈 스코프라 전체 로드마다 픽스처로 되돌아간다.
//     `goto` 는 각 test 처음 1회뿐이고 이후 이동은 전부 SPA 링크·탭이다(가짜그린 방지).
//   - playwright-getbyrole-exact-strict-mode: `추정`·`작업일` 같은 짧은 라벨은 `exact: true` 로,
//     `⋯`·스위처는 `board-helpers.ts` 의 **컨테이너 스코프 헬퍼**로 좁힌다(캠페인 R10).
//   - e2e-fixture-whoami-userid-alignment: `loginAsAlice` 를 쓴다.
//   - board-fixtures.ts 를 직접 import 하지 않는다 — 모듈 로드 시 `import.meta.env` 를 참조해
//     Playwright(Node) 런타임에서 깨진다. 상수는 인라인 동기화한다(board-kanban.spec.ts 관례).
//
// 시나리오 개요.
//   S1. 칸반 잠금 (J37)      — 칸반 보드의 추정 탭이 잠기고 **사유가 글자로 보인다**.
//                              같은 보드의 작업일 탭은 「미설정」이다(⑦ 대조군의 앞짝).
//   S2. 스크럼 대조군 (J37)  — 스크럼 보드에서는 열리고, 고른 값이 **탭을 떠났다 돌아와도** 남는다.
//   S3. 작업일 저장 (J38·J39) — 근무일·비근무일을 저장하면 **서버에서 다시 읽힌 값**이 화면에 선다.
//   S4. ★타임존만 바꾼다 (J40) — 근무일·비근무일은 손대지 않고 서울→뉴욕. 타임존이 바뀌고
//                              **다른 두 축은 살아남으며**, 옆 보드(칸반)는 여전히 미설정이다.
//   S5. 근무일 저장 → 번다운 x축에서 비근무일이 빠진다.
//   S6. 타임존만 바꾸면 번다운 x축이 달라진다.
//   S7. 경로 계약        — S5·S6 이 밟는 SPA 경로와 recharts x축 셀렉터가 살아 있는지 **초록으로** 잰다.
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
import { clickProjectViewTab } from './fixtures/project-view-tabs'
import { boardLabels } from '../src/i18n/board-labels'
import { burndownLabels } from '../src/i18n/burndown-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — src 정본의 **미러**다. 값만 동기화하고 출처를 주석으로 남긴다.
// ─────────────────────────────────────────────────────────────────────────────

/** `src/mocks/board-fixtures.ts` DEFAULT_BOARD.boardId 미러 — 종류는 **KANBAN** 이다 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** `src/mocks/board-fixtures.ts` DEFAULT_BOARD.projectKey 미러 */
const PROJECT_KEY = 'ATLAS'

/** `src/mocks/board-fixtures.ts` DEFAULT_BOARD.name 미러 */
const DEFAULT_BOARD_NAME = 'ATLAS 보드'

/** 진입 URL — 보드를 명시 지목한다(설정 화면은 기본 보드를 스스로 고르지 않는다) */
const BOARD_URL = `/projects/${PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** `src/mocks/backlog-fixtures.ts` DEFAULT_BACKLOG.sprints[0].sprint.name 미러 */
const SPRINT_NAME = '스프린트 1'

/**
 * `src/mocks/burndown-handlers.ts` DEFAULT_BURNDOWN.points 의 날짜 5건 미러 —
 * 2026-06-01(월) ~ 2026-06-05(금). **다섯 날이 전부 평일**이라 「주말이라서 빠졌다」와
 * 「비근무일로 등록해서 빠졌다」가 섞이지 않는다.
 */
const BURNDOWN_DATES = ['2026-06-01', '2026-06-02', '2026-06-03', '2026-06-04', '2026-06-05'] as const

/** S5 가 비근무일로 등록하는 날 — 위 5건의 한가운데라 x축이 좁아지면 반드시 사라진다 */
const HOLIDAY = '2026-06-03'

/** `BurndownChart.formatDateTick` 미러 — `YYYY-MM-DD` → `MM/DD` */
function toTick(isoDate: string): string {
  return isoDate.slice(5).replace('-', '/')
}

/**
 * `CreateBoardForm` 2단계 제출 버튼 문구.
 *
 * 🛑 `boardLabels` 에 키가 없다(폼이 직접 들고 있다). `board-manage.spec.ts` 가 같은 문자열을
 *    인라인으로 들고 있으므로 여기서도 같은 방식으로 미러한다 — 바뀌면 두 spec 이 함께 죽는다.
 */
const CREATE_BOARD_SUBMIT = '보드 만들기'

/** `CreateBoardForm` 2단계 이름 입력 라벨 — 위와 같은 이유로 인라인 미러 */
const BOARD_NAME_LABEL = '보드 이름'

/** S2~S4 가 자기 데이터로 만드는 스크럼 보드 이름 (테스트가 자기 보드를 만든다 — 공유 픽스처 무변경) */
const SCRUM_BOARD_NAME = '추정 테스트 보드'

const tabs = boardLabels.settings.tabs
const estimationLabels = boardLabels.settings.estimation
const workingDaysLabels = boardLabels.settings.workingDays

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 화면 진입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 화면의 `⋯` → 「보드 설정」으로 들어간다.
 *
 * `⋯` 는 `board-helpers.ts` 의 **헤더 컨테이너 스코프** 헬퍼로 잡는다. 사이드바 보드 `⋯` 와
 * 접근성 이름이 바이트 단위로 같아 전역 조회는 strict mode 로 즉사한다(캠페인 R10 · PR #35).
 */
async function openBoardSettings(page: Page): Promise<void> {
  await expect(boardHeader(page)).toBeVisible()
  await boardActionsTrigger(page).click()
  await page.getByRole('menuitem', { name: boardLabels.actions.settingsItem }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: boardLabels.settings.pageHeading }),
  ).toBeVisible()
}

/** 설정 탭 트리거 — 라벨 5종이 서로 substring 이 아니지만 계약대로 `exact` 를 명시한다 */
function settingsTab(page: Page, name: string): Locator {
  return page.getByRole('tab', { name, exact: true })
}

/**
 * 탭을 떠났다 돌아와 본문을 **재마운트**시킨다.
 *
 * `SettingsTabs` 는 `forceMount` 없는 Radix `TabsContent` 라 비활성 탭 본문이 언마운트된다 —
 * 돌아오면 초기값을 **보드 조회 응답에서 다시 읽는다.** 그래서 이 왕복이 곧 「저장이 서버에
 * 닿았는가」의 관측점이다. 패널이 자기 state 만 들고 있으면(=배선 절단) 여기서 죽는다.
 *
 * 🛑 `expect(...).toPass()` 로 감싼다. 저장의 `onSettled` 가 `invalidateQueries` 를 걸어 두는데
 *    그 refetch 가 정착하기 전에 재마운트하면 **저장 전 캐시**가 초기값이 된다 — sleep 없이
 *    이 경합을 없애는 유일한 수단이 재시도다.
 */
async function remountTab(page: Page, tabName: string, assertion: () => Promise<void>): Promise<void> {
  await expect(async () => {
    await settingsTab(page, tabs.columns).click()
    await settingsTab(page, tabName).click()
    await assertion()
  }).toPass({ timeout: 15_000 })
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 추정 탭
// ─────────────────────────────────────────────────────────────────────────────

/** 시간 추적 라디오 그룹 */
function timeTrackingGroup(page: Page): Locator {
  return page.getByRole('radiogroup', { name: estimationLabels.groupLabel })
}

/** 시간 추적 라디오 한 개 */
function timeTrackingOption(page: Page, label: string): Locator {
  return timeTrackingGroup(page).getByRole('radio', { name: label, exact: true })
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 작업일 탭
// ─────────────────────────────────────────────────────────────────────────────

/** 요일 체크박스 — `<ul aria-label="표준 근무일 요일">` 안으로 좁힌다 */
function dayCheckbox(page: Page, dayLabel: string): Locator {
  return page
    .getByRole('list', { name: workingDaysLabels.daysGroupLabel })
    .getByRole('checkbox', { name: dayLabel, exact: true })
}

/** 비근무일 목록 — `<h3>비근무일</h3>` 과 이름이 같으므로 role=list 로 가른다 */
function nonWorkingList(page: Page): Locator {
  return page.getByRole('list', { name: workingDaysLabels.nonWorkingHeading })
}

/** 저장 버튼 */
function saveWorkingDays(page: Page): Locator {
  return page.getByRole('button', { name: workingDaysLabels.save, exact: true })
}

/**
 * 지역 → 타임존 순으로 고른다 (J40 — *"select a Region, then Timezone from the dropdowns"*).
 *
 * 🛑 트리거와 팝오버 검색 입력이 **둘 다 role=combobox** 다(`지역` ↔ `지역 검색`).
 *    부분 일치면 두 개가 잡혀 즉사하므로 `exact: true` 가 필수다.
 *
 * @param page Playwright 페이지
 * @param region 지역 — `Asia` 처럼 IANA 앞머리
 * @param zoneLabel 타임존 표시 라벨 — `Asia/Seoul` → `Seoul`, `America/New_York` → `New York`
 */
async function pickTimezone(page: Page, region: string, zoneLabel: string): Promise<void> {
  const regionTrigger = page.getByRole('combobox', { name: workingDaysLabels.regionLabel, exact: true })
  await regionTrigger.click()
  await page.getByRole('option', { name: region, exact: true }).click()
  await expect(regionTrigger).toContainText(region)

  const zoneTrigger = page.getByRole('combobox', { name: workingDaysLabels.timezoneLabel, exact: true })
  await zoneTrigger.click()
  // 지역 하나에 타임존이 수십~수백 개다 — 검색으로 좁힌 뒤 고른다.
  await page.getByRole('combobox', { name: workingDaysLabels.timezoneSearch, exact: true }).fill(zoneLabel)
  await page.getByRole('option', { name: zoneLabel, exact: true }).click()
  await expect(zoneTrigger).toContainText(zoneLabel)
}

/** 「저장」을 누르고 저장 완료 문구를 확인한다 */
async function submitWorkingDays(page: Page): Promise<void> {
  await saveWorkingDays(page).click()
  await expect(page.getByText(workingDaysLabels.saved, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 번다운 차트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 설정 화면 → 백로그 → 스프린트 칸의 「번다운 보기」로 **SPA 이동**한다.
 *
 * 🛑 `page.goto` 로 대체하지 않는다. 전체 로드는 MSW store 를 픽스처로 되돌려 **방금 저장한
 *    작업일이 사라진 채** 차트를 재게 된다 — 원인이 전혀 보이지 않는 가짜 red 가 된다.
 */
async function goToBurndownViaSpa(page: Page): Promise<void> {
  await clickProjectViewTab(page, '백로그')
  const sprintColumn = page.getByRole('region').filter({ hasText: new RegExp(`^${SPRINT_NAME}`) }).first()
  await expect(sprintColumn).toBeVisible()
  await sprintColumn
    .getByRole('link', { name: `${SPRINT_NAME} ${burndownLabels.toggle.burndown} 보기`, exact: true })
    .click()
  await expect(page.getByRole('img', { name: burndownLabels.chart.ariaLabel })).toBeVisible()
}

/**
 * 번다운 x축 tick 문자열을 읽는다.
 *
 * ★recharts 내부 클래스에 기대는 **이 저장소의 유일한 자리**다. 형제 spec
 * (`sprint-burndown.spec.ts`)은 컨테이너 가시성까지만 재는데, 「x축이 **좁아진다**」는
 * 축 자체를 읽지 않고는 잴 수 없다. 셀렉터가 썩으면 S7 이 초록에서 죽는다.
 */
async function burndownAxisTicks(page: Page): Promise<string[]> {
  const ticks = page.locator('.recharts-xAxis .recharts-cartesian-axis-tick-value')
  await expect(ticks.first()).toBeVisible()
  return (await ticks.allTextContents()).map((text) => text.trim())
}

/** 「미설정 → 근무일 고르기(월~금) → 비근무일 등록 → 저장」 한 묶음 */
async function configureWorkingDaysWithHoliday(page: Page): Promise<void> {
  await settingsTab(page, tabs.workingDays).click()
  await page.getByRole('button', { name: workingDaysLabels.configureDays, exact: true }).click()
  await page.getByLabel(workingDaysLabels.dateInputLabel).fill(HOLIDAY)
  await page.getByRole('button', { name: workingDaysLabels.addDate, exact: true }).click()
  await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()
  await submitWorkingDays(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('보드 설정 — 추정 · 작업일 (부채 177 Task 23)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 칸반 잠금 + 미설정 (뮤테이션 ①·⑦ 의 앞짝)
  //
  // Given  alice 로 로그인해 **칸반** 보드(ATLAS 보드)의 설정 화면에 있다
  // When   「추정」 탭을 연다
  // Then   라디오 2종이 전부 비활성이고 **잠긴 사유가 글자로** 보인다 (J37)
  //        이어서 「작업일」 탭은 요일 체크박스 대신 **미설정 안내**를 그린다 (R6)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 칸반 보드 — 추정 탭이 사유와 함께 잠기고, 작업일은 미설정이다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)

    // When. 추정 탭
    await settingsTab(page, tabs.estimation).click()

    // Then. 사유가 글자로 보인다 — 비활성만 하면 사용자는 「고장」으로 읽는다.
    await expect(page.getByText(estimationLabels.kanbanLocked)).toBeVisible()
    await expect(timeTrackingOption(page, estimationLabels.optionNone)).toBeDisabled()
    await expect(timeTrackingOption(page, estimationLabels.optionRemainingAndSpent)).toBeDisabled()

    // Then. 작업일 탭은 미설정 — 「요일 7개가 다 꺼진 화면」이 아니다(미설정 ≠ 근무일 0개).
    await settingsTab(page, tabs.workingDays).click()
    await expect(page.getByText(workingDaysLabels.unsetNotice)).toBeVisible()
    await expect(page.getByRole('list', { name: workingDaysLabels.daysGroupLabel })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2~S4 — 스크럼 보드 한 바퀴 (뮤테이션 ②·③·④·⑤·⑥·⑦·⑧)
  //
  // 한 test 로 잇는 이유. MSW store 는 전체 로드마다 픽스처로 되돌아가므로, test 를 쪼개면
  // 앞 단계가 만든 보드와 저장이 전부 사라진다(`board-manage.spec.ts` 가 세운 관례).
  //
  // Given  alice 가 **스스로 만든 스크럼 보드**의 설정 화면에 있다 (공유 픽스처를 건드리지 않는다)
  // When   ① 추정을 「잔여 추정 + 소요 시간」으로 바꾸고 ② 근무일에서 금요일을 빼고 비근무일을 넣고
  //        ③ **타임존만** 서울 → 뉴욕으로 바꾼다
  // Then   각 단계가 탭을 떠났다 돌아와도 남아 있고, 타임존만 바꾼 저장이 근무일·비근무일을
  //        지우지 않으며, 옆 보드(칸반)는 여전히 미설정이다
  // ───────────────────────────────────────────────────────────────────────────
  test('S2~S4 스크럼 보드 — 추정 열림 → 작업일 저장 → 타임존만 변경, 옆 보드는 그대로', async ({
    page,
  }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await expect(boardHeader(page)).toBeVisible()

    // ── Given. 이 테스트가 쓸 스크럼 보드를 **자기가 만든다** ────────────────────
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.switcher.createItem }).click()
    const createDialog = page.getByRole('dialog', { name: boardLabels.switcher.createDialogTitle })
    await expect(createDialog).toBeVisible()
    await selectBoardType(createDialog, 'SCRUM')
    await goToBoardNameStep(createDialog)
    await createDialog.getByLabel(BOARD_NAME_LABEL).fill(SCRUM_BOARD_NAME)
    await createDialog.getByRole('button', { name: CREATE_BOARD_SUBMIT, exact: true }).click()
    await expect(boardSwitcherTrigger(page)).toContainText(SCRUM_BOARD_NAME)

    await openBoardSettings(page)

    // ── S2. 추정 탭이 열린다 — ①의 대조군. 「항상 잠긴 구현」이 여기서 죽는다 ──────
    await settingsTab(page, tabs.estimation).click()
    await expect(page.getByText(estimationLabels.kanbanLocked)).toHaveCount(0)
    const remaining = timeTrackingOption(page, estimationLabels.optionRemainingAndSpent)
    await expect(remaining).toBeEnabled()
    await remaining.click()
    await expect(remaining).toBeChecked()

    // Then. 탭을 떠났다 돌아와도 남는다 — 화면 state 가 아니라 **서버 값**이라는 뜻이다.
    await remountTab(page, tabs.estimation, async () => {
      await expect(timeTrackingOption(page, estimationLabels.optionRemainingAndSpent)).toBeChecked()
    })

    // ── S3. 작업일 저장 — 근무일에서 금요일을 빼고 비근무일 하루를 넣는다 ──────────
    await settingsTab(page, tabs.workingDays).click()
    await expect(page.getByText(workingDaysLabels.unsetNotice)).toBeVisible()
    await page.getByRole('button', { name: workingDaysLabels.configureDays, exact: true }).click()

    const friday = dayCheckbox(page, workingDaysLabels.dayLabels.FRI)
    await expect(friday).toBeChecked() // 「근무일 고르기」의 출발점은 월~금이다
    await friday.click()
    await expect(friday).not.toBeChecked()

    await page.getByLabel(workingDaysLabels.dateInputLabel).fill(HOLIDAY)
    await page.getByRole('button', { name: workingDaysLabels.addDate, exact: true }).click()
    await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()

    await submitWorkingDays(page)

    // Then. 재마운트 후에도 서버 값이 선다 — ④·⑤(배선 절단)가 여기서 죽는다.
    await remountTab(page, tabs.workingDays, async () => {
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.FRI)).not.toBeChecked()
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.MON)).toBeChecked()
      await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()
    })

    // ── S4. ★타임존만 바꾼다 — 근무일·비근무일에는 손대지 않는다 ─────────────────
    await pickTimezone(page, 'Asia', 'Seoul')
    await submitWorkingDays(page)
    await remountTab(page, tabs.workingDays, async () => {
      await expect(
        page.getByRole('combobox', { name: workingDaysLabels.timezoneLabel, exact: true }),
      ).toContainText('Seoul')
    })

    await pickTimezone(page, 'America', 'New York')
    await submitWorkingDays(page)

    // Then. 타임존이 바뀌었고 **다른 두 축은 살아 있다** — PUT 3축 교체가 다른 축을 지우면
    //       (⑥) 여기서 죽는다. 이 짝이 없으면 「타임존만 바꿨는데 근무일이 날아가는」 침묵
    //       실패를 아무도 못 본다.
    await remountTab(page, tabs.workingDays, async () => {
      await expect(
        page.getByRole('combobox', { name: workingDaysLabels.timezoneLabel, exact: true }),
      ).toContainText('New York')
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.FRI)).not.toBeChecked()
      await expect(dayCheckbox(page, workingDaysLabels.dayLabels.MON)).toBeChecked()
      await expect(nonWorkingList(page).getByText(HOLIDAY, { exact: true })).toBeVisible()
    })

    // ── S4 대조군. 옆 보드(칸반 ATLAS 보드)는 **여전히 미설정**이다 ───────────────
    // ⑦(항상 좁힌다) 과 ⑧(프로젝트 단위로 샌다) 이 여기서 죽는다. 이것이 없으면
    // 「모든 보드를 월~금으로 만드는」 구현도 위 단언을 전부 통과한다.
    await clickProjectViewTab(page, '보드')
    await expect(boardHeader(page)).toBeVisible()
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitemradio', { name: DEFAULT_BOARD_NAME }).click()
    await expect(boardSwitcherTrigger(page)).toContainText(DEFAULT_BOARD_NAME)

    await openBoardSettings(page)
    await settingsTab(page, tabs.workingDays).click()
    await expect(page.getByText(workingDaysLabels.unsetNotice)).toBeVisible()
    await expect(page.getByRole('list', { name: workingDaysLabels.daysGroupLabel })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7 — 경로 계약 (S5·S6 의 셀렉터가 살아 있는지 초록으로 잰다)
  //
  // Given  칸반 보드에 근무일·비근무일을 저장했다
  // When   설정 → 백로그 → 스프린트 칸 「번다운 보기」로 **SPA 이동**한다
  // Then   차트가 그려지고 x축 tick 을 읽을 수 있으며 스프린트 **시작일**이 그 안에 있다
  //
  // ★기대값을 「5개」로 굳히지 않는다 — 배선이 들어오면 4개가 되고, 그때 이 초록이
  //   엉뚱하게 죽으면 안 된다. 시작일은 좁혀도 남으므로 두 세계 모두에서 참이다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 근무일 저장 후 SPA 로 번다운까지 도달하고 x축을 읽을 수 있다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)
    await configureWorkingDaysWithHoliday(page)

    await goToBurndownViaSpa(page)

    const ticks = await burndownAxisTicks(page)
    expect(ticks).toContain(toTick(BURNDOWN_DATES[0]))
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 — 근무일 배선 (뮤테이션 ⑨). **지금 깨져 있다.**
  //
  // Given  칸반 보드에 표준 근무일(월~금)과 비근무일 2026-06-03 을 저장했다
  // When   같은 프로젝트의 스프린트 번다운을 연다
  // Then   x축에서 2026-06-03(=`06/03`)이 빠져 있다 (J41)
  //
  // ★MSW 번다운 핸들러가 보드 작업일 설정에서 파생하지 않아 red 일 것으로 본다 — 실측한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 근무일을 저장하면 번다운 x축에서 비근무일이 빠진다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)
    await configureWorkingDaysWithHoliday(page)

    await goToBurndownViaSpa(page)

    expect(await burndownAxisTicks(page)).not.toContain(toTick(HOLIDAY))
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6 — 타임존 배선 (뮤테이션 ⑩). **지금 깨져 있다.**
  //
  // Given  칸반 보드의 표준 근무일은 월~금으로 **고정**이고 타임존만 서울이다
  // When   근무일 요일에는 손대지 않고 **타임존만** 뉴욕으로 바꾼다
  // Then   번다운 x축이 달라진다 (J40 · 리뷰가 critical gap 으로 지목한 침묵 실패)
  //
  // ★설정은 저장되는데 차트가 안 바뀌면 아무도 모른다 — 이 단언이 그 침묵을 깬다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 타임존만 서울→뉴욕으로 바꾸면 번다운 x축이 달라진다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)
    await openBoardSettings(page)

    // Given. 근무일 월~금 + 타임존 서울
    await settingsTab(page, tabs.workingDays).click()
    await page.getByRole('button', { name: workingDaysLabels.configureDays, exact: true }).click()
    await pickTimezone(page, 'Asia', 'Seoul')
    await submitWorkingDays(page)

    await goToBurndownViaSpa(page)
    const seoulTicks = await burndownAxisTicks(page)

    // When. 근무일은 그대로 두고 **타임존만** 뉴욕으로 바꾼다
    await clickProjectViewTab(page, '보드')
    await openBoardSettings(page)
    await settingsTab(page, tabs.workingDays).click()
    await expect(dayCheckbox(page, workingDaysLabels.dayLabels.MON)).toBeChecked()
    await pickTimezone(page, 'America', 'New York')
    await submitWorkingDays(page)

    await goToBurndownViaSpa(page)
    const newYorkTicks = await burndownAxisTicks(page)

    // Then. 같은 근무일·같은 스프린트인데 타임존만 다르면 x축이 달라야 한다.
    expect(newYorkTicks).not.toEqual(seoulTicks)
  })
})
