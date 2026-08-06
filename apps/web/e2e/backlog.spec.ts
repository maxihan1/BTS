// 백로그·스프린트 보드 E2E — 세로 스택·드래그·스프린트 다이얼로그·키보드 DnD (FR-BL-01/02 D6/D7 · FR-UX-13 F5/F15)
//
// 시나리오 개요.
//   S1. 백로그 재정렬   — 백로그 카드를 같은 칸 다른 위치로 드래그 → 순서 변경(낙관적 반영 확인)
//   S2. 백로그→스프린트 — 백로그 카드를 스프린트 칸으로 드래그 → 스프린트에 이슈 표시
//   S3. 스프린트→백로그 — 스프린트 카드를 백로그 칸으로 드래그 → 백로그에 이슈 복귀
//   S5. 스프린트 내 재정렬 — 스프린트 안에서 카드 위치 변경
//   S6. 스프린트 생성   — "스프린트 생성" 폼 제출 → 새 스프린트 칸 등장
//   S7. 스프린트 시작   — 트리거 클릭 → 시작 다이얼로그 → 제출 → ACTIVE 배지 (F15 FR-3)
//   S9. 담당자 아바타   — 담당자가 있는 카드가 "담당자: {이름}" 접근성 이름을 노출 (FR-UX-13 F5)
//   S10. 조회 실패      — LS_KEY_BACKLOG_FAIL 토글 → 안내 문구 + [다시 시도] + h1 생존 (FR-UX-13 F5)
//   S11. 재시도 성공    — 1회성 실패 토글 → [다시 시도] 클릭 → 카드 복귀 (FR-UX-13 F5 스펙 S7)
//   S12. 재시도 실패    — 계속 실패 토글 → [다시 시도] 클릭 → 에러 블록·버튼 유지 (엣지 E9)
//   S13. 세로 스택 순서 — 스프린트 섹션이 백로그 섹션보다 **위**에 있다 (F15 FR-1)
//   S14. 섹션 접기 영속 — 접으면 카드가 사라지고 **새로고침 후에도** 접힌 채다 (F15 FR-2)
//   S15. 시작 값 변경   — 종료일을 바꿔 제출 → PATCH 가 나가고 ACTIVE (F15 FR-4)
//   S16. 이관 대상 목록 — PLANNED 는 후보에 있고 COMPLETED 는 없다 (F15 FR-5)
//   S17. 완료 요청 순서 — 이관 DELETE 가 전부 끝난 **뒤에** complete 가 나간다 (F15 FR-6)
//   S18. 이관 부분 실패 — complete 가 **나가지 않고** 다이얼로그가 남는다 (F15 FR-6 · ADR C1)
//   S19. 키보드 DnD     — Tab → Space → ↓ → Space 로 옆 섹션까지 옮긴다 (F15 FR-8 · FR-15)
//   S20. 키보드 Esc 취소 — 같은 상태에서 Esc → 「취소했습니다.」 + 쓰기 요청 0건 (F15 FR-16 · E16)
//
// 설계 결정.
//   - backlog-fixtures.ts 모듈 로드 시 seedBacklog(DEFAULT_BACKLOG) 자동 호출(MODE!=='test').
//     loginAsAlice → page.goto('/projects/ATLAS/backlog') 진입 시 이미 카드가 시드됨.
//   - 드래그: PointerSensor (activationConstraint: {distance:5}).
//     page.mouse.down → move(+6px) → move(대상 칸 중심, steps:20) → up.
//     board-kanban.spec.ts 선례 그대로.
//   - ★세로 스택(F15 FR-1) 전환 이후 **출발·도착이 동시에 화면에 안 보이는 것이 기본값**이다.
//     그래서 좌표를 계산하기 **전에** bringPairIntoView 로 ① 스크롤하고 ② 좌표를 다시 재고
//     ③ 동시 가시성을 단언한다. 안 그러면 화면 밖 좌표로 마우스를 움직여 조용히 실패한다
//     (스펙 §리뷰 반영 C-6 · §구현 중 실측 정정 I-9).
//   - 낙관적 업데이트 검증: 드래그 후 즉시 대상 칸의 카드 가시성으로 확인.
//     MSW store가 stateful하므로 invalidateQueries refetch 후에도 값이 유지됨
//     (msw-mutation-stateful-refetch 교훈). reload는 store 초기화 = 가짜그린이므로 절대 금지.
//     ★단 하나의 예외가 S14 다 — 거기서 재는 것은 서버 데이터가 아니라 **localStorage 접힘
//     상태**라 실제 새로고침이 유일하게 정직한 수단이다. 근거는 S14 주석에 적어 뒀다.
//   - 분별 시드: backlog에 ATLAS-1/ATLAS-2, 스프린트에 ATLAS-3/ATLAS-4 — 칸 구분 명확.
//   - 칸 셀렉터: role="region" + aria-label 포함 텍스트 (컨테이너 한정 — strict-mode 방지).
//   - 카드 셀렉터: aria-roledescription="draggable card" + issueKey 텍스트.
//   - ★문구·픽스처는 **정본을 직접 import** 한다 (F15 Task 10). `backlog-labels.ts` 는 순수
//     상수 모듈이고, `backlog-fixtures.ts` 는 `import.meta.env` 부재를 스스로 가드하므로
//     (파일 하단 주석) Playwright 의 Node 로더에서 안전하다 — 2026-08-05 실측 확인.
//     「직접 import 하면 오류」라는 이 파일의 옛 서술은 그 가드가 생기기 전 이야기다.
//     기존 F5 상수들은 미러로 남겨 두되(불필요한 변경 회피) 신규 시나리오는 전부 import 를 쓴다.
//   - MSW serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block).
//
// SKIP 시나리오.
//   - S4(충돌 409 토스트): backlog-handlers.ts에 충돌 플래그 분기가 없다. board-handlers.ts는
//     AGILE_CONFLICT LS 플래그를 별도 구현했으나 backlog는 해당 없음. addInitScript 분기 추가는
//     구현 코드 수정 범위이므로 SKIP (사유: MSW 핸들러 분기 미구현 — 후속 FR에서 보강 가능).
//
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { backlogLabels } from '../src/i18n/backlog-labels'
import { DEFAULT_BACKLOG } from '../src/mocks/backlog-fixtures'

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
// 상수 — FR-UX-13 F15 (세로 스택 · 다이얼로그 · 키보드 DnD)
//
// 여기서부터는 미러가 아니라 **정본 import** 다 (파일 상단 §설계 결정 참조).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `src/mocks/backlog-handlers.ts` 의 `LS_KEY_SPRINT_UNASSIGN_FAIL` 값 미러.
 *
 * 값은 **실패시킬 이슈 키의 쉼표 구분 목록**이다 — 목록에 든 키만 500 이고 나머지는 정상 204 다.
 * S18(이관 **부분** 실패)은 「일부만 실패」가 필요해서 전건 실패 토글로는 만들 수 없다.
 *
 * ★이 값만 미러인 이유. `backlog-handlers.ts` 는 `msw` 를 런타임 import 하므로 spec 에서
 *   끌어오면 Playwright 의 Node 로더가 MSW 를 통째로 평가하게 된다. LS 키는 문자열 하나뿐이라
 *   `LS_KEY_BACKLOG_FAIL` 과 같은 관례(미러 + 출처 주석)를 그대로 따른다.
 */
