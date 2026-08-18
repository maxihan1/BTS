// FR-UX-06 PR21 Task 8 E2E — 칸반 보드 셀(컬럼×스윔레인 그룹) 내 카드 순서변경
//
// 시나리오 개요.
//   S1. 순서변경 happy path — NONE 스윔레인, 같은 컬럼 내 카드를 드래그해 순서 변경
//   S2. stateful 반영     — S1 이후 react-query invalidateQueries 재조회(onSettled)에서도
//                           변경된 순서가 유지됨(MSW rerank가 board 조회에 stateful 반영)
//   S3. 컬럼 간 이동 무회귀 — 상태 전환(카드가 다른 컬럼으로 이동)는 PR21 이전과 동일하게 동작
//   S4. 스윔레인 활성 + 같은 셀 내 순서변경 — ASSIGNEE 스윔레인 on, 같은 담당자 그룹 안에서 순서 변경
//   S5. 스윔레인 그룹 경계 넘는 드래그 → noop — 다른 담당자 그룹으로 드래그해도 변경 없음(PR21 스코프)
//   S6. 키보드 순서변경(KeyboardSensor) — Space로 집고 ArrowDown으로 이동, Space로 드롭
//
// 설계 결정.
//   - 드래그 시뮬레이션은 dnd-kit PointerSensor 계약을 따라 기존 board-kanban.spec.ts /
//     board-epic-swimlane.spec.ts와 동일한 patten을 재사용한다.
//     page.mouse.down → move(+6px, activation 초과) → move(대상 카드 중심, steps:20) → up.
//   - page.reload() / page.goto() 재진입 금지 — board-kanban.spec.ts 교훈("reload 금지, store
//     리셋 = 가짜그린") 그대로 적용. MSW store는 페이지의 JS 모듈 스코프에 상주하므로 풀 네비게이션은
//     boardStore/backlogStore를 초기 시드값으로 되돌려 "그 어떤 값도 재시드된 값과 우연히 같아 보여
//     통과하는" 가짜 성공을 만든다. S2("새로고침 후 순서 유지")는 대신 react-query의 자동
//     invalidateQueries(onSettled) 재조회 GET을 명시적으로 기다린 뒤 DOM 순서가 되돌아가지 않았음을
//     확인하는 방식으로 검증한다 — 이 재조회가 바로 "서버 진실과 재동기화"이므로 동등한 검증이다.
//   - MSW rerank stateful 갭(controller 보고 사항, qa-engineer 조사 결과):
//     useReorderCard는 PATCH /api/v1/issues/:key/rank를 호출하며, 이 endpoint는 backlog-handlers.ts
//     rerankIssueHandler가 처리해 backlogStore(별도 Map, issue-tracking 백로그 mock)를 변이한다.
//     board GET(board-handlers.ts)은 원래 boardStore 자신의 rank만 읽었기 때문에, rerank 후
//     invalidateQueries 재조회 시 boardStore의 옛 rank로 되돌아가는 회귀가 있었다(새로고침 후
//     순서가 사라짐 — msw-mutation-stateful-refetch와 동일 계열). 이 작업에서 board-handlers.ts에
//     읽기전용 오버레이(resolveLiveRank)를 추가해 backlogStore에 같은 issueKey가 있으면 그 최신
//     rank를 우선 사용하도록 stateful 연결했다(파일 범위: board-handlers.ts/board-fixtures.ts만
//     수정, backlog-handlers.ts는 원본 그대로). DEFAULT_BOARD(ATLAS)·REORDER_SWIMLANE_BOARD(RT-*)
//     둘 다 board-fixtures.ts의 seedBacklog로 backlogStore에 짝 항목을 시드하므로 오버레이 대상이다
//     (rerankIssueHandler가 backlogStore에서 issueKey를 찾아야 404가 안 남). 시드 rank를 보드
//     초기 순서와 동일 문자열로 맞춰 오버레이가 순서 중립이라, S4/S5는 optimistic 동작을 검증한다.
//   - 컨테이너 한정 셀렉터(playwright-getbyrole-exact-strict-mode 교훈) — strict-mode 위반 방지.
//   - MSW serviceWorkers:'block' 금지(e2e-msw-serviceworker-block 교훈).
//   - board-fixtures.ts 직접 import 금지 — import.meta.env.MODE 참조가 Node.js 런타임 오류 유발.
//     필요한 상수(boardId, projectKey 등)를 인라인으로 동기화 정의.
import type { Locator, Page } from '@playwright/test'
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts와 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts DEFAULT_BOARD.boardId 와 동기화 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** board-fixtures.ts DEFAULT_BOARD.projectKey 와 동기화 */
const DEFAULT_PROJECT_KEY = 'ATLAS'

