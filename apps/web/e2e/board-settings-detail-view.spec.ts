// 부채 177 E2E — 상세 보기 구성이 **모달과 사이드패널 양쪽**에 반영된다 (스펙 R7·R7b·R7c · J46~J48)
//
// 시나리오 개요 (스펙 S4 · 완료 기준 9c · 계획 Task 24).
//   D1. 그룹 4종을 편집한 뒤 **모달**로 열면 그 필드가 그 순서로 보인다
//   D2. 같은 편집을 하고 **사이드패널**로 열어도 **같은** 필드가 같은 순서로 보인다
//   D3. 키보드 드래그로 순서를 바꾸면 **두 표현 모두** 새 순서로 보인다
//
// ★**D1 ↔ D2 는 짝으로만 산다.** 한쪽만 두면 「모달에만 반영되고 사이드패널은 그대로인」 구현이
//   통과하고, 그것이 R7c 가 막으려는 「같은 이슈가 **여는 방식에 따라** 다르게 보인다」다.
//   `#455` 가 두 표현을 낸 직후라 지금 가장 나기 쉬운 회귀이고, 리뷰 CONCERN C2 가
//   「그 축이 E2E 에 없다」고 지목해 이 spec 이 신설됐다. 두 표현을 **각각의 test 로** 나눈 것도
//   그 때문이다 — 한 test 에 합치면 두 방향(모달만 배선 / 사이드패널만 배선)이 같은 test 를
//   죽여 반쪽 봉합을 가르지 못한다.
//
// ## 뮤테이션 표 — 각 축이 무엇과 무엇을 가르나 (실측 2026-09-06 · 매번 전량 실행 후 원복)
//
// | 뮤테이션 | 죽는 테스트 | 가르는 것 |
// |---|---|---|
// | ① **모달만 배선** — `useIssueDetailViewFields` 가 `presentation === 'modal'` 일 때만 구성을 낸다 | **D2 · D3** (D1 은 산다) | 한쪽 표현에만 반영 ↔ 두 표현이 같은 구성 (R7c) |
// | ② **사이드패널만 배선** — 위의 반대 | **D1 · D3** (D2 는 산다) | 반쪽 봉합 ↔ 두 표현이 같은 구성 |
// | ③ **순서 무시·집합만** — `toDetailViewRows` 가 라벨순으로 `sort` | **D1 · D2 · D3** | `containsAll` 수준의 단언 ↔ 순서까지 잰다 (J48) |
//
// ★**①②는 두 방향을 다 걸어야 한다.** 한 방향만 걸면 반쪽 봉합을 가르지 못한다 — 죽는 집합이
//   서로 달라야(②는 D1·D3, ①은 D2·D3) 실패 목록만 보고 「어느 쪽을 안 배선했는지」가 읽힌다.
//   그래서 D1 과 D2 를 한 test 로 합치지 않는다.
// ★**③이 셋을 다 죽이는 것은 단언이 넓어서가 아니다** — 시드 순서 자체를 정렬 결과와 다르게
//   골랐기 때문이다(아래 [SEEDED] 주석). D3 은 그중 **드래그가 만든 순서**를 재는 유일한 축이라
//   ①②③ 어디로도 대체되지 않는다.
//
// 설계 결정.
//   - **드래그를 키보드로 한다** (`board-settings.spec.ts` 가 세운 관례). Space 로 집고 화살표로
//     옮기고 Space 로 놓는다 — 포인터 흉내보다 안정적이고, 동시에 `KeyboardSensor` 가 빠지면
//     이 spec 이 red 가 되어 **키보드 접근 자체**를 잰다(유닛은 센서를 모른다).
//   - **`goto` 는 한 번뿐이다.** MSW store 는 문서를 새로 읽을 때마다 픽스처로 되돌아간다 —
//     설정에서 저장한 구성을 상세가 읽으려면 그 사이 이동이 전부 SPA 여야 한다.
//   - `board-fixtures.ts` 를 import 하지 않는다 — 모듈 로드 시 `import.meta.env.MODE` 를 봐서
//     Playwright(Node) 런타임에서 깨진다(`board-kanban.spec.ts` 가 세운 관례).
//   - 문구는 `board-labels.ts` · `i18n/ko.ts` 를 그대로 import 한다. 하드코딩하면 i18n 이
//     바뀌었을 때 이 spec 만 조용히 남는다.
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { boardLabels } from '../src/i18n/board-labels'
import { issueDetailStrings } from '../src/i18n/ko'

