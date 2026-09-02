// FR-BL-02 D6 E2E — 스프린트 편집·삭제 (`⋯` 관리 메뉴 → 편집 창 · 삭제 확인 창)
//
// 시나리오 개요 (plan `2026-09-01-sprint-manage-ui.md` Task 8 · J2 · J4).
//   S1. 편집 — `⋯` → 「스프린트 편집」 → 이름 변경 → 스프린트 칸 헤더가 새 이름이 된다 (FR-1)
//   S2. 삭제 — `⋯` → 「스프린트 삭제」 → 확인 → 칸이 사라지고 **그 이슈가 백로그 칸에 나타난다**
//              (FR-3 · X1)
//   S3. 완료된 스프린트 — 편집 창에서 **날짜만** 잠기고 이름·목표는 열려 있다 (FR-2 · J1)
//
// 설계 결정.
//   - ★**진입 URL 에 `?board=` 를 명시한다** (FR-BD-04 · #424 C5). 생략하면 서버가 기본 보드로
//     폴백하는데 **응답에 그 사실이 없어** 어느 보드를 보고 있는지 스펙이 확정하지 못한다.
//     S2 의 재요청 판정식도 그 보드 스코프를 그대로 되묻는다.
//   - ★**시나리오마다 test 를 나눈다.** 메뉴에서 연 Radix 다이얼로그는 닫은 뒤에도
//     `body { pointer-events: none }` 이 남는 경우가 있어(task 5 실측) 한 test 에서 창을 두 번
//     열면 두 번째 클릭이 인터셉트로 타임아웃한다. test 가 갈리면 페이지가 새로 뜨므로 그
//     상태가 애초에 없다. MSW store 도 페이지 로드마다 픽스처로 되돌아가 격리가 함께 성립한다
//     (board-manage.spec.ts 가 「한 test 안에서 잇는다」를 택한 것과 **반대 방향**의 선택이고,
//     이유는 그쪽이 앞 단계의 결과를 뒤 단계가 소비하는 데 반해 여기 셋은 서로 독립이라서다).
//   - 셀렉터는 **접근성 이름뿐**이다 — 이 PR 은 `data-testid` 를 하나도 새로 만들지 않았다.
//     🛑 `이름`(편집 창 필드 라벨) ⊂ `스프린트 이름`(칸 생성 폼 `aria-label`) 이고 **둘은 같은
//        화면에 공존한다.** `exact: true` 를 빠뜨리면 strict mode 로 즉사한다
//        (`backlog-labels.ts` 의 `SPRINT_FORM_LABELS` KDoc 이 못박은 규율).
//   - 문구·픽스처는 정본을 **직접 import** 한다 (backlog.spec.ts 가 세운 관례). `backlog-labels.ts`
//     는 순수 상수 모듈이고 `backlog-fixtures.ts` 는 `import.meta.env` 부재를 스스로 가드하므로
//     Playwright(Node) 로더에서 안전하다. 값을 미러하면 픽스처가 바뀐 날 조용히 갈린다.
//   - MSW `serviceWorkers: 'block'` 금지 · `waitForTimeout` 금지 (기존 spec 과 같은 규약).
import { test, expect } from '@playwright/test'
import type { Locator, Page, Request } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { backlogSprintColumn } from './fixtures/board-helpers'
import { backlogLabels } from '../src/i18n/backlog-labels'
import { ATLAS_DEFAULT_BOARD_ID, DEFAULT_BACKLOG } from '../src/mocks/backlog-fixtures'
import type { StoredSprint } from '../src/mocks/backlog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 전부 픽스처 파생 (손으로 적은 값 0)
// ─────────────────────────────────────────────────────────────────────────────

/** 진입 URL — 보고 있는 보드를 **URL 에 명시**한다 (FR-BD-04) */
const ENTRY_URL = `/projects/${DEFAULT_BACKLOG.projectKey}/backlog?board=${ATLAS_DEFAULT_BOARD_ID}`

/** 백로그 조회 경로 — S2 재요청 관측이 이것과 **완전 일치**로 판정한다 */
const BACKLOG_PATHNAME = `/api/v1/projects/${DEFAULT_BACKLOG.projectKey}/backlog`

