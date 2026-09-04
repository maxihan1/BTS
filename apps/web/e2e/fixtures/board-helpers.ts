// 보드 생성 폼(CreateBoardForm) 1단계 종류 선택 + 백로그 스프린트 시작 + 보드 헤더 스코프 e2e 공통 헬퍼 — FR-BD-04 D6·D7
import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { backlogLabels } from '../../src/i18n/backlog-labels'
import { boardLabels } from '../../src/i18n/board-labels'

/**
 * 1단계에서 고를 수 있는 보드 종류.
 *
 * `src/api/boards.ts` 의 `BoardType` 과 같은 값이지만 그 모듈을 값으로 import 하지 않는다 —
 * `api/client` 를 거쳐 `import.meta.env` 에 닿아 Playwright(Node) 런타임에서 깨진다.
 * board-manage.spec.ts / board-kanban.spec.ts 가 board-fixtures.ts 상수를 인라인으로
 * 동기화해 둔 것과 같은 이유다.
 */
export type BoardTypeChoice = 'SCRUM' | 'KANBAN'

/**
 * 종류별 라디오의 접근성 이름.
 *
 * `CreateBoardForm` 이 `RadioGroupItem` 에 `aria-labelledby` 로 라벨 요소를 걸어 두었으므로
 * 라디오의 이름은 곧 그 라벨 문자열이다. 문구 정본이 `board-labels.ts` 라 여기서 다시 적지 않는다.
 */
const BOARD_TYPE_RADIO_NAME: Record<BoardTypeChoice, string> = {
  SCRUM: boardLabels.createForm.typeStep.scrumLabel,
  KANBAN: boardLabels.createForm.typeStep.kanbanLabel,
}

/**
 * 1단계에서 보드 종류를 고른다.
 *
 * ★키보드(`keyboard.press('ArrowUp')`)로 고르지 않는다. Radix roving-focus 가 포커스 이동을
 * `setTimeout` 으로 미루는데 `press()` 의 keydown→keyup 간격이 0ms 라 선택이 빠지는 것이
 * 실측됐다(사람 손가락은 항상 그보다 느리므로 제품 결함이 아니다). 마우스 클릭이 안전하다.
 *
 * 클릭 뒤 `toBeChecked` 로 선택을 확정한다 — 선택이 빠져도 「다음」은 눌리므로, 이 확인이
 * 없으면 기본값(칸반)으로 만들어진 보드를 스크럼으로 착각하는 가짜 초록이 된다.
 *
 * @param scope 폼을 감싼 컨테이너. 다이얼로그 안이면 그 `dialog` locator, 빈 상태면 `page`
 * @param kind 고를 보드 종류
 */
export async function selectBoardType(
  scope: Page | Locator,
  kind: BoardTypeChoice,
): Promise<void> {
  const radio = scope.getByRole('radio', { name: BOARD_TYPE_RADIO_NAME[kind], exact: true })
  await radio.click()
  await expect(radio).toBeChecked()
}

/**
 * 1단계 → 2단계. 「다음」을 눌러 이름 입력 단계로 넘어간다.
 *
 * 종류를 고르지 않고 이것만 부르면 기본 선택(칸반)으로 넘어간다 — 라디오는 항상 하나가
 * 선택돼 있어 「아무것도 안 고르고 다음」은 도달할 수 없다.
 *
 * 🛑 1단계 진행 버튼은 「다음」이고 「보드 만들기」는 **2단계 제출 버튼 전용**이다
 *    (`board-labels.ts` 의 즉사 계약). 두 문자열을 바꿔 쓰면 strict mode 로 즉사한다.
 *
 * @param scope 폼을 감싼 컨테이너. 다이얼로그 안이면 그 `dialog` locator, 빈 상태면 `page`
 */
export async function goToBoardNameStep(scope: Page | Locator): Promise<void> {
  await scope.getByRole('button', { name: boardLabels.createForm.next, exact: true }).click()
}

/**
 * 백로그 화면의 스프린트 칸 locator — `SprintColumn` 의 `aria-label` 은 「{이름} 칸, {n}개 이슈」다.
 *
 * 이슈 수는 조작마다 바뀌므로 이름 접두만 정규식으로 맞춘다 (`backlog.spec.ts`
 * `getColumnLocator` 와 같은 규약이다. 그쪽은 spec 지역 헬퍼라 여기서 다시 적는다).
 *
 * @param page Playwright Page
 * @param sprintName 스프린트 이름
 */
export function backlogSprintColumn(page: Page, sprintName: string): Locator {
  return page.getByRole('region', { name: new RegExp(`^${sprintName} 칸`) })
}

