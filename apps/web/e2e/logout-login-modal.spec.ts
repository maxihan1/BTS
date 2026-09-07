// 로그아웃 → 로그인 모달이 실제로 뜨는가 (FR-AU-01 · Maxi 보고 2026-09-07)
//
// ## 이 스펙이 존재하는 이유
//
// 이 결함은 **2026-08 부터 저장소에 기록돼 있었다.** `start-page.spec.ts` 헤더가
// 「로그아웃 후 navigate 가 발생하지 않는다(history.pushState 호출 자체가 없음 — 직접 계측해
// 확인)」이라 적고, `active-project.spec.ts` 와 함께 **popstate 를 수동 재발행하는 우회**로
// 로그인 폼에 도달한다. 두 스펙 모두 그 우회를 「테스트 전용이며 실제 결함을 고치지 않는다」고
// 명시했다. 즉 **우회가 결함을 가린 채 초록을 유지**했다.
//
// 🛑 그래서 이 스펙은 우회를 쓰지 않는다. 로그아웃 버튼을 누르고 **그 뒤에 일어나는 일만**
//    잰다. 여기에 popstate 를 한 줄이라도 넣으면 같은 은폐가 재생산된다.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'
import { loginPageStrings } from '../src/i18n/ko'

test.describe('로그아웃 (FR-AU-01)', () => {
  test('로그아웃하면 /login 으로 이동하고 로그인 모달이 뜬다', async ({ page }) => {
    await loginAsAlice(page)

    // 앵커 — 실제로 로그인된 화면에 있다(부재 단언만으로는 공허하다)
    await expect(page.getByRole('button', { name: /계정 메뉴$/ })).toBeVisible()

    await page.getByRole('button', { name: /계정 메뉴$/ }).click()
    await page.getByRole('menuitem', { name: '로그아웃', exact: true }).click()

    // ① 세션이 지워진다 (이것은 종전에도 동작했다)
    await page.waitForFunction(() => sessionStorage.getItem('bts.auth') === null)

    // ② ★라우트가 실제로 /login 으로 이동한다 — 이것이 빠져 있던 절반이다
    await expect(page).toHaveURL(/\/login$/)

    // ③ ★로그인 모달이 뜬다 — 사용자가 보는 최종 결과
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(
      page.getByRole('heading', { name: loginPageStrings.heading }),
    ).toBeVisible()
  })
})
