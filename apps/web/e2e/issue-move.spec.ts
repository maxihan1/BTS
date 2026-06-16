// FR-MV-01 D7 E2E — 이슈 이동 마법사 시나리오 (단건 이동 + 서브태스크 동반 + 옛 키 308 redirect)
//
// Given-When-Then 명시 — spec S1/S3/S4 대응.
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 버튼(이슈 이동/취소/이동 등)은
//     exact:true 또는 data-testid / aria-label 컨테이너 한정
//   - msw-mutation-stateful-refetch: moveIssueHandler가 movedIssueStore에 기록 →
//     이후 GET :oldKey에서 308 redirect 시뮬
//   - msw-derived-behavior-shared-store-e2e: 파생 응답(redirect)은 공유 store에서 읽음
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - e2e-msw-scenario-toggle-localstorage-flag: subtask 시나리오는
//     localStorage '__bts_e2e_move_scenario'='subtask' addInitScript로 분기
//   - worktree-stale-base-rebase-and-e2e-msw-traps: SPA 내부 이동(pushState+popstate),
//     드롭다운/로딩 대기, CSRF 쿠키 수동 시드
//   - e2e-fixture-whoami-userid-alignment: loginAsAlice 재사용 (alice=ADMIN, canEdit=true)
//   - ui-pr-defer-e2e-regression-latent: 이슈 이동 버튼 추가로 기존 issue E2E 회귀 확인
//
// MSW 핵심 사항.
//   - issue-move-handlers.ts: movedIssueStore(stateful) — move 후 GET :oldKey → 308
//   - 시나리오 분기: LS_KEY_MOVE_SCENARIO='subtask'이면 subtaskPreviewFixture 반환
//   - 새 키 이슈 조회: newKeyIssueHandler가 INFRA-5/6/7 응답
//   - 핸들러 우선순위: issueMoveHandlers가 issueHandlers보다 먼저 등록됨 (handlers.ts)
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { issueMoveStrings } from '../src/i18n/ko'
import { LS_KEY_MOVE_SCENARIO } from '../src/mocks/issue-move-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture, 단건 이동 및 서브태스크 공통) */
const ISSUE_WITH_SUBTASKS_URL = '/issues/ATLAS-1'

/** 이동 대상 프로젝트 키 */
const TARGET_PROJECT_KEY = 'INFRA'

