// FR-UX-06 Phase 5 PR21b E2E — 칸반 보드 스윔레인 간 드래그 필드변경(담당자/우선순위/에픽)
//
// 시나리오 개요 (docs/specs/2026-07-25-fr-ux-06-pr21b-swimlane-field-change.md 기준).
//   S1. 담당자 재할당 — ASSIGNEE 스윔레인, 다른 담당자 줄로 드롭 → 담당자 변경 + 대상 줄 이동 + MSW 반영 후 유지
//   S2. 담당자 해제   — 미배정 줄로 드롭 → 담당자 해제(null)
//   S3. 우선순위 변경 — PRIORITY 스윔레인, 다른 우선순위 줄로 드롭 → 우선순위 변경
//   S4. 에픽 재배치(에픽A→에픽B) — EPIC 스윔레인, 2-step(해제 후 연결)
//   S6. 에픽 해제(에픽→없음) — "에픽 없음" 줄로 드롭 → 에픽 연결 해제(1-step)
//   S7. 낙관적 반영·롤백 — 서버 실패(OCC 409) 시 원위치 복귀 + 오류 toast
//   S8. 같은 그룹 내 드롭 — PR21의 순서변경(rank) 회귀 없음
//
// 생략한 시나리오 (여유 시 트랙 — "무리한 8개보다 확실한 5개" 원칙).
//   S5(없음→에픽 1-step connect) — S4가 2-step의 connect 단계를 이미 실질 검증하고,
//     S6이 1-step disconnect를 검증하므로 1-step connect만 별도로 추가하지 않았다.
//   FR-8(dragOver 하이라이트) — 기능적 필드변경 검증에 집중, 시각 하이라이트는 별도 트랙.
//
// 설계 결정.
//   - 드래그 헬퍼(dragCardOntoCard)는 board-reorder.spec.ts(PR21 Task 8)의
//     PointerSensor 시퀀스(mouse down → move(+6px) → move(대상 카드 중심, steps:20) → up)를
//     그대로 재사용한다. 새로 발명하지 않음 — @dnd-kit PointerSensor activationConstraint:
//     { distance: 5 }.
//   - 이슈 store(issue-handlers.ts issueFixtureMap) 제약 — changeAssignee/updateIssue/
//     connectEpicChild/disconnectEpicChild 핸들러는 모두 resolveIssue(key)로 issueFixtureMap에
//     등록된 이슈만 처리한다(없으면 404). board-fixtures.ts의 스윔레인 전용 보드(SWIMLANE_BOARD·
//     EPIC_SWIMLANE_BOARD·REORDER_SWIMLANE_BOARD 등)의 카드 키(SWIM-*, EPICTEST-*, RT-* 등)는
//     이슈 store에 등록되어 있지 않아 필드변경 API가 전부 404로 실패한다(board-handlers.test.ts
//     주석 "DEFAULT_BOARD(ATLAS 프로젝트) 카드 ATLAS-1/2/4는 issueFixtureMap에 이미 짝이 맞아
//     있다"에서 실측 확인). 따라서 이 스펙은 DEFAULT_BOARD(ATLAS-1/ATLAS-4, TODO 컬럼)만 사용한다.
//     같은 그룹 내 순서변경 회귀(S8)만 REORDER_SWIMLANE_BOARD(PR21과 동일 보드)를 재사용한다
//     — reorder는 이슈 store를 거치지 않는 PATCH /issues/:key/rank 경로라 이 제약이 없다.
//   - "이름 있는 담당자" 줄 재현(S1) — DEFAULT_BOARD 기본 시드는 ATLAS-1(whoami alice 식별자,
//     user-fixtures 목록엔 없어 'unknown' 상태) / ATLAS-4(미배정)뿐이라 기본값만으로는 실존 이름
//     담당자 줄을 만들 수 없다. 테스트 Given 단계에서 page.evaluate(fetch)로
//     PATCH /api/v1/issues/ATLAS-4/assignee { assigneeId: bob }를 직접 호출해 "bob" 줄을
//     사전 준비한다(issue-versions-link.spec.ts createVersionInStore와 동일한 API 사전시드
//     패턴 — UI 밖에서 이 호출 자체는 검증 대상이 아니다). expectedVersion은 생략해 OCC 체크를
//     우회한다(핸들러는 expectedVersion undefined면 버전 비교를 건너뜀).
//   - 에픽 2-step(S4) 재현 — issueFixtureMap에 실제 typeKey='epic' 이슈는 ATLAS-EPIC-1
//     하나뿐이다. "에픽B" 역할은 ATLAS-FOR-EPIC(typeKey='task', 소속 에픽 지정 검증용 fixture)을
//     빌려 쓴다 — connectEpicChildHandler/disconnectEpicChildHandler는 epicKey 위치 이슈의
//     type을 검증하지 않고 존재 여부만 확인하므로(issue-handlers.ts 실측), board-drop.ts
//     입장에서 epicKey는 불투명 문자열이라 기능 검증에는 영향이 없다. S4/S6 모두 이 두 이슈
//     (ATLAS-EPIC-1, ATLAS-FOR-EPIC)를 각각 사전 연결(Given)한 뒤 드래그로 재배치/해제한다.
//   - S7 롤백(OCC 409) — 자연 발생하는 버전 불일치를 그대로 활용한다. board-fixtures.ts
//     DEFAULT_BOARD ATLAS-4 카드의 seed version은 0이지만, issue-fixtures.ts
//     issueAtlasFixture(issueAtlas4Fixture).version은 3이다 — 두 store(board/issue)가 별개라
//     기존부터(PRE_EXISTING) 존재하던 실측 불일치다. ATLAS-4를 active(드래그 주체)로 삼으면
//     프론트가 board 카드의 version(0)을 expectedVersion으로 보내지만 issue store 실제
//     version(3)과 달라 changeAssigneeHandler가 자연스럽게 409 VERSION_CONFLICT를 반환한다 —
//     별도 localStorage 토글 없이 실제 OCC 충돌 경로를 그대로 재현한다(board-kanban.spec.ts의
//     LS_KEY_BOARD_CONFLICT 토글과 달리, 이 파일 files 범위(board-swimlane-field-change.spec.ts
//     단독)에서는 토글을 새로 추가할 수 없어 이 자연 발생 경로를 택했다).
//   - 컨테이너 한정 셀렉터(playwright-getbyrole-exact-strict-mode 교훈) — 스윔레인 그룹은
//     role="group" + aria-label={그룹라벨}로 exact:true 매칭한다.
//   - MSW serviceWorkers:'block' 금지(e2e-msw-serviceworker-block 교훈).
//   - page.reload() / page.goto() 재진입 금지(각 시나리오는 한 번의 goto로 완결) — store 리셋
//     가짜그린 방지(board-kanban.spec.ts / board-reorder.spec.ts 교훈 동일 적용).
//   - board-fixtures.ts 직접 import 금지 — import.meta.env.MODE 참조가 Node.js 런타임 오류
//     유발. 필요한 상수를 인라인으로 동기화 정의.
import type { Locator, Page } from '@playwright/test'
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts / issue-fixtures.ts / user-fixtures.ts / swimlane-group.ts /
// board-labels.ts와 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts DEFAULT_BOARD.boardId 와 동기화 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** board-fixtures.ts DEFAULT_BOARD.projectKey 와 동기화 */
const DEFAULT_PROJECT_KEY = 'ATLAS'

