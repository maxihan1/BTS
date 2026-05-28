// FR-IS-01 D7 E2E-2 비로그인 가드 — 이슈 라우트 3개 → /login redirect (returnTo 보존)
import { test, expect } from '@playwright/test'

const PROTECTED_ROUTES = [
  { name: '이슈 목록 (/issues)', path: '/issues' },
  { name: '이슈 상세 (/issues/ATLAS-1)', path: '/issues/ATLAS-1' },
  { name: '이슈 생성 (/issues/new)', path: '/issues/new' },
] as const

for (const route of PROTECTED_ROUTES) {
  test(`E2E-2 비로그인 가드 — ${route.name} → /login?returnTo=${route.path} redirect`, async ({ page }) => {
    // Given. 비로그인 상태 — sessionStorage 비어있음 (페이지 진입 전 강제 초기화)
    await page.goto('/login')
    await page.evaluate(() => sessionStorage.clear())

    // When. 보호된 이슈 라우트 직접 접근
    await page.goto(route.path)

    // Then. /login 으로 redirect + returnTo 쿼리에 원래 경로 보존
    // TanStack Router beforeLoad 가드가 returnTo 쿼리 (URL-encoded) 로 원경로 보존.
    await page.waitForURL(/\/login(\?|$)/)
    const url = new URL(page.url())
    expect(url.pathname).toBe('/login')
    const returnTo = url.searchParams.get('returnTo')
    expect(returnTo).toBe(route.path)

    // 로그인 페이지 헤딩이 보이는지 확인 (실제로 페이지가 렌더됐는지)
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  })
}
