// Jira 패리티 J1 E2E — 상세 표시 방식(모달 ↔ 사이드패널) 토글과 세션 유지
//
// 시나리오 개요.
//   P1  기본은 모달 — 목록 행 클릭 시 dialog 가 뜨고 URL 은 그대로다
//   P2  모달 `⋯` → 「사이드바로 열기」 → 그 자리에서 패널로 바뀐다(즉시 전환)
//   P3  선호가 세션에 남는다 — 새로고침 뒤 행 클릭이 곧바로 패널로 열린다
//   P4  패널 `⋯` → 「모달로 열기」 → 모달로 되돌아온다(왕복)
//
// 근거. Atlassian 공식 글(조회 2026-09-04) —
//   "Your choice to view your work in the modal or the Preview Panel will persist across Jira
//    views within the same session" · "revert to sidebar by opening an issue in the modal,
//    clicking the '…' and selecting 'Open issues in sidebar'"
//   https://community.atlassian.com/forums/Jira-articles/Preview-Panels-will-soon-replace-the-detail-view-in-Jira-s/ba-p/3196999
//
// 설계 결정.
//   - 문구는 `issueDetailStrings` 를 그대로 import 한다. 하드코딩하면 i18n 이 바뀌었을 때
//     이 spec 만 조용히 남는다(loginStrings 관례 미러).
//   - 메뉴는 **마우스 클릭**으로 연다. Radix roving-focus 가 `keyboard.press` 의 0ms
//     keydown→keyup 간격을 흘려 선택이 빠지는 것이 이 저장소에서 이미 관측됐다.
//   - `⋯` 는 모달 안과 패널 안 양쪽에 있지만 **동시에 하나만** 떠 있어 이름이 겹치지 않는다.
//     그 배타 자체는 `IssueDetailPresentation.test` 가 유닛에서 지킨다.
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueAtlas1Fixture } from '../src/mocks/issue-fixtures'
import { issueDetailStrings } from '../src/i18n/ko'

/** ATLAS 이슈 목록 URL */
const ISSUES_URL = '/issues'

/** 대상 이슈 키 — 기본 4건 시드 중 하나 */
const TARGET_KEY = issueAtlas1Fixture.key

/** 대상 이슈 요약 — 상세 제목(h2) 로 잡는다 */
const TARGET_SUMMARY = issueAtlas1Fixture.summary

/** split 분기 기준(min-width: 1024px)을 넘는 와이드 뷰포트 */
const WIDE_VIEWPORT = { width: 1280, height: 900 }

/** 상세 모달 — 이름에 이슈 키가 들어간다(e2e 224곳의 dialog 와 겹치지 않게). */
function getModal(page: Page) {
  return page.getByRole('dialog', { name: `이슈 상세 ${TARGET_KEY}` })
}

/**
 * 상세 사이드패널 — complementary 가 아니라 region 이다(사이드바의 유일성을 지킨다).
 *
 * ★role 이 아니라 **CSS 로케이터**로 잡는다. Radix Dialog 는 열릴 때 형제(= 셸 루트)에
 * `aria-hidden` 을 걸고, `getByRole` 은 접근성 트리에서 숨겨진 요소를 매치하지 않는다 —
 * 그래서 모달이 떠 있는 상태의 「패널 없음」 단언이 배타가 깨져도 통과한다. 유닛에서 뮤테이션
 * 프로브가 잡아낸 바로 그 공허를 여기서도 되풀이하지 않는다(`IssueDetailPresentation.test`).
 * 여기서 재려는 것은 「눈에 보이나」가 아니라 「DOM 에 아예 없나」다.
 */
function getSidePanel(page: Page) {
  return page.locator(`section[aria-label="${issueDetailStrings.sidePanelLabel}"]`)
}

/**
 * 오른쪽에 붙은 상세의 제목(h2).
 *
 * ★목록 화면에서 사이드바 선호는 **기존 split view 페인**이 맡고, 그 밖의 화면은 전역
 * `IssueDetailSidePanel` 이 맡는다. 껍데기는 둘이지만 안쪽은 같은 `variant='pane'` 이라
 * 사용자 눈에는 같은 것이고, 제목 h2 하나로 양쪽을 함께 잡을 수 있다. 목록의 split view 를
 * 전역 패널로 갈아치우지 않은 이유는 `selected` URL 에 딥링크·커서 `j`/`k`·뒤로가기가
 * 걸려 있어서다.
 */
function getSideDetailHeading(page: Page) {
  return page.getByRole('heading', { level: 2, name: TARGET_SUMMARY })
}

