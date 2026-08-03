// FR-UX-09 F3 E2E — 보드/백로그/스프린트 이슈 생성 진입점
//
// 이 spec 이 갖는 판정은 **단위 테스트가 구조적으로 볼 수 없는 것**이다.
//   - 백로그/보드 단위 테스트는 `use-backlog` 를 mock 하므로 「만들었더니 칸에 나타난다」가
//     영원히 성립하지 않는다. 그 판정은 실제 MSW 를 쓰는 여기 몫이다.
//   - `BacklogBoard.test`·보드 라우트 테스트는 생성 모달을 **스텁**으로 둔다.
//     진짜 모달이 열리고 제출되는지도 여기서만 확인된다.
//
// 🛑 셀렉터는 `exact: true` 를 기본으로 쓴다. 부분 일치가 이 PR 이 막으려는 결함 양식이다
//    (상단바 `만들기` · 모달 제출 `이슈 생성` 과의 substring 충돌).
// 🛑 기존 `issue-create-dialog.spec.ts` 를 수정하지 않는다 — 수정이 필요해졌다면
//    그건 이름 충돌이 실재한다는 신호이고, 답은 e2e 수정이 아니라 이름 변경이다.
import { test, expect } from '@playwright/test'
import type { Page, Request } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

const BACKLOG_URL = '/projects/ATLAS/backlog'
const BOARD_URL = '/projects/ATLAS/board'

const BACKLOG_ENTRY = '백로그 칸에 이슈 추가'
const SPRINT_1_ENTRY = '스프린트 1 스프린트에 이슈 추가'
const BOARD_ENTRY = '이슈 추가'

const DIALOG_NAME = '새 이슈 만들기'
const SUMMARY_LABEL = '제목'
const SUBMIT_BUTTON = '이슈 생성'

const BACKLOG_COLUMN_NAME = '백로그'
const SPRINT_1_NAME = '스프린트 1'

/** 칸(region)을 이름으로 한정한다 — 칸 밖 요소와 섞이지 않게 (backlog.spec.ts 관례). */
function column(page: Page, name: string) {
  return page.getByRole('region', { name: new RegExp(`^${name} 칸`) })
}

/** 생성·배정 요청을 수집한다 — 「1회 제출 + 배정 1회」를 네트워크로 증명한다. */
function collectWrites(page: Page): { creates: Request[]; assigns: Request[] } {
  const creates: Request[] = []
  const assigns: Request[] = []
  page.on('request', (req) => {
    const url = new URL(req.url())
    if (req.method() !== 'POST') return
    if (url.pathname === '/api/v1/issues') creates.push(req)
    if (/^\/api\/v1\/sprints\/[^/]+\/issues$/.test(url.pathname)) assigns.push(req)
  })
  return { creates, assigns }
}

/** 열린 모달에서 제목만 채워 제출한다. */
async function submitWithSummary(page: Page, summary: string): Promise<void> {
  const dialog = page.getByRole('dialog', { name: DIALOG_NAME })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel(SUMMARY_LABEL, { exact: true }).fill(summary)
  await dialog.getByRole('button', { name: SUBMIT_BUTTON, exact: true }).click()
  await expect(dialog).toBeHidden()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 백로그 칸
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 백로그 칸 진입점 (FR-1)', () => {
  test('S1 백로그 칸에서 만들면 URL 이 안 바뀌고 그 칸에 나타난다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)

    const before = page.url()
    await page.getByRole('button', { name: BACKLOG_ENTRY, exact: true }).click()
    expect(page.url()).toBe(before)

    await submitWithSummary(page, '백로그에서 만든 이슈')

    await expect(
      column(page, BACKLOG_COLUMN_NAME).getByText('백로그에서 만든 이슈'),
    ).toBeVisible()
    expect(page.url()).toBe(before)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 스프린트 칸 (이 PR 의 핵심 계약)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 스프린트 칸 진입점 (FR-2/FR-4)', () => {
  test('S2 스프린트 칸에서 만들면 그 스프린트 칸에 나타난다 — 생성 1회 + 배정 1회', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BACKLOG_URL)
    const writes = collectWrites(page)

    await page.getByRole('button', { name: SPRINT_1_ENTRY, exact: true }).click()
    await submitWithSummary(page, '스프린트에서 만든 이슈')

    // ★백로그가 아니라 스프린트 칸이다 — 이 단언이 이 PR 의 존재 이유다.
    await expect(
      column(page, SPRINT_1_NAME).getByText('스프린트에서 만든 이슈'),
    ).toBeVisible()
    await expect(
      column(page, BACKLOG_COLUMN_NAME).getByText('스프린트에서 만든 이슈'),
    ).toBeHidden()

    // ★네트워크로도 고정한다 — 「응답 코드만 보는」 단언은 목이 멱등 201 을 주므로 언제나 통과한다.
    expect(writes.creates).toHaveLength(1)
    expect(writes.assigns).toHaveLength(1)
  })

  // ★S7(COMPLETED 스프린트 미렌더)은 여기 두지 않는다.
  //
  // 기본 시드에 `COMPLETED` 스프린트가 **0개**라(2026-08-03 실측 `backlog-fixtures.ts`),
  // 「완료된 칸마다 진입점이 없다」를 여기서 쓰면 **루프가 한 번도 돌지 않고 통과하는
  // 공허한 테스트**가 된다. 시드를 늘리는 대신, 상태를 직접 주입할 수 있는
  // `SprintColumn.test.tsx`(「★COMPLETED 스프린트에는 진입점이 없다」)가 그 판정을 갖는다 —
  // 그쪽은 completedSprint 픽스처를 실제로 렌더하므로 비-공허하다.
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 보드 헤더
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 보드 헤더 진입점 (FR-3/FR-12)', () => {
  test('S4 보드에서 만들면 제자리에 머물고 토스트로 갈 길을 남긴다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    const before = page.url()
    await page.getByRole('button', { name: BOARD_ENTRY, exact: true }).click()
    await submitWithSummary(page, '보드에서 만든 이슈')

    expect(page.url()).toBe(before)
    await expect(page.getByText('이슈를 만들었습니다')).toBeVisible()
    await expect(page.getByRole('button', { name: '보기', exact: true })).toBeVisible()
  })

  test('S4b 보드 진입점은 컬럼별이 아니라 화면에 1개다 (ADR D-3)', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto(BOARD_URL)

    await expect(page.getByRole('button', { name: BOARD_ENTRY, exact: true })).toHaveCount(1)
  })
})
