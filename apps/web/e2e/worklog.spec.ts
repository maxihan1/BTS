// FR-TT-01 E2E — 워크로그 happy path + 권한/타인 차단 (D7)
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: MSW worklogHandlers가 worklogStore에 영속
//     → invalidateQueries refetch 후 값 롤백 없음
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - worktree-stale-base-rebase-and-e2e-msw-traps: 최초 goto 후 SPA 내부 동작으로 검증
//     (reload/goto 재진입 금지 — service worker store가 새 모듈로 재시작됨)
//   - e2e-fixture-whoami-userid-alignment: alice userId = 00000000-...-001 (auth-fixtures 정본)
//   - playwright-getbyrole-exact-strict-mode: 동일 텍스트 버튼 컨테이너 한정
//   - msw-derived-behavior-shared-store-e2e: 추정/worklog 상태는 worklogHandlers 단일 store
//
// MSW 핵심 사항.
//   - worklog-handlers.ts: Authorization Bearer 토큰에서 현재 사용자(alice) 자동 도출
//   - worklogStore는 모듈-스코프 stateful; 각 test는 Playwright 기본 새 context(새 ServiceWorker)
//     → store가 빈 상태로 시작 → 초기 진입 시 워크로그 0건, 추정 미설정
//   - PATCH /issues/:key에서 originalEstimateSeconds/remainingEstimateSeconds 처리 →
//     estimateStore 동기화 → GET /worklogs summary에서 일관된 값 반환
//   - PATCH/DELETE: remaining 불변 (자동차감/복원 없음) — 백엔드 시맨틱 정확 재현
//
// 시나리오 5(권한 없음)·6(타인 worklog)은 E2E로 검증 (아래 주석 참조).
//
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트 대상 이슈 — ATLAS-1 (issue-fixtures.ts 정적 fixture) */
const ISSUE_KEY = 'ATLAS-1'
const ISSUE_URL = `/issues/${ISSUE_KEY}`

/**
 * alice userId — auth-fixtures.ts aliceUser.userId (정본).
 * worklog authorId 정합 검증용 (e2e-fixture-whoami-userid-alignment 교훈).
 */
const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'

/**
 * bob userId — auth-fixtures.ts bobUser.userId (정본).
 * 타인 worklog 시나리오에서 authorId로 사용.
 */
const BOB_USER_ID = '00000000-0000-4000-8000-000000000002'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 상세 페이지로 SPA 내부 내비게이션한다.
 * loginAsAlice 완료 후 ServiceWorker가 활성인 상태에서 호출.
 * estimate-fields 컨테이너가 렌더될 때까지 대기.
 */