const LS_KEY_SPRINT_UNASSIGN_FAIL = '__bts_e2e_sprint_unassign_fail'

/** S15 가 시작 다이얼로그에서 새로 채워 넣는 종료일 — 픽스처의 `endDate: null` 과 반드시 달라야 한다 */
const CHANGED_END_DATE = '2026-12-31'

/**
 * `SprintColumnHeader.tsx`·`SprintColumn.tsx` 의 상태 배지 `aria-label` 형식 미러.
 *
 * 접두사(`스프린트 상태: `)는 컴포넌트가 직접 조립하고 `backlogLabels` 에 없다. 상태 문자열
 * 자체는 정본(`backlogLabels.status`)에서 가져와 두 벌로 갈리지 않게 한다.
 */
function sprintStatusLabel(status: keyof typeof backlogLabels.status): string {
  return `스프린트 상태: ${backlogLabels.status[status]}`
}

/**
 * 픽스처에서 해당 상태의 스프린트를 꺼낸다. **없으면 그 자리에서 실패시킨다.**
 *
 * ★가짜 그린 차단 장치다 (`unreachable-state-fixture-is-fake-green`). S16 의
 * 「이관 대상에 COMPLETED 가 **없다**」는 픽스처에 COMPLETED 가 없어도 자동으로 참이 된다 —
 * 「있다」가 먼저 성립해야 「없다」를 잰 것이 된다. 픽스처가 퇴화하면 여기서 즉시 터진다.
 */
function sprintFixtureByStatus(status: 'PLANNED' | 'ACTIVE' | 'COMPLETED') {
  const found = DEFAULT_BACKLOG.sprints.find((entry) => entry.sprint.status === status)
  if (found === undefined) {
    throw new Error(
      `backlog-fixtures.ts DEFAULT_BACKLOG 에 ${status} 스프린트가 없습니다. ` +
        'S16 의 「COMPLETED 는 이관 대상에 없다」가 아무것도 재지 않는 자동 통과로 바뀝니다.',
    )
  }
  return found
}

/** ACTIVE 스프린트 — 완료 다이얼로그(S16·S17·S18)의 유일한 트리거이자 S19 키보드 드래그 출발지 */
const ACTIVE_SPRINT = sprintFixtureByStatus('ACTIVE')

/** PLANNED 스프린트 — 시작 다이얼로그(S7·S15) 트리거이자 이관 대상 후보 */
const PLANNED_SPRINT = sprintFixtureByStatus('PLANNED')

/** COMPLETED 스프린트 — 이관 대상에서 **제외돼야 하는** 실물 (S16 짝 단언의 나머지 절반) */
const COMPLETED_SPRINT = sprintFixtureByStatus('COMPLETED')

/** 픽스처 배열에서 값을 꺼내되 비어 있으면 실패시킨다 — 픽스처 퇴화를 조용히 넘기지 않는다 */
function requiredIssueKey(issue: { key: string } | undefined, what: string): string {
  if (issue === undefined) {
    throw new Error(`backlog-fixtures.ts DEFAULT_BACKLOG 에 ${what} 이(가) 없습니다.`)
  }
  return issue.key
}

/**
 * ACTIVE 스프린트의 **마지막** 이슈.
 *
 * S19 는 이 카드를 아래로 옮겨 섹션 경계를 넘는다 — 마지막 카드여야 `↓` 한 번의 다음 후보가
 * 다음 섹션의 첫 카드가 된다. S18 에서는 이 키만 이관을 실패시켜 「부분 실패」를 만든다.
 */
const ACTIVE_ISSUE_LAST = requiredIssueKey(ACTIVE_SPRINT.issues.at(-1), 'ACTIVE 스프린트의 마지막 이슈')

/** PLANNED 스프린트의 첫 이슈 — S19 가 카드를 놓을 착지 지점의 이웃 */
const PLANNED_ISSUE_FIRST = requiredIssueKey(PLANNED_SPRINT.issues[0], 'PLANNED 스프린트의 첫 이슈')

/**
 * 출발·도착을 한 화면에 넣을 때 위아래로 남겨 두는 여백(px).
 *
 * 0 으로 두면 드래그 도중 포인터가 스크롤 컨테이너 가장자리에 닿아 dnd-kit 의 자동 스크롤이
 * 발동하고, 그러면 측정해 둔 좌표가 드래그 중에 어긋난다.
 */
const PAIR_VIEW_MARGIN_PX = 24

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
// 헬퍼 — 섹션 접기 (F15 FR-2)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 섹션 접기/펼치기 토글 locator.
 *
 * 이름은 상태(접힘/펼침)로 바뀌지 않는다 — 상태는 `aria-expanded` 가 말한다 (F15 FR-2).
 * 그래서 조회는 이름 하나로 접힘·펼침 양쪽에서 동일하게 성립한다.
 *
 * @param sectionName 섹션 이름 — 백로그는 `backlogLabels.backlogTitle`, 스프린트는 그 이름
 */
function getCollapseToggle(page: Page, sectionName: string): Locator {
  return page.getByRole('button', {
    name: backlogLabels.collapseSection(sectionName),
    exact: true,
  })
}

