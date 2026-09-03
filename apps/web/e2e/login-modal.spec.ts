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
import { E2E_REFRESH_EXPIRED_KEY } from '../src/mocks/auth-handlers'

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

  // S5 는 이 PR 이 만든 흐름 전체를 한 번에 잰다 — 딥링크 → 모달 → 로그인 → **원래 페이지 복귀**.
  // 🛑 유닛으로는 이걸 잡을 수 없다. `LoginDialog.test.tsx` 는 라우터를 통째로 mock 하므로
  //    `requireAuth` 가 **넣는** returnTo 와 `handleSuccess` 가 **읽는** returnTo 가 실제로
  //    맞물리는지 검증하지 못한다 — 두 쪽이 각자 초록이면서 왕복이 끊길 수 있다.
  //    기존 e2e 도 `issue-auth-guard.spec.ts` 가 「/login?returnTo= 로 보낸다」까지만 재고
  //    복귀는 아무도 안 봤다.
  test('S5 미인증 딥링크 → 모달 → 로그인 → 원래 페이지로 복귀한다 (returnTo 왕복)', async ({
    page,
  }) => {
    const deepLink = '/issues/ATLAS-1'
    await page.goto(deepLink)

    // 🛑 URL 을 먼저 읽지 마라. `goto` 는 load 까지만 기다리고 SPA 라우터의 redirect 는 그 뒤에
    //    일어나므로, 곧바로 `page.url()` 을 보면 아직 딥링크 그대로다(실측).
    const dialog = page.getByRole('dialog', { name: loginPageStrings.heading })
    await expect(dialog).toBeVisible()

    // requireAuth 가 returnTo 를 보존한 채 /login 으로 보냈다
    await page.waitForURL('**/login?returnTo=*')
    const redirected = new URL(page.url())
    expect(redirected.pathname).toBe('/login')
    expect(redirected.searchParams.get('returnTo')).toBe(deepLink)

    // 모달 안에서 로그인한다 — 배경이 pointer-events 로 잠기므로 dialog 로 스코프한다
    const providerSelect = dialog.getByRole('combobox', { name: loginStrings.providerLabel })
    await expect(providerSelect).not.toBeDisabled()
    await providerSelect.click()
    await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
    await dialog.getByLabel(loginStrings.usernameLabel).fill('alice')
    await dialog.getByLabel(loginStrings.passwordLabel).fill('password')
    await dialog.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()

    // start_page(dashboards)가 아니라 **원래 보려던 페이지**로 가야 한다
    await page.waitForURL(`**${deepLink}`)
    await expect(dialog).toBeHidden()
    await expect(page.getByRole('heading', { name: /ATLAS-1|이슈/ }).first()).toBeVisible()
  })

  test('S4 세션 만료 → /login 으로 튕기지 않고 현재 화면 위에 모달이 뜬다', async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/issues')

    // 🛑 page.route 로는 재현할 수 없다. e2e 는 MSW **서비스워커** 환경이라 MSW 가 처리한
    //    요청은 네트워크로 나가지 않고, 따라서 Playwright 의 네트워크 가로채기에 도달하지 않는다.
    //    MSW 쪽 localStorage 토글이 유일한 경로다(e2e-msw-scenario-toggle-localstorage-flag).
    // 🛑 sessionStorage 를 지우는 방식도 쓸 수 없다. store 가 비면 requireAuth 가 먼저
    //    /login 으로 보내버려서 「이동하지 않는다」는 이 테스트의 명제를 검증할 수 없다 —
    //    토큰은 살아 있고 **서버가 거절하는** 상태여야 한다.
    await page.evaluate((key) => {
      window.localStorage.setItem(key, 'true')
    }, E2E_REFRESH_EXPIRED_KEY)

    // 🛑 `page.reload()` 를 쓰지 마라. 새로고침은 앱을 처음부터 부팅하므로 라우트 진입에서
    //    `requireAuth` 가 먼저 잡아 `/login?returnTo=%2Fissues` 로 보낸다(실측). 그리고 그건
    //    **올바른 동작이다** — 만료된 세션으로 새로 여는 것은 로그인 화면이 맞다.
    //    「이동하지 않는다」가 지키는 것은 **살아 있는 SPA 세션 중의 만료**다. 작성 중이던
    //    이슈·댓글을 잃지 않는 것이 그 요구의 목적이고, 새로고침에는 잃을 작성분이 없다.
    // 창 포커스로 React Query 재조회를 유발한다 — 페이지를 떠나지 않는 유일한 트리거다.
    await page.evaluate(() => {
      window.dispatchEvent(new Event('focus'))
    })

    await expect(page.getByRole('dialog', { name: loginPageStrings.heading })).toBeVisible()
    expect(page.url()).toContain('/issues')
    expect(page.url()).not.toContain('/login')
  })
})
