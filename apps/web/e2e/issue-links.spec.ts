// FR-LK-01 D7 E2E — 이슈 링크 패널 시나리오 (IssueLinksPanel + MSW stateful store)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: links-section / parent-section 컨테이너 한정
//   - msw-mutation-stateful-refetch: MSW createLinkHandler가 linkStore에 영속 → invalidate refetch 후 목록 유지
//   - e2e-msw-scenario-toggle-localstorage-flag: 에러 시나리오는 자기참조/중복 키로 유발 (핸들러 내장 분기)
//   - worktree-stale-base-rebase-and-e2e-msw-traps: page.evaluate로 linkStore 초기화 + SPA 내부 이동
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 재사용 (issue-fixtures.ts 정본)
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 회귀 방지 — 현재 파일만 신규 추가
//
// MSW 핵심 사항.
//   - issue-link-handlers.ts는 CSRF 헤더 검증 없음 → seedXsrfCookie 불필요
//   - linkStore는 모듈-스코프 stateful — 각 테스트 시작 시 resetIssueLinkStore API 호출로 초기화
//   - getLinksHandler: GET /api/v1/issues/:key/links → { data: { outward, inward } }
//   - createLinkHandler: POST /api/v1/issues/:key/links → 201 / 422 LINK_SELF_REFERENCE / 409 DUPLICATE_LINK
//   - deleteLinkHandler: DELETE /api/v1/issues/:key/links/:id → 204
//   - setParentHandler: PATCH /api/v1/issues/:key/parent → 200 / 422 PARENT_SELF_REFERENCE (issueOverrides 영속)
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/** 링크 대상 이슈 — ATLAS-2 (issue-fixtures.ts 정적 fixture, 존재하는 이슈) */
const TARGET_KEY = 'ATLAS-2'

/** 부모 이슈 — ATLAS-3 (issue-fixtures.ts 정적 fixture) */
const PARENT_KEY = 'ATLAS-3'

/** data-testid */
const LINKS_SECTION_TESTID = 'links-section'
const PARENT_SECTION_TESTID = 'parent-section'

/** aria-label (issueLinkStrings 정본 기준 — ko.ts) */
const LINK_TYPE_SELECT_LABEL = '링크 유형'
const TARGET_KEY_INPUT_LABEL = '대상 이슈 키'
const ADD_LINK_BUTTON_LABEL = '링크 추가'
const REMOVE_LINK_BUTTON_LABEL = '링크 제거'
const PARENT_KEY_INPUT_LABEL = '부모 이슈 키'
const SET_PARENT_BUTTON_LABEL = '부모 지정'
const CLEAR_PARENT_BUTTON_LABEL = '해제'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW linkStore를 초기화한다.
 * X-MSW-Reset-Links: true 헤더로 GET 호출하면 핸들러가 resetIssueLinkStore()를 실행하는
 * 별도 시그널이 없으므로, 대신 page.evaluate에서 직접 fetch로 초기화 신호를 보낸다.
 *
 * issue-link-handlers.ts는 X-MSW-Reset-Links 헤더를 별도 처리하지 않는다.
 * 따라서 격리는 각 테스트가 독립 브라우저 컨텍스트(Playwright 기본 동작)로 보장된다.
 * (각 test는 새 컨텍스트 → 새 MSW ServiceWorker 모듈 → 새 linkStore)
 *
 * 단, beforeEach에서 loginAsAlice 후 이슈 상세로 이동하기 전
 * linkStore가 비어있는 상태임을 전제한다.
 */