/**
 * 지정한 섹션들을 접어 세로 스택의 높이를 줄인다 (스펙 §리뷰 반영 C-6 ①).
 *
 * 세로 스택에서는 출발·도착이 동시에 화면에 안 들어오는 것이 기본값이라, 드래그와 무관한
 * 섹션을 먼저 접어야 한다. 접기 전후로 `aria-expanded` 를 확인해 **토글이 실제로 먹었는지**
 * 확인한다 — 안 먹었는데 진행하면 뒤이은 좌표 계산이 조용히 어긋난다.
 */
async function collapseSections(page: Page, sectionNames: readonly string[]): Promise<void> {
  for (const sectionName of sectionNames) {
    const toggle = getCollapseToggle(page, sectionName)
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 출발·도착 동시 가시성 (스펙 §리뷰 반영 C-6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 출발과 도착이 **동시에** 화면에 보이도록 스크롤하고, 그러지 못하면 그 자리에서 실패시킨다.
 *
 * ### 왜 필요한가 (I-9 실측)
 * 세로 스택 전환 후 `viewportH 720` 기준으로 스프린트 1 은 `top=415`(화면 안)인데 백로그는
 * `top=822`(화면 밖)다. 드래그 헬퍼가 출발·도착 `boundingBox()` 를 **먼저 둘 다 계산한 뒤**
 * 마우스를 움직이므로, 그대로 두면 화면 밖 좌표를 찍고 아무 일도 일어나지 않는다.
 *
 * ### 왜 `scrollIntoViewIfNeeded()` 한 줄로는 안 되나 (C-6)
 * 도착만 스크롤하면 이번에는 출발 카드가 뷰포트를 벗어나 `mouse.down()` 이 안 닿는다.
 * 그래서 ① 둘의 **합집합**을 스크롤 영역 한가운데로 옮기고 ② 호출부가 좌표를 다시 재고
 * ③ 여기서 동시 가시성을 단언한다. 셋이 한 벌이어야 「조용히 엉뚱한 좌표를 찍는」 실패가 없다.
 *
 * @param from 드래그 출발 요소 (반드시 실제 hit-test 가 닿아야 하는 쪽)
 * @param to 드롭 대상 요소
 */
async function bringPairIntoView(page: Page, from: Locator, to: Locator): Promise<void> {
  // ShellLayout 의 `<main className="… overflow-y-auto">` 가 이 화면의 유일한 스크롤 컨테이너다
  const scroller = page.getByRole('main')
  const scrollerBox = await scroller.boundingBox()
  const fromBox = await from.boundingBox()
  const toBox = await to.boundingBox()

  if (scrollerBox === null || fromBox === null || toBox === null) {
    throw new Error('bringPairIntoView: bounding box를 가져올 수 없습니다.')
  }

  const top = Math.min(fromBox.y, toBox.y)
  const bottom = Math.max(fromBox.y + fromBox.height, toBox.y + toBox.height)
  const span = bottom - top

  if (span + PAIR_VIEW_MARGIN_PX * 2 > scrollerBox.height) {
    throw new Error(
      'bringPairIntoView: 출발·도착이 동시에 화면에 들어가지 않습니다 ' +
        `(필요 ${Math.round(span)}px + 여백 ${PAIR_VIEW_MARGIN_PX * 2}px, ` +
        `스크롤 영역 ${Math.round(scrollerBox.height)}px). ` +
        '드래그와 무관한 섹션을 collapseSections 로 먼저 접으세요 (C-6 ①).',
    )
  }

  // 합집합을 스크롤 영역 한가운데로. 스크롤 한계에 걸려 덜 움직여도 아래 단언이 진실을 말한다.
  const desiredTop = scrollerBox.y + (scrollerBox.height - span) / 2
  await scroller.evaluate((element, deltaY) => {
    element.scrollTop += deltaY
  }, top - desiredTop)

  // ③ 동시 가시성 단언 — 하나라도 잘려 있으면 여기서 끝낸다.
  await expect(from).toBeInViewport({ ratio: 1 })
  await expect(to).toBeInViewport({ ratio: 1 })
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
 *   0. 출발·도착을 한 화면에 넣고 동시 가시성을 단언한다 (C-6 — 세로 스택 전환으로 추가)
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
  // ★좌표를 재기 **전에** 스크롤한다 — 순서를 뒤집으면 낡은 좌표로 마우스를 움직인다 (C-6 ②)
  await bringPairIntoView(page, card, targetColumn)
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
  // ★좌표를 재기 **전에** 스크롤한다 (C-6 ②). 세로 스택에서는 백로그가 맨 아래로 내려가
  //   기본 스크롤 위치에서 화면 밖이다 — 같은 칸 재정렬(S1)도 예외가 아니다 (I-9).
  await bringPairIntoView(page, fromCard, toCard)
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
// 헬퍼 — 스프린트 다이얼로그 (F15 FR-3 · FR-5)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 시작 다이얼로그.
 *
 * 접근 이름은 `DialogTitle` 이 주고, 그 값은 트리거 버튼과 **같은 문구**를 재사용한다
 * (FR-10 — 즉사 계약이 고정한 이름을 새 문자열로 늘리지 않는다). 그래서 다이얼로그 안의
 * 제출 버튼을 찾을 때는 반드시 이 locator 로 범위를 좁혀야 한다.
 */
function getStartSprintDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: backlogLabels.startSprint, exact: true })
}

/** 스프린트 완료 다이얼로그. 이름 재사용 이유는 {@link getStartSprintDialog} 와 같다 */
function getCompleteSprintDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: backlogLabels.completeSprint, exact: true })
}

/**
 * ACTIVE 스프린트의 완료 다이얼로그를 연다.
 *
 * 완료 버튼은 ACTIVE 상태에서만 렌더되므로, 배지를 먼저 확인해 「버튼이 없어서 못 눌렀다」와
 * 「눌렀는데 안 열렸다」를 구분한다.
 */
