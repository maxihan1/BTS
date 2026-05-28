// FR-IS-01 D7 E2E-3 미존재 키 — 404 에러 UI 노출 검증 (gap-G)
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

test('E2E-3 미존재 이슈 키 — /issues/ATLAS-99999 → role=alert + notFound 메시지', async ({ page }) => {
  // Given. alice 로그인
  await loginAsAlice(page)

  // When. mock 의 issueFixtureMap 에 없는 키 (ATLAS-99999) 접근 → MSW 가 404 응답
  await page.goto('/issues/ATLAS-99999')

  // Then. role="alert" 영역 노출 + issueDetailStrings.notFound 메시지 정합 (issues.$key.tsx:77).
  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(i18nLabels.issueDetail.notFound)

  // 상세 본문 (heading level=1 — summary) 은 렌더되지 않아야 함.
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(0)
})