/** 상세 보기 탭 문구 정본 — 설정 화면과 이슈 상세가 **같은 카탈로그**를 쓴다. */
const L = boardLabels.settings.detailView

/** 그룹 4종의 표시 이름 (J47). */
const G = L.groupLabels

/** 필드 그룹 키. 카탈로그가 곧 허용값이다 — 그룹이 늘면 여기가 먼저 컴파일 에러가 된다. */
type DetailGroup = keyof typeof G

/** board-fixtures.ts 의 DEFAULT_BOARD 와 동기화 (직접 import 금지) */
const BOARD_ID = '10000000-0000-4000-8000-000000000001'
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/board/settings?board=${BOARD_ID}`

/**
 * 상세를 열 대상 이슈 — DEFAULT_BOARD 의 첫 카드.
 *
 * ★이 이슈는 `restrictedFields` 가 비어 있다(`issue-fixtures.ts`). 열람 제한이 걸린 필드는
 * 구성에 있어도 상세에서 빠지므로(FR-PM-07), 제한이 있는 이슈를 고르면 이 spec 의 기대 목록이
 * 「구성대로 안 그린다」가 아니라 「권한대로 뺐다」로 흔들린다.
 */
const TARGET_KEY = 'ATLAS-1'

/** 한 그룹에 담을 필드와 **그 순서**. */
interface GroupPlan {
  /** 그룹 키. */
  group: DetailGroup
  /** 필드 표시 이름 — 화면 순서 그대로다. */
  fields: readonly string[]
}

/**
 * 시드 구성 — 그룹 4종(J47)에 필드를 담고 **순서까지**(J48) 정한다.
 *
 * ★**기대 순서를 후보 카탈로그 순서와도 사전순과도 다르게 골랐다.** 넷 다 그렇다 —
 * 예컨대 GENERAL 의 카탈로그 순서는 `상태 → 우선순위 → 환경` 이고 한글 사전순도 같은데,
 * 여기 기대값은 `우선순위 → 환경 → 상태` 다. 그래서 **저장 순서를 무시하고 자기 순서로
 * 정렬하는 구현**이 여기서 죽는다(`sorted()` · 카탈로그 순서 · 집합 비교 전부).
 */
const SEEDED: readonly GroupPlan[] = [
  { group: 'GENERAL', fields: ['우선순위', '환경', '상태'] },
  { group: 'DATE', fields: ['시작일', '생성일'] },
  { group: 'PEOPLE', fields: ['보고자', '담당자'] },
  { group: 'LINKS', fields: ['에픽', '상위 이슈'] },
]

// ─────────────────────────────────────────────────────────────────────────────
// 설정 화면 — 상세 보기 탭 조작 (J48)
// ─────────────────────────────────────────────────────────────────────────────

/** Radix 팝오버 본문. 퇴장 애니메이션 동안에도 DOM 에 남으므로 열림 여부로 갈라야 한다. */
const POPOVER_CONTENT = '[data-slot="popover-content"]'

/** 지금 **열려 있는** 팝오버 하나. */
function openPopover(page: Page): Locator {
  return page.locator(`${POPOVER_CONTENT}[data-state="open"]`)
}

/** 그 그룹의 필드 목록(설정 화면). 그룹 이름이 들어가 넷이 서로 겹치지 않는다. */
function settingsList(page: Page, group: DetailGroup): Locator {
  return page.getByRole('list', { name: L.listLabel(G[group]), exact: true })
}

/** 일반 필드 목록의 한 줄. 드롭 대상 표시(`bg-accent`)를 여기서 읽는다. */
function generalRow(page: Page, field: string): Locator {
  return settingsList(page, 'GENERAL').locator('li').filter({ hasText: field })
}

/** 한 줄의 순서 드래그 핸들. 저장 중에는 잠긴다(E8) — 그 잠김이 곧 「저장 중」 신호다. */
function reorderHandle(page: Page, group: DetailGroup, field: string): Locator {
  return page.getByRole('button', { name: L.reorderHandle(G[group], field), exact: true })
}

/**
 * 드롭다운에서 골라 「추가」 (J48 — *"select the field … and then select Add"*).
 *
 * ★**검색으로 좁힌 뒤 Enter 로 고른다.** 항목을 직접 누르는 판본은 Radix 팝오버의 진입
 * 애니메이션과 cmdk 의 재렌더가 겹쳐 `element is not stable` → `detached from the DOM` 로
 * 클릭이 흘렀다(실측 — 콜드 스타트에서 재현). 입력은 `fill` 이다: 한 글자씩 치는 판본이
 * 이 저장소에서 타임아웃을 낸 이력이 있다(learnings — `userEvent.type`).
 */
async function addField(page: Page, group: DetailGroup, field: string): Promise<void> {
  const trigger = page.getByRole('combobox', { name: L.candidateLabel(G[group]), exact: true })
  await expect(trigger).toBeEnabled()
  await trigger.click()

  // ★열려 있는 팝오버로 **스코프를 좁힌다.** 페이지 전역에서 찾으면 직전 그룹의 팝오버가
  //   퇴장 애니메이션 동안 DOM 에 남아 있어 검색 입력이 2개로 잡힌다(실측 — strict mode 위반).
  const popover = openPopover(page)
  const search = popover.getByPlaceholder(L.candidateSearch)
  await search.fill(field)
  await expect(popover.getByRole('option', { name: field, exact: true })).toBeVisible()
  await search.press('Enter')

  // 트리거가 고른 라벨을 그린다 = 값이 잡혔다. 여기서 갈라지면 아래 「추가」는 엉뚱한 필드를 담는다.
  await expect(trigger).toHaveText(field)
  await page.getByRole('button', { name: L.addField(G[group]), exact: true }).click()
  // 다음 그룹으로 넘어가기 전에 팝오버가 **완전히 사라질** 때까지 기다린다 — 위 스코프와 짝이다.
  await expect(page.locator(POPOVER_CONTENT)).toHaveCount(0)
}

/** 설정 화면의 그 그룹이 **이 순서 그대로** 그려져 있다. */
async function expectSettingsOrder(
  page: Page,
  group: DetailGroup,
  fields: readonly string[],
): Promise<void> {
  await expect(settingsList(page, group).locator('li')).toHaveText([...fields])
}

/**
 * 상세 보기 탭을 열고 계획대로 그룹들을 채운다.
 *
 * ★마지막에 **저장이 정착할 때까지 기다린다.** 저장 중에는 조작이 통째로 잠기므로(E8 · lost
 * update 방지) 핸들이 다시 열리는 것이 곧 「서버가 받았다」다 — `waitForTimeout` 대신 이 신호를
 * 쓴다. 정착 전에 화면을 뜨면 상세가 **저장 전 구성**을 읽어 이 spec 이 flaky 가 된다.
 */
async function seedDetailView(page: Page, plan: readonly GroupPlan[]): Promise<void> {
  await page.getByRole('tab', { name: boardLabels.settings.tabs.detailView, exact: true }).click()
  await expect(page.getByRole('heading', { name: L.heading, exact: true })).toBeVisible()

  for (const { group, fields } of plan) {
    for (const field of fields) {
      await addField(page, group, field)
    }
  }

  for (const { group, fields } of plan) {
    await expectSettingsOrder(page, group, fields)
  }

  await expectSaveSettled(page, plan)
}

/**
 * 마지막 저장이 서버에 닿을 때까지 기다린다.
 *
 * 저장 중에는 **화면 전체**의 조작이 잠기므로(`locked` · E8 lost update 방지) 아무 핸들이나
 * 다시 열리면 그것이 곧 「정착했다」다. 어느 그룹이든 상관없어 첫 줄로 잰다.
 */
async function expectSaveSettled(page: Page, plan: readonly GroupPlan[]): Promise<void> {
  const [first] = plan.flatMap(({ group, fields }) => fields.map((field) => ({ group, field })))
  if (first !== undefined) {
    await expect(reorderHandle(page, first.group, first.field)).toBeEnabled()
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 이슈 상세 — 두 표현 (J1 토글 · R7c)
// ─────────────────────────────────────────────────────────────────────────────

/** 대상 이슈의 보드 카드. 카드가 보이는 것이 곧 MSW Service Worker 가 붙었다는 신호다. */
function targetCard(page: Page): Locator {
  return page.locator(`[aria-roledescription="draggable card"][aria-label^="${TARGET_KEY} "]`)
}

/**
 * 설정 → 보드 (SPA 이동).
 *
 * ★`page.goto` 를 쓰지 않는다. 문서를 새로 읽으면 MSW store 가 픽스처로 되돌아가
 * **방금 저장한 구성이 사라진다** — 그러면 이 spec 은 늘 「구성 없음」을 재게 된다.
 */
async function backToBoard(page: Page): Promise<void> {
  await page
    .getByRole('link', { name: boardLabels.settings.backToBoard, exact: true })
    .click()
  await expect(targetCard(page)).toBeVisible()
}

/** 상세를 여는 두 길. 값은 `issueDetailModalStore` 의 표시 방식과 같다(J1). */
type Presentation = 'modal' | 'sidePanel'

/**
 * 상세를 그 표현으로 열고 **그 껍데기**를 돌려준다 — 이 spec 이 상세를 여는 **유일한 자리**다.
 *
 * ### 왜 한 곳이어야 하나
 * 진입은 언제나 모달이다 — 표시 방식 기본값이 모달이고(J1), 사이드패널로 가는 **유일한 조작**이
 * 그 모달의 `⋯` 다(`#455`). 여는 절차가 두 벌로 갈리면 한쪽만 손보게 되고, 그것이 곧 이 spec 이
 * 막으려는 결함(한쪽만 반영되는 회귀)과 **같은 모양**이다. 셀렉터가 바뀌는 날 고칠 자리도 여기다.
 *
 * @param presentation 열 표현.
 * @param opened 이미 열려 있는 모달. 주면 카드 클릭을 건너뛰고 그 자리에서 갈아탄다 —
 *   한 번 연 상세를 두 표현으로 잇달아 보는 D3 이 쓴다(모달이 떠 있으면 카드를 누를 수 없다).
 * @returns 그 표현의 껍데기 Locator. 안쪽 단언은 이 스코프 밑에서만 한다.
 */
