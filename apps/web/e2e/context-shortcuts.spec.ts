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
import { PALETTE_HELP_ITEM, SHORTCUTS } from '../src/components/keyboard-shortcuts/shortcuts'
import { CONTEXT_SHORTCUTS } from '../src/components/keyboard-shortcuts/context-shortcuts'
import { navLabels } from '../src/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** ShortcutsHelpDialog DialogTitle(비export) — keyboard-shortcuts.spec.ts 와 같은 하드코딩 선례 */
const HELP_DIALOG_TITLE = '키보드 단축키'

/** Sidebar 메인 메뉴 nav aria-label — 즉사 계약 §2 의 4종 중 하나 */
const SIDEBAR_NAV_LABEL = '메인 메뉴'

/**
 * 도움말 모달에 떠도 되는 설명 전량 — **두 레지스트리에서 파생**한다.
 *
 * ★F10 시절 이 자리는 「미구현 키 금지 문구」 하드코딩 목록이었다. 그 목록은 F10 시점의
 * 스냅샷이라 두 가지로 썩었다 — F11 이 `담당자 지정` 을 정식 description 으로 만들자
 * 한 줄은 **거짓**이 됐고, 다른 한 줄(`즐겨찾기 토글`)은 레지스트리 실제 문구가
 * `즐겨찾기 켜기/끄기` 라 그 키가 화면에 떠도 **영원히 통과**하는 공허한 단언이었다.
 *
 * 지켜야 할 계약(FR-UX-05 FR8 — 비구현 단축키를 동작하는 것처럼 보여주지 않는다)은
 * 그대로 살리되, 스냅샷이 아니라 파생형으로 바꾼다. 정렬한 배열 등식이므로
 * **레지스트리에 없는 것은 뜨지 않고(FR8) 있는 것은 빠짐없이 한 번씩 뜬다**(누락·중복 회귀
 * 차단)를 한 단언이 함께 잰다. F12 이후 키가 늘어도 이 보호는 그대로 유지된다.
 *
 * 유닛(`ShortcutsHelpDialog.test.tsx`)은 같은 계약을 **집합 등식 + 개수 등식** 두 단언으로
 * 나눠 지킨다. 여기는 정렬 배열 등식 하나로 두 성질을 함께 재는 것뿐이고 기준은 같다.
 * `.`(팔레트 별칭)은 `명령 팔레트 열기` 로 팔레트 행에 접히므로 중복 제거가 곧 정본이다.
 */
const EXPECTED_HELP_DESCRIPTIONS: string[] = [
  ...new Set([
    ...SHORTCUTS.map((shortcut) => shortcut.description),
    PALETTE_HELP_ITEM.description,
    ...CONTEXT_SHORTCUTS.map((shortcut) => shortcut.description),
  ]),
].sort()

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로 로그인하고 RootLayout(단축키 리스너) 마운트 완료까지 대기한다.
 * keyboard-shortcuts.spec.ts `loginAndWaitForRootReady` 미러.
 *
 * 상단바 전역 검색 **입력창**(FR-UX-12 F13) 렌더가 신호다 — 이름은 `navLabels` 정본에서
 * 읽는다(하드코딩 금지). `exact: true` 필수 — `검색`(AQL 페이지 제출 버튼 전용 이름)이
 * `전역 검색` 의 substring 이라 느슨한 매칭은 두 컨트롤을 함께 잡는다.
 */
