// FR-CA-02 Task 10 E2E — 캘린더 iCal 구독 URL 발급/구독/재발급/취소(/settings/calendar) 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로(MSW 기본 allow)
//   - msw-mutation-stateful-refetch: calendar-feed-handlers.ts POST/DELETE가 feed를 모듈 top-level
//     변수(stateful)에 영속한다 — invalidateQueries 후 재조회(GET)가 화면에 실제로 반영되는지 검증한다
//     (setQueryData 위에서만 통과하는 가짜 그린 방지).
//   - e2e-msw-scenario-toggle-localstorage-flag / msw-derived-behavior-shared-store-e2e: 이 핸들러는
//     에러/빈 상태 시나리오 토글이 없다(성공 경로만 존재, calendar-feed-handlers.ts 참조) — 각
//     Playwright test는 새 브라우저 컨텍스트(→ 페이지 로드 → 모듈 재평가)이므로 top-level `feed` 변수가
//     test마다 자동으로 미발급 상태로 초기화된다. 별도 addInitScript 플래그 없이도 데이터 격리가
//     보장되므로(핸들러 파일 상단 주석), 이 스펙에서는 localStorage 토글을 쓰지 않는다.
//   - playwright-getbyrole-exact-strict-mode: 모든 버튼 조회에 exact:true 사용. 단 "구독 URL이
//     발급되어 있습니다." 문구는 발급일 안내(<span>)가 형제로 뒤에 붙어 <p> 전체 텍스트와 정확히
//     일치하지 않으므로 exact:true를 쓰지 않는다(부분 일치로 판정).
//   - ui-pr-defer-e2e-regression-latent: Header.tsx 계정 드롭다운에 "캘린더 구독" 메뉴 항목이 신규
//     추가됐다 — 기존 profile.spec.ts/pat.spec.ts 등은 모두 getByRole('menuitem', {name, exact:true})로
//     자기 항목만 한정 조회하므로 신규 항목 추가로 strict mode가 깨지지 않는다(기존 E2E 동반 실행으로
//     회귀 없음을 확인).
//
// CardTitle/CardDescription은 shadcn Card 컴포넌트가 <div>로 렌더한다(components/ui/card.tsx) —
// heading role이 아니므로 카드 제목("캘린더 구독"/"구독 URL이 발급되었습니다")은 getByRole('heading')
// 대신 getByText로 조회한다. 페이지 상단 h1("캘린더 연동")만 heading role이다.

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — CalendarFeedCard.tsx labels(비공개 const, export 없음) 미러. 구현 코드를 직접 import할 수
// 없으므로 리터럴 문자열로 고정한다(pat.spec.ts/keymap.spec.ts와 동일 관례).
// ─────────────────────────────────────────────────────────────────────────────

const PAGE_URL = '/settings/calendar'
const PAGE_HEADING = '캘린더 연동'

/** calendar-feed-handlers.ts FEED_BASE_URL('http://localhost:8080') + /ical/feed/<64자 hex>.ics */
const FEED_URL_PATTERN = /^http:\/\/localhost:8080\/ical\/feed\/[0-9a-f]{64}\.ics$/

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** alice 로그인 후 /settings/calendar 진입, 페이지 제목이 뜰 때까지 대기한다. */
async function loginAndGotoCalendarFeed(page: Page): Promise<void> {
  await loginAsAlice(page)
  await page.goto(PAGE_URL)
  await expect(page.getByRole('heading', { name: PAGE_HEADING, exact: true })).toBeVisible()
}

/** 발급(1회노출) 화면의 code 블록 locator — 이 화면에서 페이지 전체에 <code> 요소는 이 하나뿐이다. */
function feedUrlCode(page: Page) {
  return page.locator('code')
}

/** 1회 노출 code 블록이 유효한 feedUrl 패턴으로 보일 때까지 기다린 뒤 텍스트를 반환한다. */
async function waitForRevealedFeedUrl(page: Page): Promise<string> {
  const code = feedUrlCode(page)
  await expect(code).toBeVisible()
  await expect(code).toHaveText(FEED_URL_PATTERN)
  const text = await code.textContent()
  return (text ?? '').trim()
}

