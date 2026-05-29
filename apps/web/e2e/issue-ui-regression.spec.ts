// FR-IS-01 D7 E2E-4 회귀 가드 — 인라인 편집 취소 / 다이얼로그 취소 / 빈 summary Zod 검증
import { test, expect } from '@playwright/test'
import { loginAsAlice, createIssueViaUI, i18nLabels } from './fixtures/issue-fixtures'
import { createdIssueFixture } from '../src/mocks/issue-handlers'

test.describe('E2E-4 UI 회귀 가드', () => {
  test('5-1 인라인 편집 취소 버튼 → 원본 summary 유지', async ({ page }) => {
    await loginAsAlice(page)
    const key = await createIssueViaUI(page, 'E2E-4-1 원본 제목')

    // 편집 모드 진입
    await page.getByRole('button', { name: i18nLabels.issueDetail.editTitleButton }).click()
    const titleInput = page.getByLabel(i18nLabels.issueDetail.titleEditLabel)
    await expect(titleInput).toBeVisible()

    // 새 값 입력 후 취소 버튼 클릭 (UI 에 Esc 핸들러 부재 → 명시 취소 버튼 사용)
    await titleInput.fill('이 값은 폐기되어야 함')
    await page.getByRole('button', { name: i18nLabels.issueDetail.cancelButton }).click()

    // 편집 모드 종료 + 원본 summary 유지 검증
    await expect(titleInput).not.toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'E2E-4-1 원본 제목' })).toBeVisible()
    expect(key).toBe(createdIssueFixture.key)
  })

  test('5-2 삭제 다이얼로그 취소 → 이슈 보존', async ({ page }) => {
    await loginAsAlice(page)
    await createIssueViaUI(page, 'E2E-4-2 삭제 취소 검증용')

    // 삭제 버튼 → 다이얼로그 노출
    await page.getByRole('button', { name: i18nLabels.issueDetail.deleteButton }).click()
    await expect(page.getByText(i18nLabels.issueDetail.deleteConfirmMessage)).toBeVisible()

    // 다이얼로그의 취소 버튼 클릭 (issues.$key.tsx:200 — confirmDelete 영역 안 cancelButton)
    // confirm/cancel 두 버튼 모두 i18nLabels.issueDetail.cancelButton 라벨이지만, 다이얼로그 안 외에 다른 cancel 버튼 없으므로 단일 매칭.
    await page.getByRole('button', { name: i18nLabels.issueDetail.cancelButton }).click()

    // 다이얼로그 닫힘 + 이슈 보존 (상세 페이지 유지, summary 그대로)
    await expect(page.getByText(i18nLabels.issueDetail.deleteConfirmMessage)).not.toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'E2E-4-2 삭제 취소 검증용' })).toBeVisible()
    // 메타패널 (IssueMetaPanel) 이 다시 노출됨 — 삭제 버튼 재노출 확인
    await expect(page.getByRole('button', { name: i18nLabels.issueDetail.deleteButton })).toBeVisible()
  })

  test('5-3 빈 summary 제출 → Zod 클라이언트 검증 메시지 + URL 그대로', async ({ page }) => {
    await loginAsAlice(page)

    await page.goto('/issues/new')
    // projectKey 만 입력, summary 빈 채로 제출
    await page.getByLabel(i18nLabels.issueCreate.projectKeyLabel).fill('ATLAS')
    await page.getByRole('button', { name: i18nLabels.issueCreate.submitButton }).click()

    // Zod 클라이언트 검증 메시지 노출 (FormMessage — issueCreateStrings.summaryRequired)
    await expect(page.getByText(i18nLabels.issueCreate.summaryRequired)).toBeVisible()
    // 페이지 URL 그대로 (서버 호출/navigate 0)
    await expect(page).toHaveURL(/\/issues\/new$/)
  })
})
