// 이슈 목록 컬럼 폭 조절 E2E — 드래그 · 키보드 · 영속 · 줄임표 · 초기화
//
// ─────────────────────────────────────────────────────────────────────────────
// Jira 대조 (계약 §1 · T1 경량 경로)
// ─────────────────────────────────────────────────────────────────────────────
// Jira 대응. Jira Cloud 이슈(work item) 검색 리스트 뷰의 컬럼 조작 —
//   「drag the right border of a column to resize it」
//   support.atlassian.com/jira-software-cloud/docs/what-is-the-new-jira-issue-search-experience/
//   (2026-09-07 조회, Cloud)
// 채택. ① 헤더 오른쪽 경계 드래그로 폭 조절 ② 폭을 넘치는 내용은 줄임표로 자름
// 편차. ① 컬럼 순서 드래그 재배열은 범위 밖(Jira 는 지원) ② 폭은 서버가 아니라
//       기기별 localStorage 에 남는다 — BTS 에 「뷰 설정」 저장소가 아직 없다.
//
// ─────────────────────────────────────────────────────────────────────────────
// 왜 유닛이 아니라 E2E 인가
// ─────────────────────────────────────────────────────────────────────────────
// 폭 조절은 **실제 레이아웃이 있어야만** 참·거짓이 갈린다. jsdom 은 레이아웃을 계산하지
// 않아 `getBoundingClientRect()` 가 전부 0 이고, `table-fixed`·`<colgroup>`·`text-ellipsis`
// 세 장치가 서로를 필요로 하는 구조라 하나가 빠져도 유닛은 초록으로 남는다.
// 유닛(`IssueTable.test.tsx` T-35~T-41)은 **배선**을, 여기서는 **결과 폭**을 잰다.
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

/** ATLAS 이슈 목록 URL */
const ISSUES_URL = '/issues'

/** 요약 컬럼 기본 폭(px) — `issue-columns.ts` 의 `defaultWidth` 와 같은 값이어야 한다 */
const SUMMARY_DEFAULT_WIDTH = 280

/** 드래그로 좁힐 폭(px) */
const DRAG_DELTA = 120

/** 요약 컬럼 헤더 셀 */
function getSummaryHeader(page: Page): Locator {
  return page.getByRole('columnheader', { name: '요약' })
}

/** 요약 컬럼 폭 조절 손잡이 */
function getSummaryResizeHandle(page: Page): Locator {
  return page.getByRole('separator', { name: '요약 열 너비 조절' })
}

/** 로케이터의 실제 렌더 폭(px) */
async function widthOf(locator: Locator): Promise<number> {
  const box = await locator.boundingBox()
  if (box === null) throw new Error('요소가 화면에 없다 — 폭을 잴 수 없다')
  return box.width
}

/**
 * 손잡이를 가로로 끈다.
 *
 * `dragTo` 가 아니라 down→move→up 을 직접 쓴다 — `dragTo` 는 목표 **요소**의 중앙으로
 * 가므로 「몇 px 옮긴다」를 표현할 수 없다.
 */
async function dragHandleBy(page: Page, handle: Locator, deltaX: number): Promise<void> {
  const box = await handle.boundingBox()
  if (box === null) throw new Error('손잡이가 화면에 없다')
  const startX = box.x + box.width / 2
  const startY = box.y + box.height / 2

  await page.mouse.move(startX, startY)
  await page.mouse.down()
  // 중간 지점을 한 번 거친다 — pointermove 가 한 번도 안 나면 드래그로 인식되지 않는다
  await page.mouse.move(startX + deltaX / 2, startY)
  await page.mouse.move(startX + deltaX, startY)
  await page.mouse.up()
}

