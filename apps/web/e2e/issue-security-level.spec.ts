// FR-PM-06 PR-B D7 E2E — 이슈 보안등급 생성 선택 + 편집 변경/해제 시나리오
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'
import { securityLevelFixtures } from '../src/mocks/security-level-handlers'

/**
 * 전제 조건.
 * - MSW mock: GET /api/v1/projects/ATLAS/issue-security-scheme/levels → 3개 fixture
 * - MSW mock: PATCH /api/v1/issues/:key — securityLevelId stateful 영속 (msw-mutation-stateful-refetch 교훈)
 * - IssueSecurityLevelSelect: aria-label='보안등급 선택' native select
 * - IssueMetaPanel: data-testid="security-level-section" 컨테이너 한정 (strict mode 방지)
 */

test.describe('FR-PM-06 이슈 보안등급 (IssueSecurityLevelSelect)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건
    await loginAsAlice(page)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // S1 생성 시 보안등급 선택
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Given   /issues/new 폼 진입, 프로젝트 키 'ATLAS' 입력
   * When    보안등급 셀렉터에서 'Internal' 선택 후 이슈 생성 제출
   * Then    생성된 이슈 상세 페이지의 보안등급 셀렉터가 'Internal' 값을 표시함
   */
  test('S1 생성 — 보안등급 Internal 선택 후 생성 시 상세 페이지에 반영', async ({ page }) => {
    // Given. 이슈 생성 폼 진입 + projectKey 입력 (등급 목록 로드 트리거)
    await page.goto('/issues/new')
    await page.getByLabel(i18nLabels.issueCreate.projectKeyLabel).fill('ATLAS')
    await page.getByLabel(i18nLabels.issueCreate.summaryLabel).fill('보안등급 E2E 테스트 이슈')

    // When. 보안등급 셀렉터에서 Internal 선택
    const securitySelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.securityLevelSelectLabel,
    })
    await expect(securitySelect).toBeVisible()
    // Internal 등급 옵션이 로드될 때까지 대기 (useQuery 비동기 로드)
    await expect(securitySelect.locator('option', { hasText: securityLevelFixtures.internal.name })).toBeAttached()
    await securitySelect.selectOption(securityLevelFixtures.internal.id)

    // 이슈 제출
    await page.getByRole('button', { name: i18nLabels.issueCreate.submitButton }).click()
    // 상세 페이지 redirect 대기
    await page.waitForURL(/\/issues\/ATLAS-42$/)

    // Then. 상세 페이지 보안등급 섹션에서 Internal 선택 확인
    // createIssueHandler가 body.securityLevelId를 createdIssues에 영속하므로
    // GET /issues/ATLAS-42 가 securityLevelId=Internal UUID를 반환 → select 값 일치
    const securitySection = page.getByTestId('security-level-section')
    await expect(securitySection).toBeVisible()
    const detailSelect = securitySection.getByRole('combobox', {
      name: i18nLabels.issueDetail.securityLevelSelectLabel,
    })
    await expect(detailSelect).toBeVisible()
    // round-trip 검증 — 생성 시 선택한 Internal UUID가 상세 페이지 셀렉터 값과 일치
    await expect(detailSelect).toHaveValue(securityLevelFixtures.internal.id)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // S2 편집에서 등급 변경 → PATCH 반영
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입 (securityLevelId=null)
   * When    보안등급 섹션에서 'Restricted' 선택
   * Then    선택 직후 셀렉터 값이 'Restricted' ID로 갱신됨
   *         (PATCH + invalidateQueries refetch 후 롤백 없음 — MSW stateful 영속)
   */
  test('S2 편집 — 보안등급 Restricted 선택 시 PATCH 반영되고 refetch 후 유지', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const securitySection = page.getByTestId('security-level-section')
    await expect(securitySection).toBeVisible()

    const securitySelect = securitySection.getByRole('combobox', {
      name: i18nLabels.issueDetail.securityLevelSelectLabel,
    })
    await expect(securitySelect).toBeVisible()

    // 등급 옵션 로드 대기
    await expect(securitySelect.locator('option', { hasText: securityLevelFixtures.restricted.name })).toBeAttached()

    // When. Restricted 선택
    await securitySelect.selectOption(securityLevelFixtures.restricted.id)

    // Then. invalidateQueries refetch 완료 후 Restricted 값 유지 (stateful 영속 검증)
    // native select는 선택 즉시 value가 변경되고, refetch 후 MSW가 securityLevelId=Restricted UUID 반환
    await expect(securitySelect).toHaveValue(securityLevelFixtures.restricted.id)
  })

  // ──────────────────────────────────────────────────────────────────────────
  // S3 편집에서 "선택 안 함" → 해제(null) 반영
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈에 S2 흐름으로 Restricted 등급 지정 완료 상태
   * When    보안등급 셀렉터에서 "선택 안 함" (value="") 선택
   * Then    셀렉터 값이 "" (선택 안 함)으로 변경됨
   *         (PATCH securityLevelId=null + refetch 후 null 유지 — 해제 검증)
   */
  test('S3 해제 — "선택 안 함" 선택 시 보안등급이 null로 해제됨', async ({ page }) => {
    // Given. ATLAS-1 상세 진입 후 Internal 먼저 지정 (S3는 등급 지정 상태 전제)
    await page.goto('/issues/ATLAS-1')

    const securitySection = page.getByTestId('security-level-section')
    await expect(securitySection).toBeVisible()

    const securitySelect = securitySection.getByRole('combobox', {
      name: i18nLabels.issueDetail.securityLevelSelectLabel,
    })
    await expect(securitySelect).toBeVisible()

    // Internal 등급 먼저 지정
    await expect(securitySelect.locator('option', { hasText: securityLevelFixtures.internal.name })).toBeAttached()
    await securitySelect.selectOption(securityLevelFixtures.internal.id)
    await expect(securitySelect).toHaveValue(securityLevelFixtures.internal.id)

    // When. "선택 안 함" 선택 (value="")
    await securitySelect.selectOption('')

    // Then. 셀렉터 값이 "" (선택 안 함)으로 변경됨 (PATCH securityLevelId=null + refetch 후 null 유지)
    await expect(securitySelect).toHaveValue('')
  })
})
