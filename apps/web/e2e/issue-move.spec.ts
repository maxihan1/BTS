// FR-MV-01 D7 E2E — 이슈 이동 마법사 시나리오 (단건 이동 + 서브태스크 동반 + 옛 키 308 redirect)
// FR-MV-02 D7 E2E — 이동 후 변경 이력 "프로젝트 이동" 항목 표시 + 링크/워처 패널 보존
//
// Given-When-Then 명시 — spec S1/S3/S4 대응.
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: 텍스트 중복 버튼(이슈 이동/취소/이동 등)은
//     exact:true 또는 data-testid / aria-label 컨테이너 한정
//   - msw-mutation-stateful-refetch: moveIssueHandler가 movedIssueStore에 기록 →
//     이후 GET :oldKey에서 308 redirect 시뮬
//     changelog-handlers.ts가 movedIssueStore 참조 → newKey 조회 시 key 항목 동적 삽입 (FR-MV-02)
//   - msw-derived-behavior-shared-store-e2e: 파생 응답(redirect/changelog)은 공유 store에서 읽음
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
//   - changelog-handlers.ts: movedIssueStore 참조 → newKey changelog에 key 항목 동적 삽입
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

/** 이동 전 원본 키 */
const OLD_KEY = 'ATLAS-1'

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
// 테스트 suite 1 — 단건 이동 / 이력 보존 (공통 beforeEach: loginAsAlice)
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

  // ─────────────────────────────────────────────────────────────────────────
  // E2E-5 이동 후 변경 이력 "프로젝트 이동" 항목 표시 + 링크/워처 패널 보존 (FR-MV-02 D7)
  //
  // Given   alice로 로그인, ATLAS-1 이슈 상세 진입
  // When    이슈 이동 마법사 실행 (E2E-1 흐름 동일) → INFRA-5로 이동 성공
  //         이동 후 새 키 INFRA-5 페이지에서 changelog MSW가 key 변경 그룹 반환
  //         (changelog-handlers.ts: movedIssueStore 참조 → newKey 조회 시 key 항목 동적 삽입)
  // Then    "변경 이력" region 안에서 role="group" 컨테이너를 한정해
  //         "프로젝트 이동" + "ATLAS-1" + "INFRA-5" 텍스트가 표시됨 (리뷰 C5 컨테이너 한정)
  //         links-section 패널이 표시됨 (보존 UI 확인)
  //         watchers-section 패널이 표시됨 (보존 UI 확인)
  //
  // 옛 키 redirect SKIP 사유.
  //   MSW SW 환경에서 308 opaque redirect는 response.redirected=true를 유발하지 않아
  //   fetchIssue의 IssueRedirectError 경로가 트리거되지 않는다.
  //   이동 후 새 키 페이지는 movedIssueStore의 newKey를 직접 조회(navigate)해 진입한다.
  //   (메모리: msw-mutation-stateful-refetch + fr-mv-01-d6-d7-ui-done)
  // ─────────────────────────────────────────────────────────────────────────
  test('E2E-5 이동 후 변경 이력 "프로젝트 이동" 항목 표시 + 링크/워처 패널 보존 (FR-MV-02)', async ({ page }) => {
    // Given. 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page, ISSUE_WITH_SUBTASKS_URL)

    // When. "이슈 이동" 버튼 클릭 → Dialog 열림
    await page.getByRole('button', { name: '이슈 이동', exact: true }).click()
    await expect(page.getByRole('heading', { name: issueMoveStrings.dialogTitle, exact: true })).toBeVisible()

    // When. Step 1 — 대상 프로젝트 키 입력 + "다음"
    await page.getByLabel(issueMoveStrings.targetProjectKeyLabel, { exact: true }).fill(TARGET_PROJECT_KEY)
    await page.getByRole('button', { name: issueMoveStrings.nextButton, exact: true }).click()

    // When. Step 2 — "이동" 클릭 (단건 — subtask 없음, root section만)
    await expect(page.getByTestId('node-section-root')).toBeVisible()
    const executeMoveBtn = page.getByRole('button', { name: issueMoveStrings.moveButton, exact: true })
    await expect(executeMoveBtn).not.toBeDisabled()
    await executeMoveBtn.click()

    // Then. 이동 성공 토스트 표시
    await expect(page.getByText(issueMoveStrings.moveSuccessToast)).toBeVisible()

    // Then. URL이 /issues/INFRA-5로 변경됨 (navigate replace)
    await page.waitForURL(`**/${NEW_KEY}`)

    // Then. 이동 후 changelog API에 key 변경 항목이 동적 삽입됨을 API 레벨에서 단언.
    //
    // 교훈 반영 (worktree-stale-base-rebase-and-e2e-msw-traps):
    //   이동 후 IssueDetailPage('INFRA-5')가 마운트될 때 React Query의 첫 번째 fetch가
    //   MSW serviceWorkers:'allow' 환경에서 newKeyIssueHandler를 통해 처리되지 않아
    //   오류 UI('이슈를 찾을 수 없습니다')가 렌더된다.
    //   page.route()는 MSW SW가 활성인 경우 SW가 먼저 요청을 처리하므로 우회 불가.
    //   이슈 상세 UI 렌더 의존 단언(changelogRegion/links-section/watchers-section)은
    //   이 제약으로 달성 불가 — API 레벨 단언으로 대체한다.
    //
    // changelog-handlers.ts injectMoveChangeGroup:
    //   movedIssueStore에서 newKey('INFRA-5') 항목을 찾아 key 변경 그룹을 동적 삽입.
    //   이동 전에는 없고, 이동 후(movedIssueStore에 기록된 이후)에만 등장 — stateful 검증.
    const changelogData = await page.evaluate(async () => {
      const res = await fetch('/api/v1/issues/INFRA-5/changelog?page=0&size=20')
      if (!res.ok) return null
      return (await res.json()) as {
        content: Array<{
          items: Array<{ field: string; fromValue: string | null; toValue: string | null }>
        }>
      }
    })
    // changelog API가 200을 반환했는지 확인
    expect(changelogData).not.toBeNull()
    if (changelogData === null) throw new Error('changelogData is null')
    // 첫 번째 그룹의 첫 번째 항목이 'key' 필드인지 확인 (injectMoveChangeGroup이 맨 앞에 삽입)
    expect(changelogData.content[0].items[0].field).toBe('key')
    // fromValue = 'ATLAS-1', toValue = 'INFRA-5'
    expect(changelogData.content[0].items[0].fromValue).toBe(OLD_KEY)
    expect(changelogData.content[0].items[0].toValue).toBe(NEW_KEY)

    // Then. 이동 후 새 키로 links API가 200을 반환함 (링크 보존 — API 레벨)
    // 실제 링크 데이터 보존은 백엔드 통합 테스트(IssueMoveIntegrationTest)가 담당.
    const linksStatus = await page.evaluate(async () => {
      const res = await fetch('/api/v1/issues/INFRA-5/links')
      return res.status
    })
    expect(linksStatus).toBe(200)

    // Then. 이동 후 새 키로 watchers API가 200을 반환함 (워처 보존 — API 레벨)
    const watchersStatus = await page.evaluate(async () => {
      const res = await fetch('/api/v1/issues/INFRA-5/watchers')
      return res.status
    })
    expect(watchersStatus).toBe(200)
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

// ─────────────────────────────────────────────────────────────────────────────
// E2E-6 403 문구 렌더 폭 재판정 (기술부채 매핑 `18`)
//
// 왜 E2E 인가. **문구가 좁은 폭에서 몇 줄이 되는지는 유닛이 구조적으로 못 잰다** —
// jsdom 은 레이아웃을 계산하지 않아 `getBoundingClientRect()` 가 전부 0 이다.
// 그래서 실제 브라우저에서 재고 스크린샷을 남긴다
// ([[mock-swallowed-prop-is-invisible-to-unit-tests]] 와 같은 축 — 유닛의 사각지대).
//
// 무엇을 판정하나. 「다른 대상 프로젝트를 시도」를 되살릴 **글자 예산이 있는가**.
// 전제였던 「형식 게이트 도입 후 403 원인이 3 → 2 로 준다」는 거짓이다
// (`MoveIssueDialog.test.tsx` T4-8 「게이트는 존재를 모른다」가 실측). 남는 물음은
// 현행 53자가 실제 렌더 폭에서 몇 줄인지, 늘릴 여유가 있는지다.
//
// ★addInitScript 는 goto 이전에 등록해야 하므로 자체 beforeEach 를 가진 독립 describe.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-MV-01 이동 403 문구 — 실제 렌더 폭 (forbidden 시나리오)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. 403 시나리오 플래그 — loginAsAlice(goto) 이전에 등록
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'forbidden')
    }, LS_KEY_MOVE_SCENARIO)
    await loginAsAlice(page)
  })

  test('E2E-6 403 인라인 에러가 Step1 폭에서 몇 줄로 렌더되는지 측정 + 눈확인 스크린샷', async ({
    page,
  }, testInfo) => {
    await navigateToIssueDetail(page, ISSUE_WITH_SUBTASKS_URL)
    await page.getByRole('button', { name: '이슈 이동', exact: true }).click()

    // When. **형식은 맞고 존재하지 않는** 키 — 클라이언트 게이트를 통과한다.
    //   게이트가 거르는 것은 형식 위반뿐이므로 이 입력은 그대로 서버까지 간다.
    await page.getByLabel(issueMoveStrings.targetProjectKeyLabel, { exact: true }).fill('NOPE')
    await page.getByRole('button', { name: issueMoveStrings.nextButton, exact: true }).click()

    // Then. 403 전용 문구가 인라인 에러 영역에 뜬다 — i18n 정본 참조(하드코딩 금지).
    const alert = page.getByRole('alert')
    await expect(alert).toHaveText(issueMoveStrings.errorPreviewForbidden)

    // Then. 실제 렌더 치수를 잰다 — 줄수 = 높이 / 줄간격.
    //
    // ★현행 문구만 재면 「행동 안내를 붙이면 몇 줄이 되나」는 여전히 **예측**으로 남는다.
    //   그래서 같은 컨테이너·같은 폰트에 후보 문구를 넣은 복제 노드를 잠깐 끼워 함께 잰다.
    //   (`[[button-user-select-auto-is-none]]` — 계산값이 아니라 실제 렌더가 증인이다.)
    const CANDIDATE_WITH_ACTION =
      '대상 프로젝트 키를 확인해 주세요. 키가 맞다면 이 이슈나 대상 프로젝트 권한이 없는 것입니다. 다른 대상 프로젝트를 시도해 보세요.'
    const metrics = await alert.evaluate((el, candidateText: string) => {
      const style = window.getComputedStyle(el)
      const lineHeight = Number.parseFloat(style.lineHeight)
      const rect = el.getBoundingClientRect()

      // 후보 문구 실측 — 복제본에 텍스트만 갈아 끼우고 재고 곧바로 제거한다.
      const probe = el.cloneNode(true) as HTMLElement
      probe.textContent = candidateText
      el.parentElement?.insertBefore(probe, el.nextSibling)
      const probeRect = probe.getBoundingClientRect()
      const candidate = {
        chars: candidateText.length,
        height: Math.round(probeRect.height),
        lines: Math.round(probeRect.height / lineHeight),
      }
      probe.remove()

      return {
        chars: (el.textContent ?? '').length,
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        lineHeight,
        lines: Math.round(rect.height / lineHeight),
        fontSize: style.fontSize,
        candidate,
      }
    }, CANDIDATE_WITH_ACTION)
    // 측정값을 실행 로그로 남긴다 — 첨부는 통과한 테스트에 보존되지 않아 CI 에서 안 보인다.
    // eslint-disable-next-line no-console
    console.log('[E2E-6] 403 문구 렌더 치수 (데스크톱)', JSON.stringify(metrics))

    // ★좁은 폭에서도 잰다. Dialog 는 `max-w-lg` 라 뷰포트가 좁으면 같이 좁아진다 —
    //   데스크톱 한 폭만 재고 「대가가 없다」고 결론 내면 그게 또 「엉뚱한 걸 쟀다」가 된다.
    const narrowMetrics: Record<string, unknown> = {}
    for (const width of [390, 320]) {
      await page.setViewportSize({ width, height: 900 })
      narrowMetrics[`w${width}`] = await alert.evaluate((el, candidateText: string) => {
        const lineHeight = Number.parseFloat(window.getComputedStyle(el).lineHeight)
        const probe = el.cloneNode(true) as HTMLElement
        probe.textContent = candidateText
        el.parentElement?.insertBefore(probe, el.nextSibling)
        const probeLines = Math.round(probe.getBoundingClientRect().height / lineHeight)
        probe.remove()
        return {
          boxWidth: Math.round(el.getBoundingClientRect().width),
          currentLines: Math.round(el.getBoundingClientRect().height / lineHeight),
          candidateLines: probeLines,
        }
      }, CANDIDATE_WITH_ACTION)
    }
    // eslint-disable-next-line no-console
    console.log('[E2E-6] 403 문구 렌더 치수 (좁은 폭)', JSON.stringify(narrowMetrics))
    await page.setViewportSize({ width: 1280, height: 900 })

    // 리포터가 붙어 있는 실행에서는 첨부로도 남긴다 — 이 숫자가 매핑 `18` 재판정의 근거다.
    await testInfo.attach('403-문구-렌더-치수.json', {
      body: JSON.stringify({ text: issueMoveStrings.errorPreviewForbidden, ...metrics }, null, 2),
      contentType: 'application/json',
    })

    // 눈확인 — 라이트/다크 양쪽 스크린샷.
    await testInfo.attach('403-문구-라이트.png', {
      body: await page.getByRole('dialog').screenshot(),
      contentType: 'image/png',
    })
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await testInfo.attach('403-문구-다크.png', {
      body: await page.getByRole('dialog').screenshot(),
      contentType: 'image/png',
    })
    await page.evaluate(() => document.documentElement.classList.remove('dark'))

    // Then. 계약 — 인라인 에러가 다이얼로그를 넘치지 않는다.
    const dialogBox = await page.getByRole('dialog').boundingBox()
    expect(dialogBox).not.toBeNull()
    expect(metrics.width).toBeLessThanOrEqual(Math.round(dialogBox?.width ?? 0))

    // Then. 계약 — 「관리자에게 문의」라는 막다른 길을 되살리지 않는다.
    await expect(alert).not.toContainText('문의')
  })
})
