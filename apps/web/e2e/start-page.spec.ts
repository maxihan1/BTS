// 시작 페이지(FR-PF-02) E2E — 설정 저장 왕복 + 로그아웃/재로그인 목적지 검증
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — MSW 기본값(allow) 사용
//     (playwright.config.ts에 미설정, 브라우저 워커가 정상 동작).
//   - msw-mutation-stateful-refetch: preferences-handlers.ts의 PATCH는 auth-fixtures의
//     AUTH_USERS 객체를 직접 mutate하고, whoamiHandler가 그 값을 그대로 반영한다. 이 store는
//     페이지 하드 리로드(page.goto/reload)마다 모듈이 재평가되어 리셋되므로, PATCH로 저장한
//     startPage 값이 "다음 로그인"에 반영되는지 검증하려면 PATCH 시점부터 재로그인 완료까지
//     하드 리로드 없이(SPA 소프트 네비게이션만으로) 진행해야 한다.
//   - playwright-getbyrole-exact-strict-mode: combobox/option/menuitem 모두 exact:true로 한정.
//   - 데이터 격리 — 각 test는 alice로 새로 로그인(새 브라우저 컨텍스트)해 자기 상태를 스스로
//     설정한다. AUTH_USERS는 페이지 하드 로드마다(모듈 재평가) 초기화되므로 테스트 간 leak이 없다
//     (preferences.spec.ts 선례와 동일).
//
//   ★ 위 회귀는 **해소됐다** (2026-09-07). 이 자리에 「로그아웃이 /login 으로 이동하지 않는다」는
//     실측 보고가 있었고, 그것을 메우는 popstate 우회를 이 스펙과 active-project.spec.ts 가
//     들고 있었다. 두 스펙이 그 우회를 든 채 초록이라 **결함이 1개월 넘게 가려져 있었다** —
//     Maxi 가 「로그아웃해도 로그인 모달이 안 뜬다」로 보고하고서야 드러났다.
//     원인은 `clearSession()` 이 AccountMenu 를 언마운트해 `mutate` 콜백이 버려지는 것이었고,
//     이동 소유권을 `useLogoutMutation` 으로 옮겨 고쳤다(그 KDoc 참조).
//     우회는 아래 logout() 에서 제거했다. **되살리지 말 것.**
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginStrings, loginPageStrings } from '../src/i18n/ko'

const PREFERENCES_HEADING = '환경 설정'
const ACCOUNT_TRIGGER = /계정 메뉴$/
const START_PAGE_COMBOBOX = '시작 페이지'

/**
 * alice로 로그인 폼을 제출한다(단일 화면 폼, `./fixtures/auth-fixtures.ts`의
 * loginAsAlice와 동일한 스텝). 로그인 성공 후 도착 URL은 startPage 설정에 따라 달라지므로
 * 이 헬퍼는 URL을 기다리지 않는다 — 호출자가 기대하는 목적지를 직접 단언해야 한다.
 */
async function submitAliceLogin(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()

  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()

  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
}

/** /settings/preferences로 이동해 페이지 제목이 뜰 때까지 대기한다(preferences.spec.ts 선례). */
async function gotoPreferences(page: Page): Promise<void> {
  await page.goto('/settings/preferences')
  await expect(
    page.getByRole('heading', { name: PREFERENCES_HEADING, exact: true }),
  ).toBeVisible()
}

/**
 * Header 계정 드롭다운에서 로그아웃해 로그인 폼까지 소프트 네비게이션으로 도달한다.
 *
 * 클릭 자체(세션 클리어)는 실제 로그아웃 mutation을 그대로 사용한다. 다만 위 헤더 코멘트에
 * 적은 회귀로 클릭만으로는 `/login`으로 이동하지 않으므로, 세션이 클리어된 뒤 `history.pushState` +
 * `popstate` 재발행으로 라우터가 `/login`을 인식하게 만든다 — TanStack Router의 history
 * 어댑터가 popstate를 리슨하는 것을 이용한 테스트 전용 우회(하드 리로드 아님, `AUTH_USERS`
 * 모듈 상태를 보존한다).
 */
