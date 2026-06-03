// FR-IS-07 종료 결의안 E2E — S1 단건 DONE 전이 + S2 미선택 거부 + S4 resolution pre-fill + S5 일괄 DONE 전이
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: 전이 핸들러가 resolutionId를 stateful 보관해야 refetch 후 롤백 방지
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: 컨테이너 한정 + exact:true
//   - zod-v4-uuid-fixture-strictness: seed UUID 재사용 (v4 형식 필수)
//   - e2e-fixture-whoami-userid-alignment: alice userId 정합
//
// 격리 가정. 각 테스트는 독립 브라우저 컨텍스트 → MSW worker 모듈 상태 격리.
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'
import { statusLabels } from '../src/i18n/bulk-operation-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — MSW stateful localStorage 플래그
// ─────────────────────────────────────────────────────────────────────────────

/** S4 검증용 — resolution 있는 DONE 이슈로 진입하는 플래그 */
const LS_KEY_RESOLUTION_ISSUE = '__bts_e2e_resolution_issue'

// ─────────────────────────────────────────────────────────────────────────────
// 단건 전이 시나리오
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-IS-07 종료 결의안 — 단건 전이', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 단건 DONE 전이 happy path
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-5(in_review 상태) 이슈 상세 페이지 진입
   * When    전이 셀렉터에서 "Approve"(→ done, DONE 카테고리) 선택
   * Then    Resolution 모달 표시됨
   * When    "Fixed" 선택 후 확인 클릭
   * Then    전이 성공 + 상태 배지가 done으로 갱신됨
   */
  test('S1 단건 DONE 전이 happy — Approve 선택 → 모달 → Fixed 선택 → 상태 done으로 갱신', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. ATLAS-5(in_review 상태) 이슈 상세 진입
    await page.goto('/issues/ATLAS-5')
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('in_review')

    // When. 전이 셀렉터에서 "Approve"(→ done, DONE 카테고리) 선택
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    await transitionSelect.selectOption({ label: 'Approve' })

    // Then. Resolution 모달 표시
    const modal = page.getByRole('dialog', { name: '종료 결의안 선택' })
    await expect(modal).toBeVisible()
    await expect(modal.getByText('종료 결의안 선택')).toBeVisible()

    // When. "Fixed" 선택
    const resolutionSelect = modal.getByRole('combobox', { name: '결의안 선택' })
    await resolutionSelect.click()
    await page.getByRole('option', { name: 'Fixed' }).click()

    // When. 확인 버튼 클릭
    await modal.getByRole('button', { name: '확인' }).click()

    // Then. 모달 닫힘
    await expect(modal).toHaveCount(0)

    // Then. 상태 배지가 done으로 갱신 (MSW stateful + invalidateQueries refetch)
    await expect(badge).toContainText('done')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 resolution 미선택 거부
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-5(in_review 상태) 이슈 상세 페이지 진입
   * When    전이 셀렉터에서 "Approve" 선택 → 모달 열림
   * Then    resolution 미선택 상태에서 확인 버튼 비활성
   */
  test('S2 미선택 거부 — Approve 선택 후 모달에서 resolution 미선택 시 확인 버튼 비활성', async ({ page }) => {
    // Given. alice 로그인 + 이슈 상세 진입
    await loginAsAlice(page)
    await page.goto('/issues/ATLAS-5')
    await expect(page.getByTestId('issue-state-badge')).toContainText('in_review')

    // When. Approve 선택 → 모달 열림
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    await transitionSelect.selectOption({ label: 'Approve' })

    // Then. 모달 표시 + 확인 버튼 비활성 (resolution 미선택)
    const modal = page.getByRole('dialog', { name: '종료 결의안 선택' })
    await expect(modal).toBeVisible()
    await expect(modal.getByRole('button', { name: '확인' })).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 resolution pre-fill — DONE 이슈 재전이 시 기존 resolution이 모달에 pre-fill됨
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-5(in_review 상태, 단건 GET는 done+resolution=Fixed 응답)
   *         (__bts_e2e_resolution_issue='done-with-resolution' 플래그로 MSW 분기)
   *         — 전이 목록은 in_review 기준으로 반환되므로 "Approve"가 존재
   *         — 단건 GET는 done+Fixed 응답 → issue.resolution=Fixed → pre-fill
   * When    전이 셀렉터에서 "Approve" 선택 → Resolution 모달 열림
   * Then    모달에 "Fixed"가 pre-fill로 선택되어 있음 + 확인 버튼 활성
   */
  test('S4 resolution pre-fill — done+Fixed 이슈에서 DONE 전이 선택 시 모달에 Fixed pre-fill', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. done+resolution 이슈 진입 플래그 (addInitScript: loginAsAlice 이후, goto 이전)
    await page.addInitScript((key) => {
      window.localStorage.setItem(key, 'done-with-resolution')
    }, LS_KEY_RESOLUTION_ISSUE)

    // Given. ATLAS-5 이슈 상세 진입 (MSW getIssueHandler가 done+Fixed resolution 응답)
    await page.goto('/issues/ATLAS-5')
    // state badge는 단건 GET 응답 기준 — done 상태 표시
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toContainText('done')

    // When. "Approve" 전이 선택 (전이 목록은 in_review 기준 → Approve 존재)
    // → DONE 카테고리 전이이므로 ResolutionModal 열림
    // → issue.resolution=Fixed이므로 pre-fill
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    await transitionSelect.selectOption({ label: 'Approve' })

    // Then. Resolution 모달 표시
    const modal = page.getByRole('dialog', { name: '종료 결의안 선택' })
    await expect(modal).toBeVisible()

    // Then. 확인 버튼 활성 (pre-fill로 Fixed가 선택됨 → canConfirm=true)
    await expect(modal.getByRole('button', { name: '확인' })).toBeEnabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 일괄 DONE 전이
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-IS-07 종료 결의안 — 일괄 전이', () => {
  /**
   * Given   alice 로그인 + /issues 진입
   * When    ATLAS-5(in_review) 선택 → 일괄 전이 Dialog → DONE 전이("Approve") 선택
   * Then    resolution 드롭다운 표시
   * When    "Fixed" 선택 후 적용
   * Then    일괄 전이 결과 Dialog 완료 + 성공 1
   */
  test('S5 일괄 DONE 전이 — ATLAS-5 선택 → Dialog DONE 전이 → resolution 선택 → 결과 완료', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /issues 진입
    await page.goto('/issues')

    // When. ATLAS-5 개별 선택
    await page.getByTestId('select-ATLAS-5').click()

    // Then. 액션바 "1건 선택됨" 확인
    const actionBar = page.getByTestId('bulk-action-bar')
    await expect(actionBar).toBeVisible()
    await expect(actionBar).toContainText('1건 선택됨')

    // When. 일괄 전이 버튼 클릭
    await actionBar.getByRole('button', { name: '일괄 전이' }).click()

    // When. 전이 Dialog 열림
    const transitionDialog = page.getByRole('dialog')
    await expect(transitionDialog.getByText('일괄 상태 전이')).toBeVisible()

    // When. 전이 상태 Select에서 "Approve"(→ done, DONE) 선택
    await transitionDialog.getByRole('combobox', { name: '전이 상태' }).click()
    await page.getByRole('option', { name: 'Approve' }).click()

    // Then. resolution 드롭다운 표시 (DONE 전이)
    await expect(transitionDialog.getByRole('combobox', { name: '결의안' })).toBeVisible()

    // Then. 적용 버튼 비활성 (resolution 미선택)
    await expect(transitionDialog.getByRole('button', { name: '적용' })).toBeDisabled()

    // When. "Fixed" 선택
    await transitionDialog.getByRole('combobox', { name: '결의안' }).click()
    await page.getByRole('option', { name: 'Fixed' }).click()

    // Then. 적용 버튼 활성
    await expect(transitionDialog.getByRole('button', { name: '적용' })).toBeEnabled()

    // When. 적용 버튼 클릭
    await transitionDialog.getByRole('button', { name: '적용' }).click()

    // Then. 결과 Dialog 완료 도달
    const resultDialog = page.getByRole('dialog')
    await expect(resultDialog.getByText('일괄 작업 결과')).toBeVisible()
    await expect(resultDialog.getByText(statusLabels.COMPLETED)).toBeVisible({ timeout: 8000 })

    // Then. 성공 1
    await expect(resultDialog.getByText('성공 1')).toBeVisible()
  })
})
