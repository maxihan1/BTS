// FR-AU-10 D7 E2E — 감사 로그 관리자 조회 페이지 (SYSTEM_ADMIN 전용)
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - playwright-getbyrole-exact-strict-mode: "감사 로그"는 Header nav+페이지 제목 양쪽 노출 → nav 컨테이너 한정
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - e2e-fixture-whoami-userid-alignment: ALICE_USER_ID는 auth-fixtures와 별도 — 직접 의존 없음
//   - worktree-stale-base-rebase-and-e2e-msw-traps: Select 드롭다운 로딩 대기 후 옵션 클릭

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// localStorage 플래그 키 상수 — fr-au-05-signup.spec.ts · auth-handlers.ts 동일
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SYSTEM_ADMIN alice 로 로그인 (fr-au-05-signup.spec.ts와 동일 패턴)
//
// addInitScript 로 LS_IS_SYSTEM_ADMIN='true' 를 먼저 심은 뒤 로그인한다.
// 로그인 중 whoami 응답이 isSystemAdmin:true 로 반환되어 requireSystemAdmin 가드 통과.
// ─────────────────────────────────────────────────────────────────────────────

async function loginAsSystemAdmin(page: import('@playwright/test').Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_IS_SYSTEM_ADMIN)
  await loginAsAlice(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — admin 메뉴 노출 + 감사 로그 페이지 진입 + 테이블 행 표시
//
// Given   SYSTEM_ADMIN alice 로 로그인
// When    Header의 "감사 로그" 링크 클릭
// Then    URL /admin/audit-logs 진입
//         페이지 제목 "인증 감사 로그" 표시
//         테이블 행 1건 이상 표시 (fixture 22건)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 admin 메뉴 노출 + 감사 로그 페이지 진입 (FR-AU-10)', () => {
  test('Given SYSTEM_ADMIN 로그인 When 감사 로그 링크 클릭 Then 테이블 행 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // Then. Header "관리 메뉴" nav 노출 확인
    const adminNav = page.getByRole('navigation', { name: '관리 메뉴' })
    await expect(adminNav).toBeVisible()

    // Then. nav 내부에 "감사 로그" 링크 노출
    // playwright-getbyrole-exact-strict-mode: nav 컨테이너 내부로 한정해 strict mode violation 회피
    const auditLogLink = adminNav.getByRole('link', { name: '감사 로그', exact: true })
    await expect(auditLogLink).toBeVisible()

    // When. "감사 로그" 링크 클릭 → SPA 내부 이동
    await auditLogLink.click()
    await page.waitForURL('**/admin/audit-logs')
    expect(new URL(page.url()).pathname).toBe('/admin/audit-logs')

    // Then. 페이지 제목 "인증 감사 로그" 표시
    await expect(page.getByRole('heading', { name: '인증 감사 로그', exact: true })).toBeVisible()

    // Then. 테이블 행 1건 이상 표시 — fixture 22건이므로 22행
    const rows = page.locator('tbody tr')
    await expect(rows.first()).toBeVisible()
    const rowCount = await rows.count()
    expect(rowCount).toBeGreaterThanOrEqual(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 이벤트 유형 필터 (LOGIN_FAILURE 선택)
//
// Given   SYSTEM_ADMIN alice → /admin/audit-logs 진입
// When    이벤트 유형 Select에서 "로그인 실패" (LOGIN_FAILURE) 선택
// Then    LOGIN_FAILURE 행만 표시 (fixture 22건 중 3건: id 15, 17, 19)
//         다른 유형(LOGIN_SUCCESS 등)의 행 미표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 이벤트 유형 필터 (FR-AU-10)', () => {
  test('Given 감사 로그 페이지 When LOGIN_FAILURE 필터 선택 Then 해당 유형만 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 페이지 직접 진입 (SPA 링크 클릭 대신 goto로 속도 최적화)
    await loginAsSystemAdmin(page)
    await page.goto('/admin/audit-logs')
    await page.waitForURL('**/admin/audit-logs')

    // Given. 초기 상태: 테이블에 여러 이벤트 유형 존재 확인 (LOGIN_SUCCESS 행 있어야 함)
    await expect(page.getByRole('heading', { name: '인증 감사 로그', exact: true })).toBeVisible()
    const rows = page.locator('tbody tr')
    await expect(rows.first()).toBeVisible()

    // When. 이벤트 유형 Select 열기
    // radix Select는 SelectTrigger를 클릭하면 포탈에 SelectContent가 마운트됨
    const selectTrigger = page.getByRole('combobox')
    await expect(selectTrigger).toBeVisible()
    await selectTrigger.click()

    // When. "로그인 실패" 옵션 선택 — radix SelectContent는 포탈이므로 page 전체에서 검색
    const loginFailureOption = page.getByRole('option', { name: '로그인 실패', exact: true })
    await expect(loginFailureOption).toBeVisible()
    await loginFailureOption.click()

    // Then. 필터 적용 후 테이블 갱신 대기 — LOGIN_FAILURE 행만 남아야 함 (fixture 3건)
    // "로그인 실패" 텍스트가 적어도 1건 표시
    await expect(rows.first()).toBeVisible()
    const filteredRowCount = await rows.count()
    // fixture 기준 LOGIN_FAILURE 3건
    expect(filteredRowCount).toBe(3)

    // Then. LOGIN_SUCCESS 이벤트 라벨("로그인 성공")이 테이블에서 사라짐
    // 이벤트 유형 열(2번째 td)에서 확인
    const eventCells = page.locator('tbody tr td:nth-child(2)')
    const allEventTexts = await eventCells.allTextContents()
    expect(allEventTexts.every((text) => text === '로그인 실패')).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 페이지네이션
//
// fixture 22건, PAGE_SIZE=50 → 페이지 1개
// → "이전" 버튼 disabled (첫 페이지이므로)
// → "다음" 버튼 disabled (마지막 페이지이므로)
// → "22개 중 1–22" 텍스트 표시
//
// Note: fixture가 50건 미만(22건)이라 페이지가 1개뿐임.
//       실제 페이지 이동 시나리오 대신, 단일 페이지 상태에서 경계값 disabled를 단언.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 페이지네이션 경계값 단언 (FR-AU-10)', () => {
  test('Given fixture 22건·size 50 When 감사 로그 페이지 Then 이전/다음 모두 disabled·범위 텍스트 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 페이지 진입
    await loginAsSystemAdmin(page)
    await page.goto('/admin/audit-logs')
    await page.waitForURL('**/admin/audit-logs')

    // Given. 테이블 데이터 로딩 완료 대기
    await expect(page.getByRole('heading', { name: '인증 감사 로그', exact: true })).toBeVisible()
    const rows = page.locator('tbody tr')
    await expect(rows.first()).toBeVisible()

    // Then. "22개 중 1–22" 범위 텍스트 표시
    // auditLogLabels.pagination.rangeOf(1, 22, 22) = "22개 중 1–22"
    await expect(page.getByText('22개 중 1–22')).toBeVisible()

    // Then. "이전" 버튼 disabled — 첫 페이지(0-base page=0)
    const previousButton = page.getByRole('button', { name: '이전', exact: true })
    await expect(previousButton).toBeDisabled()

    // Then. "다음" 버튼 disabled — 마지막 페이지 (22건 ÷ 50 = 1페이지)
    const nextButton = page.getByRole('button', { name: '다음', exact: true })
    await expect(nextButton).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 비관리자: 관리 메뉴 미노출 + /admin/audit-logs 직접 진입 차단
//
// Given   일반 사용자 alice 로 로그인 (isSystemAdmin:false, 기본 fixture)
// When 1  Header 확인
// Then 1  "관리 메뉴" nav 미노출 + "감사 로그" 링크 미노출
// When 2  /admin/audit-logs 직접 goto
// Then 2  requireSystemAdmin 가드 → /dashboard redirect
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 비관리자 미노출 + 차단 (FR-AU-10)', () => {
  test('Given 일반 사용자 로그인 When 관리 메뉴 확인 Then nav 미노출 + 직접 접근 시 redirect', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false (기본 fixture, LS 플래그 없음)
    await loginAsAlice(page)

    // Then (When 1). Header "관리 메뉴" nav 미노출
    await expect(page.getByRole('navigation', { name: '관리 메뉴' })).not.toBeVisible()

    // Then (When 1). "감사 로그" 링크 미노출 (nav 없으므로 DOM에도 없음)
    await expect(page.getByRole('link', { name: '감사 로그', exact: true })).not.toBeVisible()

    // When 2. /admin/audit-logs 직접 goto
    await page.goto('/admin/audit-logs')

    // Then 2. requireSystemAdmin 가드 → /dashboard redirect
    await page.waitForURL('**/dashboard*')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })
})
