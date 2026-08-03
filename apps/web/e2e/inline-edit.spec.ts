// FR-UX-11 F8 E2E — 이슈 상세 인라인 편집 (텍스트 클릭 진입 · Enter/Ctrl+Enter 저장 · Esc 취소)
import { test, expect, type Page, type Response } from '@playwright/test'
import { loginAsAlice, createIssueViaUI, i18nLabels } from './fixtures/issue-fixtures'

/**
 * 이 스펙이 검증하는 것 (spec §1 시나리오 번호).
 *   S1 제목 클릭 진입 · S2 제목 Enter 저장 · S3 제목 Esc 취소
 *   S4 본문 클릭 진입 · S5 본문 Ctrl+Enter 저장 · S7 본문 Esc — 변경분 보호
 *
 * 로그인/이슈 진입은 `issue-crud-happy.spec.ts` 와 **같은 헬퍼**(loginAsAlice · createIssueViaUI)를
 * 쓴다. 테스트마다 자기 이슈를 새로 만들므로(MSW 상태는 브라우저 컨텍스트마다 초기화) 격리된다.
 *
 * ★ 선재 동작 주의. 본문은 **저장에 성공해도 편집 모드가 자동으로 닫히지 않는다**
 *   (`issue-body-meta.spec.ts:57` 이 이미 못박아 둔 기존 구현 동작). 그래서 저장 여부를
 *   "편집기가 닫혔는가"로 판정하지 않고 **PATCH 응답 + 재조회 후 값**으로 판정한다.
 */

/** createIssueViaUI 기본 제목 — 복원 검증(S3)의 기준값이라 상수로 고정한다. */
const ORIGINAL_SUMMARY = 'E2E 테스트용 이슈'
/** S2 저장 후 기대 제목. ORIGINAL_SUMMARY 를 **부분 문자열로 포함**한다 → 헤딩 단언은 exact 필수. */
const UPDATED_SUMMARY = 'E2E 테스트용 이슈 (Enter 로 저장됨)'
/** S3 에서 입력했다가 Esc 로 버려질 값. */
const DISCARDED_SUMMARY = '되돌려져야 하는 제목'
/** S5 본문 저장값 — 마크다운 특수문자 없이 두어 preview 대조를 단순화한다. */
const BODY_MARKDOWN = '인라인 편집으로 저장한 본문'
/** S7 에서 확인 없이 사라지면 안 되는 작성분. */
const UNSAVED_BODY_MARKDOWN = '아직 저장 안 한 작성분'

/**
 * 이슈 단건 PATCH 응답을 기다린다.
 *
 * 저장 성공을 화면 상태로만 판정하면 "실제로는 요청이 안 나갔는데 UI 만 바뀐" 가짜 그린을
 * 잡지 못한다(본문은 저장 후에도 편집 모드가 유지되므로 특히 그렇다). 네트워크를 직접 본다.
 * 하위 경로(`/comments` 등)를 삼키지 않도록 endsWith 로 단건 URL 만 매칭한다.
 */
function waitForIssuePatch(page: Page, key: string): Promise<Response> {
  return page.waitForResponse(
    (res) => res.url().endsWith(`/api/v1/issues/${key}`) && res.request().method() === 'PATCH',
  )
}

