// FR-UX-06 PR12 D6 E2E — C3 랜드마크 계약(banner/main/complementary) 인증 화면·로그인 화면 각 1개
//
// 시나리오 개요.
//   L1. 인증 화면(alice 로그인 후 /dashboards) — banner 1 + main 1 + complementary 1
//   L2. 로그인 화면(미인증 /login)             — main 1
//
// 설계 결정.
//   - L1은 /dashboards로 진입한다 — 이 라우트는 자체 <main>/<header>/<aside>를 렌더하지
//     않는 "깨끗한" 페이지다(issues.$key 상세, admin.workflow-schemes.* 등은 자체 <main>을
//     보유해 landmark 중복 위험이 있으므로 회피, ShellLayout.tsx 랜드마크 소유 맵 참고).
//   - 페이지 자체 <header>(issues.index.tsx 등)는 ShellLayout의 <main> 자손이라 HTML-AAM 규칙상
//     암묵적 banner role을 잃는다(header가 main/article/aside/nav/section의 자손이면 role 없음).
//     그래도 회귀 안전을 위해 자체 header가 전혀 없는 /dashboards를 택했다.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto로만 페이지 전환.
//   - serviceWorkers:'block' 금지(e2e-msw-serviceworker-block) — 기본 설정 그대로 사용.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

test.describe('FR-UX-06 PR12 C3 랜드마크(banner/main/complementary)', () => {
  test('L1 Given alice 로그인 When /dashboards 진입 Then banner 1 + main 1 + complementary 1', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/dashboards')

    await expect(page.getByRole('banner')).toHaveCount(1)
    await expect(page.getByRole('main')).toHaveCount(1)
    await expect(page.getByRole('complementary')).toHaveCount(1)
  })

  test('L2 Given 미인증 When /login 진입 Then main 1', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()

    await expect(page.getByRole('main')).toHaveCount(1)
  })
})
