// FR-BD-04 D7 E2E — 스크럼 보드 한 줄기 (보드 생성 → 백로그에서 스프린트 시작 → 보드에 그 스프린트만)
//
// 시나리오 개요 (plan `2026-09-02-scrum-board-screen.md` Task 9 · FR 정본 `agile-planning.md:129`).
//   S1. 스크럼 보드 생성 — 스위처 「새 보드」 → 종류 **스크럼** → 이름 → 생성
//   S2. **시작 전** — 그 보드는 빈 상태(「활성 스프린트가 없습니다」)이고 헤더·`⋯` 메뉴가 **남아 있다**
//   S3. 백로그로 이동 — 빈 상태의 CTA 가 데려간다 (J18 — 스프린트를 시작하는 자리는 백로그다)
//   S4. 그 보드로 스코프 — 스위처로 고르면 `?board=` 가 붙고, 칸반 보드 소속 스프린트는 안 보인다
//   S5. 스프린트 생성 — 요청 바디에 `boardId` 가 실린다 (부채 E-6 의 관측점)
//   S6. 그 스프린트에 이슈를 넣고 **시작**한다
//   S7. **시작 후** — 그 보드 상세가 **다시 요청되고**, 보드 헤더에 스프린트 이름,
//       보드에 **그 스프린트의 이슈만**
//
// 설계 결정.
//   - ★**「시작 전」과 「시작 후」를 한 test 안에서 둘 다 잰다.** 전자가 없으면 「원래 그렇게
//     보였을 뿐」과 구별되지 않는다 — 「보였다」는 「바뀌었다」의 증거가 아니다.
//   - **goto 는 처음 1회뿐이다.** MSW store 는 페이지 로드마다 픽스처로 되돌아가므로 S1 이 만든
//     보드가 reload 한 번에 사라진다. 이후 이동은 전부 SPA 안에서 일어난다
//     (board-manage.spec.ts 가 세운 관례 — reload 금지).
//   - src 는 **i18n 정본만** import 한다. mock 픽스처/컴포넌트를 끌어오면 `import.meta.env` 를
//     거쳐 Playwright(Node) 런타임에서 깨진다 — 필요한 값은 미러 + 출처 주석으로 동기화한다.
//   - S7 이 성립하려면 mock 이 **파생 동작을 공유 store 로** 재현해야 한다 — 시작 핸들러가
//     그 스프린트의 보드에 `activeSprint` 를 심고, 스크럼 보드 상세가 활성 스프린트 이슈를
//     `currentStateKey` ↔ 컬럼 `stateKey` 로 배치한다(task-10·11 이 배선). 그 배선이 빠지면
//     시작 전/후 화면이 **한 픽셀도 다르지 않고**, 그것이 이 spec 이 잡는 결함이다
//     (`msw-derived-behavior-shared-store-e2e`).
import { test, expect } from '@playwright/test'
import type { Locator, Page, Request } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import {
  backlogSprintColumn,
  goToBoardNameStep,
  selectBoardType,
  startSprintFromBacklog,
} from './fixtures/board-helpers'
import { backlogLabels } from '../src/i18n/backlog-labels'
import { boardLabels } from '../src/i18n/board-labels'
import { navLabels } from '../src/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 픽스처 미러 (직접 import 금지 · 출처를 함께 적는다)
// ─────────────────────────────────────────────────────────────────────────────

/** `board-fixtures.ts` DEFAULT_BOARD.projectKey 미러 */
const PROJECT_KEY = 'ATLAS'

/** `board-fixtures.ts` DEFAULT_BOARD.boardId 미러 — 진입 발판이 되는 **칸반** 보드 */
const KANBAN_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** `board-fixtures.ts` DEFAULT_BOARD.name 미러 */
const KANBAN_BOARD_NAME = 'ATLAS 보드'

/** 진입 URL — 칸반 보드로 들어가 거기서 스크럼 보드를 만든다 */
const ENTRY_URL = `/projects/${PROJECT_KEY}/board?board=${KANBAN_BOARD_ID}`

/** S1 이 만드는 스크럼 보드 이름 */
const SCRUM_BOARD_NAME = '스크럼 보드 D7'

/** S5 가 만드는 스프린트 이름 */
const SPRINT_NAME = 'D7 스프린트'

/** S6 이 그 스프린트에 넣는 이슈 제목 — 「그 스프린트의 이슈」의 관측점 */
const SPRINT_ISSUE_SUMMARY = 'D7 스프린트에 넣은 이슈'