async function openDetail(
  page: Page,
  presentation: Presentation,
  opened?: Locator,
): Promise<Locator> {
  let modal = opened
  if (modal === undefined) {
    await targetCard(page).locator('a[href^="/issues/"]').first().click()
    modal = page.getByRole('dialog', { name: `이슈 상세 ${TARGET_KEY}`, exact: true })
    await expect(modal).toBeVisible()
  }
  if (presentation === 'modal') return modal

  await modal
    .getByRole('button', { name: issueDetailStrings.presentationMenuAriaLabel, exact: true })
    .click()
  await page
    .getByRole('menuitem', { name: issueDetailStrings.openInSidePanelItem, exact: true })
    .click()
  const panel = page.getByRole('region', { name: issueDetailStrings.sidePanelLabel, exact: true })
  await expect(panel).toBeVisible()
  return panel
}

/**
 * 그 표현이 **구성대로** 그렸는지 잰다 — 그룹마다 필드 이름이 **그 순서 그대로**.
 *
 * ★`toHaveText(배열)` 은 개수와 순서를 함께 잰다. `containsAll` 이 아니다 — 순서를 무시하고
 * 집합만 맞추는 구현이 여기서 죽어야 한다(J48).
 */
async function expectDetailFields(scope: Locator, plan: readonly GroupPlan[]): Promise<void> {
  for (const { group, fields } of plan) {
    const list = scope.getByRole('list', { name: L.listLabel(G[group]), exact: true })
    await expect(list.getByTestId('detail-view-field-name')).toHaveText([...fields])
  }
}

