// FR-IS-05 D7 이슈 일괄 작업 E2E — 일괄 편집/전이 happy path + 부분 실패 + 접수 실패 + 교집합 없음
//
// S1 일괄 편집 happy:    전체 선택 → 편집 Dialog priority 변경 → 접수 toast → 결과 Dialog 완료 → 닫기
// S2 일괄 전이 happy:    ATLAS-1 + ATLAS-3 선택(공통 closed) → 전이 Dialog → Cancel 선택 → 결과 완료
// S3 부분 실패:          __bts_e2e_bulk_partial_fail='true' → 결과 Dialog 성공 2/실패 1 + 실패 목록 ATLAS-3
// S4 접수 실패 토스트:   __bts_e2e_bulk_reject='validation' → toast.error + 결과 Dialog 미노출
// S5 교집합 없음:        전체 선택(open/in_progress/done) → 전이 Dialog → 안내 텍스트 + 적용 disabled
//
// 교훈 반영.
//   - e2e-fixture-whoami-userid-alignment: alice userId 정합
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: Dialog 컨테이너로 한정
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript 순서 (loginAsAlice 이후, goto 이전)
//   - msw-mutation-stateful-refetch: 핸들러 stateful 영속으로 폴링 롤백 방지
//
// 격리 가정. bulk MSW 핸들러의 모듈 상태(bulkOpsStore)는 Playwright per-test
// 브라우저 컨텍스트 격리(테스트마다 새 페이지 = 새 MSW worker = 새 모듈 상태)에
// 의존한다. resetBulkOperationState()를 E2E에서 호출하지 않는 이유.
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { statusLabels, failureReasonLabels } from '../src/i18n/bulk-operation-labels'

