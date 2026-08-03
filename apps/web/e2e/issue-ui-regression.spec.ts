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
    //
    // ★ exact: true 필수 (FR-UX-11 F8 회귀 봉합).
    // 이전 주석은 "다이얼로그 안 외에 다른 cancel 버튼 없으므로 단일 매칭"이었으나 **거짓이 됐다**.
    // F8 이 제목 텍스트 자체를 인라인 편집 진입 버튼으로 만들면서 그 버튼의 접근성 이름이
    // **이슈 제목 전문**이 됐고, getByRole 의 name 은 기본이 부분일치라
    // 이 테스트의 제목 'E2E-4-2 삭제 취소 검증용' 이 '취소' 에 걸려 strict mode violation 이 났다.
    // 즉 이 화면에서 짧은 라벨('취소'·'저장'·'확인' 등)은 **제목 문자열과 충돌할 수 있다** —
    // 부분일치를 쓰면 이슈 제목이 바뀔 때마다 조용히 깨진다. exact 로 못박는다.
    await page
      .getByRole('button', { name: i18nLabels.issueDetail.cancelButton, exact: true })
      .click()

    // 다이얼로그 닫힘 + 이슈 보존 (상세 페이지 유지, summary 그대로)
    await expect(page.getByText(i18nLabels.issueDetail.deleteConfirmMessage)).not.toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'E2E-4-2 삭제 취소 검증용' })).toBeVisible()
    // 메타패널 (IssueMetaPanel) 이 다시 노출됨 — 삭제 버튼 재노출 확인
    await expect(page.getByRole('button', { name: i18nLabels.issueDetail.deleteButton })).toBeVisible()
  })

  test('5-3 빈 summary 제출 → Zod 클라이언트 검증 메시지 + URL 그대로', async ({ page }) => {
    await loginAsAlice(page)

    await page.goto('/issues/new')
    // FR-UX-09 F2 — 라우트가 모달을 열고, 프로젝트는 셀렉터로 고른다
    await expect(page.getByRole('dialog', { name: '새 이슈 만들기' })).toBeVisible()
    await page
      .getByRole('dialog', { name: '새 이슈 만들기' })
      .getByLabel(i18nLabels.issueCreate.projectKeyLabel)
      .selectOption('ATLAS')
    await page.getByRole('button', { name: i18nLabels.issueCreate.submitButton }).click()

    // Zod 클라이언트 검증 메시지 노출 (FormMessage — issueCreateStrings.summaryRequired)
    await expect(page.getByText(i18nLabels.issueCreate.summaryRequired)).toBeVisible()
    // 페이지 URL 그대로 (서버 호출/navigate 0)
    await expect(page).toHaveURL(/\/issues\/new$/)
  })
})