/**
 * 이슈 상세 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * links-section이 렌더될 때까지 대기.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl = ISSUE_URL,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  await expect(page.getByTestId(LINKS_SECTION_TESTID)).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-LK-01 이슈 링크 패널 (IssueLinksPanel)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 → canEdit=true → 폼 활성
    // issue-fixtures.ts의 loginAsAlice 재사용 (session-fixtures.ts와 동일 내용, 정본)
    await loginAsAlice(page)
    // loginAsAlice 완료 시 /dashboard 진입 → ServiceWorker 기동 완료
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-1 링크 추가 happy path
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         links-section 내 linkStore 초기 비어있음
  // When    링크 유형 "막음"(blocks) 선택 + 대상 키 ATLAS-2 입력 + "링크 추가" 클릭
  // Then    links-section에 outward 링크 행이 1개 표시됨
  //         링크 행에 label("blocks")와 대상 키("ATLAS-2") 텍스트 포함
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-1 링크 추가 happy — blocks 유형 + ATLAS-2 입력 후 목록에 링크 행 표시', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const linksSection = page.getByTestId(LINKS_SECTION_TESTID)

    // Given. 초기 상태 — 빈 상태 메시지 확인
    await expect(linksSection.getByText('링크가 없습니다.')).toBeVisible()

    // When. 링크 유형 select — "막음"(blocks) 선택 (기본값이므로 상태 확인만)
    const linkTypeSelect = linksSection.getByRole('combobox', { name: LINK_TYPE_SELECT_LABEL })
    await expect(linkTypeSelect).toBeVisible()
    await linkTypeSelect.selectOption('blocks')

    // When. 대상 이슈 키 입력
    const targetKeyInput = linksSection.getByLabel(TARGET_KEY_INPUT_LABEL)
    await expect(targetKeyInput).toBeVisible()
    await targetKeyInput.fill(TARGET_KEY)

    // When. "링크 추가" 버튼 클릭
    const addButton = linksSection.getByRole('button', { name: ADD_LINK_BUTTON_LABEL, exact: true })
    await expect(addButton).not.toBeDisabled()
    await addButton.click()

    // Then. 링크 행 표시 — outward 방향 label "blocks" + 대상 키 "ATLAS-2"
    // LinkRow: label span + otherIssue.key anchor + otherIssue.summary span
    const linkLabel = linksSection.getByText('blocks', { exact: true })
    await expect(linkLabel).toBeVisible()

    const targetKeyAnchor = linksSection.getByRole('link', { name: TARGET_KEY })
    await expect(targetKeyAnchor).toBeVisible()

    // Then. 빈 상태 메시지 사라짐
    await expect(linksSection.getByText('링크가 없습니다.')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-2 에러 인라인 — 자기참조 422
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  // When    대상 키에 현재 이슈 키(ATLAS-1)를 입력 후 "링크 추가" 클릭
  //         → MSW createLinkHandler: targetKey === key → 422 LINK_SELF_REFERENCE
  // Then    links-section 내 role="alert" 인라인 에러 표시
  //         "자기 자신에게 링크할 수 없습니다." 텍스트 포함
  //         토스트(Sonner)가 아닌 인라인 에러임 확인
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-2 에러 인라인 — 자기참조(ATLAS-1→ATLAS-1) 시 422 → 인라인 에러 표시', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)

    const linksSection = page.getByTestId(LINKS_SECTION_TESTID)

    // When. 자기참조 — 대상 키에 현재 이슈 키 입력
    const targetKeyInput = linksSection.getByLabel(TARGET_KEY_INPUT_LABEL)
    await targetKeyInput.fill(ISSUE_KEY)

    const addButton = linksSection.getByRole('button', { name: ADD_LINK_BUTTON_LABEL, exact: true })
    await addButton.click()

    // Then. 인라인 에러 (role="alert") 표시
    const inlineError = linksSection.getByRole('alert')
    await expect(inlineError).toBeVisible()
    await expect(inlineError).toContainText('자기 자신에게 링크할 수 없습니다.')

    // Then. 링크 목록은 여전히 빈 상태 — 링크 행 없음
    await expect(linksSection.getByRole('link', { name: ISSUE_KEY })).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-2b 에러 인라인 — 중복 추가 409
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         ATLAS-2에 blocks 링크를 1회 추가한 상태
  // When    동일 유형(blocks) + 동일 대상(ATLAS-2) 재추가
  //         → MSW createLinkHandler: isDuplicate=true → 409 DUPLICATE_LINK
  // Then    role="alert" 인라인 에러 "이미 동일한 링크가 존재합니다."
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-2b 에러 인라인 — 중복 추가(409) 시 인라인 에러 표시', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)

    const linksSection = page.getByTestId(LINKS_SECTION_TESTID)
    const targetKeyInput = linksSection.getByLabel(TARGET_KEY_INPUT_LABEL)
    const addButton = linksSection.getByRole('button', { name: ADD_LINK_BUTTON_LABEL, exact: true })

    // 사전 상태. blocks + ATLAS-2 링크 1회 추가
    await targetKeyInput.fill(TARGET_KEY)
    await addButton.click()
    // 첫 번째 추가 성공 확인 — 링크 행 표시 대기
    await expect(linksSection.getByRole('link', { name: TARGET_KEY })).toBeVisible()

    // When. 동일 링크 재추가
    await targetKeyInput.fill(TARGET_KEY)
    await addButton.click()

    // Then. 인라인 에러 표시
    const inlineError = linksSection.getByRole('alert')
    await expect(inlineError).toBeVisible()
    await expect(inlineError).toContainText('이미 동일한 링크가 존재합니다.')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-3 링크 제거
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         blocks 유형 ATLAS-2 링크가 1개 존재
  // When    링크 행의 "링크 제거" 버튼 클릭
  //         → MSW deleteLinkHandler: 204 → invalidate refetch
  // Then    링크 행 사라짐 — 빈 상태 메시지 "링크가 없습니다." 복원
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-3 링크 제거 — 추가된 링크 제거 버튼 클릭 시 링크 행 사라짐', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)

    const linksSection = page.getByTestId(LINKS_SECTION_TESTID)
    const targetKeyInput = linksSection.getByLabel(TARGET_KEY_INPUT_LABEL)
    const addButton = linksSection.getByRole('button', { name: ADD_LINK_BUTTON_LABEL, exact: true })

    // 사전 상태. 링크 추가
    await targetKeyInput.fill(TARGET_KEY)
    await addButton.click()
    await expect(linksSection.getByRole('link', { name: TARGET_KEY })).toBeVisible()

    // When. "링크 제거" 버튼 클릭 — 링크 행 내 버튼 (aria-label="링크 제거")
    const removeButton = linksSection.getByRole('button', {
      name: REMOVE_LINK_BUTTON_LABEL,
      exact: true,
    })
    await expect(removeButton).toBeVisible()
    await removeButton.click()

    // Then. 링크 행 사라짐
    await expect(linksSection.getByRole('link', { name: TARGET_KEY })).toHaveCount(0)

    // Then. 빈 상태 메시지 복원
    await expect(linksSection.getByText('링크가 없습니다.')).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-4 부모 set + clear
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         parent-section에 부모 미설정 상태 (부모 키 input 표시)
  // When    부모 키 input에 ATLAS-3 입력 + "부모 지정" 클릭
  //         → MSW setParentHandler: 200 + issueOverrides 영속 + issueQueryKey invalidate
  // Then    parent-section에 "ATLAS-3" 링크 텍스트 표시
  //         "해제" 버튼 노출
  //
  // When2   "해제" 버튼 클릭
  //         → MSW setParentHandler: parentKey=null → issueOverrides에서 parent 제거 + invalidate
  // Then2   "ATLAS-3" 링크 사라짐
  //         부모 키 input 다시 표시
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-4 부모 set + clear — 부모 지정 후 해제 → 표시/미표시 전환', async ({ page }) => {
    // Given.
    await navigateToIssueDetail(page)

    const parentSection = page.getByTestId(PARENT_SECTION_TESTID)

    // Given. 부모 미설정 — 부모 키 input 표시
    const parentKeyInput = parentSection.getByLabel(PARENT_KEY_INPUT_LABEL)
    await expect(parentKeyInput).toBeVisible()

    // When. 부모 키 입력 + 부모 지정 클릭
    await parentKeyInput.fill(PARENT_KEY)
    const setParentButton = parentSection.getByRole('button', {
      name: SET_PARENT_BUTTON_LABEL,
      exact: true,
    })
    await expect(setParentButton).not.toBeDisabled()
    await setParentButton.click()

    // Then. 부모 이슈 링크 표시 (ParentSection hasParent=true 분기)
    // ParentSection: parent 있음 → <a aria-label={parent.key}>{parent.key}</a>
    const parentLink = parentSection.getByRole('link', { name: PARENT_KEY })
    await expect(parentLink).toBeVisible()

    // Then. "해제" 버튼 노출
    const clearButton = parentSection.getByRole('button', {
      name: CLEAR_PARENT_BUTTON_LABEL,
      exact: true,
    })
    await expect(clearButton).toBeVisible()

    // Then. 부모 키 input 사라짐 (hasParent=true 분기)
    await expect(parentKeyInput).toHaveCount(0)

    // When2. 부모 해제
    await clearButton.click()

    // Then2. 부모 링크 사라짐
    await expect(parentSection.getByRole('link', { name: PARENT_KEY })).toHaveCount(0)

    // Then2. 부모 키 input 복원 (hasParent=false 분기)
    await expect(parentSection.getByLabel(PARENT_KEY_INPUT_LABEL)).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-4b 부모 재진입 유지 — issueOverrides 영속 검증
  //
  // Given   E2E-4와 동일하게 ATLAS-1에 ATLAS-3을 부모로 지정한 상태
  // When    이슈 상세 페이지를 페이지 새로고침(reload) 없이 SPA 재진입
  //         (이슈 상세 → /dashboard → 이슈 상세 SPA 내부 이동)
  //         → issueQueryKey invalidate 후 getIssueHandler 재조회
  //         → issueOverrides.get(ATLAS-1)에 parent가 영속돼 있으므로 parent 필드 반환
  // Then    재진입 후에도 parent-section에 "ATLAS-3" 링크 표시 유지
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-4b 부모 재진입 유지 — issueOverrides 영속으로 페이지 재진입 후도 부모 표시', async ({ page }) => {
    // Given. 이슈 상세 진입 + 부모 지정
    await navigateToIssueDetail(page)

    const parentSection = page.getByTestId(PARENT_SECTION_TESTID)
    const parentKeyInput = parentSection.getByLabel(PARENT_KEY_INPUT_LABEL)
    await parentKeyInput.fill(PARENT_KEY)
    const setParentButton = parentSection.getByRole('button', {
      name: SET_PARENT_BUTTON_LABEL,
      exact: true,
    })
    await setParentButton.click()

    // 부모 지정 완료 확인
    await expect(parentSection.getByRole('link', { name: PARENT_KEY })).toBeVisible()

    // When. SPA 내부 이동 — /dashboard로 이동 후 다시 이슈 상세로 복귀
    // (window.history.pushState → popstate 방식으로 ServiceWorker 재시작 없이 이동)
    await page.evaluate(() => {
      window.history.pushState({}, '', '/dashboard')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    // /dashboard 진입 대기
    await page.waitForURL('**/dashboard*')

    // 이슈 상세로 재진입
    await navigateToIssueDetail(page)

    // Then. 재진입 후에도 parent-section에 ATLAS-3 링크 유지
    const parentSectionAfter = page.getByTestId(PARENT_SECTION_TESTID)
    await expect(parentSectionAfter.getByRole('link', { name: PARENT_KEY })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-5 (선택) blocks 순환 — LINK_CYCLE 409 인라인 에러
  //
  // 구현 현황.
  //   - issue-link-handlers.ts의 createLinkHandler에 순환 감지 로직 없음.
  //   - LINK_CYCLE 에러는 백엔드만 감지; MSW는 현재 순환 감지를 별도 구현하지 않는다.
  //   - 자기참조(422 LINK_SELF_REFERENCE)와 중복(409 DUPLICATE_LINK)은 MSW에서 처리 가능.
  //
  // 따라서 E2E-5는 MSW 수준에서 순환 감지가 구현되지 않아 SKIPPED.
  // 순환 감지는 백엔드 통합테스트 범위 (FR-LK-01 D3~D5 백엔드 E2E 별도 트랙).
  // ─────────────────────────────────────────────────────────────────────────
  // test('E2E-5 순환 링크 409 — blocks A→B→A 순환 시 인라인 에러', ...)
  // SKIPPED: MSW createLinkHandler에 순환 감지 없음 — 백엔드 통합테스트 트랙
})
