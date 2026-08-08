// FR-UX-14 F14 Task 5 E2E — 보드/백로그 카드 밀도 3요소(유형 아이콘·라벨 칩·추정 배지) 노출 검증
//
// 시나리오 개요.
//   S1. 보드 카드 3요소 — ATLAS-1(story, 라벨 2개, 추정 9000초)이 유형 아이콘·라벨 칩·추정을
//                        모두 보여준다
//   S2. 라벨 오버플로  — ATLAS-3(라벨 4개)이 앞 3개 + "+1" 오버플로 칩으로 접히고, 오버플로 칩의
//                        접근성 이름에 숨은 라벨이 담긴다
//   S3. 요소 부재      — ATLAS-4(라벨 0개·추정 null)는 유형 아이콘만 있고 라벨 칩·추정 배지는
//                        DOM 자체가 없다
//   S4. 백로그 동형 노출 — 백로그 카드(ATLAS-1)도 보드와 동일하게 3요소를 보여준다
//
// 픽스처 실측값(2026-08-07, board-fixtures.ts DEFAULT_BOARD / backlog-fixtures.ts DEFAULT_BACKLOG).
//   보드   ATLAS-1(TODO)        story, labels=[frontend,backend](2),               estimate=9000(2h 30m)
//   보드   ATLAS-4(TODO)        task,  labels=[](0),                               estimate=null
//   보드   ATLAS-2(IN PROGRESS) bug,   labels=[frontend](1),                       estimate=3600(1h 0m)
//   보드   ATLAS-3(DONE)        task,  labels=[frontend,backend,testing,documentation](4), estimate=null
//   백로그 ATLAS-1(backlog 칸)  story, labels=[frontend,documentation](2),          estimate=9000(2h 30m)
//   백로그 ATLAS-2(backlog 칸)  task,  labels=[](0),                               estimate=null
// 이 값들은 두 픽스처 파일에 이미 FR-UX-14 D7 전용 주석(Task 1이 심어 둔 것)으로 존재해 이 스펙을
// 위해 픽스처를 보강할 필요가 없었다 — 필요한 조합(라벨 0/2/4개, 추정 null/있음, 유형 3종
// story·task·bug)이 전량 이미 있다(unreachable-state-fixture-is-fake-green 재발 방지 확인 완료).
//
// 설계 결정.
//   - 이슈 타입 표시 이름은 issue-type-fixtures.ts와 동기화한다. story=스토리, task=작업, bug=버그.
//   - 카드 셀렉터는 board-reorder.spec.ts getCardLocator와 동일 관례 —
//     [aria-roledescription="draggable card"] + issueKey 텍스트 필터. 보드(BoardCard)·백로그
//     (BacklogCard) 모두 같은 aria-roledescription="draggable card" 문자열을 쓰므로 헬퍼 하나를
//     공유한다.
//   - 라벨 칩은 data-slot="badge"(ui/badge.tsx)로 식별한다. 추정 배지는 Badge가 아니라
//     aria-label="추정 …" span이라 접두 셀렉터(`[aria-label^="추정 "]`)로 구분해 부재를 확인한다.
//   - board-fixtures.ts는 직접 import 금지 — import.meta.env.MODE를 가드 없이 참조해 Playwright의
//     Node 로더에서 크래시한다(기존 board-*.spec.ts 전량의 확립된 제약). backlog-fixtures.ts는
//     가드가 있어 안전하지만, 이 스펙은 보드 쪽과 동일하게 인라인 상수로 통일한다.
//   - MSW serviceWorkers:'block' 금지(e2e-msw-serviceworker-block 교훈).
import type { Locator, Page } from '@playwright/test'
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — board-fixtures.ts DEFAULT_BOARD와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** board-fixtures.ts DEFAULT_BOARD.boardId 와 동기화 */
const DEFAULT_BOARD_ID = '10000000-0000-4000-8000-000000000001'

/** DEFAULT_BOARD URL — board-fixtures.ts DEFAULT_BOARD.projectKey('ATLAS')와 동기화 */
const BOARD_URL = `/projects/ATLAS/board?board=${DEFAULT_BOARD_ID}`

/** 백로그 페이지 URL — ATLAS 프로젝트 (backlog.spec.ts BACKLOG_URL과 동기화) */
const BACKLOG_URL = '/projects/ATLAS/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 카드 locator (board-reorder.spec.ts / backlog.spec.ts와 동일 패턴, 보드·백로그 공유)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * aria-roledescription="draggable card" 요소 중 issueKey 텍스트를 포함하는 카드 locator.
 * 보드(BoardCard)·백로그(BacklogCard) 모두 같은 aria-roledescription 문자열을 쓴다.
 */
