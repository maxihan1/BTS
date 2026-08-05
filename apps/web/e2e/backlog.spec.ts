// 백로그·스프린트 보드 E2E — 드래그 재정렬·할당·해제 + 스프린트 생성·시작 (FR-BL-01/02 D6/D7)
//
// 시나리오 개요.
//   S1. 백로그 재정렬   — 백로그 카드를 같은 칸 다른 위치로 드래그 → 순서 변경(낙관적 반영 확인)
//   S2. 백로그→스프린트 — 백로그 카드를 스프린트 칸으로 드래그 → 스프린트에 이슈 표시
//   S3. 스프린트→백로그 — 스프린트 카드를 백로그 칸으로 드래그 → 백로그에 이슈 복귀
//   S5. 스프린트 내 재정렬 — 스프린트 안에서 카드 위치 변경
//   S6. 스프린트 생성   — "스프린트 생성" 폼 제출 → 새 스프린트 칸 등장
//   S7. 스프린트 시작   — PLANNED 스프린트의 "스프린트 시작" 클릭 → ACTIVE 배지 표시
//   S9. 담당자 아바타   — 담당자가 있는 카드가 "담당자: {이름}" 접근성 이름을 노출 (FR-UX-13 F5)
//   S10. 조회 실패      — LS_KEY_BACKLOG_FAIL 토글 → 안내 문구 + [다시 시도] + h1 생존 (FR-UX-13 F5)
//   S11. 재시도 성공    — 1회성 실패 토글 → [다시 시도] 클릭 → 카드 복귀 (FR-UX-13 F5 스펙 S7)
//   S12. 재시도 실패    — 계속 실패 토글 → [다시 시도] 클릭 → 에러 블록·버튼 유지 (엣지 E9)
//
// 설계 결정.
//   - backlog-fixtures.ts 모듈 로드 시 seedBacklog(DEFAULT_BACKLOG) 자동 호출(MODE!=='test').
//     loginAsAlice → page.goto('/projects/ATLAS/backlog') 진입 시 이미 카드가 시드됨.
//   - 드래그: PointerSensor (activationConstraint: {distance:5}).
//     page.mouse.down → move(+6px) → move(대상 칸 중심, steps:20) → up.
//     board-kanban.spec.ts 선례 그대로.
//   - 낙관적 업데이트 검증: 드래그 후 즉시 대상 칸의 카드 가시성으로 확인.
//     MSW store가 stateful하므로 invalidateQueries refetch 후에도 값이 유지됨
//     (msw-mutation-stateful-refetch 교훈). reload는 store 초기화 = 가짜그린이므로 절대 금지.
//   - 분별 시드: backlog에 ATLAS-1/ATLAS-2, 스프린트에 ATLAS-3/ATLAS-4 — 칸 구분 명확.
//   - 칸 셀렉터: role="region" + aria-label 포함 텍스트 (컨테이너 한정 — strict-mode 방지).
//   - 카드 셀렉터: aria-roledescription="draggable card" + issueKey 텍스트.
//   - board-fixtures.ts와 달리 backlog-fixtures.ts의 import.meta.env.MODE를 Node.js에서
//     직접 import하면 오류가 발생한다. 필요한 상수는 인라인 정의로 동기화.
//   - MSW serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block).
//   - reload 금지 (worktree-stale-base-rebase-and-e2e-msw-traps / fr-nt-03).
//
// SKIP 시나리오.
//   - S4(충돌 409 토스트): backlog-handlers.ts에 충돌 플래그 분기가 없다. board-handlers.ts는
//     AGILE_CONFLICT LS 플래그를 별도 구현했으나 backlog는 해당 없음. addInitScript 분기 추가는
//     구현 코드 수정 범위이므로 SKIP (사유: MSW 핸들러 분기 미구현 — 후속 FR에서 보강 가능).
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — backlog-fixtures.ts와 동기화 (import.meta.env 참조 회피)
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그 페이지 URL — ATLAS 프로젝트 */
const BACKLOG_URL = '/projects/ATLAS/backlog'

/**
 * DEFAULT_BACKLOG.backlog[0].key 와 동기화.
 * 백로그 칸의 첫 번째 이슈 — S1·S2 드래그 대상.
 */
const BACKLOG_CARD_1 = 'ATLAS-1'

/**
 * DEFAULT_BACKLOG.backlog[1].key 와 동기화.
 * 백로그 칸의 두 번째 이슈 — S1 재정렬 참조점.
 */
const BACKLOG_CARD_2 = 'ATLAS-2'

/**
 * DEFAULT_BACKLOG.sprints[0].sprint.sprintId 와 동기화.
 * 스프린트 칸 식별 — S2·S3·S5·S7 대상.
 */
