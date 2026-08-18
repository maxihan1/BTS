// FR-IS-02 D7 이슈 타입 변경 E2E — S1 happy path + S2 셀렉터 옵션 + S3 아이콘
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * ATLAS-1 fixture 초기 타입: typeId=1, typeKey='bug', typeName='버그'
 * PATCH 핸들러: typeId 변경 → 응답에 갱신된 typeKey/typeName 반환
 * 이슈 상세 페이지(issues.$key.tsx): PATCH onSuccess에서 setQueryData 즉시 반영 + invalidateQueries
 *
 * 5 표준 이슈 타입 (issue-type-fixtures):
 *   id=1 '버그', id=2 '스토리', id=3 '작업', id=4 '에픽', id=5 '하위 작업'
 */

test.describe('FR-IS-02 이슈 타입 변경 (IssueMetaPanel > IssueTypeSelect)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 Happy Path — 버그 → 스토리 타입 변경
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(typeId=1, typeName='버그') 이슈 상세 페이지 진입
   * When    유형 셀렉터에서 '스토리'(typeId=2) 선택
   * Then    issue-type-name 이 '스토리' 로 갱신됨
   *         IssueTypeIcon aria-label 이 '스토리' 로 갱신됨 (S3 포함)
   */
  test('S1 happy — 버그 → 스토리 선택 시 issue-type-name 이 스토리로 갱신', async ({ page }) => {
    // Given. ATLAS-1 상세 진입 — 현재 타입 '버그' 확인
    await page.goto('/issues/ATLAS-1')
    const typeName = page.getByTestId('issue-type-name')
    await expect(typeName).toBeVisible()
    await expect(typeName).toHaveText('버그')

    // S3 — 초기 아이콘이 '버그' aria-label 로 노출됨
    await expect(page.getByRole('img', { name: '버그' })).toBeVisible()

    // When. 유형 셀렉터(typeSelectLabel)에서 '스토리' 선택
    // 주의: 같은 메타패널에 transitionSelectLabel 셀렉터도 있으므로 name 으로 정확히 타깃
    const typeSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.typeSelectLabel,
    })
    await expect(typeSelect).toBeVisible()
    await typeSelect.selectOption({ value: '2' })

    // Then. issue-type-name 이 '스토리' 로 갱신 (PATCH 응답 → setQueryData 즉시 반영)
    await expect(typeName).toHaveText('스토리')

    // S3 — 아이콘 aria-label 도 '스토리' 로 갱신됨
    await expect(page.getByRole('img', { name: '스토리' })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 셀렉터 옵션 — 활성 표준 5종 옵션 존재 단언 (전환 셀렉터와 구별)
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입
   * When    유형 셀렉터 옵션 목록 확인
   * Then    표준 5종(버그·스토리·작업·에픽·하위 작업) 옵션이 value/label 로 존재
   *         전환 셀렉터(transitionSelectLabel) 옵션과 혼용되지 않음
   */
  test('S2 셀렉터 옵션 — 유형 셀렉터에 표준 5종 옵션 존재', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const typeSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.typeSelectLabel,
    })
    await expect(typeSelect).toBeVisible()

    // Then. 5 표준 이슈 타입 옵션이 value(typeId) 와 label(typeName) 로 모두 존재
    await expect(typeSelect.locator('option[value="1"]')).toHaveCount(1)
    await expect(typeSelect.locator('option[value="1"]')).toContainText('버그')

    await expect(typeSelect.locator('option[value="2"]')).toHaveCount(1)
    await expect(typeSelect.locator('option[value="2"]')).toContainText('스토리')

    await expect(typeSelect.locator('option[value="3"]')).toHaveCount(1)
    await expect(typeSelect.locator('option[value="3"]')).toContainText('작업')

    await expect(typeSelect.locator('option[value="4"]')).toHaveCount(1)
    await expect(typeSelect.locator('option[value="4"]')).toContainText('에픽')

    await expect(typeSelect.locator('option[value="5"]')).toHaveCount(1)
    await expect(typeSelect.locator('option[value="5"]')).toContainText('하위 작업')

    // Then. 전환 셀렉터(transitionSelectLabel)는 별도 combobox — 유형 셀렉터와 혼용 없음
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    // 유형 셀렉터와 전환 셀렉터는 aria-label 이 다른 별개의 combobox
    await expect(typeSelect).not.toHaveAttribute(
      'aria-label',
      i18nLabels.issueDetail.transitionSelectLabel,
    )
  })
})
