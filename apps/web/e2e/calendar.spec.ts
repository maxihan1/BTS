// FR-CA-01 D8 E2E — 개인 캘린더(/calendar) 월/주 뷰 시나리오
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - e2e-msw-scenario-toggle-localstorage-flag: 빈 상태 토글은 addInitScript + localStorage
//     플래그(board-kanban.spec.ts LS_KEY_BOARD_CONFLICT 선례와 동일 패턴)
//   - msw-derived-behavior-shared-store-e2e: calendar-handlers.ts는 읽기 전용(store 없음) —
//     빈 상태 토글도 핸들러 내부 localStorage 분기 하나로 충분(파생 store 불필요)
//   - playwright-getbyrole-exact-strict-mode: "캘린더"/"월"/"주" 텍스트가 nav 링크·페이지 제목·
//     툴바 버튼에 중복 노출되므로 role + 컨테이너 한정 + exact:true로 strict mode 회피.
//     조회창 라벨도 sonner Toaster가 전역 aria-live="polite" 영역을 항상 마운트해두므로
//     aria-live 셀렉터 대신 라벨 텍스트 자체(getByText, exact:true)로 한정한다.
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 이벤트 클릭 SPA 이동은 URL 변경만 검증
//     (page.reload 금지)
//
// 고정 시각(page.clock).
//   calendar-handlers.ts의 SEED_ISSUE_EVENTS/SEED_WORKLOG_EVENTS는 2026-07 날짜로 고정
//   시드돼 있다. 실제 시스템 시각(wall clock)에 CalendarView의 기본 오늘(`new Date()`)이
//   의존하면 이 테스트가 미래(다른 달)에 깨진다. CalendarView/routes/calendar.tsx는 이 작업
//   범위에서 수정 금지(구현 코드)이므로, 소스를 건드리지 않고 결정적으로 만드는 유일한 방법은
//   Playwright 내장 `page.clock.setFixedTime`으로 브라우저 Date를 시드 창(2026-07) 내부로
//   고정하는 것이다(구현 코드 수정 아님, Playwright 표준 API).
//
// MSW 핵심 사항.
//   - calendar-handlers.ts: from/to 쿼리와 무관하게 고정 시드(ATLAS-12/ATLAS-30/worklog 1건)를
//     반환한다 — 그리드에 실제로 표시되는지 여부는 클라이언트 측 날짜 매칭(buildDayEvents)에서
//     결정된다.
//   - LS_KEY_CALENDAR_EMPTY='true'면 이벤트 0건(빈 응답) — 빈 상태 시나리오 전용(Task 8 확장).

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { calendarLabels } from '../src/i18n/calendar-labels'
import { LS_KEY_CALENDAR_EMPTY } from '../src/mocks/calendar-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 고정 시각 — calendar-handlers.ts SEED_ISSUE_EVENTS/SEED_WORKLOG_EVENTS의 2026-07 창 내부 */
const FIXED_TODAY = new Date(2026, 6, 8, 12, 0, 0)
/** FIXED_TODAY의 `data-date` 셀 키(DayCell/WeekDayColumn `toDateKey` 포맷과 동일) */
const FIXED_TODAY_KEY = '2026-07-08'

/** calendar-handlers.ts SEED_ISSUE_EVENTS[0].key — startDate=2026-07-03~dueDate=2026-07-10 */
const SEED_ISSUE_KEY = 'ATLAS-12'
/** 위 이벤트의 startDate(isStart 세그먼트가 렌더되는 날짜) — 유일 day cell 앵커로 strict mode 회피 */
const SEED_ISSUE_START_DATE = '2026-07-03'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 툴바 버튼 locator (그룹 aria-label로 한정, playwright-getbyrole-exact-strict-mode)
// ─────────────────────────────────────────────────────────────────────────────

/** "기간 이동" 그룹(이전/오늘/다음) 내부의 버튼 locator */
function periodButton(page: import('@playwright/test').Page, name: string) {
  return page
    .getByRole('group', { name: calendarLabels.toolbar.periodGroupAriaLabel })
    .getByRole('button', { name, exact: true })
}