const DEFAULT_SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

/**
 * DEFAULT_BACKLOG.sprints[0].sprint.name 와 동기화.
 * 스프린트 칸 헤더 텍스트 — getSprintColumnLocator 인자.
 */
const DEFAULT_SPRINT_NAME = '스프린트 1'

/**
 * DEFAULT_BACKLOG.sprints[0].issues[0].key 와 동기화.
 * 스프린트 칸의 첫 번째 이슈 — S3·S5 드래그 대상.
 */
const SPRINT_CARD_1 = 'ATLAS-3'

/**
 * DEFAULT_BACKLOG.sprints[0].issues[1].key 와 동기화.
 * 스프린트 칸의 두 번째 이슈 — S5 드래그 대상.
 */
const SPRINT_CARD_2 = 'ATLAS-4'

/** backlogLabels.backlogTitle — 백로그 칸 헤더 텍스트 */
const BACKLOG_COLUMN_NAME = '백로그'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — FR-UX-13 F5 (S9·S10)
//
// ★아래 문자열은 전부 src 정본의 **미러**다. E2E 는 src 를 직접 import 하지 않고
//   값만 동기화하고 출처를 주석으로 남긴다 (import.spec.ts LS_KEY_IMPORT_FAIL 선례 동일).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `src/mocks/backlog-handlers.ts` 의 `LS_KEY_BACKLOG_FAIL` 값 미러.
 *
 * 'true' 로 심으면 `GET /api/v1/projects/:projectKey/backlog` 만 500 을 돌려준다
 * (rank·스프린트 등 다른 엔드포인트는 무영향 — S4 의 409 충돌 토글과는 별개다).
 *
 * ★`page.route()` 가로채기는 MSW Service Worker 가 먼저 응답해 **무효**임이 실측돼 있다
 *   (import.spec.ts S3 히스토리). 시나리오 분기는 반드시 이 localStorage 토글로 한다.
 */
const LS_KEY_BACKLOG_FAIL = '__bts_e2e_backlog_fail'

/**
 * `src/mocks/backlog-handlers.ts` 의 `BACKLOG_FAIL_ONCE` 값 미러.
 *
 * 이 값으로 심으면 **첫 조회만** 500 이고 핸들러가 플래그를 스스로 지운다 — 그래서 뒤이은
 * [다시 시도] 는 성공한다. 'true'(계속 실패)로는 복귀 경로를 구조적으로 잴 수 없어서 S11 이
 * 이 값을 쓴다.
 */
const BACKLOG_FAIL_ONCE = 'once'

/** `src/mocks/user-fixtures.ts` userAliceFixture.displayName 미러 — ATLAS-1·ATLAS-3 담당자 */
const ASSIGNEE_DISPLAY_NAME = '김앨리스'

/** `BacklogCard.tsx` AssigneeSlot 의 아바타 aria-label 형식 미러 — `담당자: {표시 이름}` */
const ASSIGNEE_ARIA_LABEL = `담당자: ${ASSIGNEE_DISPLAY_NAME}`

/** backlogLabels.loadFailed 미러 — 백로그 조회 실패 안내 문구 */
const LOAD_FAILED_TEXT = '백로그를 불러올 수 없습니다.'

/**
 * backlogLabels.retry 미러 — 재조회 버튼 이름.
 *
 * ★조회는 반드시 `{ exact: true }` 로 한다. Playwright 의 `getByRole(name)` 은 **부분 일치가
 *   기본**이라, 재조회 중 라벨 backlogLabels.retrying('다시 시도 중…')이 이 값을 부분
 *   문자열로 포함해 함께 잡힌다 (playwright-getbyrole-exact-strict-mode).
 */
const RETRY_BUTTON_NAME = '다시 시도'

/** backlogLabels.page.title 미러 — **라우트가 소유**하는 h1 텍스트 (에러 상태에서도 살아 있어야 한다) */
const PAGE_TITLE = '백로그'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 칸 locator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * role="region" + aria-label에 columnName이 포함된 첫 번째 요소를 반환한다.
 * BacklogColumn/SprintColumn: aria-label="{name} 칸, {count}개 이슈"
 * 이슈 수는 동작 후 변경되므로 filter(hasText: /^{name}/) 로 시작 텍스트만 매칭한다.
 */
function getColumnLocator(
  page: import('@playwright/test').Page,
  columnName: string,
) {
  return page
    .getByRole('region')
    .filter({ hasText: new RegExp(`^${columnName}`) })
    .first()
}

/** 백로그 칸 locator 축약 헬퍼 */
function getBacklogColumn(page: import('@playwright/test').Page) {
  return getColumnLocator(page, BACKLOG_COLUMN_NAME)
}

