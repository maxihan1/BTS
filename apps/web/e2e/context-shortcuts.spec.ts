// FR-UX-10 F10 E2E — 컨텍스트 의존 단축키 (목록 항법 j/k/o/t + 사이드바 [)
//
// 시나리오
//   S1. j/k    — 커서 이동이 URL `?selected` 에 반영되고 행이 aria-current 로 강조된다
//   S3. o      — 커서 이슈를 전체화면 상세로 연다
//   S4. t      — 상세 페인 토글(닫힘 → 첫 행 열기 → 닫기)
//   S5. [      — 사이드바 접기/펼치기 + 새로고침 후 영속
//   S6. ?      — 도움말에 컨텍스트 그룹이 보이고 F11 미구현 키는 안 보인다
//   S7. g+j    — leader 대기 중에는 커서가 움직이지 않는다 (E6, ADR D-2)
//   E12.       — /issues 밖에서 j 는 죽고 [ 는 산다
//
// 설계 메모
//   - 커서는 새 상태가 아니라 기존 split 선택(`?selected`)이다(ADR D-3). 그래서
//     검증도 URL + aria-current 로 한다 — 새 셀렉터를 만들지 않는다.
//   - 단축키는 전부 클라이언트 라우팅이라 신규 MSW 핸들러가 필요 없다.
//   - 커서 단축키는 **와이드 전용**이다(issues.index.tsx `selectedKey={isWide ? …}`).
//     기본 뷰포트가 와이드라 별도 설정 없이 동작한다.

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** Header 검색 버튼 aria-label — RootLayout(단축키 리스너 등록) 마운트 완료 신호 */
const HEADER_SEARCH_ARIA_LABEL = '검색'

/** ShortcutsHelpDialog DialogTitle(비export) — keyboard-shortcuts.spec.ts 와 같은 하드코딩 선례 */
const HELP_DIALOG_TITLE = '키보드 단축키'

/** Sidebar 메인 메뉴 nav aria-label — 즉사 계약 §2 의 4종 중 하나 */
const SIDEBAR_NAV_LABEL = '메인 메뉴'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로 로그인하고 RootLayout(단축키 리스너) 마운트 완료까지 대기한다.
 * keyboard-shortcuts.spec.ts `loginAndWaitForRootReady` 미러.
 */
async function loginAndWaitForRootReady(page: Page): Promise<void> {
  await loginAsAlice(page)
  await expect(
    page.getByRole('button', { name: HEADER_SEARCH_ARIA_LABEL, exact: true }),
  ).toBeVisible()
}

/** `/issues` 로 이동하고 목록 테이블이 뜰 때까지 기다린다 */
async function gotoIssueList(page: Page): Promise<void> {
  await page.goto('/issues')
  await expect(page.getByRole('table')).toBeVisible()
}

