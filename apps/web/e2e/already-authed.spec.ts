// S8 — 이미 인증된 사용자가 /login 진입 시 /dashboards 즉시 리다이렉트 검증
//
// 회귀 흡수(FR-PF-02 Task 7, 게이트1 확정): redirectIfAuth 폴백이 `/dashboard`(단수)에서
// `/dashboards`(복수)로 바뀌었다. 공용 헬퍼 `./fixtures/auth-fixtures.ts`의 loginAsAlice()는
// 이 PR의 커밋(fr-pf-02-start-page E2E 로그인 대기 glob 완화)에서 `**/dashboard*`로 이미
// 호환 처리됐다 — /dashboard·/dashboards 모두 매칭하므로 그대로 재사용한다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { dashboardLabels } from '../src/i18n/dashboard-labels'

test('S8 이미 인증된 사용자가 /login 진입 → /dashboards 즉시 리다이렉트', async ({ page }) => {
  // 1단계. 정상 로그인 — alice 기본 startPage='dashboards'라 로그인 직후 /dashboards에 도착한다.
  await loginAsAlice(page)
  await expect(page).toHaveURL(/\/dashboards/)

  // 2단계. 인증 상태에서 /login 진입 시도
  await page.goto('/login')

  // 3단계. /dashboards 로 즉시 리다이렉트 (redirectIfAuth 가드 동작, FR-PF-02 게이트1 확정 폴백)
  await page.waitForURL('**/dashboards')
  await expect(page.getByRole('heading', { name: dashboardLabels.list.title, level: 1 })).toBeVisible()
})
