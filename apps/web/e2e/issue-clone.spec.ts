// FR-IS-06 이슈 클론 E2E — S1 happy path + S2 includeAssignee 토글 + S3 취소/검증
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: dialog 컨테이너 한정 + exact:true
//   - msw-mutation-stateful-refetch: cloneIssueHandler가 createdIssues에 영속해야
//     navigate 후 GET 단건에서 클론본 조회 가능 (구현에서 보장됨)
//   - zod-v4-uuid-fixture-strictness: loginAsAlice 재사용 (기존 v4 UUID 정합 보장)
//
// 격리 가정. 각 테스트는 독립 브라우저 컨텍스트 → MSW worker 모듈 상태 격리.
// ATLAS-1 fixture: assigneeId=null(미할당), summary='첫 번째 이슈 — 로그인 페이지 구현'
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — i18n 정본에서 파생
// ─────────────────────────────────────────────────────────────────────────────

/** 클론 버튼 aria-label (data-testid="issue-clone" 요소의 텍스트) */
const CLONE_BUTTON_LABEL = i18nLabels.issueDetail.cloneButton
/** Dialog 제목 */
const CLONE_DIALOG_TITLE = i18nLabels.issueDetail.cloneDialogTitle
/** 담당자 포함 체크박스 aria-label */
const INCLUDE_ASSIGNEE_LABEL = i18nLabels.issueDetail.cloneIncludeAssigneeLabel
/** 새 이슈 제목 입력 aria-label */
const SUMMARY_OVERRIDE_LABEL = i18nLabels.issueDetail.cloneSummaryOverrideLabel
/** 클론 생성 버튼 텍스트 */
const SUBMIT_BUTTON_LABEL = i18nLabels.issueDetail.cloneSubmitButton
/** 취소 버튼 텍스트 */
const CANCEL_BUTTON_LABEL = i18nLabels.issueDetail.cloneCancelButton