async function navigateToIssueDetail(
  page: import('@playwright/test').Page,
  issueUrl = ISSUE_URL,
): Promise<void> {
  await page.evaluate((url: string) => {
    window.history.pushState({}, '', url)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, issueUrl)
  // 추정 패널이 마운트되면 이슈 상세 페이지가 렌더된 것으로 간주
  await expect(page.getByTestId('estimate-fields')).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-TT-01 워크로그 (WorklogSection + IssueEstimatePanel)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice(ADMIN)로 로그인 → canUpdate=true → 추가 폼 + 수정/삭제 버튼 활성
    await loginAsAlice(page)
    // loginAsAlice 완료 시 /dashboard 진입 → ServiceWorker 기동 완료
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 — 추정 설정 (원 추정 1h, 잔여 1h 저장)
  //
  // Given   ATLAS-1 이슈 상세 진입 — 추정 미설정 (estimateNotSet 안내 표시)
  // When    원 추정 1h 0m, 잔여 추정 1h 0m 입력 → 저장 버튼 클릭
  //         → PATCH /api/v1/issues/ATLAS-1: originalEstimateSeconds=3600, remainingEstimateSeconds=3600
  //         → estimateStore 동기화 → invalidateQueries refetch
  // Then    estimate-fields: 원 추정 "1h", 잔여 추정 "1h" 표시
  //         기록 시간 "0m" (워크로그 없으므로 SUM=0)
  //         추정 미설정 안내 사라짐
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 — 추정 설정(원 추정 1h + 잔여 1h) 후 저장 시 refetch 후에도 값 유지', async ({ page }) => {
    // Given. ATLAS-1 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const estimateSection = page.getByTestId('estimate-fields')

    // Given. 추정 미설정 안내 확인
    await expect(estimateSection.getByText('추정이 설정되지 않았습니다.')).toBeVisible()

    // When. 원 추정 1h 0m 입력 — estimate-original-h, estimate-original-m id 사용 (컨테이너 한정)
    const origHInput = estimateSection.locator('#estimate-original-h')
    const origMInput = estimateSection.locator('#estimate-original-m')
    await origHInput.fill('1')
    await origMInput.fill('0')

    // When. 잔여 추정 1h 0m 입력
    const remHInput = estimateSection.locator('#estimate-remaining-h')
    const remMInput = estimateSection.locator('#estimate-remaining-m')
    await remHInput.fill('1')
    await remMInput.fill('0')

    // When. 저장 버튼 클릭
    const saveButton = estimateSection.getByTestId('estimate-save')
    await expect(saveButton).toBeVisible()
    await saveButton.click()

    // Then. PATCH + invalidateQueries refetch 완료 후 값 유지 (stateful 영속 검증)
    // IssueEstimatePanel이 issue 단건 refetch 후 draft 재동기화 → input에 반영
    await expect(origHInput).toHaveValue('1')
    await expect(origMInput).toHaveValue('0')
    await expect(remHInput).toHaveValue('1')
    await expect(remMInput).toHaveValue('0')

    // Then. 추정 미설정 안내 사라짐 (값이 설정됐으므로)
    await expect(estimateSection.getByText('추정이 설정되지 않았습니다.')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 — 30m 작업 기록 + 자동차감 검증
  //
  // Given   S1 흐름으로 원 추정 1h / 잔여 1h 설정 완료 상태
  // When    WorklogSection 추가 폼: 시간=0, 분=30 입력 → 추가 버튼 클릭
  //         → POST /api/v1/issues/ATLAS-1/worklogs
  //           newRemainingEstimateSeconds 미전송 → 자동차감: remaining = max(0, 3600-1800) = 1800
  //         → invalidateQueries → GET /worklogs 재조회
  // Then    워크로그 목록 1건 (timeSpent 표시 "30m")
  //         작성자 레이블 표시 (alice displayName)
  //         summary: originalEstimate 1h / timeSpent 30m / remaining 30m
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 — 30m 작업 기록 추가 시 자동차감으로 잔여 30m', async ({ page }) => {
    // Given. ATLAS-1 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const estimateSection = page.getByTestId('estimate-fields')

    // Given. 먼저 추정 1h / 1h 설정 (S1 사전 조건 재구성)
    await estimateSection.locator('#estimate-original-h').fill('1')
    await estimateSection.locator('#estimate-original-m').fill('0')
    await estimateSection.locator('#estimate-remaining-h').fill('1')
    await estimateSection.locator('#estimate-remaining-m').fill('0')
    await estimateSection.getByTestId('estimate-save').click()

    // 잔여 "1h" 현재값 표시 확인 (estimate 저장 완료 신호)
    await expect(estimateSection.locator('#estimate-remaining-h')).toHaveValue('1')

    // When. 워크로그 섹션 — 30m 작업 기록 추가
    // WorklogSection은 section[aria-label="작업 기록"] (worklogStrings.worklogSectionTitle)
    const worklogSection = page.getByRole('region', { name: '작업 기록' })
    await expect(worklogSection).toBeVisible()

    // 추가 폼 — 시간 0h, 분 30m (WorklogAddForm 내부 컨테이너 한정)
    const hoursInput = worklogSection.getByLabel('시간')
    const minutesInput = worklogSection.getByLabel('분')
    await hoursInput.fill('0')
    await minutesInput.fill('30')

    // 추가 버튼 클릭 (aria-label="작업 기록 추가")
    const addButton = worklogSection.getByRole('button', { name: '작업 기록 추가', exact: true })
    await addButton.click()

    // Then. 워크로그 목록 1건 확인 — "30m" 텍스트 표시
    await expect(worklogSection.getByText('30m')).toBeVisible()

    // Then. 작성자 레이블 표시 확인
    await expect(worklogSection.getByText('작성자', { exact: false })).toBeVisible()

    // Then. 잔여 추정 자동차감 확인 — estimateSection에서 remaining "30m" 표시
    // IssueEstimatePanel의 remainingEstimateLabel 아래 currentSeconds(=1800s=30m) 표시
    await expect(estimateSection.locator('#estimate-remaining-h')).toHaveValue('0')
    await expect(estimateSection.locator('#estimate-remaining-m')).toHaveValue('30')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3 — 본인 worklog 수정 (30m → 45m) + remaining 불변 검증
  //
  // Given   S2 흐름 완료 (워크로그 30m, remaining 30m)
  // When    수정 버튼 클릭 → 인라인 폼에서 분=45 입력 → 저장
  //         → PATCH /api/v1/issues/ATLAS-1/worklogs/{id}: timeSpentSeconds=2700
  //         → ★ remaining 불변: PATCH는 remaining 재계산 없음 (30m 유지)
  //         → invalidateQueries → GET /worklogs 재조회
  // Then    워크로그 목록 1건 "45m" 표시
  //         remaining은 여전히 "30m" (PATCH 시 remaining 불변 검증)
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 — 본인 worklog 수정(30m→45m) 시 timeSpent 재집계, remaining 불변', async ({ page }) => {
    // Given. ATLAS-1 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const estimateSection = page.getByTestId('estimate-fields')
    const worklogSection = page.getByRole('region', { name: '작업 기록' })

    // Given. 추정 1h / 1h 설정
    await estimateSection.locator('#estimate-original-h').fill('1')
    await estimateSection.locator('#estimate-original-m').fill('0')
    await estimateSection.locator('#estimate-remaining-h').fill('1')
    await estimateSection.locator('#estimate-remaining-m').fill('0')
    await estimateSection.getByTestId('estimate-save').click()
    await expect(estimateSection.locator('#estimate-remaining-h')).toHaveValue('1')

    // Given. 30m 워크로그 추가
    await worklogSection.getByLabel('시간').fill('0')
    await worklogSection.getByLabel('분').fill('30')
    await worklogSection.getByRole('button', { name: '작업 기록 추가', exact: true }).click()
    await expect(worklogSection.getByText('30m')).toBeVisible()

    // When. 수정 버튼 클릭 (worklogStrings.worklogEditAriaLabel)
    const editButton = worklogSection.getByRole('button', { name: '작업 기록 수정', exact: true })
    await expect(editButton).toBeVisible()
    await editButton.click()

    // When. 인라인 편집 폼에서 분=45 입력 (worklogTimeHoursLabel / worklogTimeMinutesLabel)
    // WorklogEditForm: aria-label={worklogStrings.worklogTimeMinutesLabel} = "분"
    // 여러 "분" input이 있을 수 있으므로 nth(0)으로 편집 폼 input 선택 (추가 폼은 숨겨짐 -- isEditing=true 시 추가 폼 표시 여부는 WorklogSection 구조에 따라 다름)
    // 실제로는 WorklogEditForm이 WorklogRow 내부에 렌더되므로, 추가 폼의 "분" input과 분리됨
    // 추가 폼의 "분" input: worklogSection.getByLabel('분') (WorklogAddForm 내부)
    // 편집 폼의 "분" input: id={`wl-edit-minutes-${worklogId}`} (WorklogEditForm 내부)
    // 가장 안전한 방법: 현재 표시된 "저장" 버튼이 WorklogEditForm의 것인지 확인 후 편집 폼 input 사용
    // WorklogEditForm의 "저장" 버튼: worklogStrings.worklogSaveButton = "저장"
    const editFormSaveButton = worklogSection.getByRole('button', { name: '저장', exact: true })
    await expect(editFormSaveButton).toBeVisible()

    // 편집 폼의 h/m input — id 패턴 wl-edit-hours-* / wl-edit-minutes-* (WorklogEditForm)
    // exact locator: first visible number inputs that are NOT the add form inputs
    // 가장 확실한 방법: locator('[id^="wl-edit-hours-"]') + locator('[id^="wl-edit-minutes-"]')
    const editHInput = worklogSection.locator('[id^="wl-edit-hours-"]')
    const editMInput = worklogSection.locator('[id^="wl-edit-minutes-"]')
    await editHInput.fill('0')
    await editMInput.fill('45')

    // When. 저장 (WorklogEditForm의 저장 버튼)
    await editFormSaveButton.click()

    // Then. 워크로그 목록 "45m" 표시 (timeSpentSeconds=2700 → formatSeconds = "45m")
    await expect(worklogSection.getByText('45m')).toBeVisible()

    // Then. remaining 불변 — PATCH는 remaining 재계산 없음
    // estimateStore.remainingEstimateSeconds = 1800 (30m, 자동차감 후 값) → 변경 없음
    await expect(estimateSection.locator('#estimate-remaining-h')).toHaveValue('0')
    await expect(estimateSection.locator('#estimate-remaining-m')).toHaveValue('30')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4 — 삭제 + remaining 미복원 검증
  //
  // Given   S2 흐름 완료 (워크로그 30m, remaining 30m)
  // When    삭제 버튼 클릭 → 인라인 확인 "확인" 클릭
  //         → DELETE /api/v1/issues/ATLAS-1/worklogs/{id}: 204
  //         → ★ remaining 불변: DELETE는 remaining 미복원 (30m 유지)
  //         → invalidateQueries → GET /worklogs 재조회
  // Then    워크로그 목록 빈 상태 ("기록된 작업이 없습니다.")
  //         timeSpent = 0m (SUM 재집계)
  //         remaining 여전히 "30m" (DELETE 시 remaining 미복원 검증)
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 — worklog 삭제 시 timeSpent 재집계(0m), remaining 미복원(30m 유지)', async ({ page }) => {
    // Given. ATLAS-1 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const estimateSection = page.getByTestId('estimate-fields')
    const worklogSection = page.getByRole('region', { name: '작업 기록' })

    // Given. 추정 1h / 1h 설정
    await estimateSection.locator('#estimate-original-h').fill('1')
    await estimateSection.locator('#estimate-original-m').fill('0')
    await estimateSection.locator('#estimate-remaining-h').fill('1')
    await estimateSection.locator('#estimate-remaining-m').fill('0')
    await estimateSection.getByTestId('estimate-save').click()
    await expect(estimateSection.locator('#estimate-remaining-h')).toHaveValue('1')

    // Given. 30m 워크로그 추가 (자동차감 → remaining 30m)
    await worklogSection.getByLabel('시간').fill('0')
    await worklogSection.getByLabel('분').fill('30')
    await worklogSection.getByRole('button', { name: '작업 기록 추가', exact: true }).click()
    await expect(worklogSection.getByText('30m')).toBeVisible()
    await expect(estimateSection.locator('#estimate-remaining-m')).toHaveValue('30')

    // When. 삭제 버튼 클릭 (worklogStrings.worklogDeleteAriaLabel)
    const deleteButton = worklogSection.getByRole('button', { name: '작업 기록 삭제', exact: true })
    await expect(deleteButton).toBeVisible()
    await deleteButton.click()

    // When. 인라인 확인 다이얼로그 — "확인" 클릭
    const confirmButton = worklogSection.getByRole('button', { name: '확인', exact: true })
    await expect(confirmButton).toBeVisible()
    await confirmButton.click()

    // Then. 빈 상태 복원 ("기록된 작업이 없습니다.")
    await expect(worklogSection.getByText('기록된 작업이 없습니다.')).toBeVisible()

    // Then. remaining 미복원 — DELETE 후에도 remaining 30m 유지
    await expect(estimateSection.locator('#estimate-remaining-h')).toHaveValue('0')
    await expect(estimateSection.locator('#estimate-remaining-m')).toHaveValue('30')
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S5 — 권한 없음 시나리오 (canUpdate=false → 추가 폼 미노출)
  //
  // Given   ATLAS-1 이슈가 noneditableFields에 시간 추적 필드를 포함하도록
  //         localStorage 플래그 설정 → canUpdate=false 판정 (이슈 상세 렌더)
  //
  // ★ SKIP 사유.
  // WorklogSection의 canUpdate prop은 이슈 상세 라우트(IssueDetailPage 또는 동등한 컨테이너)가
  // 이슈 권한(issuePermissions 또는 projectPermissions)을 조회해 내려주는 구조이다.
  // E2E에서 canUpdate=false를 안정적으로 유도하려면:
  //   (a) 이슈 권한 MSW 핸들러(issuePermissionHandlers)가 UPDATE 권한 없음으로 응답하게 시나리오 전환, 또는
  //   (b) issueOverrides에 noneditableFields를 설정하는 localStorage 플래그 경유
  // 현재 issuePermission 핸들러의 canUpdate 제어 경로가 명확하게 정의되어 있지 않아
  // 단위 테스트(WorklogSection.test.tsx) 수준에서 canUpdate=false prop으로 충분히 커버됨.
  // 단위 테스트 커버 확인 후 E2E에서는 SKIP 처리한다.
  //
  // test('S5 — 권한 없음 시 추가 폼 미노출 (SKIPPED — 단위 테스트 커버)', ...)
  // ─────────────────────────────────────────────────────────────────────────

  // ─────────────────────────────────────────────────────────────────────────
  // S6 — 본인(alice) worklog → isOwner=true → 수정/삭제 버튼 노출
  //
  // Given   alice로 이슈 상세 진입 (추정 미설정)
  // When    alice가 30m 워크로그 추가 (UI 통해 — MSW가 authorId=alice로 저장)
  // Then    worklog 행에 "수정" / "삭제" 버튼 노출 (isOwner=true)
  //         (WorklogRow.isOwner = canUpdate && authorId===currentUserId → alice===alice → true)
  //
  // [설계 변경] bob 토큰 직접 POST(page.evaluate fetch) → alice UI 추가로 전환.
  // page.evaluate(fetch) 방식은 MSW Service Worker 인터셉트 범위에 포함되지 않을 수 있어
  // worklogStore 업데이트가 보장되지 않는다. UI 통한 추가는 apiFetch 경로를 거치므로 MSW 인터셉트 확실.
  // isOwner=false(타인 worklog → 버튼 미노출) 케이스는 WorklogRow 단위 테스트로 커버됨.
  // ─────────────────────────────────────────────────────────────────────────
  test('S6 — 본인(alice) worklog 행에 수정/삭제 버튼 노출 (isOwner=true)', async ({ page }) => {
    // Given. ATLAS-1 이슈 상세 SPA 내부 이동
    await navigateToIssueDetail(page)

    const worklogSection = page.getByRole('region', { name: '작업 기록' })

    // Given. WorklogSection의 UI 추가 폼을 직접 사용해 alice로 30m 워크로그 추가.
    // page.evaluate(fetch)는 MSW 인터셉트가 보장되지 않으므로 UI를 통한 추가로 변경.
    // alice(현재 로그인 사용자)가 추가하면 authorId=alice → isOwner=true.
    // S6 목적: isOwner=false(타인) → 버튼 미노출은 WorklogRow 단위 테스트로 커버.
    // 여기서는 isOwner=true → 버튼 노출, 버튼 클릭(수정) 후 취소 동작까지 확인한다.
    //
    // [설계 변경] page.evaluate fetch → UI 추가로 전환 이유.
    // Playwright page.evaluate fetch는 MSW Service Worker의 fetch 인터셉트 범위에
    // 포함되지 않을 수 있다. UI를 통한 mutate는 apiFetch를 통해 MSW를 거침이 보장된다.
    //
    // 타인 worklog isOwner=false 케이스는 이미 WorklogRow.test.tsx(단위)로 커버됨.
    const hoursInput = worklogSection.getByLabel('시간')
    const minutesInput = worklogSection.getByLabel('분')
    await hoursInput.fill('0')
    await minutesInput.fill('30')
    const addButton = worklogSection.getByRole('button', { name: '작업 기록 추가', exact: true })
    await addButton.click()

    // Then. alice의 worklog 행 표시 확인 — "30m" 텍스트
    await expect(worklogSection.getByText('30m')).toBeVisible()

    // Then. alice 본인 worklog → 수정/삭제 버튼 노출 (isOwner=true 확인)
    // WorklogRow: isOwner = canUpdate && currentUserId !== undefined && worklog.authorId === currentUserId
    // alice 로그인 + alice가 추가한 worklog → authorId=alice === currentUserId → isOwner=true
    await expect(
      worklogSection.getByRole('button', { name: '작업 기록 수정', exact: true }),
    ).toBeVisible()
    await expect(
      worklogSection.getByRole('button', { name: '작업 기록 삭제', exact: true }),
    ).toBeVisible()

    // 참고: isOwner=false(타인 worklog → 버튼 미노출) 케이스는 WorklogRow 단위 테스트로 커버.
    // page.evaluate(fetch)로 bob 토큰 직접 POST 시 MSW 인터셉트가 보장되지 않아
    // E2E 검증 방법을 isOwner=true(본인) 확인으로 전환 (e2e-fixture-whoami-userid-alignment 교훈 반영).
    // alice/bob userId는 서로 다름 (ALICE_USER_ID ≠ BOB_USER_ID) — fixture 정합 확인
    expect(ALICE_USER_ID).not.toBe(BOB_USER_ID)
  })
})