/** "보기 전환" 그룹(월/주) 내부의 버튼 locator */
function viewToggleButton(page: import('@playwright/test').Page, name: string) {
  return page
    .getByRole('group', { name: calendarLabels.toolbar.viewToggleGroupAriaLabel })
    .getByRole('button', { name, exact: true })
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-CA-01 개인 캘린더 (/calendar 월/주 뷰)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. 브라우저 Date를 시드 창(2026-07) 내부로 고정 — wall clock 의존 제거
    await page.clock.setFixedTime(FIXED_TODAY)
    // Given. alice로 로그인 → /dashboard 진입
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-1 전역 nav 진입 — Header "캘린더" 링크 클릭 → /calendar 월 뷰 렌더
  //
  // Given  alice로 로그인, /dashboard 진입
  // When   Header 메인 메뉴의 "캘린더" 링크 클릭
  // Then   /calendar로 이동, 페이지 제목 "캘린더" 표시, 월 그리드(6주×7일=42셀) 렌더
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-1 전역 nav 진입 — "캘린더" 링크 클릭 시 /calendar 월 뷰 렌더', async ({ page }) => {
    // When. Header 메인 메뉴 "캘린더" 링크 클릭 (nav 컨테이너 한정 — strict mode 회피)
    const nav = page.getByRole('navigation', { name: '메인 메뉴' })
    await nav.getByRole('link', { name: calendarLabels.page.title, exact: true }).click()

    // Then. /calendar 이동 + 페이지 제목(h1)
    await page.waitForURL('**/calendar')
    await expect(page.getByRole('heading', { name: calendarLabels.page.title, level: 1 })).toBeVisible()

    // Then. 월 뷰 그리드 렌더 — 6주×7일 = 42 gridcell
    await expect(page.getByRole('grid')).toBeVisible()
    await expect(page.getByRole('gridcell')).toHaveCount(42)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-2 월↔주 뷰 토글
  //
  // Given  alice로 로그인, /calendar 진입 (기본 월 뷰, 42 gridcell)
  // When   툴바 "주" 버튼 클릭
  // Then   그리드가 7 gridcell(7일)로 전환됨, "주" 버튼 aria-pressed=true
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-2 월↔주 뷰 토글 — "주" 클릭 시 7일 그리드 렌더', async ({ page }) => {
    // Given. /calendar 진입 (기본 월 뷰)
    await page.goto('/calendar')
    await expect(page.getByRole('gridcell')).toHaveCount(42)

    // When. "주" 토글 버튼 클릭
    const weekButton = viewToggleButton(page, calendarLabels.toolbar.weekView)
    await weekButton.click()

    // Then. 7일 그리드로 전환 + "주" 버튼 선택 상태
    await expect(page.getByRole('gridcell')).toHaveCount(7)
    await expect(weekButton).toHaveAttribute('aria-pressed', 'true')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-3 이전/다음/오늘 네비게이션
  //
  // Given  alice로 로그인, /calendar 진입 (고정 오늘=2026-07-08, 월 뷰 라벨 "2026년 7월")
  // When   "다음" 2회(9월 이동) → "이전" 1회(8월로 복귀) → "오늘" 클릭
  // Then   각 단계마다 조회창 라벨이 이동하고, "오늘" 클릭 시 원래 월(7월)로 복귀
  //        그리드도 갱신됨(오늘 날짜 셀 `[data-date=2026-07-08]`의 존재 여부로 확인)
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-3 이전/다음/오늘 네비게이션 — 조회창 이동 + 그리드 갱신', async ({ page }) => {
    // Given. /calendar 진입, 초기 라벨 "2026년 7월" + 오늘 날짜 셀 존재
    await page.goto('/calendar')
    await expect(page.getByText('2026년 7월', { exact: true })).toBeVisible()
    await expect(page.locator(`[data-date="${FIXED_TODAY_KEY}"]`)).toBeVisible()

    // When. "다음" 2회 클릭 → 9월 이동
    await periodButton(page, calendarLabels.toolbar.next).click()
    await periodButton(page, calendarLabels.toolbar.next).click()

    // Then. 라벨이 9월로 이동 + 오늘 날짜 셀은 그리드에서 사라짐(조회창 이동 → 그리드 갱신)
    await expect(page.getByText('2026년 9월', { exact: true })).toBeVisible()
    await expect(page.locator(`[data-date="${FIXED_TODAY_KEY}"]`)).toHaveCount(0)

    // When. "이전" 1회 클릭 → 8월로 복귀
    await periodButton(page, calendarLabels.toolbar.prev).click()
    await expect(page.getByText('2026년 8월', { exact: true })).toBeVisible()

    // When. "오늘" 클릭
    await periodButton(page, calendarLabels.toolbar.today).click()

    // Then. 원래 월(7월)로 복귀 + 오늘 날짜 셀 재등장
    await expect(page.getByText('2026년 7월', { exact: true })).toBeVisible()
    await expect(page.locator(`[data-date="${FIXED_TODAY_KEY}"]`)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-4 이벤트(이슈) 클릭 → 이슈 상세 SPA 이동
  //
  // Given  alice로 로그인, /calendar 진입 (고정 오늘=2026-07-08)
  //        ATLAS-12(startDate=2026-07-03~dueDate=2026-07-10) 기간 막대가 월 그리드에 렌더
  // When   2026-07-03 셀의 ATLAS-12 막대 세그먼트(isStart) 클릭
  // Then   /issues/ATLAS-12로 SPA 내부 이동(URL 변경)
  //
  // Note. page.reload() 금지 — URL 변경만 검증(이슈 상세 렌더는 issue-tracking E2E가 커버)
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-4 이벤트 클릭 — ATLAS-12 막대 클릭 시 /issues/ATLAS-12로 이동', async ({ page }) => {
    // Given. /calendar 진입, 2026-07-03 셀에 ATLAS-12 이벤트 표시
    await page.goto('/calendar')
    const startCell = page.locator(`[data-date="${SEED_ISSUE_START_DATE}"]`)
    await expect(startCell).toBeVisible()
    const eventButton = startCell.getByRole('button', { name: new RegExp(SEED_ISSUE_KEY) })
    await expect(eventButton).toBeVisible()

    // When. 이벤트 클릭
    await eventButton.click()

    // Then. /issues/ATLAS-12로 SPA 내부 이동
    await page.waitForURL(`**/issues/${SEED_ISSUE_KEY}`)
    expect(page.url()).toContain(`/issues/${SEED_ISSUE_KEY}`)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // E2E-5 빈 기간 — 빈 상태 표시
  //
  // Given  alice로 로그인
  //        addInitScript로 LS_KEY_CALENDAR_EMPTY='true' 심기(goto 전 등록)
  // When   /calendar 진입 → calendar-handlers.ts가 이벤트 0건 응답
  // Then   "이 기간에 일정이 없습니다." 빈 상태 메시지 표시
  //        그리드 자체는 계속 렌더(42 gridcell 유지 — 빈 상태여도 날짜 탐색 가능, T5 설계)
  // ───────────────────────────────────────────────────────────────────────────
  test('E2E-5 빈 기간 — MSW 빈 응답 토글 시 빈 상태 메시지 표시', async ({ page }) => {
    // Given. addInitScript로 빈 응답 플래그 심기(goto 이전 등록 — 첫 GET부터 적용)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_CALENDAR_EMPTY)

    // When. /calendar 진입
    await page.goto('/calendar')

    // Then. 빈 상태 메시지 표시
    await expect(page.getByText(calendarLabels.empty.message)).toBeVisible()

    // Then. 그리드는 계속 렌더(42 gridcell 유지)
    await expect(page.getByRole('gridcell')).toHaveCount(42)
  })
})