/** 표시 방식 `⋯` 트리거 — 모달/패널 중 떠 있는 쪽에 하나만 있다. */
function getPresentationMenu(page: Page) {
  return page.getByRole('button', { name: issueDetailStrings.presentationMenuAriaLabel })
}

/** 목록에서 대상 이슈 행을 클릭할 수 있을 때까지 기다린 뒤 클릭한다. */
async function openTargetFromList(page: Page): Promise<void> {
  const row = page.getByTestId(`issue-summary-${TARGET_KEY}`)
  await expect(row).toBeVisible()
  await row.click()
}

/** `⋯` 를 열고 주어진 항목을 고른다. */
async function choosePresentation(page: Page, itemName: string): Promise<void> {
  await getPresentationMenu(page).click()
  await page.getByRole('menuitem', { name: itemName }).click()
}

test.describe('Jira 패리티 J1 — 상세 표시 방식 토글', () => {
  test.use({ viewport: WIDE_VIEWPORT })

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // P1. 기본은 모달
  //
  // Given  alice 로그인 + /issues 진입(와이드, 표시 방식 미설정)
  // When   ATLAS-1 행을 클릭하면
  // Then   상세가 모달로 뜨고, URL 에는 ?selected= 가 붙지 않는다.
  // ───────────────────────────────────────────────────────────────────────────
  test('P1 표시 방식 미설정에서 행 클릭 → 모달이 뜨고 URL 은 그대로', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await openTargetFromList(page)

    await expect(getModal(page)).toBeVisible()
    await expect(getSidePanel(page)).toHaveCount(0)
    // 열림 상태는 스토어가 쥔다 — URL 은 목록 그대로다.
    expect(page.url()).not.toContain('selected=')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // P2. 모달 → 사이드바 즉시 전환
  //
  // Given  P1 상태(모달 열림)에서
  // When   `⋯` → 「사이드바로 열기」를 고르면
  // Then   같은 이슈가 그 자리에서 사이드패널로 바뀌고 모달은 사라진다.
  // ───────────────────────────────────────────────────────────────────────────
  test('P2 모달 ⋯ → 사이드바로 열기 → 그 자리에서 패널로 바뀐다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await openTargetFromList(page)
    await expect(getModal(page)).toBeVisible()

    await choosePresentation(page, issueDetailStrings.openInSidePanelItem)

    await expect(getSidePanel(page)).toBeVisible()
    await expect(getModal(page)).toHaveCount(0)
    // 목록이 사라지지 않는다 — 패널은 본문을 덮는 것이 아니라 밀어낸다.
    await expect(page.getByTestId(`issue-summary-${TARGET_KEY}`)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // P3. 세션 유지
  //
  // Given  P2 로 사이드바를 고른 뒤
  // When   새로고침하고 다시 행을 클릭하면
  // Then   모달을 거치지 않고 곧바로 오른쪽 상세로 열린다.
  //        ("persist across Jira views within the same session")
  //        목록에서의 껍데기는 split view 페인이다 — getSideDetailHeading 주석 참조.
  // ───────────────────────────────────────────────────────────────────────────
  test('P3 사이드바 선호는 새로고침 뒤에도 남는다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await openTargetFromList(page)
    await choosePresentation(page, issueDetailStrings.openInSidePanelItem)
    await expect(getSidePanel(page)).toBeVisible()

    await page.reload()
    await openTargetFromList(page)

    // 모달이 아니다 — 선호가 새 문서에서도 살아 있다(sessionStorage).
    await expect(getModal(page)).toHaveCount(0)
    // 목록에서는 split view 페인이 그 자리를 맡는다(위 getSideDetailHeading 주석).
    await expect(getSideDetailHeading(page)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // P4. 왕복 — 되돌아갈 길이 있다
  //
  // Given  P2 상태(패널 열림)에서
  // When   패널의 `⋯` → 「모달로 열기」를 고르면
  // Then   같은 이슈가 모달로 되돌아오고 패널은 사라진다.
  // ───────────────────────────────────────────────────────────────────────────
  test('P4 패널 ⋯ → 모달로 열기 → 모달로 되돌아온다', async ({ page }) => {
    await page.goto(ISSUES_URL)
    await openTargetFromList(page)
    await choosePresentation(page, issueDetailStrings.openInSidePanelItem)
    await expect(getSidePanel(page)).toBeVisible()

    await choosePresentation(page, issueDetailStrings.openInModalItem)

    await expect(getModal(page)).toBeVisible()
    await expect(getSidePanel(page)).toHaveCount(0)
  })
})