test.describe('이슈 목록 컬럼 폭 조절', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  test('R1 드래그 — 헤더 오른쪽 경계를 끌면 그 열만 좁아진다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    const summary = getSummaryHeader(page)
    await expect(summary).toBeVisible()
    expect(await widthOf(summary)).toBeCloseTo(SUMMARY_DEFAULT_WIDTH, -1)

    // 옆 열(상태)의 폭은 변하면 안 된다 — 한 열을 끌었는데 다른 열이 따라 줄면
    // 사용자는 자기가 무엇을 조절했는지 알 수 없다.
    const statusWidthBefore = await widthOf(page.getByRole('columnheader', { name: '상태' }))

    await dragHandleBy(page, getSummaryResizeHandle(page), -DRAG_DELTA)

    await expect
      .poll(() => widthOf(summary))
      .toBeCloseTo(SUMMARY_DEFAULT_WIDTH - DRAG_DELTA, -1)
    expect(await widthOf(page.getByRole('columnheader', { name: '상태' }))).toBeCloseTo(
      statusWidthBefore,
      -1,
    )
  })

  test('R2 줄임표 — 폭을 넘치는 요약은 잘리되 열을 밀어내지 않는다 (Jira 패리티)', async ({
    page,
  }) => {
    await page.goto(ISSUES_URL)
    // 긴 요약을 가진 행 — fixture ATLAS-2 가 일부러 긴 문자열이다
    const longSummary = page.getByTestId('issue-summary-ATLAS-2')
    await expect(longSummary).toBeVisible()

    await dragHandleBy(page, getSummaryResizeHandle(page), -DRAG_DELTA)

    // ★단정 둘이 짝이다. 「잘렸다」만 재면 열이 밀려나도 초록이고,
    //   「안 밀렸다」만 재면 글자가 겹쳐 그려져도 초록이다.
    const overflows = await longSummary.evaluate(
      (element) => element.scrollWidth > element.clientWidth,
    )
    expect(overflows).toBe(true)
    expect(await widthOf(longSummary)).toBeLessThanOrEqual(
      await widthOf(getSummaryHeader(page)),
    )
  })

  test('R3 영속 — 새로고침해도 조절한 폭이 남는다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await expect(getSummaryHeader(page)).toBeVisible()

    await dragHandleBy(page, getSummaryResizeHandle(page), -DRAG_DELTA)
    await expect
      .poll(() => widthOf(getSummaryHeader(page)))
      .toBeCloseTo(SUMMARY_DEFAULT_WIDTH - DRAG_DELTA, -1)

    await page.reload()

    await expect
      .poll(() => widthOf(getSummaryHeader(page)))
      .toBeCloseTo(SUMMARY_DEFAULT_WIDTH - DRAG_DELTA, -1)
  })

  test('R4 키보드 — 포인터 없이 화살표로도 조절된다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await expect(getSummaryHeader(page)).toBeVisible()

    await getSummaryResizeHandle(page).focus()
    // Shift+← 64px 한 번 + ← 16px 한 번 = 80px 좁아진다
    await page.keyboard.press('Shift+ArrowLeft')
    await page.keyboard.press('ArrowLeft')

    await expect.poll(() => widthOf(getSummaryHeader(page))).toBeCloseTo(SUMMARY_DEFAULT_WIDTH - 80, -1)
  })

  test('R5 정렬 무간섭 — 손잡이를 끌어도 정렬이 바뀌지 않는다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await expect(getSummaryHeader(page)).toBeVisible()

    await dragHandleBy(page, getSummaryResizeHandle(page), -DRAG_DELTA)

    // 손잡이는 정렬 헤더 **안**에 있다. 전파를 끊지 않으면 폭을 조절할 때마다 정렬이 걸린다.
    await expect(page).not.toHaveURL(/[?&]sort=/)
    await expect(getSummaryHeader(page)).toHaveAttribute('aria-sort', 'none')
  })

  test('R6 초기화 — "너비 초기화" 로 기본 폭으로 되돌린다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await expect(getSummaryHeader(page)).toBeVisible()
    await dragHandleBy(page, getSummaryResizeHandle(page), -DRAG_DELTA)
    await expect
      .poll(() => widthOf(getSummaryHeader(page)))
      .toBeCloseTo(SUMMARY_DEFAULT_WIDTH - DRAG_DELTA, -1)

    await page.getByRole('button', { name: '컬럼' }).click()
    await page.getByRole('menuitem', { name: '너비 초기화' }).click()

    await expect.poll(() => widthOf(getSummaryHeader(page))).toBeCloseTo(SUMMARY_DEFAULT_WIDTH, -1)
  })
})
