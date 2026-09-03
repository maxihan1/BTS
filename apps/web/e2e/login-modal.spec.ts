// 전역 로그인 모달 E2E — 미인증 진입 · 닫기 봉인 · 세션 만료 시 라우트 유지
//
// S1 — 미인증으로 / 진입 → 로그인 모달이 뜬다 (인덱스 placeholder 가 아니라)
// S2 — ESC · 오버레이 클릭으로 닫히지 않고 X 버튼도 없다
// S3 — 미인증으로 보호 라우트 딥링크 → 모달 + 배경에 실데이터 없음
// S4 — 세션 만료 → URL 이 바뀌지 않고 현재 화면 위에 모달이 뜬다
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지
//   - playwright-getbyrole-exact-strict-mode: 모달 안 요소는 dialog locator 로 스코프
//     (Radix modal 이 body pointer-events 를 잠그므로 배경 클릭은 timeout 이 난다)
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { loginPageStrings, loginStrings } from '../src/i18n/ko'

test.describe('전역 로그인 모달', () => {
  test('S1 미인증으로 / 진입 → 로그인 모달이 뜬다', async ({ page }) => {
    await page.goto('/')

    const dialog = page.getByRole('dialog', { name: loginPageStrings.heading })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByLabel(loginStrings.usernameLabel)).toBeVisible()
    await expect(dialog.getByLabel(loginStrings.passwordLabel)).toBeVisible()

    // 폐기된 인덱스 placeholder 가 되살아나지 않았는지 확인한다
    await expect(page.getByText('홈 (T13 가드 추가 전 placeholder)')).toHaveCount(0)
  })

  test('S2 ESC · 오버레이 클릭으로 닫히지 않고 X 버튼도 없다', async ({ page }) => {
    await page.goto('/login')
    const dialog = page.getByRole('dialog', { name: loginPageStrings.heading })
    await expect(dialog).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(dialog).toBeVisible()

    // 오버레이 클릭 — jsdom 이 만들 수 없는 좌표 기반 바깥 클릭은 여기서만 실증된다
    await page.locator('[data-slot="dialog-overlay"]').click({ position: { x: 5, y: 5 } })
    await expect(dialog).toBeVisible()

    await expect(page.locator('[data-slot="dialog-close-button"]')).toHaveCount(0)
  })

  test('S3 미인증 딥링크 → 모달이 뜨고 배경에 실데이터가 없다', async ({ page }) => {
    await page.goto('/issues/ATLAS-1')

    await expect(page.getByRole('dialog', { name: loginPageStrings.heading })).toBeVisible()
    // 배경은 AuthBackdrop(props·hook·fetch 0개)이라 이슈 키가 화면에 존재할 수 없다
    await expect(page.getByText('ATLAS-1')).toHaveCount(0)
  })

  test('S4 세션 만료 → /login 으로 튕기지 않고 현재 화면 위에 모달이 뜬다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/issues')

    // 🛑 sessionStorage 를 지우면 안 된다. store 가 비면 requireAuth 가 먼저 /login 으로
    //    보내버려서 "이동하지 않는다"는 이 테스트의 명제 자체를 검증할 수 없다.
    //    만료는 서버 쪽에서만 일어나게 한다 — 토큰은 남아 있고 서버가 거절하는 상태.
    await page.route('**/api/v1/auth/refresh', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'refresh_token_expired' }),
      }),
    )
    await page.route('**/api/v1/issues**', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'unauthorized' }),
      }),
    )

    // 목록 재조회를 유발한다. store 의 토큰은 살아 있으므로 requireAuth 는 통과하고,
    // 이슈 API 401 → refresh 401 → 만료 프롬프트 경로를 탄다.
    await page.reload()

    await expect(page.getByRole('dialog', { name: loginPageStrings.heading })).toBeVisible()
    expect(page.url()).toContain('/issues')
    expect(page.url()).not.toContain('/login')
  })
})
