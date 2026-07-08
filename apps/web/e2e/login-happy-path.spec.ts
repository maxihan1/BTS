// 정상 로그인 E2E 시나리오 — alice/password(Local) → /dashboards 목록 페이지
//
// FR-AU-07 재조정: identifier-first 2단계 흐름 적용.
// 1단계: 미매칭 도메인(example.com) 이메일 입력 → "계속" → 2단계 폼 진입
// 2단계: provider 선택 + username + password 입력 → 로그인
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: exact:true 로 버튼 한정
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 드롭다운 로딩 대기
//   - FR-PF-02 Task 7(게이트1 확정)로 로그인 후 목적지 폴백이 `/dashboard`(단수, 환영 메시지 페이지)
//     에서 `/dashboards`(복수, 대시보드 목록 페이지)로 바뀌었다 — 목적지 URL과 도착 페이지 단언을
//     함께 갱신한다(회귀 흡수).
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'
import { dashboardLabels } from '../src/i18n/dashboard-labels'

test('S1 정상 로그인 — alice/password (Local) → /dashboards 목록 페이지', async ({ page }) => {
  await page.goto('/login')

  // 1단계. 이메일 입력 + "계속" — example.com 은 routeStore 미등록 → matched:false → 2단계 진입
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
  await page.getByLabel(loginStrings.emailLabel).fill('alice@example.com')
  await page.getByRole('button', { name: loginStrings.continueButton, exact: true }).click()

  // 2단계. 진입 대기 — provider 드롭다운이 나타날 때까지
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()

  // 2단계. provider 드롭다운에서 "Local" 명시 선택
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

  // 2단계. username 필드 — 이메일로 프리필됨, alice 로 교체
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')

  // 로그인 버튼 클릭 (exact:true — SAML/OIDC SSO 버튼과 구분)
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

  // /dashboards 리다이렉트 대기 (FR-PF-02 게이트1 확정 — start_page 미설정 시 폴백)
  await page.waitForURL('**/dashboards')

  // 대시보드 목록 페이지 제목 존재 확인
  await expect(page.getByRole('heading', { name: dashboardLabels.list.title, level: 1 })).toBeVisible()

  // Header 트리거 버튼 표시 확인 (DropdownMenu trigger) — displayName(김앨리스) 우선 표시되므로 정규식으로 한정
  await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()
})
