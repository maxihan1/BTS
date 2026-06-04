// FR-IS-08 이슈 PDF 다운로드 E2E — S1 다운로드 happy path
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: exact:true 사용
//   - download E2E C3 레이스: waitForEvent('download')를 클릭 이전에 셋업
//
// 격리 가정. 각 테스트는 독립 브라우저 컨텍스트 → MSW worker 모듈 상태 격리.
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** PDF 다운로드 대상 이슈 키 — MSW getIssueHandler가 커버하는 안정 fixture */
const TARGET_ISSUE_KEY = 'ATLAS-1'

// ─────────────────────────────────────────────────────────────────────────────
// S1 PDF 다운로드 happy path
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-IS-08 이슈 PDF 다운로드', () => {
  /**
   * Given   alice 로그인 + ATLAS-1 이슈 상세 페이지 진입
   * When    PDF 다운로드 버튼 클릭 (waitForEvent('download')를 클릭 이전에 셋업 — C3 레이스 회피)
   * Then    다운로드 파일명이 'ATLAS-1.pdf'
   */
  test('S1 PDF 다운로드 — PDF 버튼 클릭 시 ATLAS-1.pdf 다운로드', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. ATLAS-1 이슈 상세 페이지 진입
    await page.goto(`/issues/${TARGET_ISSUE_KEY}`)

    // PDF 버튼이 렌더링될 때까지 대기
    const pdfButton = page.getByRole('button', {
      name: i18nLabels.issueDetail.pdfDownloadAriaLabel,
      exact: true,
    })
    await expect(pdfButton).toBeVisible()

    // C3 레이스 회피 — downloadPromise를 클릭 이전에 셋업
    const downloadPromise = page.waitForEvent('download')

    // When. PDF 다운로드 버튼 클릭
    await pdfButton.click()

    // Then. 다운로드 파일명이 '{key}.pdf'
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe(`${TARGET_ISSUE_KEY}.pdf`)
  })
})
