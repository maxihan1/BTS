// FR-IS-01 이슈 상태 전이 E2E — S1 happy path + S2 가용전이 필터 + S5 에러 노출
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

// ATLAS-1(open), ATLAS-4(closed) 는 issue-fixtures.ts 의 정적 fixture.
// 전이 후 상태는 MSW transitionOverrides 가 stateful 보관.

test.describe('FR-IS-01 이슈 상태 전이 (IssueMetaPanel)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 Happy Path — open → in_progress 전이
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(open 상태) 이슈 상세 페이지 진입
   * When    전이 셀렉터에서 "Start Work" 선택
   * Then    상태 배지가 "in_progress" 로 갱신됨
   */
  test('S1 happy — Start Work 선택 시 상태 배지가 in_progress 로 갱신', async ({ page }) => {
    // Given. open 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-1')
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toBeVisible()
    await expect(badge).toContainText('open')

    // When. 전이 셀렉터에서 "Start Work" 선택
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()
    await transitionSelect.selectOption({ label: 'Start Work' })

    // Then. 상태 배지가 in_progress 로 갱신 (MSW stateful 응답 + 쿼리 무효화 재조회)
    await expect(badge).toContainText('in_progress')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 가용전이 필터 — open 상태에서 Start Work, Cancel 만 노출
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(open 상태) 이슈 상세 페이지 진입
   * When    전이 셀렉터 옵션 목록 확인
   * Then    "Start Work", "Cancel" 만 노출; "Submit for Review", "Approve" 없음
   */
  test('S2 가용전이 필터 — open 상태에서 Start Work·Cancel 만 노출', async ({ page }) => {
    // Given. open 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-1')

    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await expect(transitionSelect).toBeVisible()

    // Then. open 출발 전이 2건만 옵션으로 존재
    await expect(transitionSelect.locator('option[value="in_progress"]')).toHaveCount(1)
    await expect(transitionSelect.locator('option[value="in_progress"]')).toContainText('Start Work')
    await expect(transitionSelect.locator('option[value="closed"]')).toHaveCount(1)
    await expect(transitionSelect.locator('option[value="closed"]')).toContainText('Cancel')

    // Then. 타 상태 출발 전이는 존재하지 않음
    await expect(transitionSelect.locator('option[value="in_review"]')).toHaveCount(0)
    await expect(transitionSelect.locator('option[value="done"]')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5 에러 — 워크플로우 미설정 422 응답 → 에러 메시지 노출 + 상태 미변경
  // SKIP 사유: Playwright page.route() 는 MSW Service Worker 보다 후순위라
  //   POST /transition 을 intercept 할 수 없음. MSW MOCK_NO_WORKFLOW_TRIGGER
  //   트리거 문자열을 select option 으로 직접 선택하는 방법도 불가
  //   (option 값은 toStateKey 이며 ATLAS-1 open 출발 선택지에 없음).
  //   422 에러 분기는 issue-transition-handlers.test.ts 단위 테스트로 커버됨.
  //   해결 방안: main.tsx 에서 window.__mswWorker__ 노출 후 page.evaluate() 로
  //   worker.use(http.post(...)) 주입 → 구현 코드 변경 필요, implementer 담당.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1(open 상태) 이슈 상세 페이지 진입
   *         + POST /api/v1/issues/ATLAS-1/transition 을 422 으로 intercept
   * When    전이 셀렉터에서 "Start Work" 선택
   * Then    에러 메시지 노출 + 상태 배지는 여전히 "open"
   */
  test.skip('S5 에러 — 422 워크플로우 미설정 시 에러 토스트 노출 + 상태 미변경', async ({ page }) => {
    // Given. POST /transition 을 422 로 intercept
    await page.route('**/api/v1/issues/ATLAS-1/transition', (route, request) => {
      if (request.method() === 'POST') {
        return route.fulfill({
          status: 422,
          contentType: 'application/json',
          body: JSON.stringify({
            errorCode: 'workflow_not_configured',
            message: '이슈에 워크플로우가 설정되지 않았습니다.',
          }),
        })
      }
      return route.continue()
    })

    // Given. open 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-1')
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toContainText('open')

    // When. 전이 셀렉터에서 "Start Work" 선택 (422 응답 유도)
    const transitionSelect = page.getByRole('combobox', {
      name: i18nLabels.issueDetail.transitionSelectLabel,
    })
    await transitionSelect.selectOption({ label: 'Start Work' })

    // Then. 에러 메시지 노출
    await expect(
      page.getByText(i18nLabels.issueDetail.transitionWorkflowNotConfiguredError),
    ).toBeVisible()

    // Then. 상태 배지는 여전히 open (에러 시 상태 미변경)
    await expect(badge).toContainText('open')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S6 종료 상태 — closed 이슈는 전이 셀렉터 미노출
  // SKIP 사유: issueAtlas4Fixture.reporterId = 'a8b9c0d1-e2f3-4a4b-cd5c-7f8a9b0c1d2e' 의
  //   variant byte 'cd5c' 첫 글자 'c' 가 Zod v4 UUID 패턴 [89abAB] 불일치.
  //   MSW 가 200 을 반환하지만 Zod 파싱 실패 → fetchIssue ZodError throw →
  //   useQuery error non-null → role="alert" 노출 (이슈를 찾을 수 없습니다).
  //   수정 방법: src/mocks/issue-fixtures.ts issueAtlas4Fixture.reporterId 를
  //   유효한 RFC 4122 UUID 로 교체 → implementer 담당.
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-4(closed 상태) 이슈 상세 페이지 진입
   * When    메타패널 상태 영역 확인
   * Then    전이 셀렉터 없음 + "더 진행할 전이 없음" 안내 문구 노출
   */
  test.skip('S6 종료 상태 — closed 이슈는 전이 셀렉터 없고 안내 문구 노출', async ({ page }) => {
    // Given. closed 상태 이슈 상세 진입
    await page.goto('/issues/ATLAS-4')
    const badge = page.getByTestId('issue-state-badge')
    await expect(badge).toContainText('closed')

    // Then. 전이 셀렉터 없음
    await expect(
      page.getByRole('combobox', { name: i18nLabels.issueDetail.transitionSelectLabel }),
    ).toHaveCount(0)

    // Then. 종료 안내 문구 노출
    await expect(
      page.getByText(i18nLabels.issueDetail.noTransitionsAvailable),
    ).toBeVisible()
  })
})