/** DEFAULT_BOARD URL */
const DEFAULT_BOARD_URL = `/projects/${DEFAULT_PROJECT_KEY}/board?board=${DEFAULT_BOARD_ID}`

/** board-fixtures.ts REORDER_SWIMLANE_BOARD.boardId 와 동기화 (S8 회귀 확인용, PR21과 동일 보드) */
const REORDER_BOARD_ID = '10000000-0000-4000-8000-000000000007'

/** board-fixtures.ts REORDER_SWIMLANE_BOARD.projectKey 와 동기화 */
const REORDER_PROJECT_KEY = 'REORDERTEST'

/** REORDER_SWIMLANE_BOARD URL */
const REORDER_BOARD_URL = `/projects/${REORDER_PROJECT_KEY}/board?board=${REORDER_BOARD_ID}`

/** board-labels.ts boardLabels.swimlane.selectorLabel 과 동기화 */
const SWIMLANE_SELECTOR_LABEL = '스윔레인'

/** board-labels.ts boardLabels.swimlane.options 과 동기화 */
const SWIMLANE_OPTION_ASSIGNEE = '담당자'
const SWIMLANE_OPTION_PRIORITY = '우선순위'
const SWIMLANE_OPTION_EPIC = '에픽'

/** swimlane-group.ts LABEL_UNKNOWN 상수와 동기화 — 이름 미확인(unknown) 담당자 그룹 */
const LABEL_UNKNOWN_ASSIGNEE = '이름 미확인'