/**
 * S1 이 바꿔 넣는 이름.
 *
 * 픽스처의 어느 스프린트 이름과도 접두가 겹치지 않아야 한다 — 칸 조회가 `^{이름} 칸` 앵커
 * 정규식이라(`backlogSprintColumn`) 접두가 겹치면 옛 칸과 새 칸을 구별하지 못한다.
 */
const RENAMED_SPRINT_NAME = '이름 바꾼 스프린트'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 조회 — 퇴화하면 **그 자리에서** 실패시킨다
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 픽스처에서 해당 상태의 스프린트를 꺼낸다. 없으면 던진다.
 *
 * ★가짜 그린 차단 장치다 (`unreachable-state-fixture-is-fake-green`). S3 의 「완료된
 * 스프린트는 날짜가 잠긴다」는 COMPLETED 스프린트가 픽스처에서 사라지면 잴 대상 자체가
 * 없어지는데, 그때 조용히 넘어가면 시나리오가 통째로 증발한 채 초록이 된다.
 *
 * @param status 찾을 스프린트 상태
 * @returns 그 상태의 첫 스프린트 엔트리
 */
function sprintFixtureByStatus(status: 'PLANNED' | 'ACTIVE' | 'COMPLETED'): StoredSprint {
  const found = DEFAULT_BACKLOG.sprints.find((entry) => entry.sprint.status === status)
  if (found === undefined) {
    throw new Error(
      `backlog-fixtures.ts DEFAULT_BACKLOG 에 ${status} 스프린트가 없습니다. ` +
        'sprint-manage.spec.ts 의 시나리오가 잴 대상을 잃습니다.',
    )
  }
  return found
}

/**
 * 그 스프린트에 담긴 이슈 키 전수. **0건이면 던진다.**
 *
 * ★S2 의 존재 이유가 「지운 스프린트의 이슈가 **백로그로 간다**」이고, 이슈가 0건이면 그
 * 단언이 순회할 것이 없어 **아무것도 재지 않은 채** 통과한다 (`two-lists-never-check-each-other`
 * 와 같은 결의 공허 통과). 실물이 있어야 행선지를 물을 수 있다.
 *
 * @param entry 대상 스프린트 엔트리
 * @returns 이슈 키 목록 (1건 이상)
 */
function sprintIssueKeys(entry: StoredSprint): readonly string[] {
  const keys = entry.issues.map((issue) => issue.key)
  if (keys.length === 0) {
    throw new Error(
      `backlog-fixtures.ts 의 「${entry.sprint.name}」에 이슈가 0건입니다. ` +
        'S2 의 「이슈가 백로그로 돌아온다」가 공허하게 통과합니다.',
    )
  }
  return keys
}

/** S1·S2 대상 — PLANNED 스프린트 (이슈를 들고 있어 삭제의 행선지를 물을 수 있다) */
const TARGET_SPRINT = sprintFixtureByStatus('PLANNED')

/** S3 대상 — 날짜가 잠겨야 하는 COMPLETED 스프린트 */
const COMPLETED_SPRINT = sprintFixtureByStatus('COMPLETED')

/** S2 의 행선지 단언 대상 — 지워질 스프린트가 들고 있던 이슈 전수 */
const TARGET_ISSUE_KEYS = sprintIssueKeys(TARGET_SPRINT)

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 셀렉터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 칸 locator — 이름이 고정이라 접두만 맞춘다 (칸 이름에 이슈 수가 붙는다).
 *
 * @param page Playwright Page
 * @returns 백로그 칸 region
 */
function backlogColumn(page: Page): Locator {
  return page.getByRole('region', { name: new RegExp(`^${backlogLabels.backlogTitle} 칸`) })
}

/**
 * 칸 안의 이슈 카드 — 카드가 노출하는 이슈 키 텍스트로 잡는다.
 *
 * `exact: true` 로 키만 담은 노드를 고른다. 느슨하게 잡으면 카드 전체(키 + 제목 + 라벨)를
 * 담은 바깥 요소까지 걸려 같은 카드가 두 번 세어진다.
 *
 * @param column 스프린트 칸 또는 백로그 칸 locator
 * @param issueKey 이슈 키
 * @returns 그 칸 안의 카드 키 노드
 */
function issueCard(column: Locator, issueKey: string): Locator {
  return column.getByText(issueKey, { exact: true })
}

