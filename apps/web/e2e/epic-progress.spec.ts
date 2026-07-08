// FR-EP-02 E2E — 에픽 진행률 막대(EpicProgressBar) 렌더 + 자식 연결 후 갱신 검증
//
// 시나리오.
//   S1. 에픽 상세 진입 → 진행률 막대 표시 + 0% + "자식 이슈 없음" (자식 0개 초기 상태)
//       role="progressbar" aria-label="진행률 0%" 존재 확인.
//   S2. 자식 연결 후 진행률 갱신 — SPA 내부 이동으로 stateful 검증
//       ATLAS-CHILD-1(open=TODO) 연결 → total=1/done=0/todo=1 → 0% → "0 / 1 완료"
//       재진입 후에도 유지 (reload 금지 — MSW store 리셋 방지).
//
// 설계 결정.
//   - serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block 교훈).
//   - reload 금지 — MSW epicChildrenStore가 모듈 재초기화로 리셋되어 가짜그린 발생
//     (msw-mutation-stateful-refetch 교훈, worktree-stale-base-rebase-and-e2e-msw-traps 교훈).
//   - SPA 내부 이동: window.history.pushState + popstate (epic-children.spec.ts 동일 패턴).
//   - 셀렉터: epic-children-section 컨테이너 한정으로 strict-mode 위반 방지
//     (playwright-getbyrole-exact-strict-mode 교훈).
//   - 자식 연결 전: epicChildrenStore 빈 상태 → progress GET → total=0.
//     자식 연결 후: epicChildrenStore에 ATLAS-CHILD-1 추가 → progress GET → total=1.
//   - ATLAS-CHILD-1.currentStateKey='open' → softwareDefaultFixture category='TODO'
//     → doneCount=0, total=1, donePercentage=0 → countLabel='0 / 1 완료'.
//   - 진행률 막대는 EpicChildrenSection 내 <div data-testid="epic-progress-bar"> 안에
//     role="progressbar" aria-label="진행률 N%" 을 보유한다.
//   - loginAsAlice: issue-fixtures.ts 정본 (epic-children.spec.ts 동일 패턴).
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — issue-fixtures.ts / ko.ts(epicProgressStrings / epicChildrenStrings) 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** issue-fixtures.ts issueAtlasEpic1Fixture.key 와 동기화 */
const EPIC_KEY = 'ATLAS-EPIC-1'
const EPIC_URL = `/issues/${EPIC_KEY}`

/** issue-fixtures.ts issueAtlasChild1Fixture.key 와 동기화 */
const CHILD_KEY = 'ATLAS-CHILD-1'

/** data-testid (EpicChildrenSection) */
const EPIC_CHILDREN_SECTION_TESTID = 'epic-children-section'

/** data-testid (EpicProgressBar 루트) */
const EPIC_PROGRESS_BAR_TESTID = 'epic-progress-bar'

// aria-label — epicProgressStrings (ko.ts) 인라인 동기화
const PROGRESS_ARIA_LABEL_0 = '진행률 0%'

// 텍스트 — epicProgressStrings (ko.ts) 인라인 동기화
const NO_CHILDREN_STATE = '자식 이슈 없음'
const COUNT_LABEL_0_OF_1 = '0 / 1 완료'