/** swimlane-group.ts LABEL_UNASSIGNED 상수와 동기화 — 미배정 그룹 */
const LABEL_UNASSIGNED = '미배정'

/** swimlane-group.ts LABEL_NO_EPIC 상수와 동기화 — 에픽 없음 그룹 */
const LABEL_NO_EPIC = '에픽 없음'

/** user-fixtures.ts userBobFixture.id 와 동기화 — displayName null → username 'bob' 폴백 */
const BOB_USER_ID = 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a'

/** user-fixtures.ts userAliceFixture.displayName 과 동기화 (S8 회귀용) */
const ALICE_GROUP_LABEL = '김앨리스'

/** issue-fixtures.ts issueAtlasEpic1Fixture.key(typeKey='epic') 와 동기화 — "에픽A" 역할 */
const EPIC_A_KEY = 'ATLAS-EPIC-1'

/**
 * issue-fixtures.ts issueAtlasForEpicFixture.key(typeKey='task') 와 동기화 — "에픽B" 역할로
 * 빌려 쓴다. connectEpicChildHandler/disconnectEpicChildHandler는 epicKey 위치 이슈의 타입을
 * 검증하지 않고 존재만 확인한다(issue-handlers.ts 실측) — 자세한 내용은 파일 상단 설계 결정 참고.
 */
const EPIC_B_KEY = 'ATLAS-FOR-EPIC'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 컬럼/카드/스윔레인 그룹 locator (board-reorder.spec.ts / board-wip-swimlane.spec.ts와 동일 패턴)
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
 * 스윔레인 서브그룹 locator. SwimlaneSection: role="group" aria-label={그룹라벨}
 * (swimlane-group.ts groupCardsBySwimlane이 계산한 label 그대로).
 *
 * scope는 반드시 컬럼 단위(todoColumn 등)로 한정한다 — swimlaneField는 보드 전체(모든 컬럼)에
 * 적용되므로, DEFAULT_BOARD처럼 IN PROGRESS/DONE 컬럼에도 카드가 있으면 그 컬럼들도 각자
 * 스윔레인 서브그룹을 렌더한다. ATLAS-2(IN PROGRESS, assigneeId가 TODO의 ATLAS-1과 동일해
 * "이름 미확인" 그룹 생성)·ATLAS-3(DONE, 미배정이라 "미배정"·"에픽 없음" 그룹 생성)이 TODO와
 * 같은 그룹 라벨을 공유해, page 전체로 조회하면 toHaveCount(0) 등 개수 단언이 다른 컬럼의
 * 무관한 그룹까지 세어 거짓 실패한다(실측 확인 — TODO로 스코프하지 않은 최초 버전에서 재현).
 */
function getGroupLocator(scope: Locator, label: string): Locator {
  return scope.getByRole('group', { name: label, exact: true })
}

