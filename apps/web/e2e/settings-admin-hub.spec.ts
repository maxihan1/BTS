// FR-UX-06 PR13 D9 E2E — /settings·/admin 인덱스 허브 + 비-admin 리다이렉트 + TopBar 재랜딩 (Task 9, PL-4~6·8)
//
// 시나리오 개요.
//   S1  설정 허브(admin 불요)         — /settings 진입 → 제목 '설정' + 카드 목록 → 카드 클릭 시 하위 라우트 이동
//   S2  관리 허브(SYSTEM_ADMIN)       — /admin 진입 → 제목 '관리' + 관리 카드 표시
//   S3a 관리 허브 비-admin 리다이렉트 — /admin 직접 접근 → /dashboard 리다이렉트
//   S3b 관리 허브 비-admin 리다이렉트 — /admin/workflow-schemes 직접 접근 → /dashboard 리다이렉트(Task 8 가드)
//   S4  TopBar 설정아이콘 재랜딩      — 상단바 설정 아이콘(aria-label="설정") 클릭 → /settings 랜딩
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: 관리 허브 카드 라벨은 main 컨테이너로 한정해 조회한다.
//     J9 이전에는 Sidebar ADMIN_NAV_LINKS(관리 nav, <aside> 소속)와 텍스트가 겹쳐서였고,
//     지금 충돌원은 상단바 관리 허브 링크('관리 메뉴')다 — 이름은 다르지만 스코핑은 유지한다.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — playwright.config.ts 기본 그대로.
//   - reload 금지(store 리셋 = 가짜그린) — SPA goto/click만 사용, page.reload() 미호출.
//   - audit-logs.spec.ts S4 패턴 — 비-admin 직접 URL 접근 시 requireSystemAdmin 가드의
//     /dashboard 리다이렉트를 pathname 단언으로 확정.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { loginAsSystemAdmin } from './fixtures/workflow-scheme-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 설정 허브: 제목 + 카드 목록 + 카드 클릭 시 하위 라우트 이동
//
// Given   alice 로그인(admin 불요)
// When    /settings 진입
// Then    PageHeader 제목 '설정' + 카드 목록(대표 카드 가시) 표시
// When    '프로필' 카드 클릭
// Then    /settings/profile 로 이동
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 설정 허브 (FR-UX-06 PR13)', () => {
  test('Given alice 로그인 When /settings 진입 Then 제목/카드 표시 후 카드 클릭 시 하위 라우트 이동', async ({ page }) => {
    // Given. alice 로그인 — 개인 설정 허브는 인증만 요구(SYSTEM_ADMIN 불요)
    await loginAsAlice(page)

    // When. /settings 진입
    await page.goto('/settings')

    // Then. PageHeader 제목 '설정'
    await expect(page.getByRole('heading', { name: '설정', exact: true })).toBeVisible()

    // Then. 카드 목록 표시 — main 컨테이너로 한정(사이드바 등 다른 영역과 텍스트 중복 방지)
    const main = page.getByRole('main')
    const profileCard = main.getByRole('link', { name: '프로필' })
    await expect(profileCard).toBeVisible()
    await expect(main.getByRole('link', { name: '2단계 인증' })).toBeVisible()

    // When. '프로필' 카드 클릭
    await profileCard.click()

    // Then. /settings/profile 로 이동
    await page.waitForURL('**/settings/profile')
    expect(new URL(page.url()).pathname).toBe('/settings/profile')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 관리 허브: SYSTEM_ADMIN 로그인 시 제목 + 관리 카드 표시
//
// Given   SYSTEM_ADMIN alice 로그인
// When    /admin 진입
// Then    PageHeader 제목 '관리' + 관리 카드(대표 카드 가시) 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 관리 허브 (FR-UX-06 PR13)', () => {
  test('Given SYSTEM_ADMIN 로그인 When /admin 진입 Then 제목/관리 카드 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // When. /admin 진입
    await page.goto('/admin')

    // Then. PageHeader 제목 '관리'
    await expect(page.getByRole('heading', { name: '관리', exact: true })).toBeVisible()

    // Then. 관리 카드 표시 — main 컨테이너로 한정(관리 nav, <aside> 소속과 텍스트 중복 방지)
    const main = page.getByRole('main')
    await expect(main.getByRole('link', { name: '워크플로우 스킴' })).toBeVisible()
    await expect(main.getByRole('link', { name: '감사 로그' })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 관리 허브 비-admin 리다이렉트
//
// Given   기본 alice 로그인(비-admin, isSystemAdmin:false 기본 fixture)
// When    /admin 또는 /admin/workflow-schemes 직접 goto
// Then    requireSystemAdmin 가드 → /dashboard 리다이렉트
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 관리 허브 비-admin 리다이렉트 (FR-UX-06 PR13)', () => {
  test('S3a Given 비-admin 로그인 When /admin 직접 접근 Then /dashboard 리다이렉트', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false(기본 fixture, LS 플래그 없음)
    await loginAsAlice(page)

    // When. /admin 직접 goto
    await page.goto('/admin')

    // Then. requireSystemAdmin 가드 → /dashboard 리다이렉트
    await page.waitForURL('**/dashboard*')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })

  test('S3b Given 비-admin 로그인 When /admin/workflow-schemes 직접 접근 Then /dashboard 리다이렉트', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false(기본 fixture, LS 플래그 없음)
    await loginAsAlice(page)

    // When. /admin/workflow-schemes 직접 goto — Task 8이 추가한 SYSTEM_ADMIN 가드 대상
    await page.goto('/admin/workflow-schemes')

    // Then. requireSystemAdmin 가드 → /dashboard 리다이렉트
    await page.waitForURL('**/dashboard*')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — TopBar 설정아이콘 → /settings 재랜딩
//
// Given   alice 로그인(로그인 직후 /dashboard 진입 상태)
// When    상단바 설정 아이콘(aria-label="설정") 클릭
// Then    /settings 로 이동
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 TopBar 설정아이콘 재랜딩 (FR-UX-06 PR13)', () => {
  test('Given alice 로그인 When 상단바 설정 아이콘 클릭 Then /settings 랜딩', async ({ page }) => {
    // Given. alice 로그인 — loginAsAlice가 /dashboard 진입까지 완료
    await loginAsAlice(page)

    // When. 상단바(banner) 설정 아이콘 클릭
    const banner = page.getByRole('banner')
    const settingsIcon = banner.getByRole('link', { name: '설정', exact: true })
    await expect(settingsIcon).toBeVisible()
    await settingsIcon.click()

    // Then. /settings 로 이동
    await page.waitForURL('**/settings')
    expect(new URL(page.url()).pathname).toBe('/settings')
  })
})