/** 이동 후 새 키 */
const NEW_KEY = 'INFRA-5'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * "이슈 이동" 버튼(aria-label="이슈 이동")이 렌더될 때까지 대기.
 *
 * ATLAS-1은 issue-fixtures.ts 정적 fixture에 있으므로 이슈 상세가 정상 렌더된다.
 * 이동 시 ATLAS-1 → INFRA-5로 MSW 핸들러가 처리한다.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl: string,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  // "이슈 이동" 버튼이 표시될 때까지 대기 (canEdit=true, alice=ADMIN)
  await expect(page.getByRole('button', { name: '이슈 이동', exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-MV-01 D7 이슈 이동 마법사 (MoveIssueDialog + 308 redirect)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 → canEdit=true → "이슈 이동" 버튼 활성
    await loginAsAlice(page)
    // loginAsAlice 완료 시 /dashboard 진입 → ServiceWorker 기동 완료
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-1 단건 이동 (S1 — 완전 호환)
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입 (단건, subtasks=[], 완전 호환)
  // When    "이슈 이동" 버튼 클릭
  //         → MoveIssueDialog Step 1 열림
  //         → 대상 프로젝트 키 'INFRA' 입력 + "다음" 버튼 클릭
  //         → MSW previewHandler: compatiblePreviewFixture 반환 (LS 플래그 미설정)
  //         → Step 2 매핑 섹션 렌더 (compatible=true → 비호환 안내 없음)
  //         → "이동" 버튼 클릭
  //         → MSW moveIssueHandler: { issueKey: 'INFRA-5', movedSubtasks: [] }
  //         → movedIssueStore.set('ATLAS-1', { oldKey:'ATLAS-1', newKey:'INFRA-5' })
  // Then    이동 성공 토스트 "이슈가 이동되었습니다." 표시
  //         URL이 /issues/INFRA-5로 이동
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-1 단건 이동 happy path — 대상 입력 → preview → 이동 → 새 키 URL', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page, ISSUE_WITH_SUBTASKS_URL)

    // When. "이슈 이동" 버튼 클릭 → Dialog 열림
    const moveBtn = page.getByRole('button', { name: '이슈 이동', exact: true })
    await moveBtn.click()

    // Then. Dialog 제목 표시 — getByRole('heading')으로 한정 (버튼 "이슈 이동" 텍스트와 중복 회피)
    await expect(page.getByRole('heading', { name: issueMoveStrings.dialogTitle, exact: true })).toBeVisible()

    // When. Step 1 — 대상 프로젝트 키 입력
    const targetInput = page.getByLabel(issueMoveStrings.targetProjectKeyLabel, { exact: true })
    await expect(targetInput).toBeVisible()
    await targetInput.fill(TARGET_PROJECT_KEY)

    // When. "다음" 버튼 클릭 → preview 호출
    const nextBtn = page.getByRole('button', { name: issueMoveStrings.nextButton, exact: true })
    await expect(nextBtn).not.toBeDisabled()
    await nextBtn.click()

    // Then. Step 2 — 매핑 섹션 렌더 (루트 섹션)
    const rootSection = page.getByTestId('node-section-root')
    await expect(rootSection).toBeVisible()

    // Then. compatible=true → 비호환 안내 없음
    await expect(page.getByText(issueMoveStrings.workflowIncompatible)).toHaveCount(0)

    // When. "이동" 버튼 클릭 → move 실행
    const executeMoveBtn = page.getByRole('button', { name: issueMoveStrings.moveButton, exact: true })
    await expect(executeMoveBtn).not.toBeDisabled()
    await executeMoveBtn.click()

    // Then. 성공 토스트 표시
    await expect(page.getByText(issueMoveStrings.moveSuccessToast)).toBeVisible()

    // Then. URL이 /issues/INFRA-5로 이동 (navigate replace)
    await page.waitForURL(`**/${NEW_KEY}`)
  })

  // E2E-2는 아래 별도 describe 블록에서 정의됨 (addInitScript가 loginAsAlice보다 먼저 실행되어야 함)

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-3 옛 키 308 redirect (S4) — SKIPPED
  //
  // MSW ServiceWorker 환경에서 Response(null, { status: 308 })를 브라우저 fetch가
  // 자동 follow하지 않는다. fetch redirect:'follow'(기본)는 non-opaque HTTP redirect만
  // 추적하며, MSW SW가 반환한 308 응답은 opaque redirect로 처리되어
  // response.redirected === true가 되지 않는다.
  //
  // fetchIssue는 response.redirected를 체크해 IssueRedirectError를 throw하는데,
  // E2E MSW 환경에서는 이 경로를 검증할 수 없다.
  //
  // 대안 coverage.
  //   - issues.test.ts 단위 테스트: fetchIssue redirected:true → IssueRedirectError(newKey) 분기 검증됨
  //     (vi.spyOn(globalThis,'fetch')로 redirected Response 주입 — T1-2c-1/T1-2c-2)
  //   - 백엔드 통합 테스트: GET :oldKey → 308 + Location 응답 검증됨
  //
  // 이 시나리오는 실 백엔드 연동 E2E 환경(CI staging)에서 검증 예정.
  // ─────────────────────────────────────────────────────────────────────────
  // test('E2E-3 옛 키 308 redirect — SKIPPED: MSW SW는 308 opaque redirect를 유발하지 않음')
  // SKIPPED: MSW fetch redirect 시뮬 불가 — fetchIssue 308 redirected→IssueRedirectError 분기는 issues.test.ts 단위 테스트로 커버, navigate replace는 issues.$key 경로. E2E는 MSW가 non-opaque 308을 못 만드는 한계로 SKIP

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-4 Dialog 취소 — 이동 취소 시 URL 유지, Dialog 닫힘
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  // When    "이슈 이동" 버튼 클릭 → Dialog 열림 → "취소" 클릭
  // Then    Dialog 닫힘 + URL /issues/ATLAS-1 유지
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-4 Dialog 취소 — 취소 시 Dialog 닫힘 + URL 유지', async ({ page }) => {
    // Given. 이슈 상세 진입
    await navigateToIssueDetail(page, ISSUE_WITH_SUBTASKS_URL)

    // When. Dialog 열기
    await page.getByRole('button', { name: '이슈 이동', exact: true }).click()
    await expect(page.getByRole('heading', { name: issueMoveStrings.dialogTitle, exact: true })).toBeVisible()

    // When. "취소" 클릭 (Step 1 내 DialogPrimitive.Close 버튼)
    // issueMoveStrings.cancelButton = '취소'
    // Dialog 내 취소 버튼은 aria-label이 없고 텍스트만 있으므로 컨테이너 없이 단일 매칭 가능
    // (이슈 상세 페이지에 다른 "취소" 버튼이 현재 없음 — exact:true로 안전하게)
    await page.getByRole('button', { name: issueMoveStrings.cancelButton, exact: true }).click()

    // Then. Dialog 닫힘
    await expect(page.getByRole('heading', { name: issueMoveStrings.dialogTitle, exact: true })).toHaveCount(0)

    // Then. URL 유지 (SPA 내부 이동이므로 waitForURL 불필요 — 즉시 확인)
    await expect(page).toHaveURL(new RegExp('ATLAS-1$'))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E2E-2 서브태스크 동반 이동 — 별도 describe 블록
//
// addInitScript는 page.goto 이전에 등록해야 효과가 있으므로,
// beforeEach에서 loginAsAlice(page.goto('/login')) 이전에 실행되도록
// 자체 beforeEach를 가진 독립 describe 블록으로 분리한다.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-MV-01 D7 이슈 이동 — 서브태스크 동반 (subtask 시나리오)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. subtask 시나리오 플래그 — loginAsAlice(goto) 이전에 등록
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'subtask')
    }, LS_KEY_MOVE_SCENARIO)

    // Given. alice(ADMIN)로 로그인
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-2 서브태스크 동반 이동 (S2)
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  //         localStorage '__bts_e2e_move_scenario'='subtask' (addInitScript)
  //         → previewHandler: subtaskPreviewFixture 반환 (ATLAS-13, ATLAS-14 subtasks)
  // When    "이슈 이동" 버튼 클릭
  //         → Step 1 대상 'INFRA' 입력 + "다음"
  //         → Step 2: 루트 섹션 + subtask 섹션 2개(ATLAS-13, ATLAS-14) 렌더
  //         → "이동" 클릭
  //         → MSW moveIssueHandler: movedSubtasks=[ATLAS-13→INFRA-6, ATLAS-14→INFRA-7]
  // Then    "3개 이슈가 이동되었습니다." 토스트 표시
  //         URL이 /issues/INFRA-5로 이동
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-2 서브태스크 동반 이동 — 노드별 섹션 렌더 + movedSubtasks 토스트', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page, ISSUE_WITH_SUBTASKS_URL)

    // When. "이슈 이동" 버튼 클릭
    await page.getByRole('button', { name: '이슈 이동', exact: true }).click()

    // Then. Dialog 열림
    await expect(page.getByRole('heading', { name: issueMoveStrings.dialogTitle, exact: true })).toBeVisible()

    // When. Step 1 — 대상 입력 + "다음"
    await page.getByLabel(issueMoveStrings.targetProjectKeyLabel, { exact: true }).fill(TARGET_PROJECT_KEY)
    await page.getByRole('button', { name: issueMoveStrings.nextButton, exact: true }).click()

    // Then. Step 2 — 루트 섹션 + subtask 섹션 2개
    await expect(page.getByTestId('node-section-root')).toBeVisible()
    await expect(page.getByTestId('node-section-ATLAS-13')).toBeVisible()
    await expect(page.getByTestId('node-section-ATLAS-14')).toBeVisible()

    // Then. 서브태스크 섹션 헤더 표시
    await expect(page.getByText(issueMoveStrings.subtaskSectionHeader)).toBeVisible()

    // When. "이동" 버튼 클릭
    const executeMoveBtn = page.getByRole('button', { name: issueMoveStrings.moveButton, exact: true })
    await expect(executeMoveBtn).not.toBeDisabled()
    await executeMoveBtn.click()

    // Then. 서브태스크 포함 이동 성공 토스트 — "3개 이슈가 이동되었습니다."
    await expect(page.getByText(issueMoveStrings.moveSuccessWithSubtasksToast(3))).toBeVisible()

    // Then. URL이 /issues/INFRA-5로 이동
    await page.waitForURL(`**/${NEW_KEY}`)
  })
})