/**
 * container(스윔레인 그룹 등) 안의 카드들을 DOM 순서 그대로 issueKey 배열로 반환한다(S8 전용).
 * board-reorder.spec.ts getCardOrder와 동일 패턴.
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
 * sourceIssueKey 카드를 targetIssueKey 카드 위로 PointerSensor 드래그한다.
 * board-reorder.spec.ts dragCardOntoCard와 동일 시퀀스 — 새로 발명하지 않고 그대로 재사용한다.
 *
 * @dnd-kit PointerSensor activationConstraint: { distance: 5 } —
 * pointerdown 후 5px 초과 이동 시 드래그가 시작된다.
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

/** 스윔레인 셀렉터(Radix Select)에서 optionLabel을 선택한다. */
async function selectSwimlane(page: Page, optionLabel: string): Promise<void> {
  const swimlaneSelect = page.getByRole('combobox', { name: SWIMLANE_SELECTOR_LABEL, exact: true })
  await expect(swimlaneSelect).toBeVisible()
  await swimlaneSelect.click()
  await page.getByRole('option', { name: optionLabel, exact: true }).click()
}

/**
 * onSettled의 invalidateQueries가 유발하는 DEFAULT_BOARD 상세 재조회 GET을 기다린다.
 * "서버 진실과 재동기화" 지점 — 낙관적 업데이트가 아니라 MSW 반영 후에도 유지되는지 검증할 때 쓴다
 * (board-reorder.spec.ts performReorderAndWaitForRefetch와 동일 목적).
 */
async function waitForBoardRefetch(page: Page): Promise<void> {
  await page.waitForResponse(
    (res) => res.request().method() === 'GET' && new RegExp(`/api/v1/boards/${DEFAULT_BOARD_ID}(\\?|$)`).test(res.url()),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 이슈 store API 사전시드 (page.evaluate + fetch, MSW 가로챔)
// issue-versions-link.spec.ts createVersionInStore/archiveVersion과 동일 패턴.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PATCH /api/v1/issues/{issueKey}/assignee 를 직접 호출해 담당자를 사전 배정한다(Given 단계 전용).
 * expectedVersion은 의도적으로 생략한다 — changeAssigneeHandler는 expectedVersion이 undefined면
 * 버전 비교를 건너뛰어(issue-handlers.ts 실측) 사전시드 목적에 맞게 OCC 충돌 없이 항상 성공한다.
 */
async function seedAssignee(page: Page, issueKey: string, assigneeId: string): Promise<void> {
  await page.evaluate(
    async ([key, id]: [string, string]) => {
      const res = await fetch(`/api/v1/issues/${key}/assignee`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ assigneeId: id }),
      })
      if (!res.ok) throw new Error(`담당자 사전시드 실패: ${key} → ${id} (status ${res.status})`)
    },
    [issueKey, assigneeId] as [string, string],
  )
}

/**
 * POST /api/v1/issues/{epicKey}/epic-children 를 직접 호출해 에픽 연결을 사전 구성한다(Given 단계 전용).
 */
