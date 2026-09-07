// FR-IS-01 D7 E2E 공통 fixtures — alice 로그인 헬퍼 + i18n 정본 재노출 + 이슈 생성 헬퍼
import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'

import { loginStrings, issueDetailStrings, issueCreateStrings } from '../../src/i18n/ko'
import { createdIssueFixture } from '../../src/mocks/issue-handlers'

/**
 * i18n 정본 재노출 — E2E 셀렉터가 hardcoded string 대신 정본 키 참조 (PR #22 §F4 학습).
 * 라벨 변경 시 E2E 자동 동기화 (single source of truth).
 */
export const i18nLabels = {
  login: loginStrings,
  issueDetail: issueDetailStrings,
  issueCreate: issueCreateStrings,
} as const

export { loginAsAlice } from './auth-fixtures'

/**
 * Bob (dev seed LOCAL provider) 으로 로그인하고 /dashboard 진입까지 완료한다.
 *
 * 단일 화면 폼 (FR-AU-07 적용 이후).
 * bob 은 MEMBER 역할 — SOFT_DELETE 권한 없음, UPDATE 권한 있음.
 *
 * @param page Playwright Page 객체
 */
export async function loginAsBob(page: Page): Promise<void> {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'BTS 로그인' })).toBeVisible()
  const providerSelect = page.getByRole('combobox', { name: loginStrings.providerLabel })
  await expect(providerSelect).toBeVisible()
  await expect(providerSelect).not.toBeDisabled()
  await providerSelect.click()
  await page.getByRole('option', { name: loginStrings.providerLocal, exact: true }).click()
  await page.getByLabel(loginStrings.usernameLabel).fill('bob')
  await page.getByLabel(loginStrings.passwordLabel).fill('password')
  await page.getByRole('button', { name: loginStrings.submitButton, exact: true }).click()
  await page.waitForURL('**/dashboard*')
}

/**
 * /issues/new 폼을 통해 새 이슈를 생성하고 상세 페이지로 이동한 상태로 끝낸다.
 *
 * MSW mock 의 createIssueHandler 는 createdIssueFixture 응답을 stateful 보관한다.
 * 같은 test 안에서 여러 번 호출하면 항상 같은 key (createdIssueFixture.key) 가 반환된다.
 * 키는 createdIssueFixture 에서 단일 진실 원천으로 import — mock fixture 변경 시 자동 동기화.
 *
 * @param page Playwright Page 객체
 * @param summary 입력할 이슈 제목 (기본값: 테스트용 더미)
 * @returns 발급된 이슈 키 (mock fixture 의 key)
 */
export async function createIssueViaUI(
  page: Page,
  summary = 'E2E 테스트용 이슈',
): Promise<string> {
  await page.goto('/issues/new')
  // FR-UX-09 F2 — 라우트가 생성 모달을 열고, 프로젝트는 자유 텍스트가 아니라 셀렉터다
  await page
    .getByRole('dialog', { name: '새 이슈 만들기' })
    .getByLabel(issueCreateStrings.projectKeyLabel)
    .selectOption('ATLAS')
  // ★`{ exact: true }` 는 장식이 아니다. `getByLabel` 은 기본이 **부분 일치**라 「제목」이
  //   리치 에디터 툴바의 제목 수준 드롭다운(`<select aria-label="제목 수준">`)에도 걸린다.
  //   #468 이 생성 다이얼로그에 그 에디터를 넣은 뒤로 이 헬퍼 한 줄이 strict 위반을 내
  //   **19개 테스트가 한꺼번에 죽었다**(2026-09-07 실측). 지우면 그대로 되살아난다.
  //   같은 함정의 선례는 `scrum-board.spec.ts` 가 이미 `exact` 로 막아 두고 있었다.
  await page.getByLabel(issueCreateStrings.summaryLabel, { exact: true }).fill(summary)
  await page.getByRole('button', { name: issueCreateStrings.submitButton }).click()
  // 상세 페이지로 redirect 대기 — mock fixture 의 key 사용 (변경 시 자동 동기화).
  await page.waitForURL(new RegExp(`/issues/${createdIssueFixture.key}$`))
  return createdIssueFixture.key
}
