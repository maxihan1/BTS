// FR-UX-09 F2 E2E — 이슈 생성 모달 (진입 경로별 종료 동작 · 1회 제출 · 딥링크 무회귀)
//
// 이 spec 의 핵심은 **진입 경로별로 끝난 뒤 행동이 다르다**는 계약이다 (FR-16, design 리뷰 D8).
//   S2a 딥링크(/issues/new) → 상세로 이동
//   S2b 상단바(제자리) → URL 불변 + 토스트
// 두 경로에 같은 단언을 쓰면 한쪽이 반드시 깨진다 — plan 리뷰가 잡은 조합 효과다.
import { test, expect } from '@playwright/test'
import type { Page, Request } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

const DIALOG_NAME = '새 이슈 만들기'

/**
 * 모달 안으로 범위를 한정한다.
 * 상단바 `ProjectSwitcher` 도 「프로젝트」를 이름에 갖고 있어 페이지 전역 셀렉터는
 * strict mode 로 충돌한다 (learnings 2026-05-31 — 같은 이름 요소가 늘면 전역 셀렉터가 깨진다).
 */
const CREATE_BUTTON = '만들기'
const SUBMIT_BUTTON = '이슈 생성'

/** 이슈 생성/수정 관련 요청을 수집한다 — 「1회 제출」을 네트워크로 증명하기 위한 장치다. */
function collectIssueWrites(page: Page): { posts: Request[]; patches: Request[] } {
  const posts: Request[] = []
  const patches: Request[] = []
  page.on('request', (req) => {
    const url = new URL(req.url())
    if (!url.pathname.startsWith('/api/v1/issues')) return
    if (req.method() === 'POST' && url.pathname === '/api/v1/issues') posts.push(req)
    if (req.method() === 'PATCH') patches.push(req)
  })
  return { posts, patches }
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 상단바에서 열면 URL 이 바뀌지 않는다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 상단바 진입 (FR-12)', () => {
  test('S1 만들기 버튼은 URL 을 바꾸지 않고 제자리에서 모달을 연다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/issues')
    const before = page.url()

    await page.getByRole('button', { name: CREATE_BUTTON }).click()

    await expect(page.getByRole('dialog', { name: DIALOG_NAME })).toBeVisible()
    expect(page.url()).toBe(before)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2a — 딥링크 경로: 1회 제출 후 상세로 이동
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2a 딥링크 경로 — 1회 제출 후 상세 이동 (FR-11/FR-16)', () => {
  test('S2a 필드를 채워 제출하면 POST 1회 · PATCH 0회로 이슈가 만들어지고 상세로 간다', async ({ page }) => {
    await loginAsAlice(page)
    const writes = collectIssueWrites(page)

    await page.goto('/issues/new')
    await expect(page.getByRole('dialog', { name: DIALOG_NAME })).toBeVisible()

    const dialog = page.getByRole('dialog', { name: DIALOG_NAME })
    await dialog.getByLabel('프로젝트').selectOption('ATLAS')
    await dialog.getByLabel('제목', { exact: true }).fill('S2a 모달로 만든 이슈')
    await dialog.getByLabel('설명').fill('본문입니다')
    await dialog.getByLabel('우선순위 선택').selectOption('1')
    await dialog.getByPlaceholder('라벨 추가').fill('backend')
    await dialog.getByPlaceholder('라벨 추가').press('Enter')

    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()

    // 상세로 이동한다 — 딥링크로 온 사용자는 방금 만든 이슈를 보러 온 것이다
    await page.waitForURL(/\/issues\/[A-Z]+-\d+$/)
    await expect(
      page.getByRole('heading', { level: 1, name: 'S2a 모달로 만든 이슈' }),
    ).toBeVisible()

    // ★1회 제출 — 생성 후 PATCH 를 이어 붙이지 않는다 (#328 이 연 계약의 목적)
    expect(writes.posts).toHaveLength(1)
    expect(writes.patches).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2b — 상단바 경로: 제자리 유지 + 토스트
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2b 상단바 경로 — 제자리 유지 + 토스트 (FR-16)', () => {
  test('S2b 제자리에서 만들면 화면이 그대로이고 토스트가 뜬다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/issues')
    const before = page.url()

    await page.getByRole('button', { name: CREATE_BUTTON }).click()
    await expect(page.getByRole('dialog', { name: DIALOG_NAME })).toBeVisible()

    const dialog = page.getByRole('dialog', { name: DIALOG_NAME })
    await dialog.getByLabel('프로젝트').selectOption('ATLAS')
    await dialog.getByLabel('제목', { exact: true }).fill('S2b 제자리에서 만든 이슈')
    await page.getByRole('button', { name: SUBMIT_BUTTON }).click()

    // 모달은 닫히고 화면은 그대로 — 보드/목록을 보던 맥락이 끊기지 않는다
    await expect(page.getByRole('dialog', { name: DIALOG_NAME })).toHaveCount(0)
    expect(page.url()).toBe(before)
    await expect(page.getByText('이슈를 만들었습니다')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 딥링크 무회귀
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 딥링크 계약 (FR-11)', () => {
  test('S5 /issues/new 로 직접 들어와 모달을 닫으면 /issues 로 간다', async ({ page }) => {
    await loginAsAlice(page)

    await page.goto('/issues/new')
    await expect(page.getByRole('dialog', { name: DIALOG_NAME })).toBeVisible()

    // ★`exact: true` 필수 — 본문 리치 에디터 툴바의 **「취소선」** 버튼이 부분 일치로 함께
    //   걸려 strict 위반이 된다(#468 이 생성 다이얼로그에 에디터를 넣은 뒤 생긴 충돌).
    await page.getByRole('button', { name: '취소', exact: true }).click()

    await page.waitForURL(/\/issues$/)
  })
})