function getCardLocator(page: Page, issueKey: string): Locator {
  return page
    .locator('[aria-roledescription="draggable card"]')
    .filter({ hasText: issueKey })
    .first()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-14 F14 카드 밀도 — 유형 아이콘·라벨 칩·추정 배지', () => {
  // ───────────────────────────────────────────────────────────────────────
  // Given  alice 로그인 + DEFAULT_BOARD 진입
  // When   ATLAS-1(story, 라벨 2개, 추정 9000초) 카드를 본다
  // Then   유형 아이콘("스토리") · 라벨 칩(frontend, backend) · 추정 배지("2h 30m")가 모두 보인다
  // ───────────────────────────────────────────────────────────────────────
  test('보드 카드에 유형 아이콘·라벨 칩·추정이 보인다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    const card = getCardLocator(page, 'ATLAS-1')
    await expect(card).toBeVisible()

    await expect(card.getByRole('img', { name: '스토리', exact: true })).toBeVisible()
    await expect(card.getByText('frontend', { exact: true })).toBeVisible()
    await expect(card.getByText('backend', { exact: true })).toBeVisible()
    await expect(card.getByText('2h 30m', { exact: true })).toBeVisible()
    await expect(card.locator('[aria-label="추정 2h 30m"]')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────
  // Given  alice 로그인 + DEFAULT_BOARD 진입
  // When   ATLAS-3(라벨 4개: frontend/backend/testing/documentation) 카드를 본다
  // Then   앞 3개(frontend/backend/testing)만 칩으로 보이고, documentation은 별도 칩으로 노출되지
  //        않으며 "+1" 오버플로 칩의 접근성 이름에 숨은 라벨(documentation)이 담긴다
  // ───────────────────────────────────────────────────────────────────────
  test('라벨 4개 이상이면 +N 으로 접히고 접근성 이름에 숨은 라벨이 담긴다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    const card = getCardLocator(page, 'ATLAS-3')
    await expect(card).toBeVisible()

    await expect(card.getByText('frontend', { exact: true })).toBeVisible()
    await expect(card.getByText('backend', { exact: true })).toBeVisible()
    await expect(card.getByText('testing', { exact: true })).toBeVisible()
    await expect(card.getByText('documentation', { exact: true })).toHaveCount(0)

    await expect(card.getByText('+1', { exact: true })).toBeVisible()
    await expect(card.locator('[aria-label="라벨 1개 더 — documentation"]')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────
  // Given  alice 로그인 + DEFAULT_BOARD 진입
  // When   ATLAS-4(라벨 0개·추정 null, type=task) 카드를 본다
  // Then   유형 아이콘("작업")은 있지만 라벨 칩·추정 배지는 DOM 자체가 없다
  // ───────────────────────────────────────────────────────────────────────
  test('라벨·추정이 없는 카드는 그 요소가 아예 없다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    const card = getCardLocator(page, 'ATLAS-4')
    await expect(card).toBeVisible()

    await expect(card.getByRole('img', { name: '작업', exact: true })).toBeVisible()
    await expect(card.locator('[data-slot="badge"]')).toHaveCount(0)
    await expect(card.locator('[aria-label^="추정 "]')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────
  // Given  alice 로그인 + 백로그 페이지 진입
  // When   ATLAS-1(story, 라벨 2개, 추정 9000초) 백로그 카드를 본다
  // Then   보드와 동일하게 유형 아이콘("스토리") · 라벨 칩(frontend, documentation) ·
  //        추정 배지("2h 30m")가 모두 보인다
  // ───────────────────────────────────────────────────────────────────────
  test('백로그 카드도 동일하게 노출한다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    const card = getCardLocator(page, 'ATLAS-1')
    await expect(card).toBeVisible()

    await expect(card.getByRole('img', { name: '스토리', exact: true })).toBeVisible()
    await expect(card.getByText('frontend', { exact: true })).toBeVisible()
    await expect(card.getByText('documentation', { exact: true })).toBeVisible()
    await expect(card.getByText('2h 30m', { exact: true })).toBeVisible()
    await expect(card.locator('[aria-label="추정 2h 30m"]')).toBeVisible()
  })
})