/**
 * 스프린트 칸의 `⋯` 관리 메뉴를 열어 항목 하나를 고른다.
 *
 * 트리거 이름에 **스프린트 이름이 들어간다** — 같은 화면에 트리거가 N개 뜨므로 그것이 유일한
 * 구별 수단이다. 메뉴 항목 이름(`스프린트 편집`·`스프린트 삭제`)은 각각 다이얼로그 제목과도
 * 같은 문자열이라 `exact: true` 없이는 조회가 넓어진다.
 *
 * @param page Playwright Page
 * @param sprintName 대상 스프린트 이름
 * @param itemName 고를 메뉴 항목 이름
 */
async function selectSprintAction(
  page: Page,
  sprintName: string,
  itemName: string,
): Promise<void> {
  await page
    .getByRole('button', {
      name: backlogLabels.sprintActions.triggerAriaLabel(sprintName),
      exact: true,
    })
    .click()
  await page.getByRole('menuitem', { name: itemName, exact: true }).click()
}

/**
 * **보고 있는 보드의** 백로그 재조회 GET 인지 본다 — S2 관측의 판정식.
 *
 * `pathname` 을 통째로 맞추고 `?board=` 까지 되묻는다. `includes('/backlog')` 로 느슨하게
 * 잡으면 스코프를 잃은 기본 보드 조회가 대신 가드를 만족시켜 관측점이 증발한다(#424 실측).
 *
 * @param req 관측된 요청
 * @returns 그 보드의 백로그 조회면 true
 */
