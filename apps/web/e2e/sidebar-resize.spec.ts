// Jira 패리티 J6 E2E — 사이드바 폭 드래그 조절 + 키보드 + 영속
//
// 시나리오
//   S1. 드래그    — 핸들을 오른쪽으로 끌면 사이드바가 넓어진다
//   S2. 영속      — 새로고침해도 맞춰 둔 폭이 유지된다
//   S3. 키보드    — 화살표로 조절되고 그 값도 영속된다
//   S4. 레일      — 접으면 핸들이 사라진다 (고정 폭에서 죽은 컨트롤을 만들지 않는다)
//   E1. 경계      — 최대 폭을 넘겨 끌어도 그 이상 넓어지지 않는다
//
// 설계 메모
//   - 폭은 **픽셀 값이 아니라 「늘었다/줄었다」**로 본다. 최소·최대는 상수에서 파생시켜
//     상수를 바꿔도 스펙이 함께 움직이게 한다 — 리터럴로 박으면 상수만 바뀌었을 때
//     경계 단언이 조용히 거짓이 된다.
//   - 드래그는 실 브라우저 포인터라 유닛에서 못 덮는다. 유닛은 ARIA·키보드·렌더 게이트를,
//     여기서는 포인터 왕복과 영속을 잰다.

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { navLabels } from '../src/i18n/nav-labels'
import { MIN_SIDEBAR_WIDTH, MAX_SIDEBAR_WIDTH } from '../src/hooks/use-sidebar-width'

/** Sidebar 랜드마크 — 폭 측정 대상 */
function sidebar(page: Page) {
  return page.getByRole('complementary')
}

/** 폭 조절 핸들 — 「사이드바 접기」 토글과 이름이 갈려 있어야 성립한다 */
function resizeHandle(page: Page) {
  return page.getByRole('separator', { name: navLabels.resizeSidebar, exact: true })
}

async function widthOf(page: Page): Promise<number> {
  return sidebar(page).evaluate((el) => el.getBoundingClientRect().width)
}

async function loginAndWaitForShell(page: Page): Promise<void> {
  await loginAsAlice(page)
  await expect(
    page.getByRole('searchbox', { name: navLabels.globalSearch, exact: true }),
  ).toBeVisible()
}

/** 핸들을 잡아 가로로 `dx` 만큼 끈다 */
async function dragHandle(page: Page, dx: number): Promise<void> {
  const handle = resizeHandle(page)
  await expect(handle).toBeVisible()
  const box = await handle.boundingBox()
  expect(box, '핸들의 boundingBox 를 얻지 못했다').not.toBeNull()
  if (box === null) return

  const startX = box.x + box.width / 2
  const startY = box.y + box.height / 2
  await page.mouse.move(startX, startY)
  await page.mouse.down()
  // steps 를 나눠 pointermove 를 여러 번 발생시킨다 — 한 번에 점프하면 드래그가 아니라
  // 순간이동이라 실제 사용과 다른 경로를 탄다.
  await page.mouse.move(startX + dx, startY, { steps: 12 })
  await page.mouse.up()
}

test.describe('사이드바 폭 조절 (J6)', () => {
  test('S1 핸들을 오른쪽으로 끌면 사이드바가 넓어진다', async ({ page }) => {
    await loginAndWaitForShell(page)

    const before = await widthOf(page)
    await dragHandle(page, 80)

    await expect.poll(async () => widthOf(page)).toBeGreaterThan(before)
  })

  test('S2 맞춰 둔 폭이 새로고침 후에도 유지된다', async ({ page }) => {
    await loginAndWaitForShell(page)

    const before = await widthOf(page)
    await dragHandle(page, 90)
    await expect.poll(async () => widthOf(page)).toBeGreaterThan(before)
    const after = await widthOf(page)

    await page.reload()
    await expect(sidebar(page)).toBeVisible()

    // 정확히 같은 px 을 요구하면 서브픽셀 반올림에 걸린다 — 「원래보다 넓다」로 본다.
    await expect.poll(async () => widthOf(page)).toBeGreaterThan(before)
    expect(Math.abs((await widthOf(page)) - after)).toBeLessThan(2)
  })

  test('S3 화살표 키로 조절되고 그 값도 영속된다', async ({ page }) => {
    await loginAndWaitForShell(page)

    const before = await widthOf(page)
    await resizeHandle(page).focus()
    await page.keyboard.press('ArrowRight')
    await page.keyboard.press('ArrowRight')

    await expect.poll(async () => widthOf(page)).toBeGreaterThan(before)

    await page.reload()
    await expect(sidebar(page)).toBeVisible()
    await expect.poll(async () => widthOf(page)).toBeGreaterThan(before)
  })

  test('S4 사이드바를 접으면 핸들이 사라진다', async ({ page }) => {
    await loginAndWaitForShell(page)
    await expect(resizeHandle(page)).toBeVisible()

    // `[` — 전역 사이드바 토글. 핸들에 포커스가 있어도 이 키는 살아 있어야 한다.
    await resizeHandle(page).focus()
    await page.keyboard.press('[')

    // 레일(64px)은 고정 폭이라 splitter 가 있으면 aria-valuenow 가 거짓말이 된다.
    await expect(resizeHandle(page)).toHaveCount(0)
  })

  test('E2 사이드바에 스크롤바가 떠도 핸들을 잡을 수 있다', async ({ page }) => {
    // ★핸들은 `absolute right-0 w-1.5` 라 `aside` 의 `overflow-y-auto` 스크롤바가 그려지는
    //   오른쪽 6px 위에 정확히 앉는다. 스크롤바가 뜬 상태에서 잡히는지 실측하지 않으면
    //   「평소엔 되는데 프로젝트가 많으면 안 되는」 결함이 남는다 (코드 리뷰 C6).
    await page.setViewportSize({ width: 1280, height: 260 })
    await loginAndWaitForShell(page)

    const scrolls = await page
      .getByRole('complementary')
      .evaluate((el) => el.scrollHeight > el.clientHeight)
    expect(scrolls, '뷰포트를 줄였는데도 사이드바에 스크롤이 생기지 않았다').toBe(true)

    const before = await widthOf(page)
    await dragHandle(page, 70)
    await expect.poll(async () => widthOf(page)).toBeGreaterThan(before)
  })

  test('E1 최대 폭을 넘겨 끌어도 그 이상 넓어지지 않는다', async ({ page }) => {
    await loginAndWaitForShell(page)

    // 최대치보다 확실히 큰 거리를 끈다 — 상수에서 파생시켜 상수가 바뀌면 함께 움직인다.
    await dragHandle(page, MAX_SIDEBAR_WIDTH + 200)

    await expect.poll(async () => widthOf(page)).toBeLessThanOrEqual(MAX_SIDEBAR_WIDTH + 1)
    await expect.poll(async () => widthOf(page)).toBeGreaterThanOrEqual(MIN_SIDEBAR_WIDTH)
  })
})
