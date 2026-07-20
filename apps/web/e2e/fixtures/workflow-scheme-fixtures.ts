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

import { loginAsAlice } from './auth-fixtures'

export { loginAsAlice }

// ─────────────────────────────────────────────────────────────────────────────
// SYSTEM_ADMIN 로그인 헬퍼 — /admin/workflow-schemes 3라우트 가드 회귀 수정
// (FR-UX-06 PR13 Task 9. audit-logs.spec.ts loginAsSystemAdmin 패턴 재사용)
//
// PR13 Task 8이 /admin/workflow-schemes · /new · /$schemeKey 라우트에
// SYSTEM_ADMIN 가드(composeGuards(requireAuth, requireSystemAdmin, ...))를 추가했다.
// 기존 loginAsAlice(비-admin, isSystemAdmin:false 기본값)로는 이 라우트에 도달하면
// /dashboard로 redirect되어 아래 4개 spec이 깨진다. SYSTEM_ADMIN alice로 로그인해
// 올바른 posture(admin 전용 라우트는 admin으로 검증)를 회복한다.
//   - workflow-scheme-crud.spec.ts
//   - workflow-scheme-mappings.spec.ts
//   - workflow-scheme-standard-protect.spec.ts
//   - workflow-scheme-in-use-modal.spec.ts
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

/**
 * SYSTEM_ADMIN alice로 로그인한다.
 *
 * addInitScript로 LS_IS_SYSTEM_ADMIN='true'를 먼저 심은 뒤 로그인한다.
 * 로그인 중 whoami 응답이 isSystemAdmin:true로 반환되어 requireSystemAdmin 가드를 통과한다.
 *
 * @param page Playwright Page 객체
 */
export async function loginAsSystemAdmin(page: Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_IS_SYSTEM_ADMIN)
  await loginAsAlice(page)
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