/** 발급 완료 화면(1회 노출)에서 "닫기"를 눌러 일반 카드(재발급/취소 버튼 노출)로 되돌아간다. */
async function closeRevealAndExpectEnabledCard(page: Page): Promise<void> {
  await page.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(feedUrlCode(page)).toHaveCount(0)
  // "구독 URL이 발급되어 있습니다." 뒤에 "(발급일: ...)" 안내가 형제 <span>으로 붙으므로 부분 일치로 확인
  await expect(page.getByText('구독 URL이 발급되어 있습니다.')).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 미발급 → 발급 → URL 1회 표시 + 복사 버튼 노출
//
// Given  alice 로그인 → /settings/calendar 진입 (미발급 상태, "구독 URL 발급" 버튼만 노출)
// When   "구독 URL 발급" 클릭
// Then   구독 URL이 1회 코드 블록으로 표시되고 "복사" 버튼과 재확인 불가 경고 문구가 함께 노출된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 미발급 → 발급 → URL 1회 표시 + 복사 버튼 노출 (FR-CA-02)', () => {
  test('Given 미발급 상태 When 발급 클릭 Then URL 1회 표시 + 복사 버튼 + 재확인 불가 경고', async ({ page }) => {
    await loginAndGotoCalendarFeed(page)

    // Given. 미발급 상태 — 발급 버튼만 노출, 재발급/취소 버튼은 없음
    const issueButton = page.getByRole('button', { name: '구독 URL 발급', exact: true })
    await expect(issueButton).toBeVisible()
    await expect(page.getByRole('button', { name: '재발급', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '구독 취소', exact: true })).toHaveCount(0)

    // When. 발급 버튼 클릭
    await issueButton.click()

    // Then. URL이 코드 블록에 1회 표시됨 (유효한 feedUrl 패턴)
    const feedUrl = await waitForRevealedFeedUrl(page)
    expect(feedUrl).toMatch(FEED_URL_PATTERN)

    // Then. 재확인 불가 경고 문구 + 복사 버튼 노출
    await expect(page.getByText('다시 표시되지 않습니다')).toBeVisible()
    await expect(page.getByRole('button', { name: '복사', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 재발급 → 기존 URL 무효 경고 → 확인 → 새 URL 노출(rotate)
//
// Given  alice 로그인 → /settings/calendar 진입 → 최초 발급 완료 후 "닫기"로 카드 복귀
// When   "재발급" 클릭
// Then   기존 URL 무효화 경고 문구 표시
// When   "확인" 클릭
// Then   새 구독 URL이 1회 표시되고, 최초 발급 URL과 다른 값이다(서버 rotate 확인)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 재발급 — 기존 URL 무효 경고 표시 후 rotate (FR-CA-02)', () => {
  test('Given 발급 완료 When 재발급 확인 Then 무효 경고 표시 후 새 URL 노출(이전과 다른 값)', async ({ page }) => {
    await loginAndGotoCalendarFeed(page)

    // Given. 최초 발급 완료 → "닫기"로 카드 복귀(재발급/취소 버튼 노출)
    await page.getByRole('button', { name: '구독 URL 발급', exact: true }).click()
    const firstUrl = await waitForRevealedFeedUrl(page)
    await closeRevealAndExpectEnabledCard(page)

    // When. "재발급" 클릭
    await page.getByRole('button', { name: '재발급', exact: true }).click()

    // Then. 기존 URL 무효화 경고 표시(단일 자식 텍스트 노드 — exact:true 안전)
    await expect(
      page.getByText(
        '재발급하면 기존 구독 URL이 즉시 무효화되어 더 이상 동작하지 않습니다. 계속하시겠습니까?',
        { exact: true },
      ),
    ).toBeVisible()

    // When. 인라인 "확인" 클릭
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. 새 URL이 1회 노출되고, 최초 발급 URL과 다른 값(서버 upsert rotate)
    const secondUrl = await waitForRevealedFeedUrl(page)
    expect(secondUrl).not.toBe(firstUrl)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 취소 → 미발급 복귀
//
// Given  alice 로그인 → /settings/calendar 진입 → 발급 완료 후 "닫기"로 카드 복귀
// When   "구독 취소" 클릭 → 인라인 확인 "확인" 클릭
// Then   미발급 상태(발급 버튼만 노출)로 복귀한다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 취소 → 미발급 복귀 (FR-CA-02)', () => {
  test('Given 발급 완료 When 취소 확인 Then 미발급 상태(발급 버튼)로 복귀', async ({ page }) => {
    await loginAndGotoCalendarFeed(page)

    // Given. 발급 완료 → "닫기"로 카드 복귀(재발급/취소 버튼 노출)
    await page.getByRole('button', { name: '구독 URL 발급', exact: true }).click()
    await waitForRevealedFeedUrl(page)
    await closeRevealAndExpectEnabledCard(page)

    // When. "구독 취소" 클릭
    await page.getByRole('button', { name: '구독 취소', exact: true }).click()

    // Then. 취소 확인 경고 표시(단일 자식 텍스트 노드 — exact:true 안전)
    await expect(
      page.getByText('구독을 취소하면 발급된 URL이 더 이상 동작하지 않습니다. 계속하시겠습니까?', { exact: true }),
    ).toBeVisible()

    // When. 인라인 "확인" 클릭
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. 미발급 상태(발급 버튼만 노출)로 복귀 — 재발급/취소 버튼은 사라짐(stateful refetch 반영)
    await expect(page.getByRole('button', { name: '구독 URL 발급', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '재발급', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '구독 취소', exact: true })).toHaveCount(0)
  })
})