test.describe('FR-IS-05 이슈 일괄 작업 (BulkEdit / BulkTransition / BulkResult)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1 일괄 편집 happy path
  //   Given  alice 로그인 + /issues 진입
  //   When   전체 선택(3건) → 일괄 편집 Dialog → priority High(2) 선택 → 적용
  //   Then   접수 toast 노출 → 결과 Dialog 완료 + 성공 3/실패 0 → 닫기 → 액션바 사라짐
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 일괄 편집 happy — 전체 선택 후 편집 접수 → 결과 Dialog 완료 + 성공 3', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /issues 진입
    await page.goto('/issues')

    // When. 전체 선택 체크박스 클릭
    const selectAll = page.getByTestId('select-all-page')
    await expect(selectAll).toBeVisible()
    await selectAll.click()

    // Then. 액션바 "3건 선택됨" 확인
    const actionBar = page.getByTestId('bulk-action-bar')
    await expect(actionBar).toBeVisible()
    await expect(actionBar).toContainText('3건 선택됨')

    // When. 일괄 편집 버튼 클릭 — 액션바 컨테이너 내로 한정
    await actionBar.getByRole('button', { name: '일괄 편집' }).click()

    // When. 편집 Dialog에서 priority High(2) 선택
    const editDialog = page.getByRole('dialog')
    await expect(editDialog.getByText('일괄 편집')).toBeVisible()

    await editDialog.getByLabel('priority').selectOption('2')

    // When. 적용 버튼 클릭
    await editDialog.getByRole('button', { name: '적용' }).click()

    // Then. 접수 toast 확인 (sonner는 페이지에 텍스트로 노출)
    await expect(page.getByText('일괄 작업이 접수되었습니다.')).toBeVisible()

    // Then. 결과 Dialog 노출 + 완료 도달 (폴링 최대 2회 → 8s 여유)
    const resultDialog = page.getByRole('dialog')
    await expect(resultDialog.getByText('일괄 작업 결과')).toBeVisible()

    // Then. 완료(statusLabels.COMPLETED) 상태 도달 대기
    await expect(resultDialog.getByText(statusLabels.COMPLETED)).toBeVisible({ timeout: 8000 })

    // Then. 성공 3 / 실패 0
    await expect(resultDialog.getByText('성공 3')).toBeVisible()
    await expect(resultDialog.getByText('실패 0')).toBeVisible()

    // When. 닫기 버튼 클릭
    await resultDialog.getByRole('button', { name: '닫기' }).click()

    // Then. 액션바 사라짐 (선택 해제 확인)
    await expect(page.getByTestId('bulk-action-bar')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 일괄 전이 happy path
  //   Given  alice 로그인 + /issues 진입
  //   When   ATLAS-1(open) + ATLAS-3(done) 선택 → 일괄 전이 Dialog → Cancel 선택 → 적용
  //   Then   결과 Dialog 완료 + 성공 2
  //   주의: ATLAS-1(open→closed: Cancel), ATLAS-3(done→closed: Close)
  //         intersectTransitions는 첫 이슈(ATLAS-1) 전이 기준 — closed toStateKey 공통 항목 유지
  //         → 실제 노출 옵션 이름은 ATLAS-1 기준 'Cancel' (open→closed)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 일괄 전이 happy — ATLAS-1+ATLAS-3 선택 후 전이 접수 → 결과 Dialog 완료 + 성공 2', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /issues 진입
    await page.goto('/issues')

    // When. ATLAS-1, ATLAS-3 개별 선택
    await page.getByTestId('select-ATLAS-1').click()
    await page.getByTestId('select-ATLAS-3').click()

    // Then. 액션바 "2건 선택됨" 확인
    const actionBar = page.getByTestId('bulk-action-bar')
    await expect(actionBar).toBeVisible()
    await expect(actionBar).toContainText('2건 선택됨')

    // When. 일괄 전이 버튼 클릭
    await actionBar.getByRole('button', { name: '일괄 전이' }).click()

    // When. 전이 Dialog 열림 확인
    const transitionDialog = page.getByRole('dialog')
    await expect(transitionDialog.getByText('일괄 상태 전이')).toBeVisible()

    // When. Radix Select 트리거 클릭 → 옵션 선택
    // intersectTransitions가 ATLAS-1(open) 기준 → toStateKey=closed 항목 → 이름 'Cancel'
    await transitionDialog.getByRole('combobox', { name: '전이 상태' }).click()

    // 드롭다운 팝업에서 'Cancel' 옵션 선택 (Radix SelectContent는 portal로 렌더됨)
    await page.getByRole('option', { name: 'Cancel' }).click()

    // When. 적용 버튼 클릭
    await transitionDialog.getByRole('button', { name: '적용' }).click()

    // Then. 결과 Dialog 완료 도달 (폴링 최대 2회)
    const resultDialog = page.getByRole('dialog')
    await expect(resultDialog.getByText('일괄 작업 결과')).toBeVisible()
    await expect(resultDialog.getByText(statusLabels.COMPLETED)).toBeVisible({ timeout: 8000 })

    // Then. 성공 2
    await expect(resultDialog.getByText('성공 2')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3 부분 실패
  //   Given  __bts_e2e_bulk_partial_fail='true' → 마지막 이슈(ATLAS-3) FAILED(VERSION_CONFLICT)
  //   When   전체 선택(3건) → 일괄 편집 → priority 변경 → 적용
  //   Then   결과 Dialog 완료 + 성공 2/실패 1 + 실패 목록 ATLAS-3 + 사유 VERSION_CONFLICT 라벨
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 부분 실패 — 마지막 이슈 FAILED → 결과 Dialog 성공 2/실패 1 + 실패 목록', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 부분 실패 플래그 주입 (loginAsAlice 이후, goto 이전)
    await page.addInitScript(() => {
      window.localStorage.setItem('__bts_e2e_bulk_partial_fail', 'true')
    })

    // When. /issues 진입
    await page.goto('/issues')

    // When. 전체 선택
    await page.getByTestId('select-all-page').click()

    // When. 일괄 편집 → priority 변경 → 적용
    const actionBar = page.getByTestId('bulk-action-bar')
    await actionBar.getByRole('button', { name: '일괄 편집' }).click()

    const editDialog = page.getByRole('dialog')
    await expect(editDialog.getByText('일괄 편집')).toBeVisible()
    await editDialog.getByLabel('priority').selectOption('1')
    await editDialog.getByRole('button', { name: '적용' }).click()

    // Then. 결과 Dialog 완료 도달
    const resultDialog = page.getByRole('dialog')
    await expect(resultDialog.getByText('일괄 작업 결과')).toBeVisible()
    await expect(resultDialog.getByText(statusLabels.COMPLETED)).toBeVisible({ timeout: 8000 })

    // Then. 성공 2 / 실패 1
    await expect(resultDialog.getByText('성공 2')).toBeVisible()
    await expect(resultDialog.getByText('실패 1')).toBeVisible()

    // Then. 실패 목록에 ATLAS-3 노출
    const failureSection = resultDialog.getByText('실패 목록')
    await expect(failureSection).toBeVisible()
    await expect(resultDialog.getByText('ATLAS-3')).toBeVisible()

    // Then. 실패 사유 한국어 라벨 노출
    await expect(resultDialog.getByText(failureReasonLabels.VERSION_CONFLICT)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 접수 실패 토스트
  //   Given  __bts_e2e_bulk_reject='validation' → POST 400 detail '선택한 이슈가 없거나 너무 많습니다.'
  //   When   전체 선택 → 일괄 편집 → priority 변경 → 적용
  //   Then   toast.error '선택한 이슈가 없거나 너무 많습니다.' 노출 + 결과 Dialog 미노출
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 접수 실패 토스트 — validation reject → toast.error + 결과 Dialog 미노출', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. validation 거부 플래그 주입 (loginAsAlice 이후, goto 이전)
    await page.addInitScript(() => {
      window.localStorage.setItem('__bts_e2e_bulk_reject', 'validation')
    })

    // When. /issues 진입
    await page.goto('/issues')

    // When. 전체 선택 → 일괄 편집 → priority 변경 → 적용
    await page.getByTestId('select-all-page').click()

    const actionBar = page.getByTestId('bulk-action-bar')
    await actionBar.getByRole('button', { name: '일괄 편집' }).click()

    const editDialog = page.getByRole('dialog')
    await expect(editDialog.getByText('일괄 편집')).toBeVisible()
    await editDialog.getByLabel('priority').selectOption('3')
    await editDialog.getByRole('button', { name: '적용' }).click()

    // Then. error toast 텍스트 노출
    await expect(page.getByText('선택한 이슈가 없거나 너무 많습니다.')).toBeVisible()

    // Then. 결과 Dialog('일괄 작업 결과') 미노출 확인
    // 잠시 대기 후 확인 (toast 노출 시점과 같으므로 toast 확인 후 바로 단언)
    await expect(page.getByText('일괄 작업 결과')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 공통 전이 없음
  //   Given  전체 선택(ATLAS-1:open, ATLAS-2:in_progress, ATLAS-3:done)
  //          intersectTransitions 교집합 = 0
  //   When   일괄 전이 Dialog 열기
  //   Then   안내 텍스트 '선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다.' 노출
  //          + 적용 버튼 disabled
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 공통 전이 없음 — 전체 선택(교집합 0) → 전이 Dialog 안내 텍스트 + 적용 disabled', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /issues 진입
    await page.goto('/issues')

    // When. 전체 선택 (3건: open/in_progress/done → 공통 전이 없음)
    await page.getByTestId('select-all-page').click()

    // When. 일괄 전이 Dialog 열기
    const actionBar = page.getByTestId('bulk-action-bar')
    await expect(actionBar).toContainText('3건 선택됨')
    await actionBar.getByRole('button', { name: '일괄 전이' }).click()

    // Then. 전이 Dialog 열림
    const transitionDialog = page.getByRole('dialog')
    await expect(transitionDialog.getByText('일괄 상태 전이')).toBeVisible()

    // Then. 교집합 0건 안내 텍스트 노출
    await expect(
      transitionDialog.getByText('선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다.'),
    ).toBeVisible()

    // Then. 적용 버튼 disabled
    await expect(transitionDialog.getByRole('button', { name: '적용' })).toBeDisabled()
  })
})