async function logout(page: Page): Promise<void> {
  await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
  await page.getByRole('menuitem', { name: '로그아웃', exact: true }).click()
  await page.waitForFunction(() => sessionStorage.getItem('bts.auth') === null)

  // 🛑 여기 `popstate` 수동 재발행이 있었다. 로그아웃이 `/login` 으로 **이동하지 않던**
  //    결함을 메우는 우회였고, 두 스펙이 그 우회를 든 채 초록이라 결함이 가려져 있었다
  //    (2026-09-07 Maxi 보고로 드러남 → `useLogoutMutation` 이 이동을 소유하도록 고침).
  //    되살리지 말 것 — 되살리는 순간 같은 은폐가 재생산된다.
  //    이동 자체의 판별식은 `e2e/logout-login-modal.spec.ts` 다.
  await expect(page.getByRole('heading', { name: loginPageStrings.heading })).toBeVisible()
}

test.describe('S1 시작 페이지 변경 → 재로그인 목적지 (FR-PF-02)', () => {
  test('Given 시작 페이지=받은 알림함 저장 When 로그아웃 후 재로그인 Then /inbox로 이동한다', async ({
    page,
  }) => {
    // Given. 최초 로그인 — startPage 미설정(기본값 dashboards)이라 /dashboards로 도착한다
    await page.goto('/login')
    await submitAliceLogin(page)
    await expect(page).toHaveURL(/\/dashboards$/)

    await gotoPreferences(page)

    let patchBody: unknown
    page.on('request', (request) => {
      if (request.method() === 'PATCH' && request.url().includes('/api/v1/users/me/preferences')) {
        patchBody = request.postDataJSON()
      }
    })

    // When. "시작 페이지"를 "받은 알림함"으로 변경(선택 즉시 PATCH)
    const startPageCombobox = page.getByRole('combobox', { name: START_PAGE_COMBOBOX, exact: true })
    await startPageCombobox.click()
    await page.getByRole('option', { name: '받은 알림함', exact: true }).click()

    // Then. mutation 진행 중 disabled였던 셀렉터가 다시 활성화될 때까지 대기(PATCH 완료 신호)한
    // 뒤, 정확히 {startPage:'inbox'} 바디로 PATCH가 호출됐는지 확인한다
    await expect(startPageCombobox).toBeEnabled()
    expect(patchBody).toEqual({ startPage: 'inbox' })

    // Then. 저장 왕복 유지 — 다른 설정 페이지(프로필)로 이동했다가 재진입해도 값이 유지된다
    await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
    await page.getByRole('menuitem', { name: '프로필', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/profile$/)

    await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
    await page.getByRole('menuitem', { name: '환경 설정', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/preferences$/)
    await expect(
      page.getByRole('combobox', { name: START_PAGE_COMBOBOX, exact: true }),
    ).toHaveText('받은 알림함')

    // When. 로그아웃 후 alice로 재로그인(소프트 네비게이션 — AUTH_USERS 모듈 상태 보존)
    await logout(page)
    await submitAliceLogin(page)

    // Then. 저장된 startPage='inbox' 매핑에 따라 /inbox로 도착한다(returnTo 없음 — start_page 우선순위)
    await expect(page).toHaveURL(/\/inbox/)
  })
})

test.describe('S2 시작 페이지 기본값 (FR-PF-02)', () => {
  test('Given 시작 페이지 미설정(기본값) When 로그아웃 후 재로그인 Then /dashboards로 이동한다', async ({
    page,
  }) => {
    // Given. 최초 로그인 — startPage 변경 없음(기본값 dashboards 유지)
    await page.goto('/login')
    await submitAliceLogin(page)
    await expect(page).toHaveURL(/\/dashboards$/)

    // When. 로그아웃 후 재로그인(소프트 네비게이션)
    await logout(page)
    await submitAliceLogin(page)

    // Then. 기본값(dashboards) 매핑에 따라 /dashboards로 도착한다
    await expect(page).toHaveURL(/\/dashboards$/)
  })
})
