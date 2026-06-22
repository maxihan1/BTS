// FR-EP-01 D7 E2E — 에픽 자식 연결/해제 + 소속 에픽 지정/해제 시나리오
//
// 시나리오.
//   S1. 에픽 상세 자식 연결/해제 — EpicChildrenSection
//       에픽 이슈(ATLAS-EPIC-1) 상세 진입 → 자식 키 입력+추가 → 목록 표시 → 해제 → 사라짐
//   S2. 자식 이슈 소속 에픽 지정/해제 — IssueLinksPanel EpicSection
//       일반 이슈(ATLAS-FOR-EPIC) 상세 진입 → 에픽 키 입력+지정 → 소속 에픽 표시 → 해제
//
// 설계 결정.
//   - serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block 교훈).
//   - MSW stateful store 검증은 SPA 내부 이동(reload 금지 — store 리셋 가짜그린 방지).
//   - 데이터 격리: 각 테스트는 새 브라우저 컨텍스트로 epicChildrenStore가 빈 상태.
//     (Playwright 기본 동작 — test마다 새 ServiceWorker 모듈 컨텍스트)
//   - strict-mode: epic-children-section / epic-section 컨테이너 한정 (playwright-getbyrole-exact-strict-mode).
//   - 텍스트 중복 버튼("추가", "해제")은 컨테이너 내 getByLabel/getByRole로 한정.
//   - loginAsAlice: issue-fixtures.ts 정본 사용 (session-fixtures.ts 동일 구현이나 정본은 issue-fixtures).
//   - ATLAS-EPIC-1: typeKey='epic' → EpicChildrenSection 렌더 + showEpicSection=false.
//   - ATLAS-FOR-EPIC: typeKey='task' → showEpicSection=true → EpicSection 렌더.
//   - 연결 후 SPA 재진입으로 issueOverrides 영속 검증 (msw-mutation-stateful-refetch 교훈).
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — issue-fixtures.ts, ko.ts(epicChildrenStrings, issueLinkStrings) 인라인 동기화
// ─────────────────────────────────────────────────────────────────────────────

/** issue-fixtures.ts issueAtlasEpic1Fixture.key 와 동기화 */
const EPIC_KEY = 'ATLAS-EPIC-1'
const EPIC_URL = `/issues/${EPIC_KEY}`

/** issue-fixtures.ts issueAtlasChild1Fixture.key 와 동기화 */
const CHILD_KEY = 'ATLAS-CHILD-1'

/** issue-fixtures.ts issueAtlasForEpicFixture.key 와 동기화 */
const CHILD_ISSUE_KEY = 'ATLAS-FOR-EPIC'
const CHILD_ISSUE_URL = `/issues/${CHILD_ISSUE_KEY}`

// data-testid (EpicChildrenSection, IssueLinksPanel)
const EPIC_CHILDREN_SECTION_TESTID = 'epic-children-section'
const EPIC_SECTION_TESTID = 'epic-section'

// aria-label — epicChildrenStrings (ko.ts) 인라인 동기화
const CHILD_KEY_INPUT_LABEL = '자식 이슈 키'
const ADD_CHILD_BUTTON_LABEL = '추가'
const DISCONNECT_BUTTON_SUFFIX = '연결 해제'

// aria-label — issueLinkStrings (ko.ts) 인라인 동기화
const EPIC_KEY_INPUT_LABEL = '에픽 이슈 키'
const SET_EPIC_BUTTON_LABEL = '에픽 지정'
const CLEAR_EPIC_BUTTON_LABEL = '해제'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SPA 내부 이동 (reload 금지)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SPA 내부 네비게이션으로 이슈 상세 페이지에 진입한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 *
 * reload 대신 window.history.pushState + popstate 사용.
 * reload하면 MSW 모듈이 재초기화되어 stateful store가 리셋된다 (가짜그린 방지).
 *
 * @param page Playwright Page
 * @param issueUrl 이동할 이슈 상세 URL
 * @param waitForTestId 렌더 완료를 확인할 data-testid (기본: epic-children-section)
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