async function seedEpicConnect(page: Page, epicKey: string, childKey: string): Promise<void> {
  await page.evaluate(
    async ([epic, child]: [string, string]) => {
      const res = await fetch(`/api/v1/issues/${epic}/epic-children`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ childKey: child }),
      })
      if (!res.ok) throw new Error(`에픽 연결 사전시드 실패: ${epic} → ${child} (status ${res.status})`)
    },
    [epicKey, childKey] as [string, string],
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-06 PR21b 칸반 보드 스윔레인 간 드래그 필드변경', () => {
  // ───────────────────────────────────────────────────────────────────────
  // S1. 담당자 재할당 — 다른 담당자(named) 줄로 드롭 → 담당자 변경 + 대상 줄로 이동
  //
  // Given  alice 로그인 + DEFAULT_BOARD 진입, TODO: ATLAS-1(이름 미확인) / ATLAS-4(미배정)
  //        ATLAS-4를 bob에게 사전 배정(API 직접 호출) → ASSIGNEE 스윔레인 시 "bob" 줄 확보
  // When   ATLAS-1(이름 미확인 줄)을 ATLAS-4(bob 줄)로 드래그
  // Then   ATLAS-1의 담당자가 bob으로 바뀌고 "bob" 줄로 이동 — invalidateQueries 재조회 후에도 유지
  // ───────────────────────────────────────────────────────────────────────
  test('S1 담당자 재할당 — 다른 담당자 줄로 드롭하면 담당자가 바뀌고 대상 줄로 이동한다(재조회 후 유지)', async ({ page }) => {
    // Given. alice 로그인 + DEFAULT_BOARD 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    // Given. ATLAS-4를 bob에게 사전 배정 — "이름 있는 담당자" 줄 확보
    await seedAssignee(page, 'ATLAS-4', BOB_USER_ID)

    // Given. ASSIGNEE 스윔레인 전환 → "이름 미확인"[ATLAS-1] / "bob"[ATLAS-4]
    await selectSwimlane(page, SWIMLANE_OPTION_ASSIGNEE)
    const unknownGroup = getGroupLocator(todoColumn, LABEL_UNKNOWN_ASSIGNEE)
    const bobGroup = getGroupLocator(todoColumn, 'bob')
    await expect(unknownGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(bobGroup.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-1을 ATLAS-4(bob 줄)로 드래그 — PATCH /issues/ATLAS-1/assignee 응답 대기
    const [assigneeRes] = await Promise.all([
      page.waitForResponse(
        (res) => res.request().method() === 'PATCH' && res.url().endsWith('/api/v1/issues/ATLAS-1/assignee'),
      ),
      dragCardOntoCard(page, 'ATLAS-1', 'ATLAS-4'),
    ])
    expect(assigneeRes.status()).toBe(200)
    await waitForBoardRefetch(page)

    // Then. ATLAS-1이 "bob" 줄로 이동 + "이름 미확인" 줄은 사라짐(빈 그룹 생략) — 재조회 후에도 유지
    await expect(bobGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(bobGroup.getByText('ATLAS-4')).toBeVisible()
    await expect(getGroupLocator(todoColumn, LABEL_UNKNOWN_ASSIGNEE)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────
  // S2. 담당자 해제 — 미배정 줄로 드롭 → 담당자 해제(null)
  //
  // Given  DEFAULT_BOARD, ASSIGNEE 스윔레인. TODO: ATLAS-1(이름 미확인) / ATLAS-4(미배정)
  // When   ATLAS-1(이름 미확인 줄)을 ATLAS-4(미배정 줄)로 드래그
  // Then   ATLAS-1의 담당자가 해제(null)되어 "미배정" 줄로 이동
  // ───────────────────────────────────────────────────────────────────────
  test('S2 담당자 해제 — 미배정 줄로 드롭하면 담당자가 해제된다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    await selectSwimlane(page, SWIMLANE_OPTION_ASSIGNEE)
    const unknownGroup = getGroupLocator(todoColumn, LABEL_UNKNOWN_ASSIGNEE)
    const unassignedGroup = getGroupLocator(todoColumn, LABEL_UNASSIGNED)
    await expect(unknownGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(unassignedGroup.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-1을 ATLAS-4(미배정 줄)로 드래그
    const [assigneeRes] = await Promise.all([
      page.waitForResponse(
        (res) => res.request().method() === 'PATCH' && res.url().endsWith('/api/v1/issues/ATLAS-1/assignee'),
      ),
      dragCardOntoCard(page, 'ATLAS-1', 'ATLAS-4'),
    ])
    expect(assigneeRes.status()).toBe(200)
    await waitForBoardRefetch(page)

    // Then. ATLAS-1이 "미배정" 줄로 이동 + "이름 미확인" 줄은 사라짐 — 재조회 후에도 유지
    await expect(unassignedGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(unassignedGroup.getByText('ATLAS-4')).toBeVisible()
    await expect(getGroupLocator(todoColumn, LABEL_UNKNOWN_ASSIGNEE)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────
  // S3. 우선순위 변경 — 다른 우선순위 줄로 드롭 → 우선순위 변경
  //
  // Given  DEFAULT_BOARD, PRIORITY 스윔레인. TODO: ATLAS-1(우선순위1) / ATLAS-4(우선순위4)
  // When   ATLAS-1(우선순위1 줄)을 ATLAS-4(우선순위4 줄)로 드래그
  // Then   ATLAS-1의 우선순위가 4로 바뀌어 "우선순위 4" 줄로 이동
  // ───────────────────────────────────────────────────────────────────────
  test('S3 우선순위 변경 — 다른 우선순위 줄로 드롭하면 우선순위가 바뀐다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    await selectSwimlane(page, SWIMLANE_OPTION_PRIORITY)
    const priority1Group = getGroupLocator(todoColumn, '우선순위 1')
    const priority4Group = getGroupLocator(todoColumn, '우선순위 4')
    await expect(priority1Group.getByText('ATLAS-1')).toBeVisible()
    await expect(priority4Group.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-1을 ATLAS-4(우선순위4 줄)로 드래그 — PATCH /issues/ATLAS-1 응답 대기(assignee 접미사 없음)
    const [priorityRes] = await Promise.all([
      page.waitForResponse(
        (res) => res.request().method() === 'PATCH' && res.url().endsWith('/api/v1/issues/ATLAS-1'),
      ),
      dragCardOntoCard(page, 'ATLAS-1', 'ATLAS-4'),
    ])
    expect(priorityRes.status()).toBe(200)
    await waitForBoardRefetch(page)

    // Then. ATLAS-1이 "우선순위 4" 줄로 이동 + "우선순위 1" 줄은 사라짐 — 재조회 후에도 유지
    await expect(priority4Group.getByText('ATLAS-1')).toBeVisible()
    await expect(priority4Group.getByText('ATLAS-4')).toBeVisible()
    await expect(getGroupLocator(todoColumn, '우선순위 1')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────
  // S4. 에픽 재배치(에픽A→에픽B) — 2-step(해제 후 연결)
  //
  // Given  DEFAULT_BOARD. ATLAS-1→EPIC_A, ATLAS-4→EPIC_B로 사전 연결(API 직접 호출)
  //        EPIC 스윔레인 → "ATLAS-EPIC-1" 줄[ATLAS-1] / "ATLAS-FOR-EPIC" 줄[ATLAS-4]
  // When   ATLAS-4(에픽B 줄)를 ATLAS-1(에픽A 줄)로 드래그
  //        → useChangeCardField: disconnect(EPIC_B, ATLAS-4) 후 connect(EPIC_A, ATLAS-4) 순차 호출
  // Then   ATLAS-4가 "ATLAS-EPIC-1" 줄로 이동, "ATLAS-FOR-EPIC" 줄은 사라짐
  // ───────────────────────────────────────────────────────────────────────
  test('S4 에픽 재배치(에픽A→에픽B) — 2-step(해제 후 연결)로 처리된다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    // Given. ATLAS-1→에픽A, ATLAS-4→에픽B 사전 연결
    await seedEpicConnect(page, EPIC_A_KEY, 'ATLAS-1')
    await seedEpicConnect(page, EPIC_B_KEY, 'ATLAS-4')

    await selectSwimlane(page, SWIMLANE_OPTION_EPIC)
    const epicAGroup = getGroupLocator(todoColumn, EPIC_A_KEY)
    const epicBGroup = getGroupLocator(todoColumn, EPIC_B_KEY)
    await expect(epicAGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(epicBGroup.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-4(에픽B 줄)를 ATLAS-1(에픽A 줄)로 드래그 — 해제(DELETE) → 연결(POST) 2-step 응답 대기
    const [disconnectRes, connectRes] = await Promise.all([
      page.waitForResponse(
        (res) =>
          res.request().method() === 'DELETE' &&
          res.url().includes(`/api/v1/issues/${EPIC_B_KEY}/epic-children/ATLAS-4`),
      ),
      page.waitForResponse(
        (res) => res.request().method() === 'POST' && res.url().includes(`/api/v1/issues/${EPIC_A_KEY}/epic-children`),
      ),
      dragCardOntoCard(page, 'ATLAS-4', 'ATLAS-1'),
    ])
    expect(disconnectRes.status()).toBe(204)
    expect(connectRes.status()).toBe(201)
    await waitForBoardRefetch(page)

    // Then. ATLAS-4가 "ATLAS-EPIC-1" 줄로 이동, "ATLAS-FOR-EPIC" 줄은 사라짐 — 재조회 후에도 유지
    await expect(epicAGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(epicAGroup.getByText('ATLAS-4')).toBeVisible()
    await expect(getGroupLocator(todoColumn, EPIC_B_KEY)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────
  // S6. 에픽 해제(에픽→없음) — "에픽 없음" 줄로 드롭 → 에픽 연결 해제(1-step)
  //
  // Given  DEFAULT_BOARD. ATLAS-1→EPIC_A만 사전 연결(ATLAS-4는 에픽 미연결 기본값 유지)
  //        EPIC 스윔레인 → "ATLAS-EPIC-1" 줄[ATLAS-1] / "에픽 없음" 줄[ATLAS-4]
  // When   ATLAS-1(에픽A 줄)을 ATLAS-4(에픽 없음 줄)로 드래그
  // Then   ATLAS-1의 에픽 연결이 해제되어 "에픽 없음" 줄로 이동
  // ───────────────────────────────────────────────────────────────────────
  test('S6 에픽 해제 — 에픽 없음 줄로 드롭하면 에픽 연결이 해제된다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    // Given. ATLAS-1→에픽A만 사전 연결
    await seedEpicConnect(page, EPIC_A_KEY, 'ATLAS-1')

    await selectSwimlane(page, SWIMLANE_OPTION_EPIC)
    const epicAGroup = getGroupLocator(todoColumn, EPIC_A_KEY)
    const noEpicGroup = getGroupLocator(todoColumn, LABEL_NO_EPIC)
    await expect(epicAGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(noEpicGroup.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-1(에픽A 줄)을 ATLAS-4(에픽 없음 줄)로 드래그 — 해제(DELETE)만 발생(1-step)
    const [disconnectRes] = await Promise.all([
      page.waitForResponse(
        (res) =>
          res.request().method() === 'DELETE' &&
          res.url().includes(`/api/v1/issues/${EPIC_A_KEY}/epic-children/ATLAS-1`),
      ),
      dragCardOntoCard(page, 'ATLAS-1', 'ATLAS-4'),
    ])
    expect(disconnectRes.status()).toBe(204)
    await waitForBoardRefetch(page)

    // Then. ATLAS-1이 "에픽 없음" 줄로 이동, "ATLAS-EPIC-1" 줄은 사라짐 — 재조회 후에도 유지
    await expect(noEpicGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(noEpicGroup.getByText('ATLAS-4')).toBeVisible()
    await expect(getGroupLocator(todoColumn, EPIC_A_KEY)).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────
  // S7. 낙관적 반영·롤백 — 서버 실패(OCC 409) 시 원위치 복귀 + 오류 toast
  //
  // Given  DEFAULT_BOARD, ASSIGNEE 스윔레인. TODO: ATLAS-1(이름 미확인) / ATLAS-4(미배정)
  // When   ATLAS-4(미배정 줄)를 ATLAS-1(이름 미확인 줄)로 드래그
  //        → PATCH expectedVersion=0(board 카드 시드값) 전송하지만 issue store 실측 version=3
  //          (issue-fixtures.ts issueAtlas4Fixture.version=3 — board-fixtures.ts DEFAULT_BOARD
  //          ATLAS-4 카드 version=0과의 기존(PRE_EXISTING) 실측 불일치를 그대로 활용해 자연
  //          발생하는 OCC 409를 재현한다. 파일 상단 설계 결정 참고)
  //        → 409 VERSION_CONFLICT → useChangeCardField onError: 스냅샷 롤백 + toast.error
  // Then   "담당자 변경 중 문제가 발생했습니다. 다시 시도해 주세요." toast 표시
  //        ATLAS-4는 원래 줄(미배정)에 그대로 — 낙관적 갱신이 롤백됨
  // ───────────────────────────────────────────────────────────────────────
  test('S7 낙관적 반영·롤백 — 서버 실패(OCC 충돌) 시 원위치 복귀 + 오류 toast', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(DEFAULT_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('ATLAS-1')).toBeVisible()
    await expect(todoColumn.getByText('ATLAS-4')).toBeVisible()

    await selectSwimlane(page, SWIMLANE_OPTION_ASSIGNEE)
    const unknownGroup = getGroupLocator(todoColumn, LABEL_UNKNOWN_ASSIGNEE)
    const unassignedGroup = getGroupLocator(todoColumn, LABEL_UNASSIGNED)
    await expect(unknownGroup.getByText('ATLAS-1')).toBeVisible()
    await expect(unassignedGroup.getByText('ATLAS-4')).toBeVisible()

    // When. ATLAS-4(미배정 줄)를 ATLAS-1(이름 미확인 줄)로 드래그 — 자연 발생 OCC 409 기대
    const [assigneeRes] = await Promise.all([
      page.waitForResponse(
        (res) => res.request().method() === 'PATCH' && res.url().endsWith('/api/v1/issues/ATLAS-4/assignee'),
      ),
      dragCardOntoCard(page, 'ATLAS-4', 'ATLAS-1'),
    ])
    expect(assigneeRes.status()).toBe(409)
    // onSettled는 성공/실패 관계없이 항상 invalidateQueries → 서버 진실과 재동기화
    await waitForBoardRefetch(page)

    // Then. 오류 toast 노출 (use-change-card-field.ts buildErrorMessage('assignee'))
    await expect(
      page.getByText('담당자 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'),
    ).toBeVisible()

    // Then. ATLAS-4는 원래 줄(미배정)에 그대로 — 낙관적 갱신이 롤백됨(서버 진실과 재동기화 후에도 유지)
    await expect(unassignedGroup.getByText('ATLAS-4')).toBeVisible()
    await expect(unknownGroup.getByText('ATLAS-1')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────
  // S8. 같은 그룹 내 드롭 — PR21의 순서변경(rank) 회귀 없음
  //
  // Given  alice 로그인 + REORDER_SWIMLANE_BOARD 진입(PR21과 동일 보드)
  //        ASSIGNEE 스윔레인 선택 → "김앨리스" 그룹=[RT-1, RT-2]
  // When   같은 그룹(김앨리스) 안에서 RT-2를 RT-1 위로 드래그
  // Then   그룹 내 순서만 [RT-2, RT-1]로 바뀐다 — field-change가 아닌 reorder(PR21 동작 그대로)
  // ───────────────────────────────────────────────────────────────────────
  test('S8 같은 그룹 내 드롭 — PR21 순서변경(rank) 회귀 없음', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(REORDER_BOARD_URL)

    const todoColumn = getColumnLocator(page, 'TODO')
    await expect(todoColumn.getByText('RT-1')).toBeVisible()
    await expect(todoColumn.getByText('RT-2')).toBeVisible()
    await expect(todoColumn.getByText('RT-3')).toBeVisible()

    await selectSwimlane(page, SWIMLANE_OPTION_ASSIGNEE)
    const aliceGroup = getGroupLocator(todoColumn, ALICE_GROUP_LABEL)
    await expect(aliceGroup).toBeVisible()
    await expect(aliceGroup.getByText('RT-1')).toBeVisible()
    await expect(aliceGroup.getByText('RT-2')).toBeVisible()
    await expect.poll(() => getCardOrder(aliceGroup)).toEqual(['RT-1', 'RT-2'])

    // When. 같은 그룹(김앨리스) 안에서 RT-2를 RT-1 위로 드래그 — reorder(rank), field-change 아님
    await dragCardOntoCard(page, 'RT-2', 'RT-1')

    // Then. 그룹 내 순서만 바뀜(필드 변경 없음 — PR21 동작 그대로 유지)
    await expect.poll(() => getCardOrder(aliceGroup)).toEqual(['RT-2', 'RT-1'])
  })
})
