// FR-IS-03 D7 이슈 담당자 할당/해제 E2E — S1 할당 happy path + S2 해제
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * ATLAS-1 fixture 초기 상태: assigneeId=null (미할당)
 * PATCH /api/v1/issues/:key/assignee 핸들러: stateful issueOverrides 영속
 * invalidateQueries 후 GET 단건 재조회 시 assigneeId 유지 (교훈: MSW mutation stateful refetch)
 *
 * 사용자 fixture (user-fixtures.ts):
 *   alice: id='00000000-0000-4000-8000-000000000001', displayName='김앨리스'
 *   bob:   id='00000000-0000-4000-8000-000000000002', displayName=null (username='bob' 폴백)
 */

test.describe('FR-IS-03 이슈 담당자 (IssueMetaPanel > IssueAssigneeSelect)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건 (storageState 대신 loginAsAlice 헬퍼 — 기존 패턴 일관)
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 할당 Happy Path — 미할당 → alice(김앨리스) 담당자 지정
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(assigneeId=null) 이슈 상세 페이지 진입
   *         담당자 영역에 '미지정' 표시 확인
   * When    담당자 검색 input에 'alice' 입력
   *         검색 결과 목록에서 '김앨리스' 버튼 클릭
   * Then    assignee-section 내 assignee-current-name 이 '김앨리스' 로 갱신됨
   *         (MSW stateful PATCH + invalidateQueries refetch 후 롤백 없음)
   */
  test('S1 happy — alice 검색 후 선택 시 assignee-current-name 이 김앨리스로 갱신', async ({ page }) => {
    // Given. ATLAS-1 상세 진입 — 초기 담당자 미지정 확인
    await page.goto('/issues/ATLAS-1')
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()

    const currentName = assigneeSection.getByTestId('assignee-current-name')
    await expect(currentName).toBeVisible()
    await expect(currentName).toHaveText(i18nLabels.issueDetail.assigneeUnassigned)

    // When. 담당자 검색 input에 'alice' 입력 (aria-label 로 정확히 타깃 — strict mode 방지)
    const searchInput = assigneeSection.getByRole('textbox', {
      name: i18nLabels.issueDetail.assigneeSearchPlaceholder,
    })
    await expect(searchInput).toBeVisible()
    await searchInput.fill('alice')

    // 검색 결과 목록에서 '김앨리스' 버튼 클릭 (assignee-section 컨테이너 한정 — strict mode 방지)
    const aliceButton = assigneeSection.getByRole('button', {
      name: '김앨리스',
      exact: true,
    })
    await expect(aliceButton).toBeVisible()
    await aliceButton.click()

    // Then. assignee-current-name 이 '김앨리스' 로 갱신 (PATCH + invalidateQueries refetch 후 롤백 없음)
    await expect(currentName).toHaveText('김앨리스')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 해제 — 담당자 있는 상태에서 해제 버튼 클릭 → '미지정' 복귀
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1에 alice 담당자 지정 완료 상태 (S1 흐름 선행)
   *         assignee-current-name 이 '김앨리스' 표시
   * When    '담당자 해제' 버튼 클릭
   * Then    assignee-current-name 이 '미지정' 으로 복귀
   *         '담당자 해제' 버튼 사라짐 (value=null 이면 버튼 미노출)
   */
  test('S2 해제 — 담당자 해제 버튼 클릭 시 assignee-current-name 이 미지정으로 복귀', async ({ page }) => {
    // Given. ATLAS-1 상세 진입 후 alice 담당자 먼저 지정 (S2 는 할당 상태를 전제)
    await page.goto('/issues/ATLAS-1')
    const assigneeSection = page.getByTestId('assignee-section')
    await expect(assigneeSection).toBeVisible()

    // alice 할당 (S1 흐름 재현)
    const searchInput = assigneeSection.getByRole('textbox', {
      name: i18nLabels.issueDetail.assigneeSearchPlaceholder,
    })
    await searchInput.fill('alice')
    const aliceButton = assigneeSection.getByRole('button', {
      name: '김앨리스',
      exact: true,
    })
    await expect(aliceButton).toBeVisible()
    await aliceButton.click()

    const currentName = assigneeSection.getByTestId('assignee-current-name')
    await expect(currentName).toHaveText('김앨리스')

    // When. 담당자 해제 버튼 클릭 (assignee-section 컨테이너 한정 — strict mode 방지)
    const unassignButton = assigneeSection.getByRole('button', {
      name: i18nLabels.issueDetail.assigneeUnassignButton,
      exact: true,
    })
    await expect(unassignButton).toBeVisible()
    await unassignButton.click()

    // Then. assignee-current-name 이 '미지정' 으로 복귀 (PATCH assigneeId=null + refetch)
    await expect(currentName).toHaveText(i18nLabels.issueDetail.assigneeUnassigned)

    // Then. 해제 버튼 사라짐 (value=null 이면 버튼 미노출 — IssueAssigneeSelect 스펙)
    await expect(unassignButton).not.toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3 (선택) — 422 ASSIGNEE_NOT_FOUND / 409 VERSION_CONFLICT toast
  // SKIP 사유: MSW changeAssigneeHandler 는 userListFixture.some(id) 로 422 트리거하는데,
  //   존재하지 않는 UUID 를 UI에서 직접 선택할 방법이 없다(검색 결과 목록이 MSW가 반환한
  //   userListFixture 를 기반으로 렌더되므로 프런트에서 fixture 밖의 id 를 전달 불가).
  //   409 도 expectedVersion 불일치로 트리거해야 하나, UI 상에서 버전을 조작할 방법이 없다.
  //   두 케이스 모두 단위 테스트(vitest) 에서 충분히 커버 가능하며, E2E 조작 경로 부재로 skip.
  // ─────────────────────────────────────────────────────────────────────────
})
