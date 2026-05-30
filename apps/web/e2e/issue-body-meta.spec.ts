// FR-IS-04 D7 E2E — 이슈 본문(Markdown)/우선순위/라벨/환경/영향도 + OCC 회귀 가드
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * ATLAS-1 fixture 초기 상태:
 *   description=null, priority=3(Medium), labels=[], environment=null, impact=null
 *
 * MSW stateful — PATCH 성공 시 issueOverrides 저장, 이후 GET 단건에서 최신 값 반환.
 * 각 test 는 ATLAS-1 의 상태를 변경하므로 테스트 간 격리는 beforeEach goto/reload 로 확보.
 * (격리가 필요한 경우 resetIssueState 를 beforeEach 에서 호출 가능하나, 여기서는
 *  각 시나리오가 독립된 필드를 건드리므로 격리 불필요 — E1→E5 순서 의존 없음)
 */

test.describe('FR-IS-04 이슈 본문/메타 필드 편집 (E1~E6)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건 (storageState 대신 loginAsAlice 헬퍼 재사용 — 기존 패턴 일관)
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E1 본문 작성 → 저장 → Preview 렌더
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(description=null) 이슈 상세 페이지 진입 — 본문 없음 placeholder 노출
   * When    '본문 편집' 버튼 클릭 → Write 탭 textarea 에 Markdown 입력 → '저장' 클릭
   * Then    description-preview-content 에 렌더된 본문 표시 (placeholder 사라짐)
   */
  test('E1 본문 작성 → 저장 → preview 렌더', async ({ page }) => {
    // Given. ATLAS-1 상세 진입 — 본문 없음 상태
    await page.goto('/issues/ATLAS-1')
    const editButton = page.getByRole('button', {
      name: i18nLabels.issueDetail.descriptionEditButton,
    })
    await expect(editButton).toBeVisible()

    // When. 본문 편집 버튼 클릭 → Write 탭 활성화
    await editButton.click()
    const writeTab = page.getByRole('tab', { name: i18nLabels.issueDetail.descriptionWriteTab })
    await expect(writeTab).toBeVisible()
    await writeTab.click()

    // When. Markdown 입력
    const textarea = page.getByRole('textbox')
    await textarea.fill('**테스트 본문** — E1 시나리오')

    // When. 저장 버튼 클릭 (description 섹션 내부로 한정 — strict mode violation 방지)
    const descriptionSection = page.getByTestId('description-section')
    await descriptionSection.getByRole('button', {
      name: i18nLabels.issueDetail.descriptionSaveButton,
    }).click()

    // Then. description-preview-content 에 본문 노출 (refetch 후 반영)
    const previewContent = page.getByTestId('description-preview-content')
    await expect(previewContent).toBeVisible()
    // placeholder 는 사라져야 함
    await expect(page.getByText(i18nLabels.issueDetail.descriptionEmpty)).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2 우선순위 변경
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(priority=3, Medium) 이슈 상세 페이지 진입
   * When    우선순위 셀렉터에서 '1'(가장 높음) 선택
   * Then    즉시 PATCH → refetch 후 셀렉터 현재값이 '1' 로 반영됨
   */
  test('E2 우선순위 변경 → refetch 반영', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')
    const prioritySelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.prioritySelectLabel,
    })
    await expect(prioritySelect).toBeVisible()

    // When. 우선순위 1(가장 높음) 선택
    await prioritySelect.selectOption({ value: '1' })

    // Then. 셀렉터 현재값이 '1' 로 갱신 (PATCH → setQueryData 즉시 반영)
    await expect(prioritySelect).toHaveValue('1')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E3 영향도 설정 + 미지정 disabled
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(impact=null, 미지정) 이슈 상세 페이지 진입
   * When    영향도 셀렉터에서 '1'(높음) 선택
   * Then    즉시 PATCH → refetch 후 셀렉터 현재값이 '1'
   *         '미지정'(impactUnset) 옵션이 disabled 속성을 가짐
   */
  test('E3 영향도 설정 후 미지정 옵션 disabled', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')
    const impactSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.impactSelectLabel,
    })
    await expect(impactSelect).toBeVisible()

    // When. 영향도 1(높음) 선택
    await impactSelect.selectOption({ value: '1' })

    // Then. 셀렉터 현재값이 '1' 로 갱신
    await expect(impactSelect).toHaveValue('1')

    // Then. 미지정(impactUnset) 옵션이 disabled — 한 번 설정 후 되돌리기 불가
    const unsetOption = impactSelect.locator(`option:has-text("${i18nLabels.issueDetail.impactUnset}")`)
    await expect(unsetOption).toBeDisabled()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E4 라벨 추가 → 저장 → 칩 표시
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(labels=[]) 이슈 상세 페이지 진입 — 라벨 없음
   * When    labels-section 에서 '버그' 라벨 입력 + Enter → 저장 클릭
   * Then    refetch 후 '버그' 라벨 칩이 labels-section 에 노출됨
   */
  test('E4 라벨 추가 → 저장 → 칩 표시', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')
    const labelsSection = page.getByTestId('labels-section')
    await expect(labelsSection).toBeVisible()

    // When. 라벨 입력 필드에 '버그' 입력 + Enter
    const labelInput = labelsSection.getByPlaceholder(i18nLabels.issueDetail.labelAddPlaceholder)
    await expect(labelInput).toBeVisible()
    await labelInput.fill('버그')
    await labelInput.press('Enter')

    // When. 라벨 저장 버튼 클릭 (labels-section 안으로 한정)
    await labelsSection.getByRole('button', {
      name: i18nLabels.issueDetail.labelsSaveButton,
    }).click()

    // Then. '버그' 라벨 칩이 labels-section 에 노출됨 (refetch 후 반영)
    await expect(labelsSection.getByText('버그')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E5 환경 저장
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(environment=null) 이슈 상세 페이지 진입
   * When    environment-section 에서 'macOS 14 / Chrome 125' 입력 → 저장 클릭
   * Then    refetch 후 environment-section 에 입력값 표시됨
   */
  test('E5 환경 저장 → refetch 반영', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')
    const envSection = page.getByTestId('environment-section')
    await expect(envSection).toBeVisible()

    // When. 환경 textarea 에 값 입력
    const envInput = envSection.getByPlaceholder(i18nLabels.issueDetail.environmentPlaceholder)
    await expect(envInput).toBeVisible()
    await envInput.fill('macOS 14 / Chrome 125')

    // When. 저장 버튼 클릭 (environment-section 안으로 한정)
    await envSection.getByRole('button', {
      name: i18nLabels.issueDetail.environmentSaveButton,
    }).click()

    // Then. environment-section 에 입력값 표시됨 (refetch 후 반영)
    await expect(envSection.getByText('macOS 14 / Chrome 125')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E6 OCC 409 — 스킵 (issue-edit-conflict.spec.ts 가 커버)
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * 스킵 사유:
   *   - issue-handlers.ts 의 PATCH 409 트리거는 두 가지:
   *     (a) summary === MOCK_CONFLICT_TRIGGER — summary 필드 전용
   *     (b) expectedVersion 불일치 — UI가 항상 현재 version 을 전송하므로 E2E 유도 불가
   *   - 메타필드(priority/impact/labels/environment)에 409 를 유도하려면
   *     issue-handlers.ts 에 별도 트리거 상수가 필요하나, 그 시나리오는
   *     issue-edit-conflict.spec.ts 에서 summary 경로로 이미 검증됨.
   *   - 메타필드별 409 분기는 동일 코드 경로(updateIssueHandler 동일 함수)라
   *     추가 트리거 없이 기존 테스트로 충분히 회귀 방지됨.
   */
  test.skip('E6 메타필드 저장 409 → 충돌 toast (issue-edit-conflict.spec.ts 가 PATCH 409 커버)', () => {
    // OCC 회귀 가드는 e2e/issue-edit-conflict.spec.ts 참조
  })
})