test.describe('FR-EP-01 에픽 자식 연결/해제 + 소속 에픽 지정/해제', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 에픽 상세 자식 연결/해제 (EpicChildrenSection)
  //
  // Given  alice 로그인 + ATLAS-EPIC-1 이슈 상세 진입 (typeKey='epic')
  //        epic-children-section 렌더 확인 + 초기 빈 상태
  // When   자식 이슈 키(ATLAS-CHILD-1) 입력 + "추가" 클릭
  //        → MSW connectEpicChildHandler: 201 + epicChildrenStore 영속
  //        → invalidateQueries → GET /epic-children 재조회
  // Then   epic-children-section에 "ATLAS-CHILD-1" 행 표시
  //        SPA 내부 재진입 후에도 유지 (issueOverrides 영속 검증)
  //
  // When2  "ATLAS-CHILD-1 연결 해제" 버튼 클릭
  //        → MSW disconnectEpicChildHandler: 204 + epicChildrenStore 제거
  //        → invalidate → GET /epic-children 재조회
  // Then2  "ATLAS-CHILD-1" 행 사라짐 + 빈 상태 메시지 복원
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 에픽 자식 연결/해제 — ATLAS-CHILD-1 추가 후 목록 표시, 해제 후 사라짐', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 에픽 이슈 상세 SPA 이동
    await navigateToIssueDetail(page, EPIC_URL, EPIC_CHILDREN_SECTION_TESTID)

    const epicSection = page.getByTestId(EPIC_CHILDREN_SECTION_TESTID)

    // Given. 초기 빈 상태 확인
    await expect(epicSection.getByText('연결된 자식 이슈가 없습니다.')).toBeVisible()

    // When. 자식 이슈 키 입력
    const childKeyInput = epicSection.getByLabel(CHILD_KEY_INPUT_LABEL)
    await expect(childKeyInput).toBeVisible()
    await childKeyInput.fill(CHILD_KEY)

    // When. "추가" 버튼 클릭 — epic-children-section 내로 한정 (strict-mode 방지)
    const addButton = epicSection.getByRole('button', { name: ADD_CHILD_BUTTON_LABEL, exact: true })
    await expect(addButton).not.toBeDisabled()
    await addButton.click()

    // Then. ATLAS-CHILD-1 행 표시
    // ChildRow: data-testid="epic-child-row-ATLAS-CHILD-1" + <a aria-label="ATLAS-CHILD-1">
    const childRow = epicSection.getByTestId(`epic-child-row-${CHILD_KEY}`)
    await expect(childRow).toBeVisible()
    await expect(epicSection.getByRole('link', { name: CHILD_KEY })).toBeVisible()

    // Then. 빈 상태 메시지 사라짐
    await expect(epicSection.getByText('연결된 자식 이슈가 없습니다.')).toHaveCount(0)

    // [SPA 재진입 영속 검증]
    // When(재진입). /dashboard로 이동 후 에픽 상세 재진입 (reload 금지)
    await page.evaluate(() => {
      window.history.pushState({}, '', '/dashboard')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await page.waitForURL('**/dashboard')

    await navigateToIssueDetail(page, EPIC_URL, EPIC_CHILDREN_SECTION_TESTID)

    // Then(재진입). epicChildrenStore 영속 — ATLAS-CHILD-1 여전히 표시
    const epicSectionAfter = page.getByTestId(EPIC_CHILDREN_SECTION_TESTID)
    await expect(epicSectionAfter.getByRole('link', { name: CHILD_KEY })).toBeVisible()

    // When2. "ATLAS-CHILD-1 연결 해제" 버튼 클릭
    // ChildRow: aria-label="{childKey} 연결 해제" 버튼
    const disconnectButton = epicSectionAfter.getByRole('button', {
      name: `${CHILD_KEY} ${DISCONNECT_BUTTON_SUFFIX}`,
      exact: true,
    })
    await expect(disconnectButton).toBeVisible()
    await disconnectButton.click()

    // Then2. ATLAS-CHILD-1 행 사라짐
    await expect(epicSectionAfter.getByTestId(`epic-child-row-${CHILD_KEY}`)).toHaveCount(0)

    // Then2. 빈 상태 메시지 복원
    await expect(epicSectionAfter.getByText('연결된 자식 이슈가 없습니다.')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1b. 에픽 자식 중복 연결 — 인라인 에러
  //
  // Given  ATLAS-EPIC-1에 ATLAS-CHILD-1이 이미 연결된 상태
  // When   동일 자식 키(ATLAS-CHILD-1) 재추가
  //        → MSW connectEpicChildHandler: 409 ISSUE_EPIC_CHILD_ALREADY_LINKED
  // Then   epic-children-section 내 role="alert" 인라인 에러 표시
  //        "이미 이 에픽에 연결된 이슈입니다." 텍스트 포함
  // ───────────────────────────────────────────────────────────────────────────
  test('S1b 에픽 자식 중복 연결 — 409 ALREADY_LINKED 인라인 에러', async ({ page }) => {
    // Given. alice 로그인 + 에픽 상세 진입
    await loginAsAlice(page)
    await navigateToIssueDetail(page, EPIC_URL, EPIC_CHILDREN_SECTION_TESTID)

    const epicSection = page.getByTestId(EPIC_CHILDREN_SECTION_TESTID)
    const childKeyInput = epicSection.getByLabel(CHILD_KEY_INPUT_LABEL)
    const addButton = epicSection.getByRole('button', { name: ADD_CHILD_BUTTON_LABEL, exact: true })

    // 사전 상태. ATLAS-CHILD-1 1회 연결
    await childKeyInput.fill(CHILD_KEY)
    await addButton.click()
    await expect(epicSection.getByTestId(`epic-child-row-${CHILD_KEY}`)).toBeVisible()

    // When. 동일 자식 키 재추가
    await childKeyInput.fill(CHILD_KEY)
    await addButton.click()

    // Then. 인라인 에러(role="alert") 표시
    const inlineError = epicSection.getByRole('alert')
    await expect(inlineError).toBeVisible()
    await expect(inlineError).toContainText('이미 이 에픽에 연결된 이슈입니다.')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 자식 이슈 소속 에픽 지정/해제 (IssueLinksPanel EpicSection)
  //
  // Given  alice 로그인 + ATLAS-FOR-EPIC 이슈 상세 진입 (typeKey='task')
  //        epic-section 렌더 확인 (showEpicSection=true)
  //        에픽 미지정 상태 — 에픽 키 input 표시
  // When   에픽 키(ATLAS-EPIC-1) 입력 + "에픽 지정" 클릭
  //        → MSW connectEpicChildHandler: 201 + issueOverrides 영속
  //        → invalidateQueries → GET /issues/ATLAS-FOR-EPIC 재조회
  // Then   epic-section에 "ATLAS-EPIC-1" 링크 표시
  //        "해제" 버튼 노출
  //
  // When2  "해제" 버튼 클릭
  //        → MSW disconnectEpicChildHandler: 204 + issueOverrides에서 epic 제거
  //        → invalidate → GET 재조회
  // Then2  "ATLAS-EPIC-1" 링크 사라짐 + 에픽 키 input 복원
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 소속 에픽 지정/해제 — ATLAS-EPIC-1 지정 후 표시, 해제 후 사라짐', async ({ page }) => {
    // Given. alice 로그인 + 일반 이슈 상세 SPA 이동
    await loginAsAlice(page)
    await navigateToIssueDetail(page, CHILD_ISSUE_URL, EPIC_SECTION_TESTID)

    const epicSection = page.getByTestId(EPIC_SECTION_TESTID)

    // Given. 에픽 미지정 — 에픽 키 input 표시 확인
    const epicKeyInput = epicSection.getByLabel(EPIC_KEY_INPUT_LABEL)
    await expect(epicKeyInput).toBeVisible()

    // When. 에픽 키 입력
    await epicKeyInput.fill(EPIC_KEY)

    // When. "에픽 지정" 버튼 클릭 — epic-section 컨테이너 내로 한정
    const setEpicButton = epicSection.getByRole('button', {
      name: SET_EPIC_BUTTON_LABEL,
      exact: true,
    })
    await expect(setEpicButton).not.toBeDisabled()
    await setEpicButton.click()

    // Then. 소속 에픽 링크 표시 (EpicSection hasEpic=true 분기)
    // EpicSection: <a aria-label={epic.key}>{epic.key}</a>
    const epicLink = epicSection.getByRole('link', { name: EPIC_KEY })
    await expect(epicLink).toBeVisible()

    // Then. "해제" 버튼 노출
    const clearButton = epicSection.getByRole('button', {
      name: CLEAR_EPIC_BUTTON_LABEL,
      exact: true,
    })
    await expect(clearButton).toBeVisible()

    // Then. 에픽 키 input 사라짐 (hasEpic=true 분기)
    await expect(epicKeyInput).toHaveCount(0)

    // When2. 소속 에픽 해제
    await clearButton.click()

    // Then2. 에픽 링크 사라짐
    await expect(epicSection.getByRole('link', { name: EPIC_KEY })).toHaveCount(0)

    // Then2. 에픽 키 input 복원 (hasEpic=false 분기)
    await expect(epicSection.getByLabel(EPIC_KEY_INPUT_LABEL)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2b. 소속 에픽 재진입 유지 — issueOverrides 영속 검증
  //
  // Given  ATLAS-FOR-EPIC에 ATLAS-EPIC-1 에픽 지정 완료
  // When   SPA 내부 이동 (/dashboard → 이슈 상세 재진입)
  //        → invalidateQueries refetch → getIssueHandler → issueOverrides에서 epic 반환
  // Then   재진입 후에도 epic-section에 "ATLAS-EPIC-1" 링크 유지
  // ───────────────────────────────────────────────────────────────────────────
  test('S2b 소속 에픽 재진입 유지 — issueOverrides 영속으로 재진입 후도 에픽 표시', async ({ page }) => {
    // Given. alice 로그인 + 일반 이슈 상세 진입 + 에픽 지정
    await loginAsAlice(page)
    await navigateToIssueDetail(page, CHILD_ISSUE_URL, EPIC_SECTION_TESTID)

    const epicSection = page.getByTestId(EPIC_SECTION_TESTID)
    const epicKeyInput = epicSection.getByLabel(EPIC_KEY_INPUT_LABEL)
    await epicKeyInput.fill(EPIC_KEY)

    const setEpicButton = epicSection.getByRole('button', { name: SET_EPIC_BUTTON_LABEL, exact: true })
    await setEpicButton.click()

    // 에픽 지정 완료 확인
    await expect(epicSection.getByRole('link', { name: EPIC_KEY })).toBeVisible()

    // When. SPA 내부 이동 — /dashboard → 이슈 상세 재진입
    await page.evaluate(() => {
      window.history.pushState({}, '', '/dashboard')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await page.waitForURL('**/dashboard')

    await navigateToIssueDetail(page, CHILD_ISSUE_URL, EPIC_SECTION_TESTID)

    // Then. 재진입 후에도 epic-section에 ATLAS-EPIC-1 링크 유지 (issueOverrides 영속)
    const epicSectionAfter = page.getByTestId(EPIC_SECTION_TESTID)
    await expect(epicSectionAfter.getByRole('link', { name: EPIC_KEY })).toBeVisible()
  })
})