/**
 * `backlog-fixtures.ts` DEFAULT_BACKLOG.sprints[0].sprint.name 미러 — **칸반 보드 소속** 스프린트.
 *
 * 스코프의 반쪽을 여기서 잰다. 「그 스프린트만」은 「다른 스프린트가 없다」가 함께 참이어야
 * 성립하고, 그 「다른 것」은 실재해야 한다 (`unreachable-state-fixture-is-fake-green`).
 */
const OTHER_BOARD_SPRINT_NAME = '스프린트 1'

/** `backlog-fixtures.ts` DEFAULT_BACKLOG.sprints[0].issues[0].key 미러 — 남의 스프린트의 이슈 */
const OTHER_SPRINT_ISSUE_KEY = 'ATLAS-3'

/** `backlog-fixtures.ts` DEFAULT_BACKLOG.backlog[0].key 미러 — 어느 스프린트에도 없는 이슈 */
const BACKLOG_ONLY_ISSUE_KEY = 'ATLAS-1'

/**
 * `components/board/ScrumSprintEmptyState.tsx` `scrumEmptyStateLabels` 미러 (E1).
 *
 * 그 파일은 `@/api/boards` 를 거쳐 `import.meta.env` 에 닿으므로 spec 에서 값으로 import 할 수
 * 없다. 문구가 바뀌면 여기가 red 가 되는 것이 의도다 — 「활성 스프린트가 없다」는 이 화면의
 * 유일한 안내라 조용히 갈리면 안 된다.
 */