async function openCompleteSprintDialog(page: Page): Promise<Locator> {
  const activeColumn = getColumnLocator(page, ACTIVE_SPRINT.sprint.name)
  await expect(activeColumn.getByLabel(sprintStatusLabel('ACTIVE'))).toBeVisible()
  await activeColumn
    .getByRole('button', { name: backlogLabels.completeSprint, exact: true })
    .click()

  const dialog = getCompleteSprintDialog(page)
  await expect(dialog).toBeVisible()
  return dialog
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 요청 관측 (F15 FR-4 · FR-6)
// ─────────────────────────────────────────────────────────────────────────────

/** {@link recordSprintCompletionCalls} 가 `POST /complete` 를 기록할 때 쓰는 표식 */
const COMPLETE_CALL = 'COMPLETE'

/**
 * 이관(`DELETE`)과 완료(`POST /complete`) 요청을 **발생 순서대로** 기록한다.
 *
 * 화면 상태만으로는 「완료가 이관보다 먼저 나갔다」를 구분할 수 없다 — 결과가 같아 보이기
 * 때문이다. ADR C1(완료는 되돌릴 수 없다)이 걸린 순서라 네트워크로 직접 잰다.
 *
 * ★`page.route()` 대신 `page.on('request')` 를 쓴다. MSW Service Worker 가 요청을 먼저
 *   가로채므로 route 오버라이드는 무효지만, 요청 발생 자체는 그대로 관측된다
 *   (`inline-edit.spec.ts`·`issue-create-entry-points.spec.ts` 선례).
 *
 * @param sprintId 관측 대상 스프린트 UUID
 * @returns 기록 배열. 테스트가 나중에 읽는다 (등록은 `goto` 이전에 해야 한다)
 */
function recordSprintCompletionCalls(page: Page, sprintId: string): string[] {
  const calls: string[] = []
  page.on('request', (request) => {
    const { pathname } = new URL(request.url())
    if (
      request.method() === 'DELETE' &&
      pathname.startsWith(`/api/v1/sprints/${sprintId}/issues/`)
    ) {
      calls.push(`DELETE ${pathname.slice(pathname.lastIndexOf('/') + 1)}`)
    }
    if (request.method() === 'POST' && pathname === `/api/v1/sprints/${sprintId}/complete`) {
      calls.push(COMPLETE_CALL)
    }
  })
  return calls
}

/**
 * 스프린트 메타 수정(`PATCH /sprints/{id}`) 요청 body 를 순서대로 기록한다.
 *
 * S15 가 「값을 바꾸면 변경분이 실제로 나간다」를 재는 유일한 증인이다 — 화면만 보면
 * S7(값 무변경)과 결과가 똑같아서 두 경로를 구분할 수 없다.
 */
function recordSprintPatchBodies(page: Page, sprintId: string): string[] {
  const bodies: string[] = []
  page.on('request', (request) => {
    const { pathname } = new URL(request.url())
    if (request.method() === 'PATCH' && pathname === `/api/v1/sprints/${sprintId}`) {
      bodies.push(request.postData() ?? '')
    }
  })
  return bodies
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 키보드 DnD (F15 FR-8 · FR-15 · FR-16)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * dnd-kit 의 스크린리더 공지 영역.
 *
 * `@dnd-kit/accessibility` 의 `LiveRegion` 이 `id="DndLiveRegion-{n}"` · `role="status"` 로
 * 그린다. 키보드 드래그는 화면이 거의 안 바뀌므로 **지금 어느 섹션 위에 있는가**를 알 수 있는
 * 유일한 관측 창구다.
 */
function getDragLiveRegion(page: Page): Locator {
  return page.locator('[id^="DndLiveRegion"]')
}

/**
 * 목표 요소에 포커스가 닿을 때까지 Tab 을 누른다.
 *
 * `locator.focus()` 로 대신하지 않는 이유. S19 가 재는 것은 「키보드만으로 카드를 옮길 수
 * 있다」이고, 그 전제가 **카드가 Tab 순서에 있다**는 것이다. 프로그램 포커스를 쓰면 그 전제를
 * 건너뛰어 버려 카드에서 `tabindex` 가 사라져도 테스트가 초록으로 남는다.
 *
 * 횟수는 셸 크롬(사이드바·상단바)에 따라 달라지므로 상한만 두고 **목표 상태로 판정**한다.
 */
async function focusByTab(page: Page, target: Locator, maxTabs = 80): Promise<void> {
  // 이미 포커스가 닿아 있으면 그대로 둔다. Tab 을 먼저 누르면 포커스가 떠나 버려, 한 테스트
  // 안에서 같은 카드를 두 번 집는 경로(S20 짝 단언 — 취소 뒤 dnd-kit 이 카드로 포커스를
  // 되돌려 놓는다)가 성립하지 않는다.
  const already = await target.evaluate((element) => element === document.activeElement)
  if (already) return

  for (let attempt = 0; attempt < maxTabs; attempt += 1) {
    await page.keyboard.press('Tab')
    const focused = await target.evaluate((element) => element === document.activeElement)
    if (focused) return
  }
  throw new Error(`focusByTab: Tab 을 ${maxTabs}회 눌렀는데 대상에 포커스가 닿지 않았습니다.`)
}

/**
 * 카드를 키보드로 집어 PLANNED 스프린트 위까지 옮긴다. **놓지는 않는다.**
 *
 * S19(Space 로 놓기)와 S20(Esc 로 취소)이 공유하는 「집고 이동」 절차다. 두 시나리오의 차이는
 * **마지막 한 키**뿐이라, 그 앞을 공유해야 「같은 상태에서 키만 다르다」가 실제로 성립한다.
 *
 * ★방향키 횟수를 하드코딩하지 않는다 — 근거는 S19 주석에 있다. 목표 상태(공지가 대상
 *   스프린트를 읽는다)로 판정하고 그때까지 누른다.
 */
async function pickUpAndMoveOverPlannedSprint(page: Page, card: Locator): Promise<void> {
  await focusByTab(page, card)

  // Space 로 집는다 (Enter 는 활성화 키가 아니다 — 카드 안 링크를 살리기 위함, FR-16)
  await page.keyboard.press('Space')
  await expect(card).toHaveAttribute('aria-pressed', 'true')

  const liveRegion = getDragLiveRegion(page)
  await expect(async () => {
    await page.keyboard.press('ArrowDown')
    await expect(liveRegion).toHaveText(
      backlogLabels.announce.overSprint(PLANNED_SPRINT.sprint.name),
      { timeout: 1_000 },
    )
  }).toPass({ timeout: 15_000 })
}

/**
 * 백로그 화면이 내는 **쓰기 요청**을 전부 기록한다 (S20).
 *
 * 드롭 한 번이 부를 수 있는 것은 셋이다 — `PATCH /issues/{key}/rank`(재정렬) ·
 * `POST /sprints/{id}/issues`(배정) · `DELETE /sprints/{id}/issues/{key}`(해제).
 * 그런데도 셋을 **열거하지 않는다**. 열거하면 새 엔드포인트가 생긴 날 「0건」이 조용히
 * 참이 되기 때문이다 (`two-lists-never-check-each-other`). GET 이 아닌 `/api/v1/` 전량을 담는다.
 */
function recordBacklogWrites(page: Page): string[] {
  const calls: string[] = []
  page.on('request', (request) => {
    const { pathname } = new URL(request.url())
    if (request.method() === 'GET') return
    if (!pathname.startsWith('/api/v1/')) return
    calls.push(`${request.method()} ${pathname}`)
  })
  return calls
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
  //
  // F15 갱신 (I-9). 세로 스택에서 백로그가 맨 아래로 내려가 기본 스크롤 위치에서는 두 카드가
  //   화면 밖이다. 좌표 재계산과 동시 가시성 단언은 dragCardToCard 안의 bringPairIntoView 가
  //   맡는다 — 같은 칸 안 재정렬이라 섹션을 접을 필요는 없다.
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
  //
  // F15 갱신 (C-6). 세로 스택에서 출발(백로그 맨 아래)과 도착(스프린트 1)이 동시에 안 보인다.
  //   드래그와 무관한 두 섹션을 먼저 접어 높이를 줄이고, 좌표 재계산·동시 가시성 단언은
  //   dragCardToColumn 안의 bringPairIntoView 가 맡는다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 백로그→스프린트 이동 — ATLAS-2를 백로그에서 스프린트 1로 드래그', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 드래그와 무관한 섹션을 접어 출발·도착을 한 화면에 넣는다 (C-6 ①)
    await collapseSections(page, [ACTIVE_SPRINT.sprint.name, COMPLETED_SPRINT.sprint.name])

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
  //
  // F15 갱신 (C-6). S2 와 같은 이유로 무관한 섹션을 먼저 접는다 — 방향만 반대다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 스프린트→백로그 이동 — ATLAS-3을 스프린트 1에서 백로그로 드래그', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 드래그와 무관한 섹션을 접어 출발·도착을 한 화면에 넣는다 (C-6 ①)
    await collapseSections(page, [ACTIVE_SPRINT.sprint.name, COMPLETED_SPRINT.sprint.name])

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
  //        → **확인 다이얼로그**가 열리고 그 안에서 제출한다 (F15 FR-3 로 추가된 단계)
  // Then   스프린트 1 칸 헤더의 상태 배지가 ACTIVE로 변경됨
  //        "스프린트 시작" 버튼이 "스프린트 완료" 버튼으로 교체됨
  //        (MSW startSprintHandler → store 변이 → invalidateQueries refetch 후 일관)
  //
  // F15 갱신. 트리거 이름(`스프린트 시작`)은 즉사 계약이라 **그대로**고(FR-10), 다이얼로그
  //   제목·제출 버튼도 같은 문구를 재사용한다. 그래서 조회는 반드시 `dialog` 로 한정한다 —
  //   전역 조회는 트리거와 제출 버튼이 함께 잡혀 strict mode 로 깨진다.
  //   값을 **바꾸는** 경로는 S15 가 따로 잰다 (여기는 값 무변경 = `PATCH` 미발사 경로).
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

    // When. "스프린트 시작" 버튼 클릭 → 확인 다이얼로그가 열린다
    await startButton.click()
    const dialog = getStartSprintDialog(page)
    await expect(dialog).toBeVisible()

    // When. 다이얼로그 안에서 제출 (값은 그대로 둔다)
    await dialog.getByRole('button', { name: backlogLabels.startSprint, exact: true }).click()

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

  // ─────────────────────────────────────────────────────────────────────────
  // S13. 세로 스택 순서 (FR-UX-13 F15 · FR-1)
  //
  // Given  alice 로그인 + DEFAULT_BACKLOG 자동 시드
  // When   /projects/ATLAS/backlog 진입
  // Then   스프린트 섹션이 백로그 섹션보다 **위**에 있고, 둘의 좌변이 같다
  //
  // 왜 좌변까지 보나.
  //   가로 배치였을 때 두 칸은 **같은 y**에 나란히 섰다. 그러니 「y 가 다르다」만으로는
  //   세로 스택이 됐다는 증거가 되지만, 「좌변이 같다」까지 봐야 둘이 정말 한 줄로 쌓였고
  //   가로 스크롤이 남아 있지 않다는 것이 된다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S13 세로 스택 — 스프린트 섹션이 백로그 섹션보다 위에 한 줄로 쌓인다', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    const sprintColumn = getSprintColumn(page)
    const backlogColumn = getBacklogColumn(page)
    await expect(sprintColumn).toBeVisible()
    await expect(backlogColumn).toBeVisible()

    // When. 두 섹션의 화면 사각형을 잰다 (뷰포트 밖이어도 좌표는 나온다)
    const sprintBox = await sprintColumn.boundingBox()
    const backlogBox = await backlogColumn.boundingBox()
    if (sprintBox === null || backlogBox === null) {
      throw new Error('S13: 섹션 bounding box를 가져올 수 없습니다.')
    }

    // Then. 스프린트가 통째로 백로그보다 위다 (겹치지 않는다)
    expect(sprintBox.y + sprintBox.height).toBeLessThanOrEqual(backlogBox.y)

    // Then. 좌변이 같다 — 옆으로 나란히 선 것이 아니라 한 줄로 쌓였다
    expect(Math.round(sprintBox.x)).toBe(Math.round(backlogBox.x))
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S14. 섹션 접기 영속 (FR-UX-13 F15 · FR-2)
  //
  // Given  alice 로그인 + 백로그 페이지 진입
  // When   백로그 섹션의 접기 토글 클릭 → 실제 새로고침
  // Then   접기 직후 카드가 사라지고, 새로고침 뒤에도 접힌 채로 복원된다
  //
  // ★이 파일에서 유일하게 page.reload() 를 쓰는 시나리오다.
  //   다른 곳에서 reload 가 금지인 이유는 MSW store 가 초기화돼 「서버에 저장됐다」가 가짜로
  //   통과하기 때문이다. 그런데 여기서 재는 것은 서버 데이터가 아니라 **localStorage 에
  //   적힌 접힘 상태**라서, SPA 내부 이동으로는 「같은 탭 메모리에 남아 있었을 뿐」과 구분되지
  //   않는다. 실제 새로고침만이 정직한 수단이다.
  //
  // 비-공허 짝.
  //   「카드가 안 보인다」만 보면 백로그 조회가 통째로 실패해도 통과한다. 그래서 새로고침 뒤
  //   **접지 않은 스프린트 섹션의 카드가 보인다**를 함께 단언해 보드가 정상 렌더됐음을
  //   같은 테스트가 증언하게 한다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S14 섹션 접기 — 카드가 사라지고 새로고침 후에도 접힌 채로 복원된다', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. 백로그 섹션이 펼쳐진 채로 카드를 보여 준다
    await expect(getBacklogColumn(page).getByText(BACKLOG_CARD_1)).toBeVisible()
    const toggle = getCollapseToggle(page, backlogLabels.backlogTitle)
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')

    // When. 접기
    await toggle.click()

    // Then. 카드가 DOM 에서 사라진다 (헤더는 남는다 — 토글이 그 증인)
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(getBacklogColumn(page).getByText(BACKLOG_CARD_1)).toHaveCount(0)

    // When. 실제 브라우저 새로고침
    await page.reload()

    // Then. 보드는 정상 렌더됐다 (비-공허 짝 — 접지 않은 섹션의 카드가 보인다)
    await expect(getSprintColumn(page).getByText(SPRINT_CARD_1)).toBeVisible()

    // Then. 백로그 섹션은 접힌 채로 복원된다
    await expect(getCollapseToggle(page, backlogLabels.backlogTitle)).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    await expect(getBacklogColumn(page).getByText(BACKLOG_CARD_1)).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S15. 시작 다이얼로그에서 값을 바꿔 제출 (FR-UX-13 F15 · FR-4)
  //
  // Given  alice 로그인 + PLANNED 스프린트(종료일 없음)
  // When   시작 다이얼로그를 열어 **종료일을 채운 뒤** 제출
  // Then   `PATCH /sprints/{id}` 가 그 값을 담아 1회 나가고, 스프린트가 ACTIVE 가 된다
  //
  // 왜 S7 의 확장이 아니라 별도 시나리오인가.
  //   S7 은 값을 안 바꾸는 경로라 `PATCH` 가 **나가지 않는** 것이 정답이다(FR-4 — 불필요한
  //   버전 증가가 낙관적 잠금 충돌면을 넓힌다). 두 경로는 화면 결과가 똑같아서 요청을 보지
  //   않으면 구분할 수 없고, 한 테스트에 합치면 어느 쪽도 제대로 재지 못한다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S15 시작 다이얼로그 — 종료일을 바꿔 제출하면 변경분이 나가고 ACTIVE 가 된다', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. PATCH 기록 시작 (goto 이전에 등록해야 첫 요청부터 잡힌다)
    const patchBodies = recordSprintPatchBodies(page, PLANNED_SPRINT.sprint.sprintId)
    await page.goto(BACKLOG_URL)

    // Given. PLANNED 스프린트의 시작 다이얼로그를 연다
    const sprintColumn = getSprintColumn(page)
    await expect(sprintColumn.getByLabel(sprintStatusLabel('PLANNED'))).toBeVisible()
    await sprintColumn.getByRole('button', { name: backlogLabels.startSprint, exact: true }).click()
    const dialog = getStartSprintDialog(page)
    await expect(dialog).toBeVisible()

    // When. 종료일을 채우고 제출한다
    await dialog
      .getByLabel(backlogLabels.startDialog.endDateLabel, { exact: true })
      .fill(CHANGED_END_DATE)
    await dialog.getByRole('button', { name: backlogLabels.startSprint, exact: true }).click()

    // Then. 스프린트가 시작됐다
    await expect(sprintColumn.getByLabel(sprintStatusLabel('ACTIVE'))).toBeVisible()

    // Then. 변경분이 실제로 서버로 나갔다 — S7 과 갈리는 유일한 지점
    expect(patchBodies).toHaveLength(1)
    expect(patchBodies[0]).toContain(CHANGED_END_DATE)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S16. 완료 다이얼로그의 이관 대상 목록 (FR-UX-13 F15 · FR-5)
  //
  // Given  alice 로그인 + 픽스처에 ACTIVE·PLANNED·COMPLETED 스프린트가 각각 있다
  // When   ACTIVE 스프린트의 완료 다이얼로그를 열어 이관 대상 Select 를 펼친다
  // Then   옮길 수 있는 곳(백로그·PLANNED)은 후보에 있고,
  //        반드시 409 로 끝나는 COMPLETED 와 자기 자신은 후보에 없다
  //
  // ★가짜 그린 차단 (`unreachable-state-fixture-is-fake-green` — 이 저장소에서 2회 적발).
  //   「COMPLETED 가 없다」만 쓰면 픽스처에 COMPLETED 가 아예 없을 때 자동으로 통과한다.
  //   그래서 ① 모듈 로드 시점에 sprintFixtureByStatus 가 셋의 실재를 강제하고
  //         ② 이 테스트가 **COMPLETED 섹션이 화면에 실제로 있다**를 먼저 단언한 뒤
  //         ③ 그 이름이 후보 목록에는 없다를 잰다.
  //   ACTIVE 를 「있다」쪽 단언에 넣지 않는 이유는 그것이 완료 대상 자신이라 **제외되는 것이
  //   정답**이기 때문이다 — 대신 「자기 자신도 후보에 없다」로 같은 사실을 잰다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S16 완료 다이얼로그 — 이관 후보에 PLANNED 는 있고 COMPLETED·자기 자신은 없다', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    // Given. COMPLETED 스프린트가 화면에 **실재한다** (짝 단언의 나머지 절반)
    const completedColumn = getColumnLocator(page, COMPLETED_SPRINT.sprint.name)
    await expect(completedColumn.getByLabel(sprintStatusLabel('COMPLETED'))).toBeVisible()

    // Given. ACTIVE 스프린트의 완료 다이얼로그를 연다
    const dialog = await openCompleteSprintDialog(page)

    // Given. 미완료 목록이 떴다 — 이관 대상 Select 는 미완료가 1건 이상일 때만 그려진다
    await expect(
      dialog.getByText(backlogLabels.completeDialog.summary(0, ACTIVE_SPRINT.issues.length), {
        exact: true,
      }),
    ).toBeVisible()

    // When. 이관 대상 Select 를 펼친다
    await dialog
      .getByRole('combobox', { name: backlogLabels.completeDialog.moveTargetLabel })
      .click()

    // Then. 옮길 수 있는 곳은 후보에 있다
    await expect(
      page.getByRole('option', {
        name: backlogLabels.completeDialog.backlogOption,
        exact: true,
      }),
    ).toBeVisible()
    await expect(
      page.getByRole('option', { name: PLANNED_SPRINT.sprint.name, exact: true }),
    ).toBeVisible()

    // Then. 반드시 409 로 끝나는 COMPLETED 는 후보에 없다
    await expect(
      page.getByRole('option', { name: COMPLETED_SPRINT.sprint.name, exact: true }),
    ).toHaveCount(0)

    // Then. 완료 대상 자기 자신도 후보에 없다
    await expect(
      page.getByRole('option', { name: ACTIVE_SPRINT.sprint.name, exact: true }),
    ).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S17. 완료 요청 순서 (FR-UX-13 F15 · FR-6 · ADR C1)
  //
  // Given  alice 로그인 + ACTIVE 스프린트에 미완료 이슈가 있다
  // When   완료 다이얼로그에서 「스프린트 완료」를 누른다 (이관 대상 = 백로그, 기본값)
  // Then   이관 DELETE 가 전부 나간 **뒤에** `POST /complete` 가 마지막으로 1회 나간다
  //
  // 왜 화면이 아니라 네트워크인가.
  //   순서가 뒤집혀도 최종 화면은 똑같아 보인다. 그런데 COMPLETED 가 된 뒤의 이슈는 꺼낼 수도
  //   옮길 수도 없어 **영구 동결**된다(ADR C1). 되돌릴 수 없는 연산이라 순서 자체가 계약이다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S17 스프린트 완료 — 이관 DELETE 가 모두 끝난 뒤에 완료 요청이 나간다', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 요청 기록 시작 (goto 이전)
    const calls = recordSprintCompletionCalls(page, ACTIVE_SPRINT.sprint.sprintId)
    await page.goto(BACKLOG_URL)

    // Given. 완료 다이얼로그를 연다
    const dialog = await openCompleteSprintDialog(page)

    // When. 「스프린트 완료」 제출 (이관 대상은 기본값 = 백로그)
    await dialog.getByRole('button', { name: backlogLabels.completeSprint, exact: true }).click()

    // Then. 완료가 성사돼 다이얼로그가 닫힌다
    await expect(dialog).toBeHidden()

    // Then. 완료 요청은 **맨 마지막**에 딱 한 번 나갔다
    expect(calls.at(-1)).toBe(COMPLETE_CALL)
    expect(calls.filter((call) => call === COMPLETE_CALL)).toHaveLength(1)

    // Then. 그 앞은 전부 이관 DELETE 이고, 미완료 이슈 전건이 대상이었다
    const transfers = calls.slice(0, -1)
    expect(transfers.every((call) => call.startsWith('DELETE '))).toBe(true)
    expect(new Set(transfers)).toEqual(
      new Set(ACTIVE_SPRINT.issues.map((issue) => `DELETE ${issue.key}`)),
    )
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S18. 이관 부분 실패 (FR-UX-13 F15 · FR-6 · ADR C1)
  //
  // Given  alice 로그인
  //        LS_KEY_SPRINT_UNASSIGN_FAIL 에 **마지막 이슈 키만** 심어 이관 1건을 실패시킨다
  // When   완료 다이얼로그에서 「스프린트 완료」를 누른다
  // Then   `POST /complete` 가 **0건**이고 다이얼로그는 닫히지 않는다
  //
  // 왜 「일부만」 실패시키나.
  //   전건 실패 토글로는 「일부는 옮겼는데도 완료를 참았다」는 상태를 만들 수 없다. 위험한 것은
  //   전부 실패했을 때가 아니라 절반쯤 성공했을 때 그대로 밀어붙이는 경우다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S18 이관 부분 실패 — 완료 요청이 나가지 않고 다이얼로그가 남는다', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 요청 기록 + 부분 실패 토글 주입 (둘 다 goto 이전)
    const calls = recordSprintCompletionCalls(page, ACTIVE_SPRINT.sprint.sprintId)
    await page.addInitScript(
      ({ key, value }: { key: string; value: string }) => {
        window.localStorage.setItem(key, value)
      },
      { key: LS_KEY_SPRINT_UNASSIGN_FAIL, value: ACTIVE_ISSUE_LAST },
    )
    await page.goto(BACKLOG_URL)

    // Given. 완료 다이얼로그를 연다
    const dialog = await openCompleteSprintDialog(page)

    // When. 「스프린트 완료」 제출
    await dialog.getByRole('button', { name: backlogLabels.completeSprint, exact: true }).click()

    // Then. 부분 실패를 그대로 말한다 — 「스프린트는 완료되지 않았습니다」가 문구에 들어 있다
    await expect(dialog.getByRole('alert')).toContainText(
      backlogLabels.completeDialog.moveFailedAlert(ACTIVE_SPRINT.issues.length, 1),
    )

    // Then. 다이얼로그가 남는다 — 사용자가 재시도할 수 있다
    await expect(dialog).toBeVisible()

    // Then. 되돌릴 수 없는 완료 요청은 한 번도 나가지 않았다 (ADR C1)
    expect(calls.filter((call) => call === COMPLETE_CALL)).toHaveLength(0)

    // Then. 성공한 이관은 그대로 나갔다 — 「아무것도 안 했다」가 아니라 「참았다」임을 못박는다
    expect(calls.filter((call) => call.startsWith('DELETE '))).toHaveLength(
      ACTIVE_SPRINT.issues.length,
    )
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S19. 키보드만으로 카드 옮기기 (FR-UX-13 F15 · FR-8 · FR-15 · FR-16)
  //
  // Given  alice 로그인 + ACTIVE 스프린트의 마지막 카드
  // When   Tab 으로 카드에 포커스 → Space 로 집기 → ↓ 로 다음 섹션까지 → Space 로 놓기
  // Then   카드가 PLANNED 스프린트로 옮겨지고 출발 섹션에서 사라진다
  //
  // ★방향키 횟수를 하드코딩하지 않는다.
  //   순수 함수 단언으로는 「↓ 한 번이면 다음 섹션 첫 카드」지만, 실브라우저에서는 첫 ↓ 가
  //   스크롤 컨테이너의 자동 스크롤에 흡수돼 이동이 0인 경우가 있다
  //   (`@dnd-kit/core` KeyboardSensor 가 스크롤만 하고 조기 반환하는 분기 — T9 실측).
  //   그래서 **목표 상태(공지가 대상 스프린트를 읽는다)** 로 판정하고 그때까지 누른다.
  //
  // 관측 창구.
  //   키보드 드래그는 화면이 거의 안 바뀌므로 dnd-kit 의 스크린리더 공지 영역을 읽는다.
  //   그 문구 자체가 FR-9 의 산출물이라, 이 시나리오는 공지가 살아 있다는 증인도 겸한다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S19 키보드 DnD — Tab → Space → ↓ → Space 로 카드가 다음 스프린트로 옮겨진다', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    const sourceColumn = getColumnLocator(page, ACTIVE_SPRINT.sprint.name)
    const targetColumn = getColumnLocator(page, PLANNED_SPRINT.sprint.name)
    await expect(sourceColumn.getByText(ACTIVE_ISSUE_LAST)).toBeVisible()
    await expect(targetColumn.getByText(PLANNED_ISSUE_FIRST)).toBeVisible()

    // Given. 카드는 Tab 순서 안에 있다 — 키보드 사용자가 도달할 수 있다는 전제
    // When. Space 로 집고 대상 스프린트 위에 닿을 때까지 ↓ 를 누른다 (횟수는 화면이 정한다)
    const card = getCardLocator(page, ACTIVE_ISSUE_LAST)
    await pickUpAndMoveOverPlannedSprint(page, card)

    // When. Space 로 놓는다
    await page.keyboard.press('Space')

    // Then. 카드가 대상 스프린트로 옮겨졌다
    await expect(targetColumn.getByText(ACTIVE_ISSUE_LAST)).toBeVisible()

    // Then. 출발 섹션에서는 사라졌다 (이동이지 복제가 아니다)
    await expect(sourceColumn.getByText(ACTIVE_ISSUE_LAST)).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S20. 키보드 드래그 도중 Esc = 취소 (FR-UX-13 F15 · FR-16 · 스펙 E16)
  //
  // Given  S19 와 **완전히 같은 상태** — 카드를 집어 PLANNED 스프린트 위까지 옮겨 둔 상태
  // When   마지막 한 키만 다르게 누른다 (Space 대신 Esc)
  // Then   ① 공지가 「취소했습니다.」 ② 카드가 원래 자리 그대로 ③ 쓰기 요청 0건
  //
  // ★왜 이 시나리오가 필요한가.
  //   활성화 키 표(`backlogKeyboardCodes`)에서 `end` 와 `cancel` 이 겹치면 dnd-kit 이 `end` 를
  //   먼저 보고 즉시 return 해서 **Esc 가 드롭이 된다**(`core.cjs.development.js:1196-1203`).
  //   두 목록을 각각 단언하는 유닛 테스트로는 이 겹침을 못 잡는다. 실동작을 재는 곳은 여기다.
  //
  // ★부정 단언(「요청이 0건이다」)만 두면 공허하다 — 기록기가 애초에 아무것도 못 잡는 상태여도
  //   통과한다. 그래서 같은 테스트 안에서 **Space 로 놓으면 요청이 실제로 나간다**를 이어서 잰다.
  // ─────────────────────────────────────────────────────────────────────────
  test('S20 키보드 드래그 Esc — 취소 공지 + 쓰기 요청 0건 (짝: Space 는 요청을 낸다)', async ({ page }) => {
    // Given. alice 로그인 + 백로그 페이지 진입
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    const sourceColumn = getColumnLocator(page, ACTIVE_SPRINT.sprint.name)
    const targetColumn = getColumnLocator(page, PLANNED_SPRINT.sprint.name)
    await expect(sourceColumn.getByText(ACTIVE_ISSUE_LAST)).toBeVisible()
    await expect(targetColumn.getByText(PLANNED_ISSUE_FIRST)).toBeVisible()

    // Given. 카드를 집어 대상 스프린트 위까지 옮겨 둔다 (S19 와 같은 지점)
    const card = getCardLocator(page, ACTIVE_ISSUE_LAST)
    await pickUpAndMoveOverPlannedSprint(page, card)

    // 여기서부터 쓰기 요청을 기록한다 — 집고 이동하는 동안은 원래 요청이 없다
    const writes = recordBacklogWrites(page)

    // When. Esc 로 취소한다
    await page.keyboard.press('Escape')

    // Then. 스크린리더가 「취소했습니다.」를 읽는다 (안내 문구가 약속한 그대로)
    const liveRegion = getDragLiveRegion(page)
    await expect(liveRegion).toHaveText(backlogLabels.announce.cancelled)

    // Then. 카드가 원래 자리 그대로다 — 출발 섹션에 남고 대상 섹션에는 없다
    await expect(sourceColumn.getByText(ACTIVE_ISSUE_LAST)).toBeVisible()
    await expect(targetColumn.getByText(ACTIVE_ISSUE_LAST)).toHaveCount(0)

    // Then. 쓰기 요청 0건. **발사 기회를 실제로 준 뒤에** 센다 — 곧바로 세면
    //       「아직 안 나갔을 뿐」과 「영영 안 나간다」가 구별되지 않는다.
    await page.waitForTimeout(500)
    expect(writes).toEqual([])

    // ── 짝 단언 ── 같은 상태에서 마지막 키만 Space 로 바꾸면 요청이 실제로 나간다.
    //    이게 없으면 위 「0건」은 기록기가 죽어 있어도 통과하는 공허한 단언이다.
    await pickUpAndMoveOverPlannedSprint(page, card)
    await page.keyboard.press('Space')

    await expect(targetColumn.getByText(ACTIVE_ISSUE_LAST)).toBeVisible()
    await expect.poll(() => writes.length).toBeGreaterThan(0)
  })
})
