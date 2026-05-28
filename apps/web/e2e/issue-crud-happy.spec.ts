// FR-IS-01 D7 E2E-1 Happy Path — 이슈 생명주기 (생성 → 조회 → 인라인 수정 → 소프트 삭제 → 목록 제외)
import { test, expect } from '@playwright/test'
import { loginAsAlice, createIssueViaUI, i18nLabels } from './fixtures/issue-fixtures'

test('E2E-1 이슈 생명주기 — 생성 → 조회 → 수정 → 소프트 삭제 → 목록에서 제외', async ({ page }) => {
  // ── Given. alice 로그인 ──────────────────────────────────────────────────────
  await loginAsAlice(page)

  // ── When 1. /issues/new 에서 이슈 생성 ──────────────────────────────────────
  const summary = 'E2E 테스트용 이슈'
  const key = await createIssueViaUI(page, summary)

  // ── Then 1. 자동 발급 키 (ATLAS-42) 로 상세 페이지 진입, summary 노출 ──────
  await expect(page).toHaveURL(new RegExp(`/issues/${key}$`))
  await expect(page.getByRole('heading', { level: 1, name: summary })).toBeVisible()

  // ── When 2. 인라인 편집 모드 진입 → 새 값 입력 → 저장 ───────────────────────
  const updatedSummary = 'E2E 테스트용 이슈 (수정됨)'
  await page.getByRole('button', { name: i18nLabels.issueDetail.editTitleButton }).click()
  const titleInput = page.getByLabel(i18nLabels.issueDetail.titleEditLabel)
  await expect(titleInput).toBeVisible()
  await titleInput.fill(updatedSummary)
  await page.getByRole('button', { name: i18nLabels.issueDetail.saveButton }).click()

  // ── Then 2. 새 값으로 상세 헤딩 동기 (서버 응답 후) ─────────────────────────
  await expect(page.getByRole('heading', { level: 1, name: updatedSummary })).toBeVisible()

  // ── When 3. destructive 삭제 버튼 → 확인 다이얼로그 → 확인 클릭 ─────────────
  await page.getByRole('button', { name: i18nLabels.issueDetail.deleteButton }).click()
  await expect(
    page.getByText(i18nLabels.issueDetail.deleteConfirmMessage),
  ).toBeVisible()
  await page.getByRole('button', { name: i18nLabels.issueDetail.confirmButton }).click()

  // ── Then 3. /issues 목록 navigate + 해당 키 목록에서 제외 (gap-H) ──────────
  await page.waitForURL(/\/issues$/)
  await expect(page.getByRole('list', { name: '이슈 목록' })).toBeVisible()
  // 삭제된 이슈 키는 aria-label 매칭에서 제외되어야 함 (mock 핸들러 stateful 필터링).
  await expect(page.getByLabel(key)).toHaveCount(0)
})