test.describe('FR-IS-06 이슈 클론 (CloneIssueDialog)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건 (storageState 대신 loginAsAlice 헬퍼 재사용 — 기존 패턴 일관)
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 Happy Path — 새 제목 입력 후 클론 생성 → 새 이슈로 이동
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입
   *         메타패널에 "이슈 클론" 버튼 노출 확인
   * When    "이슈 클론" 버튼 클릭 → Dialog 열림 확인
   *         includeAssignee 체크박스 기본 체크 확인
   *         summaryOverride 에 새 제목 입력
   *         "클론 생성" 버튼 클릭
   * Then    새 이슈 URL (/issues/ATLAS-N) 으로 navigate
   *         새 이슈 상세 헤딩에 입력한 제목 표시
   */
  test('S1 happy — 새 제목 입력 후 클론 생성 시 새 이슈로 이동', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    // Given. 메타패널의 "이슈 클론" 버튼 확인
    // data-testid="issue-clone" 으로 정확히 타깃 — 같은 텍스트 버튼이 여러 곳 노출될 때 strict mode 방지
    const cloneButton = page.getByTestId('issue-clone')
    await expect(cloneButton).toBeVisible()
    await expect(cloneButton).toHaveText(CLONE_BUTTON_LABEL)

    // When. 클론 버튼 클릭 → Dialog 열림
    await cloneButton.click()

    // Then. Dialog 열림 확인 (Dialog 컨테이너를 role=dialog 로 한정)
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText(CLONE_DIALOG_TITLE)).toBeVisible()

    // Then. includeAssignee 체크박스 기본 체크 확인
    const includeAssigneeCheckbox = dialog.getByRole('checkbox', {
      name: INCLUDE_ASSIGNEE_LABEL,
    })
    await expect(includeAssigneeCheckbox).toBeVisible()
    await expect(includeAssigneeCheckbox).toBeChecked()

    // When. summaryOverride 입력 — dialog 컨테이너 한정 (strict mode 방지)
    const summaryInput = dialog.getByRole('textbox', { name: SUMMARY_OVERRIDE_LABEL })
    await expect(summaryInput).toBeVisible()
    const newSummary = 'S1 클론 테스트용 새 제목'
    await summaryInput.fill(newSummary)

    // When. 클론 생성 버튼 클릭 — dialog 컨테이너 한정 (strict mode 방지)
    const submitButton = dialog.getByRole('button', { name: SUBMIT_BUTTON_LABEL, exact: true })
    await expect(submitButton).toBeVisible()
    await submitButton.click()

    // Then. 새 이슈 URL(/issues/ATLAS-N)로 navigate
    // MSW cloneIssueHandler: ATLAS-1 기준 최대번호+1 → ATLAS-6
    await page.waitForURL(/\/issues\/ATLAS-\d+$/)

    // Then. 새 이슈 헤딩에 입력한 제목 표시
    await expect(page.getByRole('heading', { level: 1, name: newSummary })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 includeAssignee 토글 — 체크 해제 후 클론 시 담당자 없이 생성
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(assigneeId=null) 이슈 상세 페이지 진입
   *         Dialog 열림 — includeAssignee 기본 체크 확인
   * When    includeAssignee 체크박스 해제 → 체크 해제 확인
   *         "클론 생성" 버튼 클릭
   * Then    새 이슈 URL로 navigate
   *         새 이슈 담당자 섹션에 "미지정" 표시 (assigneeId=null 유지)
   *
   * 참고. ATLAS-1 원본의 assigneeId 가 이미 null이므로 includeAssignee=false 여도
   *       결과는 동일(미지정). 체크박스 해제→재체크 UI 반응성 + 클론 후 담당자 없음
   *       두 가지를 함께 검증한다.
   */
  test('S2 includeAssignee 해제 — 체크박스 토글 후 클론 시 새 이슈 담당자 미지정', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const cloneButton = page.getByTestId('issue-clone')
    await expect(cloneButton).toBeVisible()

    // When. 클론 버튼 클릭 → Dialog 열림
    await cloneButton.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // Given. 기본 체크 확인
    const includeAssigneeCheckbox = dialog.getByRole('checkbox', {
      name: INCLUDE_ASSIGNEE_LABEL,
    })
    await expect(includeAssigneeCheckbox).toBeChecked()

    // When. 체크박스 해제
    await includeAssigneeCheckbox.uncheck()
    await expect(includeAssigneeCheckbox).not.toBeChecked()

    // When. 재체크 후 재해제 (UI 반응성 검증)
    await includeAssigneeCheckbox.check()
    await expect(includeAssigneeCheckbox).toBeChecked()
    await includeAssigneeCheckbox.uncheck()
    await expect(includeAssigneeCheckbox).not.toBeChecked()

    // When. summaryOverride 없이 클론 생성 (원본 제목 사용 경로)
    const submitButton = dialog.getByRole('button', { name: SUBMIT_BUTTON_LABEL, exact: true })
    await submitButton.click()

    // Then. 새 이슈로 navigate
    await page.waitForURL(/\/issues\/ATLAS-\d+$/)

    // Then. 담당자 섹션에 "미지정" 표시 (assigneeId=null)
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()
    const currentName = assigneeSection.getByTestId('assignee-current-name')
    await expect(currentName).toBeVisible()
    await expect(currentName).toHaveText(i18nLabels.issueDetail.assigneeUnassigned)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3a Dialog 취소 — 취소 버튼 클릭 시 Dialog 닫힘, 이슈 페이지 유지
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 상세 페이지 진입
   *         Dialog 열림
   * When    "취소" 버튼 클릭
   * Then    Dialog 닫힘 (dialog role 요소 사라짐)
   *         이슈 상세 페이지 URL 유지 (/issues/ATLAS-1)
   */
  test('S3a 취소 — 취소 버튼 클릭 시 Dialog 닫힘, 이슈 페이지 유지', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const cloneButton = page.getByTestId('issue-clone')
    await cloneButton.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // When. 취소 버튼 클릭 — dialog 컨테이너 한정 (strict mode 방지)
    const cancelButton = dialog.getByRole('button', { name: CANCEL_BUTTON_LABEL, exact: true })
    await expect(cancelButton).toBeVisible()
    await cancelButton.click()

    // Then. Dialog 닫힘
    await expect(dialog).not.toBeVisible()

    // Then. 이슈 상세 페이지 유지
    await expect(page).toHaveURL(/\/issues\/ATLAS-1$/)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3b summaryOverride maxLength 255 — 255자 이하 허용, 256자째 입력 차단
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 상세 페이지 진입, Dialog 열림
   * When    summaryOverride 입력란에 255자 문자열 입력
   * Then    입력값 길이 255자 (maxLength 이하 — 허용)
   * When    이어서 1자 더 타이핑
   * Then    입력값 길이 여전히 255자 (maxLength=255 로 차단됨)
   */
  test('S3b summaryOverride maxLength 255 — 255자 허용, 256자째 차단', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const cloneButton = page.getByTestId('issue-clone')
    await cloneButton.click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // When. 255자 입력
    const summaryInput = dialog.getByRole('textbox', { name: SUMMARY_OVERRIDE_LABEL })
    await expect(summaryInput).toBeVisible()
    const text255 = 'a'.repeat(255)
    await summaryInput.fill(text255)

    // Then. 255자 허용
    await expect(summaryInput).toHaveValue(text255)

    // When. 이어서 1자 더 타이핑 시도 (type은 delay:null — PR #35 교훈, long-string timeout 방지)
    await summaryInput.pressSequentially('b', { delay: 0 })

    // Then. maxLength=255 로 차단 — 여전히 255자
    const value = await summaryInput.inputValue()
    expect(value.length).toBe(255)
  })
})