/** DEFAULT_BOARD URL */
const DEFAULT_BOARD_URL = `/projects/${DEFAULT_PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** board-fixtures.ts REORDER_SWIMLANE_BOARD.boardId 와 동기화 */
const REORDER_BOARD_ID = '10000000-0000-4000-8000-000000000007'

/** board-fixtures.ts REORDER_SWIMLANE_BOARD.projectKey 와 동기화 */
const REORDER_PROJECT_KEY = 'REORDERTEST'

/** REORDER_SWIMLANE_BOARD URL */
const REORDER_BOARD_URL = `/projects/${REORDER_PROJECT_KEY}/board?board=${REORDER_BOARD_ID}`

/** board-labels.ts boardLabels.swimlane.selectorLabel 과 동기화 */
/**
 * 셀 내 순서변경이 진행 중임을 알리는 드래그 공지 문구.
 *
 * `KanbanBoard.tsx` 의 `toPresentAnnouncement` 가 `reorder` 액션에 대해 만드는 문자열과
 * 동기화한다 — `` `${컬럼명} 안에서 순서를 조정하고 있습니다.` ``.
 * S6 이 **방향키 횟수 대신 이 목표 상태로 판정**하기 위해 쓴다.
 */
const REORDER_IN_PROGRESS_ANNOUNCEMENT = 'TODO 안에서 순서를 조정하고 있습니다.'

const SWIMLANE_SELECTOR_LABEL = '스윔레인'

/** board-labels.ts boardLabels.swimlane.options.ASSIGNEE 와 동기화 */
const SWIMLANE_OPTION_ASSIGNEE = '담당자'

/** user-fixtures.ts userAliceFixture.displayName 과 동기화 */
const ALICE_GROUP_LABEL = '김앨리스'

/** user-fixtures.ts userBobFixture.username(displayName null → username 폴백) 과 동기화 */
const BOB_GROUP_LABEL = 'bob'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 컬럼/카드 locator (board-kanban.spec.ts / board-epic-swimlane.spec.ts와 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * role="group" + aria-label에 columnName이 포함된 첫 번째 컬럼 locator.
 * BoardColumn: aria-label={`${column.name} 컬럼, ${column.cards.length}개 카드`}
 */
function getColumnLocator(page: Page, columnName: string): Locator {
  return page
    .getByRole('group')
    .filter({ hasText: new RegExp(`^${columnName}`) })
    .first()
}

/**
 * aria-roledescription="draggable card" 요소 중 issueKey가 aria-label에 포함된 카드 locator.
 * BoardCard: aria-label={`${card.issueKey} — ${card.summary}`}, aria-roledescription="draggable card"
 */
function getCardLocator(page: Page, issueKey: string): Locator {
  return page
    .locator('[aria-roledescription="draggable card"]')
    .filter({ hasText: issueKey })
    .first()
}

/**
 * container(컬럼 또는 스윔레인 그룹) 안의 카드들을 DOM 순서 그대로 issueKey 배열로 반환한다.
 * aria-label 형식 `{issueKey} — {summary}`에서 issueKey만 추출한다.
 */
async function getCardOrder(container: Locator): Promise<string[]> {
  const cards = container.locator('[aria-roledescription="draggable card"]')
  const count = await cards.count()
  const keys: string[] = []
  for (let i = 0; i < count; i += 1) {
    const label = await cards.nth(i).getAttribute('aria-label')
    keys.push(label?.split(' — ')[0]?.trim() ?? '')
  }
  return keys
}

/**
 * sourceIssueKey 카드를 targetIssueKey 카드 위로 PointerSensor 드래그한다(같은 셀 내 순서변경).
 *
 * @dnd-kit PointerSensor activationConstraint: { distance: 5 } —
 * pointerdown 후 5px 초과 이동 시 드래그가 시작된다. board-kanban.spec.ts dragCardToColumn과
 * 동일 시퀀스(6px 이동 → steps:20 점진 이동)를 카드→카드 타겟에 적용한다.
 */
async function dragCardOntoCard(page: Page, sourceIssueKey: string, targetIssueKey: string): Promise<void> {
  const source = getCardLocator(page, sourceIssueKey)
  const target = getCardLocator(page, targetIssueKey)

  const sourceBox = await source.boundingBox()
  const targetBox = await target.boundingBox()

  if (sourceBox === null || targetBox === null) {
    throw new Error(
      `dragCardOntoCard: bounding box를 가져올 수 없습니다. source=${sourceIssueKey}, target=${targetIssueKey}`,
    )
  }

  const sourceCX = sourceBox.x + sourceBox.width / 2
  const sourceCY = sourceBox.y + sourceBox.height / 2
  const targetCX = targetBox.x + targetBox.width / 2
  const targetCY = targetBox.y + targetBox.height / 2

  await page.mouse.move(sourceCX, sourceCY)
  await page.mouse.down()
  // activation constraint 초과: 6px 이동
  await page.mouse.move(sourceCX + 6, sourceCY)
  // 대상 카드 중심으로 점진적 이동 (steps로 pointermove 이벤트 여러 번 발화)
  await page.mouse.move(targetCX, targetCY, { steps: 20 })
  await page.mouse.up()
}

/**
 * 드래그 액션과 함께 PATCH /rank 응답 + 그 뒤 이어지는 invalidateQueries GET 재조회 응답을
 * 모두 기다린다. onSettled가 성공/실패 관계없이 항상 invalidate하므로, 이 GET이 곧
 * "서버 진실과 재동기화" — page.reload() 없이 stateful 반영을 검증하는 지점이다.
 *
 * @param page Playwright Page
 * @param issueKey rerank 대상 이슈 키 (PATCH 경로 매칭용)
 * @param action PATCH를 유발하는 드래그 액션
 */
async function performReorderAndWaitForRefetch(
  page: Page,
  issueKey: string,
  action: () => Promise<void>,
): Promise<void> {
  const [patchRes] = await Promise.all([
    page.waitForResponse(
      (res) => res.request().method() === 'PATCH' && res.url().includes(`/api/v1/issues/${issueKey}/rank`),
    ),
    action(),
  ])
  expect(patchRes.status()).toBe(200)

  // onSettled의 invalidateQueries가 유발하는 board 상세 재조회 GET — 순서 회귀 여부 판별점
  await page.waitForResponse(
    (res) => res.request().method() === 'GET' && new RegExp(`/api/v1/boards/${DEFAULT_BOARD_ID}(\\?|$)`).test(res.url()),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-06 PR21 칸반 보드 셀 내 카드 순서변경', () => {
  // ───────────────────────────────────────────────────────────────────────
  // S1. 순서변경 happy path — NONE 스윔레인, 같은 컬럼 내 드래그
  //
  // Given  alice 로그인 + DEFAULT_BOARD 진입
  //        TODO 컬럼: [ATLAS-1, ATLAS-4] (rank 오름차순)
  // When   ATLAS-1을 ATLAS-4 위치로 드래그(PointerSensor)
  // Then   TODO 컬럼 카드 순서가 [ATLAS-4, ATLAS-1]로 바뀐다(낙관적 업데이트)
  // ───────────────────────────────────────────────────────────────────────
  test('S1 순서변경 happy path — 같은 컬럼 내 ATLAS-1을 ATLAS-4 뒤로 드래그', async ({ page }) => {
    // Given. alice 로그인 + DEFAULT_BOARD 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    // Given. 초기 순서 확인 — [ATLAS-1, ATLAS-4]
    await expect.poll(() => getCardOrder(todoColumn)).toEqual(['ATLAS-1', 'ATLAS-4'])

    // When. ATLAS-1을 ATLAS-4 위치로 드래그
    await dragCardOntoCard(page, 'ATLAS-1', 'ATLAS-4')

    // Then. 순서가 [ATLAS-4, ATLAS-1]로 바뀜(낙관적 업데이트 — reorderCardInCell)
    await expect.poll(() => getCardOrder(todoColumn)).toEqual(['ATLAS-4', 'ATLAS-1'])
  })

  // ───────────────────────────────────────────────────────────────────────
  // S2. stateful 반영 — invalidateQueries 재조회 후에도 순서 유지
  //
  // Given  S1과 동일하게 ATLAS-1 → ATLAS-4 뒤로 드래그
  // When   PATCH /rank 응답 + onSettled invalidateQueries의 GET 재조회를 모두 기다림
  // Then   재조회 후에도 TODO 컬럼 순서가 [ATLAS-4, ATLAS-1] 그대로 유지된다
  //        (board-handlers.ts가 backlogStore의 최신 rank를 오버레이하지 않으면
  //         이 재조회에서 옛 순서 [ATLAS-1, ATLAS-4]로 되돌아가는 회귀가 재현된다)
  // ───────────────────────────────────────────────────────────────────────
  test('S2 stateful 반영 — invalidateQueries 재조회 후에도 순서 유지(MSW rerank stateful)', async ({ page }) => {
    // Given. alice 로그인 + DEFAULT_BOARD 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect.poll(() => getCardOrder(todoColumn)).toEqual(['ATLAS-1', 'ATLAS-4'])

    // When. 드래그 + PATCH 응답 + invalidate GET 재조회를 모두 기다림
    await performReorderAndWaitForRefetch(page, 'ATLAS-1', () => dragCardOntoCard(page, 'ATLAS-1', 'ATLAS-4'))

    // Then. 재조회 완료 후에도 순서가 [ATLAS-4, ATLAS-1] 그대로 — 서버(backlogStore 오버레이) 반영 확인
    await expect.poll(() => getCardOrder(todoColumn)).toEqual(['ATLAS-4', 'ATLAS-1'])
  })

  // ───────────────────────────────────────────────────────────────────────
  // S3. 컬럼 간 이동 무회귀 — 상태 전환은 PR21 이전과 동일
  //
  // Given  DEFAULT_BOARD, TODO 컬럼에 ATLAS-1 존재
  // When   ATLAS-1을 IN PROGRESS 컬럼으로 드래그(다른 컬럼 = move, reorder 아님)
  // Then   ATLAS-1이 IN PROGRESS 컬럼에 나타나고 TODO 컬럼에서 사라짐 (board-kanban.spec.ts S2 동일 회귀 확인)
  // ───────────────────────────────────────────────────────────────────────
  test('S3 컬럼 간 이동 무회귀 — ATLAS-1을 TODO에서 IN PROGRESS로 드래그', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()

    const inProgressColumn = getColumnLocator(page, 'IN PROGRESS')
    const inProgressBox = await inProgressColumn.boundingBox()
    const cardBox = await getCardLocator(page, 'ATLAS-1').boundingBox()
    if (inProgressBox === null || cardBox === null) {
      throw new Error('S3: bounding box를 가져올 수 없습니다')
    }

    const cardCX = cardBox.x + cardBox.width / 2
    const cardCY = cardBox.y + cardBox.height / 2
    const colCX = inProgressBox.x + inProgressBox.width / 2
    const colCY = inProgressBox.y + inProgressBox.height / 2

    await page.mouse.move(cardCX, cardCY)
    await page.mouse.down()
    await page.mouse.move(cardCX + 6, cardCY)
    await page.mouse.move(colCX, colCY, { steps: 20 })
    await page.mouse.up()

    // Then. IN PROGRESS 컬럼에 ATLAS-1 표시, TODO 컬럼에서 사라짐
    await expect(inProgressColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-1')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────
  // S4. 스윔레인 활성 + 같은 셀 내 순서변경
  //
  // Given  alice 로그인 + REORDER_SWIMLANE_BOARD 진입
  //        TODO 컬럼: RT-1(alice), RT-2(alice), RT-3(bob)
  //        ASSIGNEE 스윔레인 선택 → "김앨리스" 그룹=[RT-1, RT-2], "bob" 그룹=[RT-3]
  // When   같은 그룹(김앨리스) 안에서 RT-2를 RT-1 위로 드래그
  // Then   김앨리스 그룹 순서가 [RT-2, RT-1]로 바뀐다
  // ───────────────────────────────────────────────────────────────────────
  test('S4 스윔레인 활성 — 같은 셀(김앨리스 그룹) 내 RT-2를 RT-1 위로 드래그', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(REORDER_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('RT-1')).toBeVisible()
    await expect(todoColumn.getByText('RT-2')).toBeVisible()
    await expect(todoColumn.getByText('RT-3')).toBeVisible()

    // Given. ASSIGNEE 스윔레인 선택
    const swimlaneSelect = page.getByRole('combobox', { name: SWIMLANE_SELECTOR_LABEL, exact: true })
    await expect(swimlaneSelect).toBeVisible()
    await swimlaneSelect.click()
    await page.getByRole('option', { name: SWIMLANE_OPTION_ASSIGNEE, exact: true }).click()

    const aliceGroup = page.getByRole('group', { name: ALICE_GROUP_LABEL, exact: true })
    await expect(aliceGroup).toBeVisible()
    await expect(aliceGroup.getByText('RT-1')).toBeVisible()
    await expect(aliceGroup.getByText('RT-2')).toBeVisible()

    // Given. 초기 그룹 내 순서 [RT-1, RT-2]
    await expect.poll(() => getCardOrder(aliceGroup)).toEqual(['RT-1', 'RT-2'])

    // When. 같은 셀(김앨리스 그룹) 안에서 RT-2를 RT-1 위로 드래그
    await dragCardOntoCard(page, 'RT-2', 'RT-1')

    // Then. 그룹 내 순서가 [RT-2, RT-1]로 바뀜
    await expect.poll(() => getCardOrder(aliceGroup)).toEqual(['RT-2', 'RT-1'])
  })

  // ───────────────────────────────────────────────────────────────────────
  // S5. 스윔레인 그룹 경계를 넘는 드래그 → noop (PR21 스코프 — 필드변경은 PR21b)
  //
  // Given  REORDER_SWIMLANE_BOARD, ASSIGNEE 스윔레인 활성
  //        "김앨리스" 그룹=[RT-1, RT-2], "bob" 그룹=[RT-3]
  // When   RT-1(김앨리스 그룹)을 RT-3(bob 그룹) 위로 드래그
  // Then   아무 변경 없음 — 김앨리스 그룹 순서 [RT-1, RT-2] 그대로, bob 그룹은 [RT-3] 그대로
  // ───────────────────────────────────────────────────────────────────────
  test('S5 스윔레인 그룹 경계 넘는 드래그 → noop — RT-1을 bob 그룹(RT-3)으로 드래그해도 무변화', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(REORDER_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('RT-1')).toBeVisible()

    const swimlaneSelect = page.getByRole('combobox', { name: SWIMLANE_SELECTOR_LABEL, exact: true })
    await expect(swimlaneSelect).toBeVisible()
    await swimlaneSelect.click()
    await page.getByRole('option', { name: SWIMLANE_OPTION_ASSIGNEE, exact: true }).click()

    const aliceGroup = page.getByRole('group', { name: ALICE_GROUP_LABEL, exact: true })
    const bobGroup = page.getByRole('group', { name: BOB_GROUP_LABEL, exact: true })
    await expect(aliceGroup).toBeVisible()
    await expect(bobGroup).toBeVisible()
    await expect.poll(() => getCardOrder(aliceGroup)).toEqual(['RT-1', 'RT-2'])
    await expect.poll(() => getCardOrder(bobGroup)).toEqual(['RT-3'])

    // When. RT-1(김앨리스 그룹)을 RT-3(bob 그룹) 위로 드래그 — 그룹이 다르므로 noop 기대
    await dragCardOntoCard(page, 'RT-1', 'RT-3')

    // Then. 두 그룹 모두 변화 없음
    await expect.poll(() => getCardOrder(aliceGroup)).toEqual(['RT-1', 'RT-2'])
    await expect.poll(() => getCardOrder(bobGroup)).toEqual(['RT-3'])
  })

  // ───────────────────────────────────────────────────────────────────────
  // S6. 키보드 순서변경 (KeyboardSensor) — Space로 집고 ArrowDown, Space로 드롭
  //
  // Given  DEFAULT_BOARD, TODO 컬럼: [ATLAS-1, ATLAS-4]
  // When   ATLAS-1 카드에 포커스 → Space(집기) → ArrowDown 반복(다음 카드 위치로 이동) → Space(드롭)
  // Then   TODO 컬럼 순서가 [ATLAS-4, ATLAS-1]로 바뀐다
  //
  // 주의. @dnd-kit/core KeyboardSensor 기본 coordinateGetter는 방향키 1회당 25px 이동한다
  // (defaultKeyboardCoordinateGetter). 카드 높이+gap을 넘어서려면 여러 번 누른다.
  // board-kanban.spec.ts는 "컬럼 간" 키보드 이동이 headless에서 실패함을 이미 기록했으나(over.id가
  // 원래 컬럼으로 복귀), 이 시나리오는 "같은 컬럼 내 인접 카드"로 이동 거리가 훨씬 짧아 별도로 검증한다.
  // ───────────────────────────────────────────────────────────────────────
  test('S6 키보드 순서변경 — Space로 집고 ArrowDown, Space로 드롭', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect.poll(() => getCardOrder(todoColumn)).toEqual(['ATLAS-1', 'ATLAS-4'])

    const card = getCardLocator(page, 'ATLAS-1')

    // ★이 테스트가 main 에서도 빨갛던 **진짜 원인은 거리가 아니라 대기 부재**였다.
    //
    // 예전 코드는 Space 로 집은 **직후** 곧바로 ArrowDown 을 연타했다. dnd-kit 은 집기
    // 시점에 드래그 상태를 세우는데, 그 전에 도착한 화살표 입력은 **그냥 삼켜진다**.
    // 실측(2026-08-08) — 대기 없이 6·8회를 눌러도 순서 불변, 대기를 주면 **4회로도** 바뀐다.
    // 「150px 가 모자라서」라는 기존 주석의 진단은 틀렸다.
    //
    // 고정 타임아웃 대신 **집기가 끝났다는 신호**(`aria-pressed="true"`, dnd-kit 이 부여)를
    // 기다린다 — 머신이 느려도 빨라도 흔들리지 않는다.
    //
    // ★**방향키 횟수를 하드코딩하지 않는다.** 목표 상태(공지가 「순서를 조정하고 있습니다」를
    // 읽는다)로 판정하고 그때까지 한 번씩 누른다 — `backlog.spec.ts` 의
    // `pickUpAndMoveOverPlannedSprint` 가 같은 문제를 이미 이렇게 풀었다.
    // 화살표 1회당 이동량(dnd-kit 기본 25px)도, 카드 높이도 이 방식에는 무관하다.
    await card.focus()

    // 집기 — Space
    await page.keyboard.press('Space')
    await expect(card).toHaveAttribute('aria-pressed', 'true')

    const liveRegion = page.locator('[id^="DndLiveRegion"]')
    await expect(async () => {
      await page.keyboard.press('ArrowDown')
      await expect(liveRegion).toHaveText(REORDER_IN_PROGRESS_ANNOUNCEMENT, { timeout: 1_000 })
    }).toPass({ timeout: 15_000 })

    // 드롭
    await page.keyboard.press('Space')

    // Then. 순서가 [ATLAS-4, ATLAS-1]로 바뀜
    await expect.poll(() => getCardOrder(todoColumn)).toEqual(['ATLAS-4', 'ATLAS-1'])
  })
})