const SCRUM_EMPTY = {
  title: '활성 스프린트가 없습니다',
  description: '백로그에서 스프린트를 시작하세요.',
  backlogLink: '백로그로 이동',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 셀렉터
// ─────────────────────────────────────────────────────────────────────────────

/** 보드 스위처 트리거 — 접근성 이름이 「보드 선택, 현재 …」이라 정규식으로 잡는다 */
function boardSwitcherTrigger(page: Page): Locator {
  return page.getByRole('button', { name: /보드 선택/ })
}

/** 보드 관리 `⋯` 트리거 — 접근성 이름이 「보드 관리, …」 */
function boardActionsTrigger(page: Page): Locator {
  return page.getByRole('button', { name: /보드 관리/ })
}

/** 스위처를 열어 보드를 고른다 — 보드 화면과 백로그 화면이 같은 컴포넌트를 쓴다 */
async function switchToBoard(page: Page, boardName: string): Promise<void> {
  await boardSwitcherTrigger(page).click()
  await page.getByRole('menuitemradio', { name: boardName }).click()
}

/** 백로그 칸 locator — 백로그 칸은 이름이 고정이다 */
function backlogColumn(page: Page): Locator {
  return page.getByRole('region', { name: new RegExp(`^${backlogLabels.backlogTitle} 칸`) })
}

/**
 * 프로젝트 뷰 전환 nav 안의 링크.
 *
 * 사이드바에도 같은 이름의 링크가 있어 전역 조회는 strict mode 로 깨진다 — nav 로 좁힌다
 * (`ProjectNavTabs` 의 `aria-label` 은 e2e 계약 문자열이다).
 */
function viewNavLink(page: Page, label: string): Locator {
  return page
    .getByRole('navigation', { name: navLabels.projectViewNav })
    .getByRole('link', { name: label, exact: true })
}

/** 현재 URL 의 `?board=` 값 */
function currentBoardParam(page: Page): string | null {
  return new URL(page.url()).searchParams.get('board')
}

/**
 * 그 보드의 **상세 재조회** GET 인지 본다 — S7 재요청 관측의 판정식.
 *
 * `pathname` 을 통째로 맞춘다. `includes('/api/v1/boards/')` 로 느슨하게 잡으면 **다른 보드**의
 * 조회(S7 은 `?board=` 없이 보드 화면에 들어가므로 기본 보드를 먼저 부른다)와 하위 자원
 * (`/api/v1/boards/{id}/cards/...`)까지 걸려, 「그 보드가 다시 조회됐다」가 아닌 것이 가드를
 * 만족시킨다. 카드 필터 query string 은 붙었다 떨어졌다 하므로 경로만 본다.
 *
 * @param req 관측된 요청
 * @param boardId 재조회를 기대하는 보드 UUID
 * @returns 그 보드 상세 GET 이면 true
 */
function isBoardDetailGet(req: Request, boardId: string): boolean {
  return req.method() === 'GET' && new URL(req.url()).pathname === `/api/v1/boards/${boardId}`
}

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('스크럼 보드 — 활성 스프린트만 보인다 (FR-BD-04 D7)', () => {
  test('S1~S7 스크럼 보드 한 줄기 — 생성 → 빈 상태 → 백로그에서 시작 → 그 스프린트만', async ({
    page,
  }) => {
    // Given. alice 로그인 후 칸반 보드로 진입 (이후 goto 없음 — store 를 살려 둔다)
    await loginAsAlice(page)
    await page.goto(ENTRY_URL)
    await expect(boardSwitcherTrigger(page)).toContainText(KANBAN_BOARD_NAME)

    // ── S1. 스크럼 보드 생성 ────────────────────────────────────────────────
    await boardSwitcherTrigger(page).click()
    await page.getByRole('menuitem', { name: boardLabels.switcher.createItem }).click()

    const createDialog = page.getByRole('dialog', {
      name: boardLabels.switcher.createDialogTitle,
    })
    await expect(createDialog).toBeVisible()

    await selectBoardType(createDialog, 'SCRUM')
    await goToBoardNameStep(createDialog)
    // 「보드 이름」·「보드 만들기」는 `CreateBoardForm.tsx` 안의 리터럴이라 `boardLabels` 에 없다
    // (board-manage.spec.ts 도 같은 이유로 리터럴을 쓴다)
    await createDialog.getByLabel('보드 이름').fill(SCRUM_BOARD_NAME)
    await createDialog.getByRole('button', { name: '보드 만들기', exact: true }).click()

    // Then. 만든 보드로 이동한다 — 이후 단계가 이 id 로 스코프를 잰다
    await expect(boardSwitcherTrigger(page)).toContainText(SCRUM_BOARD_NAME)
    await expect.poll(() => currentBoardParam(page)).not.toBe(KANBAN_BOARD_ID)
    const scrumBoardId = currentBoardParam(page)
    expect(scrumBoardId).not.toBeNull()
    // ★타입을 좁힌다 — `expect(...).not.toBeNull()` 은 런타임 단언일 뿐 TS 를 좁히지 않는다.
    //   `!` 는 절대 규칙 금지고 `?? ''` 폴백을 쓰면 S7 의 재요청 판정식이 보드 스코프를 잃는다.
    //   위 단언이 이미 통과했으므로 여기서 던지는 일은 없다.
    if (scrumBoardId === null) throw new Error('S1: 보드 생성 후 URL 에 ?board= 가 없다')

    // ── S2. **시작 전** — 빈 상태이고 헤더·`⋯` 메뉴는 남아 있다 (E1 · FR-1) ──
    await expect(page.getByText(SCRUM_EMPTY.title, { exact: true })).toBeVisible()
    await expect(page.getByText(SCRUM_EMPTY.description, { exact: true })).toBeVisible()
    // 활성 스프린트가 없으니 헤더 표기도 없다 — S7 의 「생겼다」가 여기서만 의미를 얻는다
    await expect(page.getByTestId('active-sprint-summary')).toHaveCount(0)
    // ★early-return 이 아니라 인라인 대체라는 것 — 이것이 깨지면 보드를 지울 수도 없다
    await expect(page.getByRole('heading', { name: boardLabels.page.title, exact: true })).toBeVisible()
    await expect(boardActionsTrigger(page)).toBeVisible()

    // ── S3. 백로그로 이동 — 빈 상태의 CTA 가 데려간다 (J18) ──────────────────
    await page.getByRole('link', { name: SCRUM_EMPTY.backlogLink, exact: true }).click()
    await expect(
      page.getByRole('heading', { name: backlogLabels.page.title, exact: true }),
    ).toBeVisible()

    // ── S4. 그 보드로 스코프 ───────────────────────────────────────────────
    await switchToBoard(page, SCRUM_BOARD_NAME)
    await expect.poll(() => currentBoardParam(page)).toBe(scrumBoardId)

    // Then. 칸반 보드 소속 스프린트는 이 스코프에 없다. 다만 그 이슈는 **백로그 칸에 남는다**
    // (E12 · J20 — 스코프 밖 스프린트의 이슈를 어디에도 안 넣으면 화면에서 증발한다)
    await expect(backlogSprintColumn(page, OTHER_BOARD_SPRINT_NAME)).toHaveCount(0)
    await expect(backlogColumn(page).getByText(OTHER_SPRINT_ISSUE_KEY)).toBeVisible()

    // ── S5. 스프린트 생성 — 바디에 boardId 가 실린다 (E-6) ──────────────────
    const createSprintRequest = page.waitForRequest(
      (req) => req.url().endsWith('/api/v1/sprints') && req.method() === 'POST',
    )
    await page.getByLabel(backlogLabels.sprintNamePlaceholder).fill(SPRINT_NAME)
    await page.getByRole('button', { name: backlogLabels.createSprint, exact: true }).click()

    // ★요청 바디가 유일한 관측점이다 — boardId 를 흘려도 스프린트 칸은 그대로 뜬다
    //   (mock 이 폴백을 갖지 않아 조용히 어느 보드에도 안 붙는다)
    expect(JSON.parse((await createSprintRequest).postData() ?? '{}')).toMatchObject({
      boardId: scrumBoardId,
    })

    const sprintColumn = backlogSprintColumn(page, SPRINT_NAME)
    await expect(sprintColumn).toBeVisible()

    // ── S6. 그 스프린트에 이슈를 넣고 시작한다 ──────────────────────────────
    await page
      .getByRole('button', {
        name: backlogLabels.createIssueInSprint(SPRINT_NAME),
        exact: true,
      })
      .click()

    const issueDialog = page.getByRole('dialog', { name: '새 이슈 만들기' })
    await expect(issueDialog).toBeVisible()
    await issueDialog.getByLabel('제목', { exact: true }).fill(SPRINT_ISSUE_SUMMARY)
    await issueDialog.getByRole('button', { name: '이슈 생성', exact: true }).click()
    await expect(issueDialog).toBeHidden()
    await expect(sprintColumn.getByText(SPRINT_ISSUE_SUMMARY)).toBeVisible()

    // ★S7 의 재요청 관측을 **시작 전에** 무장한다.
    //
    //   시작은 `use-backlog.ts` 의 `invalidateAfterSprintTransition` 에서 `boardKeys.all` 을
    //   무효화한다. 그 무효화를 「화면이 바뀌었다」로만 재면 가드가 **벽시계에 결합**된다 —
    //   무효화를 지워도 S2 의 보드 상세 조회와 S7 렌더 사이가 `useBoard` 의 `staleTime` 30초를
    //   넘기는 순간 React Query 가 스스로 refetch 해 같은 화면이 나온다. 그 사이에는 S3~S6 의
    //   UI 조작이 십수 회 들어가므로 스위트가 느려질수록 가드가 조용히 동어반복으로 퇴화한다.
    //   그래서 **재요청 자체**를 별도 축으로 관측한다 (렌더 단언은 그대로 둔다 — 다른 축이다).
    const scrumBoardRefetch = page.waitForRequest((req) => isBoardDetailGet(req, scrumBoardId))

    await startSprintFromBacklog(page, SPRINT_NAME)

    // ── S7. **시작 후** — 보드가 그 스프린트를 보여준다 (J5 · J18) ───────────
    await viewNavLink(page, backlogLabels.page.boardLink).click()
    await switchToBoard(page, SCRUM_BOARD_NAME)
    await expect(boardSwitcherTrigger(page)).toContainText(SCRUM_BOARD_NAME)

    // Then. 무효화가 **실제로 재요청을 냈다**. 무효화가 빠지면 이 보드 상세는 S2 에서 받은
    // 캐시가 아직 fresh 라 요청이 아예 안 나가고 여기서 타임아웃으로 죽는다 — 아래 렌더
    // 단언들이 벽시계 덕에 우연히 통과하는 날에도 이 줄은 red 다.
    await scrumBoardRefetch

    // Then. 헤더에 활성 스프린트 이름 — S2 에서 0개였던 그 자리다 (FR-2)
    await expect(page.getByTestId('active-sprint-summary')).toContainText(SPRINT_NAME)
    // Then. 빈 상태는 사라졌다 — 「시작하면 보드가 바뀐다」의 전반부 (E1 → 해소)
    await expect(page.getByText(SCRUM_EMPTY.title, { exact: true })).toHaveCount(0)

    // Then. **그 스프린트의 이슈만** 보인다 (J5 — "only the work items added to the sprint you started")
    await expect(page.getByText(SPRINT_ISSUE_SUMMARY).first()).toBeVisible()
    // ★「만」의 나머지 절반 — 남의 스프린트 이슈도, 스프린트에 없는 백로그 이슈도 안 보인다
    await expect(page.getByText(OTHER_SPRINT_ISSUE_KEY)).toHaveCount(0)
    await expect(page.getByText(BACKLOG_ONLY_ISSUE_KEY)).toHaveCount(0)
  })
})
