// 정상 로그인 E2E 시나리오 — alice/password(Local) → /dashboard 환영 메시지
//
// FR-AU-07 재조정: identifier-first 2단계 흐름 적용.
// 1단계: 미매칭 도메인(example.com) 이메일 입력 → "계속" → 2단계 폼 진입
// 2단계: provider 선택 + username + password 입력 → 로그인
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: exact:true 로 버튼 한정
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 드롭다운 로딩 대기
import { test, expect } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'

test('S1 정상 로그인 — alice/password (Local) → /dashboard 환영 메시지', async ({ page }) => {
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

  // /dashboard 리다이렉트 대기
  await page.waitForURL('**/dashboard')

  // dashboard 본문에 환영 메시지 존재 확인
  await expect(page.getByRole('heading', { name: '환영합니다, alice' })).toBeVisible()

  // Header 트리거 버튼 표시 확인 (DropdownMenu trigger) — displayName(김앨리스) 우선 표시되므로 정규식으로 한정
  await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()
})