function isScopedBacklogGet(req: Request): boolean {
  const url = new URL(req.url())
  return (
    req.method() === 'GET' &&
    url.pathname === BACKLOG_PATHNAME &&
    url.searchParams.get('board') === ATLAS_DEFAULT_BOARD_ID
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('스프린트 관리 — 편집·삭제 (FR-BL-02 D6)', () => {
  test('S1 편집 — 이름을 바꾸면 스프린트 칸 헤더가 새 이름이 된다 (FR-1 · J2)', async ({ page }) => {
    // Given. alice 로 로그인하고 **보드를 명시해** 백로그에 진입한다. 옛 이름의 칸이 실재한다
    await loginAsAlice(page)
    await page.goto(ENTRY_URL)
    await expect(backlogSprintColumn(page, TARGET_SPRINT.sprint.name)).toBeVisible()

    // When. `⋯` → 편집 → 이름만 바꿔 제출
    await selectSprintAction(page, TARGET_SPRINT.sprint.name, backlogLabels.editSprint)

    const dialog = page.getByRole('dialog', { name: backlogLabels.editSprint, exact: true })
    await expect(dialog).toBeVisible()
    // 🛑 `이름` ⊂ `스프린트 이름`(칸 생성 폼) — 창 안으로 좁히고 `exact: true` 를 함께 쓴다
    await dialog
      .getByLabel(backlogLabels.sprintForm.nameLabel, { exact: true })
      .fill(RENAMED_SPRINT_NAME)
    await dialog.getByRole('button', { name: backlogLabels.editSprint, exact: true }).click()

    // Then. 창이 닫히고 칸 헤더가 새 이름이다.
    // 칸 이름은 백로그 조회 응답에서 나오므로 이 단언이 곧 「PATCH 가 나갔고 무효화가 돌았다」다
    // — 폼 안에서만 바뀌었다면 여기 이름은 옛 값 그대로다.
    await expect(dialog).toBeHidden()
    await expect(backlogSprintColumn(page, RENAMED_SPRINT_NAME)).toBeVisible()
    // ★「바뀌었다」의 나머지 절반 — 옛 이름의 칸은 **없다**. 이 줄이 없으면 칸이 하나 늘어나도
    //   (이름 변경이 아니라 복제여도) 초록이다.
    await expect(backlogSprintColumn(page, TARGET_SPRINT.sprint.name)).toHaveCount(0)
  })

  test('S2 삭제 — 칸이 사라지고 그 이슈가 백로그 칸으로 돌아온다 (FR-3 · J4 · X1)', async ({
    page,
  }) => {
    // Given. 그 스프린트의 이슈들이 **스프린트 칸에 있고 백로그 칸에는 없다**
    await loginAsAlice(page)
    await page.goto(ENTRY_URL)

    const sprintColumn = backlogSprintColumn(page, TARGET_SPRINT.sprint.name)
    await expect(sprintColumn).toBeVisible()
    // ★이 「전」 단언이 없으면 아래의 「백로그에 있다」가 처음부터 참일 수 있어 **행선지를
    //   아무것도 재지 못한다** — 「보였다」는 「옮겨졌다」의 증거가 아니다.
    for (const key of TARGET_ISSUE_KEYS) {
      await expect(issueCard(sprintColumn, key)).toBeVisible()
      await expect(issueCard(backlogColumn(page), key)).toHaveCount(0)
    }

    // When. `⋯` → 삭제 → 확인
    await selectSprintAction(page, TARGET_SPRINT.sprint.name, backlogLabels.deleteSprint)

    const dialog = page.getByRole('dialog', { name: backlogLabels.deleteSprint, exact: true })
    await expect(dialog).toBeVisible()
    // 「이슈는 삭제되지 않고 백로그로 돌아갑니다」가 확인 창 안에 있어야 한다 — 소프트 삭제의
    // 유일한 고지다 (J4 · board-manage.spec.ts S4 와 같은 계약)
    await expect(dialog).toContainText(
      backlogLabels.sprintActions.deleteDescription(
        TARGET_SPRINT.sprint.name,
        TARGET_ISSUE_KEYS.length,
      ),
    )

    // ★재요청을 **누르기 전에** 무장한다. 렌더 단언과 다른 축이다 — 화면이 바뀌었다는 사실은
    //   보고 있는 보드가 아닌 다른 스코프의 조회로도 만들어질 수 있지만, 이 판정식은 `?board=`
    //   까지 되묻기 때문에 스코프를 잃은 폴백 조회로는 만족되지 않는다.
    const scopedBacklogRefetch = page.waitForRequest(isScopedBacklogGet)
    await dialog
      .getByRole('button', { name: backlogLabels.sprintActions.deleteConfirm, exact: true })
      .click()

    // Then. 성공했을 때만 창이 닫힌다 (실패는 창 안 `role="alert"` 로 남는 계약)
    await expect(dialog).toBeHidden()
    await scopedBacklogRefetch

    // Then. 스프린트 칸은 사라졌다
    await expect(sprintColumn).toHaveCount(0)
    // Then. ★그리고 **그 이슈는 백로그 칸에 있다.** 위의 「칸이 사라졌다」만으로는 이슈가
    //   증발해도 초록이다 — 이 줄이 이 시나리오의 존재 이유다 (FR-4 · X1).
    for (const key of TARGET_ISSUE_KEYS) {
      await expect(issueCard(backlogColumn(page), key)).toBeVisible()
    }
  })

  test('S3 완료된 스프린트 — 편집 창에서 날짜만 잠긴다 (FR-2 · J1)', async ({ page }) => {
    // Given. COMPLETED 스프린트의 칸이 실재한다
    await loginAsAlice(page)
    await page.goto(ENTRY_URL)
    await expect(backlogSprintColumn(page, COMPLETED_SPRINT.sprint.name)).toBeVisible()

    // When. 그 스프린트의 `⋯` → 편집
    await selectSprintAction(page, COMPLETED_SPRINT.sprint.name, backlogLabels.editSprint)

    const dialog = page.getByRole('dialog', { name: backlogLabels.editSprint, exact: true })
    await expect(dialog).toBeVisible()

    // Then. 날짜 두 칸이 잠기고 **사유가 창 안에 있다**. 숨기지 않고 비활성 + 사유로 두는 것이
    // 계약이라 셋을 함께 잰다 — 잠금만 재면 사유가 사라져도 초록이다.
    await expect(
      dialog.getByLabel(backlogLabels.sprintForm.startDateLabel, { exact: true }),
    ).toBeDisabled()
    await expect(
      dialog.getByLabel(backlogLabels.sprintForm.endDateLabel, { exact: true }),
    ).toBeDisabled()
    await expect(
      dialog.getByText(backlogLabels.sprintForm.datesLocked, { exact: true }),
    ).toBeVisible()

    // Then. ★「날짜**만**」의 나머지 절반 — 이름·목표는 열려 있다 (J1 *"You can only edit the
    //   name and goal for a complete sprint"*). 이 두 줄이 없으면 폼 전체가 잠겨도 초록이다.
    await expect(
      dialog.getByLabel(backlogLabels.sprintForm.nameLabel, { exact: true }),
    ).toBeEnabled()
    await expect(
      dialog.getByLabel(backlogLabels.sprintForm.goalLabel, { exact: true }),
    ).toBeEnabled()
  })
})
