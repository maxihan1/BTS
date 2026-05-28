// FR-IS-01 D7 E2E 공통 fixtures — alice 로그인 헬퍼 + i18n 정본 재노출 + 이슈 생성 헬퍼
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

import { loginStrings, issueDetailStrings, issueCreateStrings } from '../../src/i18n/ko'

/**
 * i18n 정본 재노출 — E2E 셀렉터가 hardcoded string 대신 정본 키 참조 (PR #22 §F4 학습).
 * 라벨 변경 시 E2E 자동 동기화 (single source of truth).
 */
export const i18nLabels = {
  login: loginStrings,
  issueDetail: issueDetailStrings,
  issueCreate: issueCreateStrings,
} as const

/**
 * Alice (dev seed LOCAL provider) 로 로그인하고 /dashboard 진입까지 완료한다.
 *
 * MSW dev mock 환경 가정 (backend dev 서버 불필요).
 * apps/web/e2e/login-happy-path.spec.ts 의 S1 시나리오 패턴 일관.
 *
 * @param page Playwright Page 객체
 */
export async function loginAsAlice(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  await page.getByLabel(loginStrings.usernameLabel).fill('alice')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton }).click()
  await page.waitForURL('**/dashboard')
}

/**
 * /issues/new 폼을 통해 새 이슈를 생성하고 상세 페이지로 이동한 상태로 끝낸다.
 *
 * MSW mock 의 createIssueHandler 가 createdIssueFixture (ATLAS-42) 응답을 stateful 보관한다.
 * 같은 test 안에서 여러 번 호출하면 항상 같은 key (ATLAS-42) 가 반환된다.
 *
 * @param page Playwright Page 객체
 * @param summary 입력할 이슈 제목 (기본값: 테스트용 더미)
 * @returns 발급된 이슈 키 (현재 mock 은 ATLAS-42 고정)
 */
export async function createIssueViaUI(
  page: Page,
  summary = 'E2E 테스트용 이슈',
): Promise<string> {
  await page.goto('/issues/new')
  await page.getByLabel(issueCreateStrings.projectKeyLabel).fill('ATLAS')
  await page.getByLabel(issueCreateStrings.summaryLabel).fill(summary)
  await page.getByRole('button', { name: issueCreateStrings.submitButton }).click()
  // 상세 페이지로 redirect 대기 — createdIssueFixture.key 'ATLAS-42' 고정
  await page.waitForURL(/\/issues\/ATLAS-42$/)
  return 'ATLAS-42'
}
