// FR-PL-01 E2E — 이슈 일정 필드(시작일·마감일·목표일) happy path 시나리오
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * 전제 조건.
 * - MSW mock: PATCH /api/v1/issues/:key — FR-PL-01 날짜 3필드 stateful 영속 (Task 7 완료)
 *   applyDatePatch: undefined=유지 / null=클리어 / "yyyy-MM-dd"=설정
 * - IssueScheduleFields: data-testid="schedule-fields" 컨테이너,
 *   aria-label로 각 input 식별, data-testid="schedule-save" 저장 버튼
 * - ATLAS-1 fixture: startDate/dueDate/targetDate 미설정 (undefined → input 빈 값)
 * - serviceWorkers:'block' 금지 — MSW 핸들러로 해결 (memory: e2e-msw-serviceworker-block)
 * - stateful store 경유 — PATCH 후 GET 재조회 시 날짜 롤백 없음 (memory: msw-mutation-stateful-refetch)
 */

test.describe('FR-PL-01 이슈 일정 필드 (IssueScheduleFields)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건
    await loginAsAlice(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 날짜 3필드 설정 happy path
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 진입 — 날짜 3필드 모두 미설정(빈 값)
   * When    시작일 = 2026-07-01, 마감일 = 2026-07-31, 목표일 = 2026-08-15 입력 후 저장
   * Then    invalidateQueries + refetch 후 각 input에 입력한 날짜가 그대로 유지됨
   *         (MSW stateful 영속 검증 — refetch 롤백 없음)
   */
  test('S1 — 시작일·마감일·목표일 설정 후 저장 시 refetch 후에도 날짜 유지', async ({ page }) => {
    // Given. ATLAS-1 이슈 상세 진입
    await page.goto('/issues/ATLAS-1')

    // schedule-fields 컨테이너 가시 확인 (strict mode 방지 — 모든 selector를 이 컨테이너 내로 한정)
    const scheduleSection = page.getByTestId('schedule-fields')
    await expect(scheduleSection).toBeVisible()

    // 초기 상태 확인 — 3개 input이 모두 빈 값
    const startInput = scheduleSection.getByLabel(i18nLabels.issueDetail.startDateLabel)
    const dueInput = scheduleSection.getByLabel(i18nLabels.issueDetail.dueDateLabel)
    const targetInput = scheduleSection.getByLabel(i18nLabels.issueDetail.targetDateLabel)

    await expect(startInput).toBeVisible()
    await expect(dueInput).toBeVisible()
    await expect(targetInput).toBeVisible()
    await expect(startInput).toHaveValue('')
    await expect(dueInput).toHaveValue('')
    await expect(targetInput).toHaveValue('')

    // When. 날짜 3필드 입력 (네이티브 input[type=date] — fill로 yyyy-MM-dd 직접 입력)
    await startInput.fill('2026-07-01')
    await dueInput.fill('2026-07-31')
    await targetInput.fill('2026-08-15')

    // 저장 버튼 클릭
    const saveButton = scheduleSection.getByTestId('schedule-save')
    await expect(saveButton).toBeVisible()
    await saveButton.click()

    // Then. PATCH + invalidateQueries refetch 완료 후 날짜 값 유지 (stateful 영속 검증)
    // refetch 후 MSW가 날짜 설정값을 그대로 반환 → input 값 롤백 없음
    await expect(startInput).toHaveValue('2026-07-01')
    await expect(dueInput).toHaveValue('2026-07-31')
    await expect(targetInput).toHaveValue('2026-08-15')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 날짜 클리어 (null) 검증
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 진입 — S1과 동일 플로우로 마감일을 먼저 설정
   * When    마감일 input을 지워(빈 값) 저장
   * Then    refetch 후 마감일 input이 빈 값으로 유지됨 (null 클리어 반영)
   *         시작일은 직전 설정값 유지 (Unchanged=기존값 보존 검증)
   */
  test('S2 — 마감일 클리어 시 null로 해제되고 시작일은 기존값 유지', async ({ page }) => {
    // Given. ATLAS-1 상세 진입 — 먼저 시작일·마감일 설정
    await page.goto('/issues/ATLAS-1')

    const scheduleSection = page.getByTestId('schedule-fields')
    await expect(scheduleSection).toBeVisible()

    const startInput = scheduleSection.getByLabel(i18nLabels.issueDetail.startDateLabel)
    const dueInput = scheduleSection.getByLabel(i18nLabels.issueDetail.dueDateLabel)
    const saveButton = scheduleSection.getByTestId('schedule-save')

    // 시작일·마감일 설정 후 저장 (S2 사전 조건 구성)
    await startInput.fill('2026-07-01')
    await dueInput.fill('2026-07-31')
    await saveButton.click()
    await expect(startInput).toHaveValue('2026-07-01')
    await expect(dueInput).toHaveValue('2026-07-31')

    // When. 마감일만 지워(빈 값) 저장 — toApiValue('')=null(클리어)
    await dueInput.fill('')
    await saveButton.click()

    // Then. 마감일 빈 값 유지(null 클리어 반영), 시작일은 기존값 보존
    await expect(dueInput).toHaveValue('')
    await expect(startInput).toHaveValue('2026-07-01')
  })
})