/**
 * 백로그에서 그 스프린트를 **시작**한다 — 칸 헤더의 「스프린트 시작」 → 확인 다이얼로그 제출.
 *
 * J18 이 이 자리를 못박는다 — *"select **Start sprint**, and the stories will move into the
 * **Active sprints** view."* 스프린트가 시작되는 화면은 보드가 아니라 백로그다.
 *
 * 🛑 트리거·다이얼로그 제목·제출 버튼이 **모두 같은 문구**(`backlogLabels.startSprint`)라
 *    전역 조회는 strict mode 로 즉사한다. 트리거는 스프린트 칸으로, 제출은 dialog 로 좁힌다
 *    (`backlog.spec.ts` S7 이 세운 관례).
 *
 * 반환 전에 상태 배지가 ACTIVE 인지까지 확인한다 — 다이얼로그가 닫히는 것은 제출이 나갔다는
 * 뜻일 뿐이고, store 변이 후 refetch 까지 끝났는지는 배지만 말한다.
 *
 * @param page Playwright Page
 * @param sprintName 시작할 스프린트 이름
 */
export async function startSprintFromBacklog(page: Page, sprintName: string): Promise<void> {
  const column = backlogSprintColumn(page, sprintName)
  const trigger = column.getByRole('button', { name: backlogLabels.startSprint, exact: true })
  await expect(trigger).toBeVisible()
  await trigger.click()

  const dialog = page.getByRole('dialog', { name: backlogLabels.startSprint, exact: true })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: backlogLabels.startSprint, exact: true }).click()
  await expect(dialog).toBeHidden()

  await expect(
    column.getByLabel(`스프린트 상태: ${backlogLabels.status.ACTIVE}`),
  ).toBeVisible()
}

/**
 * 보드 화면의 **헤더 행** — 보드 스위처와 `⋯` 관리 메뉴가 사는 컨테이너.
 *
 * ★**왜 앵커(`^…$` · `exact: true`)가 아니라 컨테이너인가.**
 * 「보드 관리, {이름}」·「보드 선택, 현재 {이름}」의 문구 생산자는 `src/i18n/board-labels.ts` 의
 * `triggerAriaLabel` **하나**뿐이다. 사이드바 프로젝트 트리의 하위 목록이 보드 목록으로 바뀌면
 * (`components/layout/ProjectTree.tsx` 가 예고한 캠페인 PR ⑨) 같은 헬퍼가 같은 문자열을 만들어
 * **현재 열린 보드**에 대해 헤더와 사이드바의 접근성 이름이 바이트 단위로 같아진다. 앵커는
 * 「사이드바가 다른 이름을 쓴다」는 가정 위에서만 옳고, 컨테이너 스코프는 무슨 이름을 쓰든
 * 불변이다 — 그래서 좁히는 축을 이름이 아니라 위치로 잡는다.
 *
 * 🛑 이 헬퍼를 쓰는 spec 은 진입 직후 `await expect(boardHeader(page)).toBeVisible()` 로 컨테이너
 *    **실재를 먼저 단언**해야 한다. `data-testid` 가 어긋나면 하위 조회가 조용히 count 0 이
 *    되는데, `toHaveCount(0)`(보드를 다 지운 뒤의 부재 단언)은 **그대로 통과**하기 때문이다.
 *
 * @param page Playwright Page
 * @returns 보드 헤더 행 locator (`projects.$projectKey.board.tsx` 의 `board-header`)
 */
export function boardHeader(page: Page): Locator {
  return page.getByTestId('board-header')
}

/**
 * 보드 관리 `⋯` 트리거 — 헤더 행 안으로 좁힌 것.
 *
 * 🛑 **정규식을 유지한다.** 접근성 이름이 「보드 관리, {보드 이름}」이고 `board-manage.spec.ts` S3
 *    이 테스트 중간에 보드 이름을 바꾼다 — `exact: true` 로 굳히거나 `triggerAriaLabel(name)` 을
 *    직접 부르면 rename 단계에서 죽는다. 중복은 이름이 아니라 컨테이너로 가른다.
 *
 * @param page Playwright Page
 * @returns 헤더 행의 `⋯` 버튼 locator
 */
export function boardActionsTrigger(page: Page): Locator {
  return boardHeader(page).getByRole('button', { name: /보드 관리/ })
}

/**
 * 보드 스위처 트리거 — 헤더 행 안으로 좁힌 것.
 *
 * 🛑 `boardActionsTrigger` 와 같은 이유로 **정규식을 유지한다**. 접근성 이름이
 *    「보드 선택, 현재 {보드 이름}」이라 현재 보드 이름이 바뀌면 앵커·`exact` 는 즉시 깨진다.
 *
 * @param page Playwright Page
 * @returns 헤더 행의 보드 선택 버튼 locator
 */
export function boardSwitcherTrigger(page: Page): Locator {
  return boardHeader(page).getByRole('button', { name: /보드 선택/ })
}