async function loginAndWaitForRootReady(page: Page): Promise<void> {
  await loginAsAlice(page)
  await expect(
    page.getByRole('searchbox', { name: navLabels.globalSearch, exact: true }),
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
    await expect.poll(() => selectedFromUrl(page)).toBe(first)
  })

  test('S1-b 첫 행에서 k 는 무동작이다 (감싸지 않는다, E3)', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    // ★`first` 를 읽기 전에 URL 갱신을 반드시 기다린다. 안 기다리면 `first` 가 null 이 될
    // 수 있고, 그러면 아래 단언이 `null === null` 로 **공허 통과**해 E3(감싸지 않음)
    // 가드가 영영 안 도는 상태로 굳는다.
    await page.keyboard.press('j')
    await expect(page).toHaveURL(/selected=/)
    const first = selectedFromUrl(page)
    expect(first).not.toBeNull()

    await page.keyboard.press('k')

    // negative 단언에도 settle barrier 를 준다 — `press` 는 이벤트 디스패치까지만
    // 기다리고 라우터 히스토리 갱신은 그 뒤에 온다.
    await expect.poll(() => selectedFromUrl(page)).toBe(first)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. o — 전체화면 열기
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 o → 커서 이슈를 전체화면 상세로 연다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    await page.keyboard.press('j')
    await expect(page).toHaveURL(/selected=/)
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
  test('S6 ? → 도움말에 컨텍스트 그룹이 보이고 표시 항목이 레지스트리와 정확히 일치한다', async ({
    page,
  }) => {
    await loginAndWaitForRootReady(page)
    await gotoIssueList(page)

    await page.keyboard.press('Shift+Slash')
    const dialog = page.getByRole('dialog', { name: HELP_DIALOG_TITLE })
    await expect(dialog).toBeVisible()

    // 그룹 3개 — F11 이 「이슈 상세에서」를 더했다
    await expect(dialog.getByRole('heading', { name: '어디서나' })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: '이슈 목록에서' })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: '이슈 상세에서' })).toBeVisible()

    // 컨텍스트 5종 설명
    await expect(dialog.getByText('다음 이슈로 이동', { exact: true })).toBeVisible()
    await expect(dialog.getByText('사이드바 접기/펼치기', { exact: true })).toBeVisible()

    // 기존 5종 문구 verbatim 무회귀 (C5-b)
    await expect(dialog.getByText('새 이슈 생성', { exact: true })).toBeVisible()

    // ★FR-UX-05 FR8 — 표시 항목 집합이 레지스트리와 정확히 같다(양방향).
    // 하드코딩 금지 문구 목록을 파생형으로 교체한 자리다([EXPECTED_HELP_DESCRIPTIONS] 주석 참조).
    await expect
      .poll(async () =>
        (await dialog.locator('dt').allTextContents()).map((text) => text.trim()).sort(),
      )
      .toEqual(EXPECTED_HELP_DESCRIPTIONS)
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

    // ★negative 단언에 settle barrier 를 세운다.
    //
    // `press` 는 이벤트 디스패치까지만 기다린다. 회귀(=`g` 직후 `j` 가 커서를 움직임)가
    // 생기면 그 URL 갱신은 이 줄보다 **늦게** 도착하므로, 동기 읽기는 갱신 전 URL 을 보고
    // 통과해버린다. 그러면 ADR D-2 의 대표 가드가 아무것도 막지 않는다.
    //
    // 배리어 방법 — 뒤이어 `t`(열림이 URL 로 관측되는 키)를 눌러 **한 왕복이 끝난 것을
    // 확인**하고, 그 시점에 커서가 첫 행인지 본다. 회귀가 있었다면 `g`+`j` 가 이미 첫 행을
    // 잡았을 것이고 `t` 는 그것을 닫아 `selected` 가 사라진다 — 즉 두 경우가 갈린다.
    await page.keyboard.press('t')
    await expect(page).toHaveURL(/selected=/)

    // `t` 가 연 것은 **첫 행**이어야 한다. `g`+`j` 가 새어 커서를 이미 옮겼다면
    // `t` 는 그 커서를 닫았을 것이므로 위 배리어에서 이미 실패한다.
    const afterToggle = selectedFromUrl(page)
    expect(afterToggle).not.toBeNull()

    // ★열 **위치**(`td:nth(1)`)로 잡지 않는다. 컬럼이 하나 늘거나 순서가 바뀌면 조용히
    //   엉뚱한 셀을 읽는다 — 유형 컬럼이 앞에 붙으면서 실제로 그렇게 깨졌다(2026-09-07).
    //   키 셀 링크(`aria-label` + `/issues/` href)는 위치와 무관한 계약이다.
    const firstRowKey = await page
      .locator('table tbody tr')
      .first()
      .locator('a[aria-label][href^="/issues/"]')
      .innerText()
    expect(afterToggle).toBe(firstRowKey.trim())

    // leader 네비게이션(`g i` 등)도 일어나지 않았다 — 목록 라우트를 벗어나지 않았다.
    // 정규식을 `/issues` 로 끝나거나 `?` 가 붙는 형태로 한정한다(다른 라우트 배제).
    expect(new URL(page.url()).pathname).toBe('/issues')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E12. 컨텍스트 밖
  // ───────────────────────────────────────────────────────────────────────────
  test('E12 /issues 밖에서는 j 가 죽고 [ 는 산다', async ({ page }) => {
    await loginAndWaitForRootReady(page)
    await page.goto('/dashboards')
    await expect(page.getByRole('navigation', { name: SIDEBAR_NAV_LABEL })).toBeVisible()

    const urlBefore = page.url()
    const sidebar = page.getByRole('navigation', { name: SIDEBAR_NAV_LABEL })
    const widthBefore = await sidebar.evaluate((el) => el.getBoundingClientRect().width)

    await page.keyboard.press('j')

    // ★`j` 가 죽었다는 negative 를 동기로 읽으면, 회귀로 생긴 네비게이션이 이 줄보다
    // 늦게 도착해 통과해버린다. `[`(여기서 살아 있어야 하는 키)를 눌러 **한 왕복이 끝난
    // 것을 확인**한 뒤에 URL 불변을 단언한다 — negative 를 완료된 라운드트립 뒤로 민다.
    await page.keyboard.press('[')
    await expect
      .poll(async () => sidebar.evaluate((el) => el.getBoundingClientRect().width))
      .toBeLessThan(widthBefore)

    // 사이드바 왕복이 끝난 지금도 경로·검색 파라미터가 그대로여야 한다 (j 무동작).
    expect(page.url()).toBe(urlBefore)
  })
})
