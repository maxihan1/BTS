// 보드 생성 폼(CreateBoardForm) 1단계 종류 선택을 지나가는 e2e 공통 헬퍼 — FR-BD-04 D6
import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
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