// aria-label — epicChildrenStrings (ko.ts) 인라인 동기화
const CHILD_KEY_INPUT_LABEL = '자식 이슈 키'
const ADD_CHILD_BUTTON_LABEL = '추가'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 이동 (reload 금지)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SPA 내부 네비게이션으로 이슈 상세 페이지에 진입한다.
 * reload 대신 window.history.pushState + popstate 를 사용한다.
 * reload 하면 MSW 모듈이 재초기화되어 stateful store(epicChildrenStore)가 리셋된다 (가짜그린 방지).
 *
 * epic-children.spec.ts navigateToIssueDetail 와 동일 패턴.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl: string,
  waitForTestId: string,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  await expect(page.getByTestId(waitForTestId)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-EP-02 에픽 진행률 막대 (EpicProgressBar)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 에픽 상세 진입 → 진행률 막대 표시 (자식 0개 초기 상태)
  //
  // Given  alice 로그인 + ATLAS-EPIC-1 이슈 상세 진입 (typeKey='epic')
  //        epicChildrenStore 빈 상태 → getEpicProgressHandler: total=0
  // When   epic-children-section 렌더 완료
  //        → GET /api/v1/epics/ATLAS-EPIC-1/progress → { total:0, done:0, donePercentage:0 }
  // Then   data-testid="epic-progress-bar" 표시
  //        role="progressbar" aria-label="진행률 0%" 표시
  //        "자식 이슈 없음" 텍스트 표시 (total=0 빈 상태)
  //        "0%" 퍼센트 텍스트 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 초기 진입 — 진행률 막대 + 0% + 자식 이슈 없음 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 에픽 이슈 상세 SPA 이동
    await navigateToIssueDetail(page, EPIC_URL, EPIC_CHILDREN_SECTION_TESTID)

    const epicSection = page.getByTestId(EPIC_CHILDREN_SECTION_TESTID)

    // Then. 진행률 막대 컨테이너 표시
    const progressBar = epicSection.getByTestId(EPIC_PROGRESS_BAR_TESTID)
    await expect(progressBar).toBeVisible()

    // Then. role="progressbar" aria-label="진행률 0%" — epic-children-section 내로 한정
    const progressBarRole = epicSection.getByRole('progressbar', {
      name: PROGRESS_ARIA_LABEL_0,
      exact: true,
    })
    await expect(progressBarRole).toBeVisible()

    // Then. "자식 이슈 없음" 빈 상태 텍스트 (total=0 분기)
    // ProgressBarContent: isEmpty=true → epicProgressStrings.noChildrenState
    await expect(epicSection.getByText(NO_CHILDREN_STATE, { exact: true })).toBeVisible()

    // Then. "0%" 퍼센트 텍스트 (donePercentage=0)
    await expect(epicSection.getByText('0%', { exact: true })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 자식 연결 후 진행률 갱신 — SPA 내부 이동 stateful 검증
  //
  // Given  alice 로그인 + ATLAS-EPIC-1 이슈 상세 진입
  //        epicChildrenStore 빈 상태 → donePercentage=0, "자식 이슈 없음"
  // When   ATLAS-CHILD-1(open=TODO) 연결
  //        → connectEpicChildHandler: 201 + epicChildrenStore 영속
  //        → invalidateQueries: GET /api/v1/epics/ATLAS-EPIC-1/progress 재조회
  //        → { total:1, done:0, donePercentage:0, byCategory:{todo:1,inProgress:0,done:0} }
  // Then   진행률 막대: role="progressbar" aria-label="진행률 0%" 유지
  //        "0%" 퍼센트 텍스트 유지
  //        "0 / 1 완료" 카운트 텍스트 표시 (total=1 → noChildrenState 사라짐)
  //        "자식 이슈 없음" 텍스트 사라짐
  //
  // When2  SPA 내부 이동 (/dashboard → 에픽 상세 재진입, reload 금지)
  //        → invalidateQueries refetch → epicChildrenStore 영속 상태 유지
  // Then2  재진입 후에도 "0 / 1 완료" 카운트 텍스트 유지 (stateful 검증)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 자식 연결 후 진행률 갱신 — 카운트 텍스트 + SPA 재진입 영속', async ({ page }) => {
    // Given. alice 로그인 + 에픽 이슈 상세 SPA 이동
    await loginAsAlice(page)
    await navigateToIssueDetail(page, EPIC_URL, EPIC_CHILDREN_SECTION_TESTID)

    const epicSection = page.getByTestId(EPIC_CHILDREN_SECTION_TESTID)

    // Given. 초기 "자식 이슈 없음" 확인
    await expect(epicSection.getByText(NO_CHILDREN_STATE, { exact: true })).toBeVisible()

    // When. ATLAS-CHILD-1 연결 — epic-children-section 내로 한정 (strict-mode 방지)
    const childKeyInput = epicSection.getByLabel(CHILD_KEY_INPUT_LABEL)
    await expect(childKeyInput).toBeVisible()
    await childKeyInput.fill(CHILD_KEY)

    const addButton = epicSection.getByRole('button', { name: ADD_CHILD_BUTTON_LABEL, exact: true })
    await expect(addButton).not.toBeDisabled()
    await addButton.click()

    // 자식 행 표시 대기 (connectEpicChildHandler 완료 + invalidate refetch)
    await expect(epicSection.getByTestId(`epic-child-row-${CHILD_KEY}`)).toBeVisible()

    // Then. "자식 이슈 없음" 텍스트 사라짐 (total=1)
    await expect(epicSection.getByText(NO_CHILDREN_STATE, { exact: true })).toHaveCount(0)

    // Then. "0 / 1 완료" 카운트 텍스트 — epicProgressStrings.countLabel(0, 1)
    // ATLAS-CHILD-1.currentStateKey='open' → category='TODO' → doneCount=0
    await expect(epicSection.getByText(COUNT_LABEL_0_OF_1, { exact: true })).toBeVisible()

    // Then. role="progressbar" aria-label="진행률 0%" 유지 (done=0)
    await expect(
      epicSection.getByRole('progressbar', { name: PROGRESS_ARIA_LABEL_0, exact: true }),
    ).toBeVisible()

    // Then. "0%" 퍼센트 텍스트 유지
    await expect(epicSection.getByText('0%', { exact: true })).toBeVisible()

    // [SPA 재진입 영속 검증]
    // When2. /dashboard 이동 후 에픽 상세 재진입 (reload 금지 — MSW store 리셋 방지)
    await page.evaluate(() => {
      window.history.pushState({}, '', '/dashboard')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await page.waitForURL('**/dashboard*')

    await navigateToIssueDetail(page, EPIC_URL, EPIC_CHILDREN_SECTION_TESTID)

    // Then2. 재진입 후에도 epicChildrenStore 영속 → "0 / 1 완료" 유지
    const epicSectionAfter = page.getByTestId(EPIC_CHILDREN_SECTION_TESTID)
    await expect(epicSectionAfter.getByText(COUNT_LABEL_0_OF_1, { exact: true })).toBeVisible()

    // Then2. "자식 이슈 없음" 여전히 없음
    await expect(epicSectionAfter.getByText(NO_CHILDREN_STATE, { exact: true })).toHaveCount(0)
  })
})
