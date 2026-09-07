// 이슈 상세 독립 스크롤 E2E — 본문/메타가 따로 흐르고 헤더는 제자리에 남는가 (Jira 패리티 J25~J27)
import { test, expect, type Locator, type Page } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * 여기서만 잴 수 있는 것.
 *
 * 유닛(`src/routes/issues.$key.test.tsx`)은 **구조**를 본다 — 세 영역이 갈라져 있고 각자
 * 무엇을 선언했는가. 그것으로는 「실제로 따로 스크롤되는가」를 알 수 없다. jsdom 에 레이아웃
 * 엔진이 없어 `scrollHeight`·`clientHeight` 가 전부 0 이기 때문이다.
 *
 * ★그 간극이 이 저장소에서 실제로 값을 물린 적이 있다 — #455 의 「잘린 메타패널」은 유닛이
 * 전부 초록인 채 눈확인에서만 드러났다. 그래서 이 spec 은 클래스 이름을 다시 세지 않고
 * **픽셀과 스크롤 오프셋**만 본다. 구조 판정과 겹치지 않는 자리다.
 *
 * 뷰포트를 낮게 잡는 이유. `lg:` 2단 분기를 살리려면 폭은 1024 이상이어야 하고, 두 열이
 * 실제로 넘치게 하려면 높이는 낮아야 한다. 픽스처 본문을 길게 만드는 대신 창을 줄인다 —
 * 넘침은 콘텐츠 길이가 아니라 **높이 관계**의 문제이므로 그쪽이 정직하다.
 */
const SPLIT_VIEWPORT = { width: 1280, height: 460 } as const

/** 한 요소의 세로 스크롤 오프셋. */
async function scrollTopOf(locator: Locator): Promise<number> {
  return locator.evaluate((el) => el.scrollTop)
}

/** 넘치는가 — 스크롤할 여지가 실제로 있는가. */
async function overflowsVertically(locator: Locator): Promise<boolean> {
  return locator.evaluate((el) => el.scrollHeight > el.clientHeight + 1)
}

/** 사용자가 이 상자를 굴릴 수 있는가 — 브라우저가 실제로 계산한 `overflow-y` 값. */
async function scrollableDeclarationOf(locator: Locator): Promise<string> {
  return locator.evaluate((el) => getComputedStyle(el).overflowY)
}

/** 문서(창) 자체의 스크롤 오프셋. 0 이어야 바깥으로 새지 않은 것이다. */
async function documentScrollTop(page: Page): Promise<number> {
  return page.evaluate(() => document.scrollingElement?.scrollTop ?? 0)
}

test.describe('이슈 상세 — 본문/메타 독립 스크롤 + 고정 헤더 (J25~J27)', () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize(SPLIT_VIEWPORT)
    await loginAsAlice(page)
    await page.goto('/issues/ATLAS-1')
    await expect(page.getByTestId('issue-detail-header')).toBeVisible()
  })

  /**
   * Given   본문 영역이 뷰포트보다 길어 스크롤 여지가 있다
   * When    본문 영역만 아래로 굴린다
   * Then    본문만 내려가고 메타·문서는 0 에 남으며 헤더는 화면상 같은 자리에 있다
   */
  test('S1 본문을 굴려도 메타·문서·헤더는 움직이지 않는다', async ({ page }) => {
    const header = page.getByTestId('issue-detail-header')
    const body = page.getByTestId('issue-detail-body-scroll')
    const meta = page.getByTestId('issue-detail-meta-scroll')

    // Given. 넘치지 않으면 이 테스트는 아무것도 재지 못한다 — 공허한 통과를 먼저 막는다.
    expect(await overflowsVertically(body)).toBe(true)

    const headerBefore = await header.boundingBox()
    expect(headerBefore).not.toBeNull()

    // When. 본문 영역만 굴린다.
    await body.evaluate((el) => { el.scrollTop = 240 })

    // Then. 본문은 실제로 내려갔다.
    expect(await scrollTopOf(body)).toBeGreaterThan(0)
    // Then. 메타는 따라오지 않는다 — 이것이 「두 열이 각자 스크롤한다」의 관측 가능한 형태다.
    expect(await scrollTopOf(meta)).toBe(0)
    // Then. 문서도 움직이지 않는다 — 움직였다면 넘침이 조상으로 샌 것이다.
    expect(await documentScrollTop(page)).toBe(0)
    // Then. 헤더는 화면상 같은 자리다 — Maxi 지적 「서머리가 고정」의 직접 판정.
    const headerAfter = await header.boundingBox()
    expect(headerAfter?.y).toBeCloseTo(headerBefore?.y ?? -1, 0)
  })

  /**
   * Given   메타 영역이 뷰포트보다 길어 스크롤 여지가 있다
   * When    메타 영역만 아래로 굴린다
   * Then    메타만 내려가고 본문은 제자리다 (반대 방향도 성립해야 「독립」이다)
   */
  test('S2 메타를 굴려도 본문은 움직이지 않는다', async ({ page }) => {
    const body = page.getByTestId('issue-detail-body-scroll')
    const meta = page.getByTestId('issue-detail-meta-scroll')

    expect(await overflowsVertically(meta)).toBe(true)

    await meta.evaluate((el) => { el.scrollTop = 240 })

    expect(await scrollTopOf(meta)).toBeGreaterThan(0)
    expect(await scrollTopOf(body)).toBe(0)
    expect(await documentScrollTop(page)).toBe(0)
  })

  /**
   * Given   상세 화면이 열려 있다
   * When    아무것도 하지 않는다
   * Then    제목이 헤더 안에 있고, 헤더는 두 스크롤 영역 어느 쪽에도 들어 있지 않다
   *
   * ★유닛과 겹쳐 보이지만 재는 것이 다르다 — 여기서는 **실제 브라우저가 만든 박스**로
   *   헤더가 본문 영역 위(더 작은 y)에 있고 세로로 겹치지 않는지를 본다. 겹치면
   *   `scrollIntoView` 로 데려간 댓글이 헤더 밑에 깔린다(#459 가 맞춘 위치가 어긋난다).
   */
  test('S3 헤더가 본문 영역과 세로로 겹치지 않는다', async ({ page }) => {
    const headerBox = await page.getByTestId('issue-detail-header').boundingBox()
    const bodyBox = await page.getByTestId('issue-detail-body-scroll').boundingBox()
    expect(headerBox).not.toBeNull()
    expect(bodyBox).not.toBeNull()
    if (headerBox === null || bodyBox === null) return

    // 헤더의 아래 끝이 본문의 위 끝보다 아래로 내려오면 겹친 것이다(1px 오차 허용).
    expect(headerBox.y + headerBox.height).toBeLessThanOrEqual(bodyBox.y + 1)
    // 제목은 헤더 쪽 박스 안에 있다.
    const headingBox = await page.getByRole('heading', { level: 1 }).boundingBox()
    expect(headingBox).not.toBeNull()
    if (headingBox === null) return
    expect(headingBox.y).toBeGreaterThanOrEqual(headerBox.y - 1)
    expect(headingBox.y).toBeLessThan(bodyBox.y)
  })
})

