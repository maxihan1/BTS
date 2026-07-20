// FR-WF-02 D7 E2E-4 — 사용 중 스킴 삭제 차단 모달 (409 SCHEME_IN_USE → SchemeInUseModal 노출)
// FR-UX-06 PR13 Task 9 회귀 수정 — /admin/workflow-schemes에 SYSTEM_ADMIN 가드 추가(Task 8)로
// 비-admin alice는 /dashboard로 redirect된다. loginAsSystemAdmin으로 갱신.
import { test, expect } from '@playwright/test'
import { loginAsSystemAdmin, navigateToSchemeDetail, i18nLabels } from './fixtures/workflow-scheme-fixtures'

const labels = i18nLabels.workflowScheme

/**
 * E2E-4 시나리오 — SchemeInUseModal + usedByProjects (조건부 link navigate)
 *
 * Given: alice 로그인 + 사용 중 커스텀 스킴 (custom-scheme-alpha, usedByProjectsCount=2) 상세 진입
 * When:  메타 패널 「삭제」 버튼 클릭 → DELETE API → 409 SCHEME_IN_USE 응답
 * Then:  SchemeInUseModal (role=alertdialog) 노출, title 확인, 사용 중 프로젝트 수 표시, 「확인」 으로 닫힘
 *
 * G3 처리: SchemeInUseModal이 프로젝트 링크 목록을 렌더하지 않는 현재 버전에서는
 *          link 클릭 시나리오를 skip하고 annotation으로 사유를 명시한다.
 *          (후속 PR에서 usedByProjects 배열 제공 + 링크 렌더 예정 — SchemeInUseModal 주석 참조)
 */
test.describe('E2E-4 사용 중 스킴 삭제 차단 — SchemeInUseModal', () => {
  // custom-scheme-alpha: usedByProjectsCount=2, isStandard=false, 삭제 시 409 SCHEME_IN_USE
  const IN_USE_SCHEME_KEY = 'custom-scheme-alpha'
  const IN_USE_SCHEME_PROJECTS_COUNT = 2

  test.beforeEach(async ({ page }) => {
    // SYSTEM_ADMIN alice 로그인(Task 8 가드) — workflow-schemes는 admin 전용
    await loginAsSystemAdmin(page)
    await navigateToSchemeDetail(page, IN_USE_SCHEME_KEY)
  })

  test('삭제 버튼 클릭 → 409 SCHEME_IN_USE → SchemeInUseModal 노출 + title 검증', async ({ page }) => {
    // When: 메타 패널 「삭제」 버튼 클릭 → DELETE API → 409 응답
    // exact: true — 매핑 테이블 "매핑 삭제 (버그)" 등 버튼과 strict mode 충돌 방지
    await page.getByRole('button', { name: labels.metaPanel.deleteButton, exact: true }).click()

    // Then: SchemeInUseModal (role=alertdialog) 노출
    const modal = page.getByRole('alertdialog')
    await expect(modal).toBeVisible()

    // title 텍스트 검증
    await expect(modal.getByText(labels.inUseModal.title)).toBeVisible()
  })

  test('SchemeInUseModal — 사용 중 프로젝트 label + count 표시', async ({ page }) => {
    // When
    await page.getByRole('button', { name: labels.metaPanel.deleteButton, exact: true }).click()

    const modal = page.getByRole('alertdialog')
    await expect(modal).toBeVisible()

    // usedByProjectsLabel 표시 — exact: true로 description 내 부분 문자열 오매칭 방지
    await expect(modal.getByText(labels.inUseModal.usedByProjectsLabel, { exact: true })).toBeVisible()

    // 사용 중 프로젝트 수 표시 (fixture 값 2 포함)
    await expect(modal.getByText(new RegExp(String(IN_USE_SCHEME_PROJECTS_COUNT)))).toBeVisible()
  })

  test('SchemeInUseModal — 「확인」 버튼 클릭 → 모달 닫힘', async ({ page }) => {
    // When
    await page.getByRole('button', { name: labels.metaPanel.deleteButton, exact: true }).click()

    const modal = page.getByRole('alertdialog')
    await expect(modal).toBeVisible()

    // 「확인」 버튼 클릭
    await modal.getByRole('button', { name: labels.inUseModal.confirmButton }).click()

    // Then: 모달 닫힘
    await expect(modal).not.toBeVisible()
  })

  test('G3 — usedByProjects link navigate (현재 버전 skip)', async ({ page }, testInfo) => {
    // When
    await page.getByRole('button', { name: labels.metaPanel.deleteButton, exact: true }).click()

    const modal = page.getByRole('alertdialog')
    await expect(modal).toBeVisible()

    // G3: SchemeInUseModal이 프로젝트 링크 목록을 렌더하지 않는 현재 버전.
    // data-testid="used-by-project-link" 요소가 0개이면 link 클릭 시나리오를 skip한다.
    const projectLinks = modal.locator('[data-testid="used-by-project-link"]')
    const linkCount = await projectLinks.count()

    if (linkCount === 0) {
      testInfo.annotations.push({
        type: 'skip-reason',
        description:
          'G3: SchemeInUseModal이 usedByProjects 링크 목록을 아직 렌더하지 않음 — ' +
          '후속 PR에서 usedByProjects 배열 + 링크 렌더 추가 후 이 시나리오 활성화 예정',
      })
      return
    }

    // 링크가 존재하는 버전에서만 실행: 첫 번째 링크 클릭 → /projects/{key}/settings/workflow-scheme 이동
    await projectLinks.first().click()
    await expect(page).toHaveURL(/\/projects\/.+\/settings\/workflow-scheme/)
  })
})