/** 현재 커서(=split 선택) 행의 이슈 키를 URL 에서 읽는다 */
function selectedFromUrl(page: Page): string | null {
  return new URL(page.url()).searchParams.get('selected')
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-UX-10 F10 컨텍스트 단축키', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. j/k — 커서 이동
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 j/k → 커서가 URL ?selected 와 행 강조에 반영된다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    // Given. 커서 없음
    expect(selectedFromUrl(page)).toBeNull()

    // When. j — 첫 행을 잡는다
    await page.keyboard.press('j')
    await expect(page).toHaveURL(/selected=/)
    const first = selectedFromUrl(page)
    expect(first).not.toBeNull()

    // Then. 그 행이 aria-current 로 강조된다 (기존 split view 자산 재사용)
    await expect(page.locator('tr[aria-current="true"]')).toHaveCount(1)

    // When2. j 한 번 더 — 다른 행으로 이동
    await page.keyboard.press('j')
    await expect(page).not.toHaveURL(new RegExp(`selected=${first}$`))
    const second = selectedFromUrl(page)
    expect(second).not.toBe(first)

    // When3. k — 되돌아온다
    await page.keyboard.press('k')
    expect(selectedFromUrl(page)).toBe(first)
  })

  test('S1-b 첫 행에서 k 는 무동작이다 (감싸지 않는다, E3)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    await page.keyboard.press('j')
    const first = selectedFromUrl(page)

    await page.keyboard.press('k')

    expect(selectedFromUrl(page)).toBe(first)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. o — 전체화면 열기
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 o → 커서 이슈를 전체화면 상세로 연다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    await page.keyboard.press('j')
    const cursor = selectedFromUrl(page)
    expect(cursor).not.toBeNull()

    await page.keyboard.press('o')

    await expect(page).toHaveURL(new RegExp(`/issues/${cursor}$`))
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. t — 상세 페인 토글
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 t → 닫힌 페인을 첫 행으로 열고, 다시 누르면 닫는다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    // When. t — 첫 행을 잡아 연다
    await page.keyboard.press('t')
    await expect(page).toHaveURL(/selected=/)

    // When2. t — 닫는다
    await page.keyboard.press('t')
    await expect(page).not.toHaveURL(/selected=/)
    await expect(page.locator('tr[aria-current="true"]')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. [ — 사이드바 토글 + 영속
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 [ → 사이드바를 접고 새로고침해도 유지된다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    const sidebar = page.getByRole('navigation', { name: SIDEBAR_NAV_LABEL })
    const widthBefore = await sidebar.evaluate((el) => el.getBoundingClientRect().width)

    await page.keyboard.press('[')

    // 접히면 레일(64px)로 좁아진다 — 픽셀 값이 아니라 "줄었다"만 본다
    await expect
      .poll(async () => sidebar.evaluate((el) => el.getBoundingClientRect().width))
      .toBeLessThan(widthBefore)

    // localStorage 영속 — 새로고침 후에도 접힌 채다
    await page.reload()
    await expect(sidebar).toBeVisible()
    await expect
      .poll(async () => sidebar.evaluate((el) => el.getBoundingClientRect().width))
      .toBeLessThan(widthBefore)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6. ? — 도움말 그룹
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 ? → 도움말에 컨텍스트 그룹이 보이고 F11 미구현 키는 안 보인다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    await page.keyboard.press('Shift+Slash')
    const dialog = page.getByRole('dialog', { name: HELP_DIALOG_TITLE })
    await expect(dialog).toBeVisible()

    // 그룹 2개
    await expect(dialog.getByRole('heading', { name: '어디서나' })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: '이슈 목록에서' })).toBeVisible()

    // 컨텍스트 5종 설명
    await expect(dialog.getByText('다음 이슈로 이동', { exact: true })).toBeVisible()
    await expect(dialog.getByText('사이드바 접기/펼치기', { exact: true })).toBeVisible()

    // 기존 5종 문구 verbatim 무회귀 (C5-b)
    await expect(dialog.getByText('새 이슈 생성', { exact: true })).toBeVisible()

    // F11 미구현 키는 표기 금지 (C5, FR-UX-05 FR8 승계)
    await expect(dialog.getByText('담당자 지정', { exact: true })).toHaveCount(0)
    await expect(dialog.getByText('즐겨찾기 토글', { exact: true })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7. g+j — leader 대기 중 무동작 (E6)
  //
  // ★이 시나리오가 ADR D-2(단일 판별 파이프라인)의 존재 이유다. 리스너를 둘로 두면
  // 전역이 reset 을 내보내며 preventDefault 를 하지 않아 같은 이벤트가 컨텍스트
  // 리스너에도 도달하고, 커서가 함께 움직인다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 g 직후 j 는 커서를 움직이지 않는다 (E6)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    await page.keyboard.press('g')
    await page.keyboard.press('j')

    // 커서도 안 움직이고 leader 이동도 안 일어난다
    expect(selectedFromUrl(page)).toBeNull()
    await expect(page).toHaveURL(/\/issues(\?|$)/)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E12. 컨텍스트 밖
  // ───────────────────────────────────────────────────────────────────────────
  test('E12 /issues 밖에서는 j 가 죽고 [ 는 산다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await page.goto('/dashboards')
    await expect(page.getByRole('navigation', { name: SIDEBAR_NAV_LABEL })).toBeVisible()

    const urlBefore = page.url()
    await page.keyboard.press('j')
    expect(page.url()).toBe(urlBefore)

    // [ 는 app-shell 컨텍스트라 어디서나 산다
    const sidebar = page.getByRole('navigation', { name: SIDEBAR_NAV_LABEL })
    const widthBefore = await sidebar.evaluate((el) => el.getBoundingClientRect().width)
    await page.keyboard.press('[')
    await expect
      .poll(async () => sidebar.evaluate((el) => el.getBoundingClientRect().width))
      .toBeLessThan(widthBefore)
  })
})