/**
 * 표시 방식 3종이 **같은 규칙**을 지키는지 본다 (J1 — 전체화면 · 모달 · 사이드패널).
 *
 * ★위 describe 는 전체화면만 잰다. 모달·사이드패널은 껍데기가 자기 높이 경계를 스스로 주므로
 * 그 껍데기가 `overflow-y-auto` 로 남아 있으면 **안쪽 두 열이 함께 밀려** 분리가 성립하지
 * 않는다 — 고친 곳이 셋인데 재는 곳이 하나면 나머지 둘은 조용히 되돌아갈 수 있다.
 */
test.describe('이슈 상세 — 모달·사이드패널도 같은 분리를 지킨다 (J1 × J26)', () => {
  /** 목록에서 상세를 연다. 진입은 언제나 모달이다(표시 방식 기본값 · J1). */
  async function openModal(page: Page): Promise<Locator> {
    await page.goto('/issues')
    await page.locator('a[href^="/issues/ATLAS-"]').first().click()
    const modal = page.getByRole('dialog', { name: /^이슈 상세 ATLAS-/ })
    await expect(modal).toBeVisible()
    return modal
  }

  test.beforeEach(async ({ page }) => {
    await page.setViewportSize(SPLIT_VIEWPORT)
    await loginAsAlice(page)
  })

  test('S4 모달 껍데기는 스크롤하지 않고 안쪽 두 열이 각자 스크롤한다', async ({ page }) => {
    const modal = await openModal(page)

    const body = modal.getByTestId('issue-detail-body-scroll')
    const meta = modal.getByTestId('issue-detail-meta-scroll')
    await expect(body).toBeVisible()

    // Given. 안쪽이 넘친다 — 넘치지 않으면 아무것도 재지 못한다.
    expect(await overflowsVertically(body)).toBe(true)
    // Then. 껍데기는 사용자가 굴릴 수 있는 상자가 아니다.
    //   ★`scrollHeight > clientHeight` 로 재지 않는다 — `overflow: hidden` 상자의
    //     `scrollHeight` 는 **잘려서 안 보이는 자손까지** 세므로 이 판정에서는 거짓 신호다.
    //     사용자가 굴릴 수 있는가는 `overflow-y` 가 정한다.
    expect(await scrollableDeclarationOf(modal)).toBe('hidden')

    await body.evaluate((el) => { el.scrollTop = 200 })
    expect(await scrollTopOf(body)).toBeGreaterThan(0)
    expect(await scrollTopOf(meta)).toBe(0)
    expect(await scrollTopOf(modal)).toBe(0)
  })

  test('S5 사이드패널 껍데기도 스크롤하지 않는다', async ({ page }) => {
    const modal = await openModal(page)

    // 사이드패널로 가는 유일한 조작이 모달의 `⋯` 다(#455).
    await modal
      .getByRole('button', { name: i18nLabels.issueDetail.presentationMenuAriaLabel, exact: true })
      .click()
    await page
      .getByRole('menuitem', { name: i18nLabels.issueDetail.openInSidePanelItem, exact: true })
      .click()

    // ★`{ name: /상세/ }` 로 잡으면 안 된다 — 상세 본문 `<section aria-label="이슈 상세">` 와
    //   둘이 걸려 strict 위반이다. 정본 문자열로 정확히 지목한다.
    const panel = page.getByRole('region', {
      name: i18nLabels.issueDetail.sidePanelLabel,
      exact: true,
    })
    await expect(panel).toBeVisible()

    const body = panel.getByTestId('issue-detail-body-scroll')
    const meta = panel.getByTestId('issue-detail-meta-scroll')
    await expect(body).toBeVisible()

    expect(await overflowsVertically(body)).toBe(true)
    expect(await scrollableDeclarationOf(panel)).toBe('hidden')

    await body.evaluate((el) => { el.scrollTop = 200 })
    expect(await scrollTopOf(body)).toBeGreaterThan(0)
    expect(await scrollTopOf(meta)).toBe(0)
    expect(await scrollTopOf(panel)).toBe(0)
  })
})
