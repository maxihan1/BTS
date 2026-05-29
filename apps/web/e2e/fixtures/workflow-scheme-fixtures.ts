// FR-WF-02 D7 E2E 공통 fixtures — alice 로그인 + navigate helpers + i18n 정본 재노출
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

import { loginStrings, loginPageStrings } from '../../src/i18n/ko'
import { workflowSchemeLabels } from '../../src/i18n/workflow-scheme-labels'

/**
 * i18n 정본 재노출 — E2E 셀렉터가 hardcoded string 대신 정본 키 참조.
 *
 * PR #22 §F4 학습 — 라벨 변경 시 E2E 자동 동기화 (single source of truth).
 * 영역 한정: login / loginPage / workflowScheme 만 포함.
 */
export const i18nLabels = {
  login: loginStrings,
  loginPage: loginPageStrings,
  workflowScheme: workflowSchemeLabels,
} as const

/**
 * Alice (dev seed LOCAL provider) 로 로그인하고 /dashboard 진입까지 완료한다.
 *
 * MSW dev mock 환경 가정 (backend dev 서버 불필요).
 * issue-fixtures.ts 의 loginAsAlice 와 동등 패턴 — 프로젝트 내 일관성 유지.
 *
 * @param page Playwright Page 객체
 */
export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(
    page.getByRole('heading', { name: loginPageStrings.heading }),
  ).toBeVisible()
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton }).click()
  await page.waitForURL('**/dashboard')
}

/**
 * /admin/workflow-schemes 진입하고 사이드바 nav 가시 검증까지 완료한다.
 *
 * WorkflowSchemeSidebar nav[aria-label] 을 가시 기준으로 사용.
 *
 * @param page Playwright Page 객체
 */
export async function navigateToSchemeList(page: Page): Promise<void> {
  await page.goto('/admin/workflow-schemes')
  await expect(
    page.getByRole('navigation', { name: workflowSchemeLabels.sidebar.nav }),
  ).toBeVisible()
}

/**
 * /admin/workflow-schemes/{schemeKey} 진입하고 사이드바 nav 가시 검증까지 완료한다.
 *
 * @param page Playwright Page 객체
 * @param schemeKey 이동할 스킴 키 (예: 'DEFAULT', 'CUSTOM-01')
 */
export async function navigateToSchemeDetail(
  page: Page,
  schemeKey: string,
): Promise<void> {
  await page.goto(`/admin/workflow-schemes/${schemeKey}`)
  await expect(
    page.getByRole('navigation', { name: workflowSchemeLabels.sidebar.nav }),
  ).toBeVisible()
}

/**
 * /projects/{projectKey}/settings/workflow-scheme 진입하고 페이지 heading 가시 검증까지 완료한다.
 *
 * @param page Playwright Page 객체
 * @param projectKey 이동할 프로젝트 키 (예: 'ATLAS')
 */
export async function navigateToProjectAssignment(
  page: Page,
  projectKey: string,
): Promise<void> {
  await page.goto(`/projects/${projectKey}/settings/workflow-scheme`)
  await expect(
    page.getByRole('heading', { name: workflowSchemeLabels.assignment.pageHeading }),
  ).toBeVisible()
}