/** 스프린트 칸 locator 축약 헬퍼 — DEFAULT_SPRINT_NAME 기준 */
function getSprintColumn(page: import('@playwright/test').Page) {
  return getColumnLocator(page, DEFAULT_SPRINT_NAME)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 카드 locator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * aria-roledescription="draggable card" 요소 중 issueKey 텍스트를 포함하는 카드 locator.
 * BacklogCard: aria-roledescription={backlogLabels.draggableCard} = "draggable card"
 */
function getCardLocator(
  page: import('@playwright/test').Page,
  issueKey: string,
) {
  return page
    .locator('[aria-roledescription="draggable card"]')
    .filter({ hasText: issueKey })
    .first()
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — PointerSensor 드래그
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드를 PointerSensor로 대상 칸의 중심으로 드래그한다.
 * 크로스 칸 이동(S2/S3) 또는 칸 여백 드롭에 사용한다.
 *
 * @dnd-kit PointerSensor activationConstraint: { distance: 5 } —
 * pointerdown 후 5px 초과 이동 시 드래그가 시작된다.
 *
 * 구현 시퀀스.
 *   1. 카드 중심에 pointerdown
 *   2. 6px 이동 (activation threshold 초과)
 *   3. 대상 칸 중심으로 점진적 이동 (steps: 20 — pointermove 이벤트 여러 번 발화)
 *   4. pointerup
 *
 * board-kanban.spec.ts 선례와 동일한 패턴 (FR-BD-01 D7 교훈).
 *
 * @param page Playwright Page
 * @param issueKey 드래그할 카드 issueKey
 * @param targetColumn 대상 칸 locator
 */
async function dragCardToColumn(
  page: import('@playwright/test').Page,
  issueKey: string,
  targetColumn: import('@playwright/test').Locator,
): Promise<void> {
  const card = getCardLocator(page, issueKey)
  const cardBox = await card.boundingBox()
  const colBox = await targetColumn.boundingBox()

  if (cardBox === null || colBox === null) {
    throw new Error(
      `dragCardToColumn: bounding box를 가져올 수 없습니다. issueKey=${issueKey}`,
    )
  }

  const cardCX = cardBox.x + cardBox.width / 2
  const cardCY = cardBox.y + cardBox.height / 2
  const colCX = colBox.x + colBox.width / 2
  const colCY = colBox.y + colBox.height / 2

  await page.mouse.move(cardCX, cardCY)
  await page.mouse.down()
  // activation constraint 초과 (5px > 5px 이므로 6px)
  await page.mouse.move(cardCX + 6, cardCY)
  // 대상 칸으로 점진적 이동
  await page.mouse.move(colCX, colCY, { steps: 20 })
  await page.mouse.up()
}

/**
 * 카드를 PointerSensor로 대상 카드의 상단 중심으로 드래그한다.
 * 같은 칸 재정렬(S1/S5)에 사용한다 — card droppable을 hit해야 정확한 dropIndex 산출이 가능하다.
 *
 * 대상 카드의 상단 4분의 1 지점을 목표로 삼아 카드 droppable을 명확히 hit한다.
 *
 * @param page Playwright Page
 * @param fromKey 드래그할 카드 issueKey
 * @param toKey 드롭 대상 카드 issueKey (이 카드 위에 드롭)
 */
async function dragCardToCard(
  page: import('@playwright/test').Page,
  fromKey: string,
  toKey: string,
): Promise<void> {
  const fromCard = getCardLocator(page, fromKey)
  const toCard = getCardLocator(page, toKey)
  const fromBox = await fromCard.boundingBox()
  const toBox = await toCard.boundingBox()

  if (fromBox === null || toBox === null) {
    throw new Error(
      `dragCardToCard: bounding box를 가져올 수 없습니다. from=${fromKey}, to=${toKey}`,
    )
  }

  const fromCX = fromBox.x + fromBox.width / 2
  const fromCY = fromBox.y + fromBox.height / 2
  // 대상 카드의 상단 1/4 지점 — 카드 droppable rect에 포함되면서
  // 다른 요소와 겹치지 않는 위치
  const toCX = toBox.x + toBox.width / 2
  const toCY = toBox.y + toBox.height * 0.25

  await page.mouse.move(fromCX, fromCY)
  await page.mouse.down()
  await page.mouse.move(fromCX + 6, fromCY)
  await page.mouse.move(toCX, toCY, { steps: 20 })
  await page.mouse.up()
}

/**
 * 칸 안의 카드 issueKey 배열을 DOM 순서대로 반환한다.
 *
 * BacklogCard는 aria-roledescription="draggable card" 요소에 issueKey 텍스트를 포함한다.
 * data-card-droppable 속성으로 카드 droppable을 식별하고, 속성값 파싱으로 issueKey를 추출한다.
 *
 * 순서: aria-roledescription="draggable card" 요소들 중 칸 안에 있는 것들의 텍스트에서
 *       issueKey를 추출한다.
 *
 * @param column 칸 locator
 */
async function getCardKeysInColumn(
  column: import('@playwright/test').Locator,
): Promise<string[]> {
  // data-card-droppable="card:{context}:{key}" 속성에서 key 파싱
  const cards = column.locator('[data-card-droppable]')
  const count = await cards.count()
  const keys: string[] = []
  for (let i = 0; i < count; i++) {
    const card = cards.nth(i)
    const attr = await card.getAttribute('data-card-droppable')
    if (attr !== null) {
      // 형식: "card:backlog:ATLAS-1" 또는 "card:sprint:ATLAS-3"
      const parts = attr.split(':')
      // parts[0]='card', parts[1]=context, parts[2]=issueKey (단, ATLAS-1 = parts.slice(2).join(':'))
      if (parts.length >= 3) {
        keys.push(parts.slice(2).join(':'))
      }
    }
  }
  return keys
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-BL-01/02 백로그·스프린트 보드 (재정렬/이동/스프린트 관리)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. 백로그 재정렬
  //
  // Given  alice 로그인 (ServiceWorker 활성)
  //        DEFAULT_BACKLOG 자동 시드 → 백로그에 ATLAS-1, ATLAS-2 / 스프린트에 ATLAS-3, ATLAS-4
  // When   /projects/ATLAS/backlog 진입
  //        ATLAS-1 카드를 ATLAS-2 하단으로 드래그 (백로그 칸 내 재정렬)
  // Then   낙관적 업데이트: 카드가 여전히 백로그 칸에 존재 (이동 완료 신호)
  //        PATCH /api/v1/issues/{key}/rank 호출됨 (MSW store 변이)
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 백로그 재정렬 — ATLAS-1을 ATLAS-2 위로 드래그 → 순서 유지(no-op) 또는 순서 변경 확인', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 백로그 칸에 ATLAS-1(첫 번째), ATLAS-2(두 번째) 순서로 표시됨
    const backlogColumn = getBacklogColumn(page)
    await expect(backlogColumn).toBeVisible()
    await expect(backlogColumn.getByText(BACKLOG_CARD_1)).toBeVisible()
    await expect(backlogColumn.getByText(BACKLOG_CARD_2)).toBeVisible()

    // Given. 초기 DOM 순서 확인: [ATLAS-1, ATLAS-2]
    const initialOrder = await getCardKeysInColumn(backlogColumn)
    expect(initialOrder).toEqual([BACKLOG_CARD_1, BACKLOG_CARD_2])

    // When. ATLAS-2를 ATLAS-1 카드 위로 드래그 (ATLAS-2를 맨 앞으로 이동)
    // card droppable을 hit해야 dropIndex가 정확히 계산된다.
    await dragCardToCard(page, BACKLOG_CARD_2, BACKLOG_CARD_1)

    // Then. 두 카드 모두 백로그 칸에 존재
    await expect(backlogColumn.getByText(BACKLOG_CARD_1)).toBeVisible()
    await expect(backlogColumn.getByText(BACKLOG_CARD_2)).toBeVisible()

    // Then. DOM 순서가 변경됨 — ATLAS-2가 ATLAS-1보다 앞에 위치
    // MSW rerank → store rank 갱신 → invalidateQueries refetch → 새 순서 렌더
    // (reload 금지 — SPA 내부 refetch로만 확인)
    await expect(async () => {
      const newOrder = await getCardKeysInColumn(backlogColumn)
      expect(newOrder).toEqual([BACKLOG_CARD_2, BACKLOG_CARD_1])
    }).toPass({ timeout: 5000 })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. 백로그→스프린트 이동
  //
  // Given  alice 로그인 + 백로그 페이지 진입
  //        백로그에 ATLAS-1, ATLAS-2 / 스프린트 1에 ATLAS-3, ATLAS-4
  // When   ATLAS-2 카드를 스프린트 1 칸으로 PointerSensor 드래그
  // Then   낙관적 업데이트: 스프린트 1 칸에 ATLAS-2 카드 나타남
  //        백로그 칸에서 ATLAS-2 사라짐
  //        (MSW assignToSprintHandler → store 변이 → invalidateQueries refetch 후 일관)
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 백로그→스프린트 이동 — ATLAS-2를 백로그에서 스프린트 1로 드래그', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 백로그에 ATLAS-2 확인
    const backlogColumn = getBacklogColumn(page)
    await expect(backlogColumn.getByText(BACKLOG_CARD_2)).toBeVisible()

    // Given. 스프린트 1 칸 확인
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn).toBeVisible()
    await expect(sprintColumn.getByText(SPRINT_CARD_1)).toBeVisible()

    // When. ATLAS-2를 스프린트 1 칸으로 드래그
    await dragCardToColumn(page, BACKLOG_CARD_2, sprintColumn)

    // Then. 스프린트 1 칸에 ATLAS-2 나타남 (낙관적 이동)
    await expect(sprintColumn.getByText(BACKLOG_CARD_2)).toBeVisible()

    // Then. 백로그 칸에서 ATLAS-2 사라짐
    await expect(backlogColumn.getByText(BACKLOG_CARD_2)).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. 스프린트→백로그 이동
  //
  // Given  alice 로그인 + 백로그 페이지 진입
  //        스프린트 1에 ATLAS-3, ATLAS-4 존재
  // When   ATLAS-3 카드를 백로그 칸으로 PointerSensor 드래그
  // Then   낙관적 업데이트: 백로그 칸에 ATLAS-3 나타남
  //        스프린트 1 칸에서 ATLAS-3 사라짐
  //        (MSW unassignFromSprintHandler → store 변이 → refetch 후 일관)
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 스프린트→백로그 이동 — ATLAS-3을 스프린트 1에서 백로그로 드래그', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 스프린트 1 칸에 ATLAS-3 확인
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn.getByText(SPRINT_CARD_1)).toBeVisible()

    // Given. 백로그 칸 확인
    const backlogColumn = getBacklogColumn(page)
    await expect(backlogColumn).toBeVisible()

    // When. ATLAS-3을 백로그 칸으로 드래그
    await dragCardToColumn(page, SPRINT_CARD_1, backlogColumn)

    // Then. 백로그 칸에 ATLAS-3 나타남 (낙관적 이동)
    await expect(backlogColumn.getByText(SPRINT_CARD_1)).toBeVisible()

    // Then. 스프린트 1 칸에서 ATLAS-3 사라짐
    await expect(sprintColumn.getByText(SPRINT_CARD_1)).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 충돌 409 토스트 — SKIP
  //
  // backlog-handlers.ts에 충돌 분기(LS 플래그)가 미구현됨.
  // board-handlers.ts의 AGILE_CONFLICT 패턴을 따르려면 구현 코드 수정이 필요하므로
  // qa 역할 범위 밖. 후속 FR에서 backlog-handlers.ts에 분기 추가 시 보강 가능.
  // ─────────────────────────────────────────────────────────────────────────

  // ─────────────────────────────────────────────────────────────────────────
  // S5. 스프린트 내 재정렬
  //
  // Given  alice 로그인 + 백로그 페이지 진입
  //        스프린트 1에 ATLAS-3, ATLAS-4 존재
  // When   ATLAS-4 카드를 스프린트 1 칸 내에서 드래그 (칸 상단으로)
  // Then   ATLAS-4가 스프린트 1 칸에 여전히 존재 (칸 내 재정렬 완료)
  //        ATLAS-3도 여전히 스프린트 1 칸에 존재
  // ─────────────────────────────────────────────────────────────────────────
  test('S5 스프린트 내 재정렬 — ATLAS-4를 ATLAS-3 위로 드래그 → 순서 변경 확인', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 스프린트 1 칸에 ATLAS-3(첫 번째), ATLAS-4(두 번째) 확인
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn.getByText(SPRINT_CARD_1)).toBeVisible()
    await expect(sprintColumn.getByText(SPRINT_CARD_2)).toBeVisible()

    // Given. 초기 DOM 순서 확인: [ATLAS-3, ATLAS-4]
    const initialOrder = await getCardKeysInColumn(sprintColumn)
    expect(initialOrder).toEqual([SPRINT_CARD_1, SPRINT_CARD_2])

    // When. ATLAS-4를 ATLAS-3 카드 위로 드래그 (ATLAS-4를 맨 앞으로 이동)
    // card droppable을 hit해 dropIndex를 정확히 산출한다.
    await dragCardToCard(page, SPRINT_CARD_2, SPRINT_CARD_1)

    // Then. 두 카드 모두 스프린트 1 칸에 존재
    await expect(sprintColumn.getByText(SPRINT_CARD_2)).toBeVisible()
    await expect(sprintColumn.getByText(SPRINT_CARD_1)).toBeVisible()

    // Then. DOM 순서가 변경됨 — ATLAS-4가 ATLAS-3보다 앞에 위치
    // MSW rerank → store rank 갱신 → invalidateQueries refetch → 새 순서 렌더
    await expect(async () => {
      const newOrder = await getCardKeysInColumn(sprintColumn)
      expect(newOrder).toEqual([SPRINT_CARD_2, SPRINT_CARD_1])
    }).toPass({ timeout: 5000 })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6. 스프린트 생성
  //
  // Given  alice 로그인 (CREATE 권한) + 백로그 페이지 진입
  //        CreateSprintForm 폼이 화면 상단에 존재
  // When   스프린트 이름 입력(aria-label="스프린트 이름") + 스프린트 생성 버튼 클릭
  // Then   새 스프린트 칸이 화면에 등장 (스프린트 이름 + PLANNED 배지)
  //        새 스프린트 칸에 "스프린트 시작" 버튼 표시
  //
  // Note.  기존 DEFAULT_SPRINT_NAME("스프린트 1")이 이미 있으므로 구분을 위해 다른 이름 사용.
  // ─────────────────────────────────────────────────────────────────────────
  test('S6 스프린트 생성 — 폼에 이름 입력 후 생성 클릭 → 새 스프린트 칸 등장', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 스프린트 생성 폼 존재 확인
    await expect(page.getByRole('form', { name: '스프린트 생성 폼' })).toBeVisible()

    // When. 스프린트 이름 입력
    const newSprintName = 'E2E 테스트 스프린트'
    await page.getByLabel('스프린트 이름').fill(newSprintName)

    // When. 스프린트 생성 버튼 클릭
    await page.getByRole('button', { name: '스프린트 생성', exact: true }).click()

    // Then. 새 스프린트 칸이 화면에 등장
    const newSprintColumn = getColumnLocator(page, newSprintName)
    await expect(newSprintColumn).toBeVisible()

    // Then. PLANNED 배지 표시 (스프린트 상태)
    await expect(newSprintColumn.getByLabel('스프린트 상태: PLANNED')).toBeVisible()

    // Then. 스프린트 시작 버튼 표시 (PLANNED 상태이므로)
    await expect(newSprintColumn.getByRole('button', { name: '스프린트 시작', exact: true })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S7. 스프린트 시작
  //
  // Given  alice 로그인 (CREATE 권한) + 백로그 페이지 진입
  //        DEFAULT_BACKLOG의 스프린트 1이 PLANNED 상태
  // When   "스프린트 시작" 버튼 클릭 (스프린트 1 칸 헤더)
  // Then   스프린트 1 칸 헤더의 상태 배지가 ACTIVE로 변경됨
  //        "스프린트 시작" 버튼이 "스프린트 완료" 버튼으로 교체됨
  //        (MSW startSprintHandler → store 변이 → invalidateQueries refetch 후 일관)
  // ─────────────────────────────────────────────────────────────────────────
  test('S7 스프린트 시작 — 스프린트 1의 "스프린트 시작" 클릭 → ACTIVE 배지 표시', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 스프린트 1 칸 + PLANNED 배지 확인
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn).toBeVisible()
    await expect(sprintColumn.getByLabel('스프린트 상태: PLANNED')).toBeVisible()

    // Given. "스프린트 시작" 버튼 존재 확인
    const startButton = sprintColumn.getByRole('button', { name: '스프린트 시작', exact: true })
    await expect(startButton).toBeVisible()

    // When. "스프린트 시작" 버튼 클릭
    await startButton.click()

    // Then. 스프린트 상태 배지가 ACTIVE로 변경됨
    await expect(sprintColumn.getByLabel('스프린트 상태: ACTIVE')).toBeVisible()

    // Then. "스프린트 완료" 버튼으로 교체됨 (ACTIVE 상태 버튼 슬롯)
    await expect(
      sprintColumn.getByRole('button', { name: '스프린트 완료', exact: true }),
    ).toBeVisible()

    // Then. "스프린트 시작" 버튼 사라짐
    await expect(
      sprintColumn.getByRole('button', { name: '스프린트 시작', exact: true }),
    ).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // 보조. 초기 렌더 확인
  //
  // Given  alice 로그인
  //        DEFAULT_BACKLOG 자동 시드
  // When   /projects/ATLAS/backlog 진입
  // Then   "백로그" 칸에 ATLAS-1, ATLAS-2 표시
  //        "스프린트 1" 칸에 ATLAS-3, ATLAS-4 표시
  //        스프린트 1이 PLANNED 상태
  //        스프린트 생성 폼 표시
  //        페이지 헤더 "백로그" 표시
  // ─────────────────────────────────────────────────────────────────────────
  test('초기 렌더 — DEFAULT_BACKLOG 시드 후 백로그·스프린트 칸과 카드가 정확히 표시됨', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. 백로그 페이지 진입
    await page.goto(BACKLOG_URL)

    // Then. 페이지 헤더 확인
    await expect(page.getByRole('heading', { name: '백로그', level: 1 })).toBeVisible()

    // Then. 백로그 칸에 ATLAS-1, ATLAS-2 확인
    const backlogColumn = getBacklogColumn(page)
    await expect(backlogColumn).toBeVisible()
    await expect(backlogColumn.getByText(BACKLOG_CARD_1)).toBeVisible()
    await expect(backlogColumn.getByText(BACKLOG_CARD_2)).toBeVisible()

    // Then. 스프린트 1 칸에 ATLAS-3, ATLAS-4 확인
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn).toBeVisible()
    await expect(sprintColumn.getByText(SPRINT_CARD_1)).toBeVisible()
    await expect(sprintColumn.getByText(SPRINT_CARD_2)).toBeVisible()

    // Then. 스프린트 1이 PLANNED 상태
    await expect(sprintColumn.getByLabel('스프린트 상태: PLANNED')).toBeVisible()

    // Then. 스프린트 생성 폼 표시
    await expect(page.getByRole('form', { name: '스프린트 생성 폼' })).toBeVisible()

    // Then. 스프린트 ID가 DEFAULT_SPRINT_ID와 일치 (droppable id="sprint-{sprintId}")
    await expect(page.locator(`[data-droppable="sprint-${DEFAULT_SPRINT_ID}"]`)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S9. 담당자 이니셜 아바타 (FR-UX-13 F5)
  //
  // Given  alice 로그인 + DEFAULT_BACKLOG 자동 시드
  //        ATLAS-1.assigneeId = ALICE_USER_ID → 사용자 다건 조회로 '김앨리스' 해석
  // When   /projects/ATLAS/backlog 진입
  // Then   ATLAS-1 카드 안에 "담당자: 김앨리스" 접근성 이름을 가진 아바타가 보인다
  //
  // 셀렉터 결정. 같은 담당자의 ATLAS-3 이 스프린트 칸에도 있어 전역 조회는 strict mode 로
  //   깨진다. 카드 locator 로 한정해 "담당자가 배정된 그 카드"임을 함께 증명한다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S9 담당자 이니셜 아바타 — 담당자가 있는 카드가 "담당자: 김앨리스" 접근성 이름을 노출한다', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 담당자가 배정된 카드(ATLAS-1)가 렌더됨
    const assignedCard = getCardLocator(page, BACKLOG_CARD_1)
    await expect(assignedCard).toBeVisible()

    // Then. 그 카드 안에 담당자 아바타가 접근성 이름으로 노출된다
    await expect(assignedCard.getByLabel(ASSIGNEE_ARIA_LABEL)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S10. 백로그 조회 실패 → 안내 + 재시도 (FR-UX-13 F5)
  //
  // Given  alice 로그인
  //        LS_KEY_BACKLOG_FAIL='true' 를 addInitScript 로 심어 GET backlog 가 500 을 반환
  // When   /projects/ATLAS/backlog 진입
  // Then   role="alert" 안내 문구 + [다시 시도] 버튼이 보이고,
  //        라우트가 소유한 h1 "백로그" 는 에러 상태에서도 살아 있다
  //
  // 설계 결정.
  //   - addInitScript 는 loginAsAlice **이후**·goto **이전**에 등록해야 첫 로드부터 먹는다
  //     (e2e-msw-scenario-toggle-localstorage-flag).
  //   - Playwright 는 테스트마다 BrowserContext(및 localStorage)를 새로 만들므로 플래그가
  //     다른 시나리오로 새지 않는다 — 별도 정리 코드 불필요 (import.spec.ts S3 선례).
  //   - 재시도 버튼 조회는 exact:true — retrying('다시 시도 중…')이 부분 일치로 함께 잡힌다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S10 조회 실패 — 안내 문구와 [다시 시도] 가 보이고 h1 "백로그" 가 살아 있다', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 백로그 조회 실패 토글 주입 (goto 이전)
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_KEY_BACKLOG_FAIL)

    // When. 백로그 페이지 진입
    await page.goto(BACKLOG_URL)

    // Then. 조회 실패 안내가 alert 로 노출된다
    await expect(page.getByRole('alert')).toContainText(LOAD_FAILED_TEXT)

    // Then. 탈출구가 있다 — 재조회 버튼 (exact:true 필수)
    await expect(
      page.getByRole('button', { name: RETRY_BUTTON_NAME, exact: true }),
    ).toBeVisible()

    // Then. h1 은 라우트 소유라 에러 상태에서도 살아 있다 (jira-parity-contract §2 즉사 계약)
    await expect(page.getByRole('heading', { name: PAGE_TITLE, level: 1 })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S11. 재시도 성공 → 정상 복귀 (FR-UX-13 F5 · 스펙 S7)
  //
  // Given  alice 로그인
  //        LS_KEY_BACKLOG_FAIL=BACKLOG_FAIL_ONCE 로 심어 **첫 조회만** 500 이 되게 한다
  // When   /projects/ATLAS/backlog 진입 → 에러 안내 확인 → [다시 시도] 클릭
  // Then   백로그 칸에 카드가 실제로 돌아오고 에러 블록은 사라진다
  //
  // 왜 이 시나리오가 필요한가.
  //   S10 은 [다시 시도] 버튼의 **존재만** 보고 한 번도 누르지 않는다. 유닛 T4-2 도 refetch
  //   **호출 횟수**만 세므로 재조회 결과가 화면에 반영되는지는 아무도 보지 않았다. 즉 스펙
  //   S7("재시도 클릭 후 정상 복귀")이 사람 눈확인에만 남아 있었다 — 여기서 자동으로 닫는다.
  //
  // 왜 'true' 가 아니라 1회성 토글인가.
  //   'true' 는 재조회도 500 이라 복귀를 **구조적으로 잴 수 없다**. 핸들러가 1회성 값일 때만
  //   플래그를 지우므로 첫 조회 실패 → 재시도 성공이라는 시간 순서가 결정적으로 만들어진다.
  //
  // 셀렉터. 재시도 버튼은 exact:true — retrying('다시 시도 중…')이 부분 일치로 함께 잡힌다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S11 재시도 성공 — [다시 시도] 클릭 후 백로그 카드가 복귀한다', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 첫 조회만 실패하는 1회성 토글 주입 (goto 이전)
    await page.addInitScript(
      ({ key, value }: { key: string; value: string }) => {
        window.localStorage.setItem(key, value)
      },
      { key: LS_KEY_BACKLOG_FAIL, value: BACKLOG_FAIL_ONCE },
    )

    // Given. 백로그 페이지 진입 → 첫 조회 실패로 에러 안내가 떠 있다
    await page.goto(BACKLOG_URL)
    await expect(page.getByRole('alert')).toContainText(LOAD_FAILED_TEXT)

    // When. [다시 시도] 클릭 (exact:true 필수)
    await page
      .getByRole('button', { name: RETRY_BUTTON_NAME, exact: true })
      .click()

    // Then. 백로그 칸의 카드가 실제로 렌더된다 — 재조회 결과가 화면에 반영됐다는 증인
    const backlogColumn = getBacklogColumn(page)
    await expect(backlogColumn.getByText(BACKLOG_CARD_1)).toBeVisible()

    // Then. 에러 블록은 사라진다 (복귀의 나머지 절반)
    await expect(page.getByRole('alert')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S12. 재시도도 실패 → 에러 블록 유지 (FR-UX-13 F5 · 엣지 E9)
  //
  // Given  alice 로그인
  //        LS_KEY_BACKLOG_FAIL='true' 로 심어 조회가 **매번** 500 이 되게 한다
  // When   /projects/ATLAS/backlog 진입 → [다시 시도] 클릭
  // Then   에러 안내와 [다시 시도] 버튼이 그대로 살아 있다 (탈출구가 사라지지 않는다)
  //
  // 비-공허 근거.
  //   "클릭이 실제로 재조회를 낸다"의 증인은 **S11** 이다 — 같은 버튼·같은 경로에서 화면이
  //   바뀌는 것을 이미 증명한다. S12 는 그 재조회가 다시 실패했을 때 화면이 빈 상태로
  //   무너지지 않고 탈출구를 유지하는지만 잰다 (짝 시나리오).
  // ─────────────────────────────────────────────────────────────────────────
  test('S12 재시도 실패 — 재조회도 500 이면 에러 안내와 [다시 시도] 가 유지된다', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 계속 실패 토글 주입 (goto 이전)
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_KEY_BACKLOG_FAIL)

    // Given. 백로그 페이지 진입 → 에러 안내가 떠 있다
    await page.goto(BACKLOG_URL)
    await expect(page.getByRole('alert')).toContainText(LOAD_FAILED_TEXT)

    // When. [다시 시도] 클릭 (exact:true 필수)
    const retryButton = page.getByRole('button', {
      name: RETRY_BUTTON_NAME,
      exact: true,
    })
    await retryButton.click()

    // Then. 에러 안내가 유지된다 — 재조회 실패 후 빈 화면으로 무너지지 않는다
    await expect(page.getByRole('alert')).toContainText(LOAD_FAILED_TEXT)

    // Then. 탈출구도 유지된다 — 재조회가 끝나면 버튼 라벨이 '다시 시도'로 돌아온다
    //   (재조회 중에는 '다시 시도 중…'이라 exact:true 조회가 일시적으로 비고, auto-retrying
    //    단언이 그 창을 넘겨 최종 상태를 본다)
    await expect(retryButton).toBeVisible()
  })
})
