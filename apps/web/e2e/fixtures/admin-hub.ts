// 관리 화면 진입 경로 단일 출처 — 진입점이 바뀌어도 소비 spec 이 함께 따라온다 (Jira 패리티 J9)
import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 관리 진입점의 접근 이름 — **즉사 계약 문자열**(`jira-parity-contract.md` §2).
 *
 * 🔒 글자를 바꾸지 마라. `navLabels.adminNav` 와 같은 값이어야 하고, 그 짝은
 * `src/i18n/__tests__/nav-labels.test.ts` 가 잡는다.
 */
export const ADMIN_ENTRY_NAME = '관리 메뉴'

/**
 * 관리 링크들이 모여 있는 컨테이너를 열고 돌려준다.
 *
 * 🛑 **`page.goto('/admin')` 으로 바꾸지 마라.** hard navigation 이 MSW Service Worker 를
 * 재초기화해 시드된 store 가 리셋된다 — `global-permissions.spec.ts`(S1~S5 전량)와
 * `notification-policies.spec.ts` S7 이 정확히 그 이유로 SPA 내부 이동을 요구한다.
 * 이 헬퍼가 존재하는 이유가 그것이다. 진입점이 어디로 옮겨가든 **클릭 경로**를 유지한다.
 *
 * @param page Playwright Page
 * @returns 관리 링크들을 담은 컨테이너 Locator
 */
export async function openAdminLinks(page: Page): Promise<Locator> {
  const nav = page.getByRole('navigation', { name: ADMIN_ENTRY_NAME, exact: true })
  await expect(nav).toBeVisible()
  return nav
}

/**
 * 관리 화면 하나로 이동한다 — 진입점 클릭 → 대상 링크 클릭.
 *
 * @param page Playwright Page
 * @param linkName 대상 링크의 접근 이름(예: `'감사 로그'`). `exact` 로 집는다 —
 *   `'워크플로우 관리'` 와 `'워크플로우 스킴'` 처럼 접두를 공유하는 쌍이 있다
 */
export async function gotoAdminPage(page: Page, linkName: string): Promise<void> {
  const container = await openAdminLinks(page)
  const link = container.getByRole('link', { name: linkName, exact: true })
  await expect(link).toBeVisible()
  await link.click()
}

/**
 * 관리 진입점이 **보이지 않는지** 확인한다 — 비관리자 게이팅 단언용.
 *
 * @param page Playwright Page
 */
export async function expectAdminEntryHidden(page: Page): Promise<void> {
  await expect(
    page.getByRole('navigation', { name: ADMIN_ENTRY_NAME, exact: true }),
  ).not.toBeVisible()
}