test.describe('FR-UX-11 F8 인라인 편집 (이슈 상세)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 — issue-crud-happy 와 동일 헬퍼
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 + S2. 제목 클릭 진입 → Enter 저장
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given  수정 권한이 있는 이슈 상세 — 제목 텍스트 자체가 편집 진입면(button)이다
   * When   제목 텍스트를 클릭해 입력창을 열고(S1) 값을 바꾼 뒤 `Enter` 를 누른다(S2)
   * Then   저장 버튼을 누르지 않았는데도 PATCH 200 이 나가고 헤딩이 새 제목으로 바뀐다
   */
  test('S1·S2 제목 텍스트를 클릭해 열고 Enter 로 저장한다', async ({ page }) => {
    // Given. 새 이슈를 만들고 그 상세로 진입 (테스트마다 자기 데이터)
    const key = await createIssueViaUI(page, ORIGINAL_SUMMARY)
    const heading = page.getByRole('heading', { level: 1, name: ORIGINAL_SUMMARY, exact: true })
    await expect(heading).toBeVisible()

    // When 1 (S1). 제목 텍스트 클릭 — `✎ 제목 수정` 버튼을 거치지 않는 새 진입 경로.
    // 헤딩 안으로 한정해 찾는다(같은 문자열이 다른 곳에 있어도 strict mode 로 깨지지 않게).
    await heading.getByRole('button', { name: ORIGINAL_SUMMARY, exact: true }).click()
    const titleInput = page.getByLabel(i18nLabels.issueDetail.titleEditLabel)
    await expect(titleInput).toBeVisible()
    await expect(titleInput).toHaveValue(ORIGINAL_SUMMARY)

    // When 2 (S2). 값 변경 후 Enter — 저장 버튼은 누르지 않는다
    const patch = waitForIssuePatch(page, key)
    await titleInput.fill(UPDATED_SUMMARY)
    await titleInput.press('Enter')

    // Then. 실제 PATCH 가 200 으로 완료되고 읽기 모드 헤딩이 새 값으로 갱신된다
    expect((await patch).status()).toBe(200)
    await expect(
      page.getByRole('heading', { level: 1, name: UPDATED_SUMMARY, exact: true }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. 제목 Esc 취소 → 원본 복원
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given  제목 편집 중이고 값을 바꿔 둔 상태
   * When   `Esc` 를 누른다
   * Then   입력창이 닫히고 **원래 제목**이 그대로 남는다 (편집분은 버려진다)
   */
  test('S3 제목 편집 중 Esc 가 취소하고 원본 제목이 복원된다', async ({ page }) => {
    // Given. 이슈 상세 진입 → 제목 클릭으로 편집 열기 → 값 변경
    await createIssueViaUI(page, ORIGINAL_SUMMARY)
    await page
      .getByRole('heading', { level: 1, name: ORIGINAL_SUMMARY, exact: true })
      .getByRole('button', { name: ORIGINAL_SUMMARY, exact: true })
      .click()
    const titleInput = page.getByLabel(i18nLabels.issueDetail.titleEditLabel)
    await titleInput.fill(DISCARDED_SUMMARY)
    await expect(titleInput).toHaveValue(DISCARDED_SUMMARY)

    // When. Esc
    await titleInput.press('Escape')

    // Then. 편집 입력창이 사라지고 원본 제목이 남는다.
    // ★ exact 필수 — 부분일치면 'E2E 테스트용 이슈 (…)' 같은 저장된 제목도 통과해 가짜 그린이 된다.
    await expect(titleInput).toBeHidden()
    await expect(
      page.getByRole('heading', { level: 1, name: ORIGINAL_SUMMARY, exact: true }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 + S5. 본문 클릭 진입 → Ctrl+Enter 저장
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given  본문이 비어 있는 이슈 상세 — placeholder 자체도 클릭 진입면이다
   * When   본문 영역을 클릭해 편집기를 열고(S4) 내용을 입력한 뒤 `Ctrl+Enter` 를 누른다(S5)
   * Then   PATCH 200 + 요청 본문에 입력값이 실려 나가고, 읽기 모드 preview 에 그 값이 렌더된다
   */
  test('S4·S5 본문 텍스트를 클릭해 열고 Ctrl+Enter 로 저장한다', async ({ page }) => {
    // Given. 본문 없음 상태의 새 이슈
    const key = await createIssueViaUI(page, ORIGINAL_SUMMARY)
    const emptyBody = page.getByText(i18nLabels.issueDetail.descriptionEmpty)
    await expect(emptyBody).toBeVisible()

    // When 1 (S4). 본문 클릭 — `본문 편집` 버튼을 거치지 않는 새 진입 경로
    await emptyBody.click()
    const bodyTextarea = page.getByRole('textbox', {
      name: i18nLabels.issueDetail.descriptionEditButton,
    })
    await expect(bodyTextarea).toBeVisible()

    // When 2 (S5). 내용 입력 후 Ctrl+Enter — 저장 버튼은 누르지 않는다
    const patch = waitForIssuePatch(page, key)
    await bodyTextarea.fill(BODY_MARKDOWN)
    await bodyTextarea.press('Control+Enter')

    // Then 1. 요청이 실제로 나갔고 입력값이 실려 있다 (UI 만 바뀐 가짜 그린 차단)
    const patchResponse = await patch
    expect(patchResponse.status()).toBe(200)
    const patchBody: unknown = patchResponse.request().postDataJSON()
    expect(patchBody).toMatchObject({ description: BODY_MARKDOWN })

    // Then 2. 저장 성공 후에도 편집 모드는 자동으로 닫히지 않는다(선재 동작 — issue-body-meta.spec.ts:57).
    // '취소'로 읽기 모드로 전환해 **재조회된 값**이 화면에 반영됐는지 확인한다.
    // 저장/취소 버튼은 화면에 3쌍(본문·환경·라벨)이라 tablist 를 직접 자식으로 가진 본문 편집기로 한정한다.
    const descriptionEditor = page.locator('div:has(> [role="tablist"])')
    await descriptionEditor
      .getByRole('button', { name: i18nLabels.issueDetail.descriptionCancelButton })
      .click()
    await expect(page.getByTestId('description-preview-content')).toContainText(BODY_MARKDOWN)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7. 본문 Esc — 변경분이 있으면 확인을 거친다 (편차 D-1)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given  본문 편집 중이고 내용을 바꿔 둔 상태
   * When   `Esc` 를 누른다
   * Then   작성분이 즉시 사라지지 않고 확인 패널(`편집 그만두기` / `계속 편집`)이 뜨며,
   *        `계속 편집` 을 고르면 편집 모드와 작성분이 그대로 유지된다
   */
  test('S7 본문 Esc 는 변경분이 있으면 확인 패널을 띄우고 편집 모드를 유지한다', async ({
    page,
  }) => {
    // Given. 본문 클릭으로 편집 진입 → 내용 입력 (원본은 빈 값이므로 변경분 있음)
    await createIssueViaUI(page, ORIGINAL_SUMMARY)
    await page.getByText(i18nLabels.issueDetail.descriptionEmpty).click()
    const bodyTextarea = page.getByRole('textbox', {
      name: i18nLabels.issueDetail.descriptionEditButton,
    })
    await bodyTextarea.fill(UNSAVED_BODY_MARKDOWN)

    // When. Esc
    await bodyTextarea.press('Escape')

    // Then 1. 확인 패널의 두 선택지가 노출되고 작성분은 그대로다 (Jira 는 확인 없이 버린다 — 의도적 편차)
    const discardButton = page.getByRole('button', {
      name: i18nLabels.issueDetail.descriptionDiscardConfirmButton,
    })
    const keepEditingButton = page.getByRole('button', {
      name: i18nLabels.issueDetail.descriptionDiscardCancelButton,
    })
    await expect(discardButton).toBeVisible()
    await expect(keepEditingButton).toBeVisible()
    await expect(bodyTextarea).toHaveValue(UNSAVED_BODY_MARKDOWN)

    // Then 2. '계속 편집'을 고르면 확인 패널만 닫히고 편집 모드·작성분은 살아남는다
    await keepEditingButton.click()
    await expect(discardButton).toBeHidden()
    await expect(bodyTextarea).toHaveValue(UNSAVED_BODY_MARKDOWN)
  })
})