// ─────────────────────────────────────────────────────────────────────────────

test.describe('보드 설정 — 상세 보기 (부채 177 · J46~J48)', () => {
  // 사이드패널은 `min-width: 1024px` 에서만 뜬다(좁으면 표시 방식과 무관하게 모달이 맡는다).
  test.use({ viewport: { width: 1440, height: 900 } })

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // D1. 모달 축
  //
  // Given  보드 설정 상세 보기 탭에서 그룹 4종에 필드를 담고(J47·J48)
  // When   보드로 돌아가 카드를 눌러 상세를 **모달**로 열면
  // Then   그 필드가 그룹마다 저장한 순서 그대로 보인다.
  // ───────────────────────────────────────────────────────────────────────────
  test('D1 그룹 4종을 편집하면 모달이 그 구성대로 그린다', async ({ page }) => {
    await page.goto(SETTINGS_URL)
    await seedDetailView(page, SEEDED)

    await backToBoard(page)

    await expectDetailFields(await openDetail(page, 'modal'), SEEDED)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // D2. 사이드패널 축 — D1 의 **짝**
  //
  // Given  D1 과 **같은** 구성을 담고
  // When   상세를 **사이드패널**로 열면
  // Then   같은 필드가 같은 순서로 보인다 — 여는 방식이 화면을 가르지 않는다(R7c).
  // ───────────────────────────────────────────────────────────────────────────
  test('D2 같은 구성을 사이드패널로 열어도 같은 필드가 같은 순서로 보인다', async ({ page }) => {
    await page.goto(SETTINGS_URL)
    await seedDetailView(page, SEEDED)

    await backToBoard(page)

    await expectDetailFields(await openDetail(page, 'sidePanel'), SEEDED)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // D3. 드래그 정렬 — 순서가 두 표현 모두에 닿는다
  //
  // Given  일반 필드에 `우선순위 → 환경 → 상태` 를 담고
  // When   `우선순위` 핸들을 키보드로 집어 한 칸 아래로 놓으면
  // Then   설정 화면이 `환경 → 우선순위 → 상태` 가 되고,
  //        **모달과 사이드패널 둘 다** 그 새 순서로 그린다(J48 · R7c).
  // ───────────────────────────────────────────────────────────────────────────
  test('D3 키보드 드래그로 바꾼 순서가 모달과 사이드패널 모두에 반영된다', async ({ page }) => {
    const before: readonly GroupPlan[] = [{ group: 'GENERAL', fields: ['우선순위', '환경', '상태'] }]
    // 한 칸 아래로 옮긴 뒤의 순서. 이것도 카탈로그 순서(`상태 → 우선순위 → 환경`)와도
    // 사전순과도 다르다 — 순서를 무시하는 구현이 여기서도 죽는다.
    const reordered = ['환경', '우선순위', '상태']
    const after: readonly GroupPlan[] = [{ group: 'GENERAL', fields: reordered }]

    await page.goto(SETTINGS_URL)
    await seedDetailView(page, before)

    // ── 키보드 드래그 — Space 로 집고 ArrowDown 으로 옮기고 Space 로 놓는다 ─────────
    const handle = reorderHandle(page, 'GENERAL', '우선순위')
    await handle.click()
    await expect(handle).toBeFocused()

    await page.keyboard.press('Space')
    // ★★**집힌 것을 확인하고서야 화살표를 누른다.** dnd-kit 의 `KeyboardSensor` 는 활성화
    //   **직후 `setTimeout` 으로** document 의 keydown 리스너를 붙인다
    //   (`@dnd-kit/core@6.3.1` core.cjs:1163). 그 전에 날아온 화살표는 아무 데도 닿지 않고
    //   사라지고, 이어지는 Space 는 **제자리에** 놓아 요청이 0건이 된다 — 화면도 콘솔도
    //   조용해서 「드래그가 왜 안 먹지」만 남는다(이 spec 의 RED 가 정확히 그 상태였다.
    //   PATCH 0건 · 목록은 시드 순서 그대로). `aria-pressed` 는 dnd-kit 이 role=button
    //   드래그에 거는 표준 신호라(core.cjs:3433) 이 대기가 곧 접근성 계약이기도 하다.
    //   ★형제 `board-settings.spec.ts` S2 가 이 함정을 안 밟은 것은 화살표를 25번 누르기
    //     때문이다 — 앞의 한둘이 사라져도 나머지가 옮긴다. 한 번만 누르는 여기엔 여유가 없다.
    await expect(handle).toHaveAttribute('aria-pressed', 'true')

    // 한 줄은 36px 이고 dnd-kit 의 한 걸음은 25px 이지만, 대상은 **겹침 면적**으로 고르므로
    // 한 걸음이면 다음 줄이 이긴다 — 실측 기하로 자기 줄 9px 대 다음 줄 23px 이다.
    await page.keyboard.press('ArrowDown')
    // ★놓기 전에 **어디에 놓이는지가 화면에 보인다**(드롭 대상 표시 · T19 가 형제 드래그
    //   표면 3곳과 같은 조합으로 맞춘 자리). 이 대기가 곧 「React 가 새 `over` 를 반영했다」의
    //   확인이라, 반영 전에 놓아 제자리 드롭이 되는 두 번째 경주를 함께 닫는다.
    await expect(generalRow(page, '환경')).toHaveClass(/bg-accent/)

    await page.keyboard.press('Space')

    await expectSettingsOrder(page, 'GENERAL', reordered)
    await expect(reorderHandle(page, 'GENERAL', '우선순위')).toBeEnabled()

    await backToBoard(page)
    const modal = await openDetail(page, 'modal')
    await expectDetailFields(modal, after)

    // 같은 상세를 그 자리에서 갈아탄다 — 다시 열지 않는 것이 「같은 이슈」임을 못 박는다.
    await expectDetailFields(await openDetail(page, 'sidePanel', modal), after)
  })
})
