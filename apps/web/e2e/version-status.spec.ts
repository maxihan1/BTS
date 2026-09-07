// FR-VR-02 E2E — 버전 상태 전환 시나리오 (릴리스/보관/보관해제/불허 전환 거부)
//
// MSW version-handlers.ts 의 stateful versionStore 위에서 동작한다.
// 각 테스트는 X-MSW-Reset-Versions 헤더로 저장소를 초기화해 격리한다.
//
// 커버 시나리오.
//   T1. UNRELEASED → RELEASED (릴리스): 상태 뱃지 "출시됨" 확인, 릴리스 버튼 사라짐
//   T2. UNRELEASED → ARCHIVED (보관): 상태 뱃지 "보관됨" + 수정/삭제 버튼 disabled 확인 (S3, S6)
//   T3. ARCHIVED → UNRELEASED (보관 해제): 상태 뱃지 "미출시" + 수정/삭제 버튼 다시 활성 확인 (S4)
//   T4. ARCHIVED 상태에서 릴리스 버튼 미노출 확인 (S5 — 불허 전환 버튼 자체 없음)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 미사용
//   - msw-mutation-stateful-refetch: 전환 후 refetch 결과로 뱃지 상태 검증
//   - playwright-getbyrole-exact-strict-mode: aria-label("버전명 + 전환라벨") 한정 셀렉터
//   - e2e-fixture-whoami-userid-alignment: alice 로그인 고정
//   - ui-pr-defer-e2e-regression-latent: 기존 version-management 회귀 동반 실행
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/versions`

/** version-labels.ts 에서 가져온 한국어 라벨 (import 없이 문자열 상수로 관리) */
const labels = {
  pageHeading: '버전 관리',
  addButton: '버전 추가',
  saveButton: '저장',
  nameLabel: '이름',
  // 상태 뱃지
  badgeUnreleased: '미출시',
  badgeReleased: '출시됨',
  badgeArchived: '보관됨',
  // 전환 버튼 라벨 (versionTransitionLabel)
  releaseButton: '릴리스',
  unreleaseButton: '되돌리기',
  archiveButton: '보관',
  unarchiveButton: '보관 해제',
  // 수정/삭제
  editButton: '수정',
  deleteButton: '삭제',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW versionStore를 초기화한다.
 * X-MSW-Reset-Versions: true 헤더를 포함해 GET 목록을 호출하면
 * listVersionsHandler가 resetVersionStore()를 실행한다.
 */
async function resetVersionHandlerState(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async () => {
    await fetch(`/api/v1/projects/ATLAS/versions`, {
      headers: { 'X-MSW-Reset-Versions': 'true' },
    })
  })
}

/**
 * VersionFormDialog를 통해 버전을 생성하고 목록에 이름이 나타날 때까지 대기한다.
 * 생성 후 refetch 결과(stateful) 반영 포함.
 */
async function createVersion(
  page: import('@playwright/test').Page,
  name: string,
): Promise<void> {
  await page.getByRole('button', { name: labels.addButton }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByLabel(labels.nameLabel).fill(name)
  await page.getByRole('button', { name: labels.saveButton }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 목록에 이름 노출 확인
  await expect(page.getByText(name, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('버전 상태 전환 (FR-VR-02)', () => {
  test.beforeEach(async ({ page }) => {
    // alice 로 로그인 후 버전 설정 페이지 진입
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 2 })).toBeVisible()
    // 저장소 격리 — 각 테스트 시작 시 versionStore 초기화
    await resetVersionHandlerState(page)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // T1 — 릴리스 (UNRELEASED → RELEASED)
  //
  // Given  "T1-1.0.0" 버전이 UNRELEASED 상태로 목록에 있음
  // When   행의 "T1-1.0.0 릴리스" 버튼 클릭
  // Then   상태 뱃지가 "출시됨"으로 변경됨
  //        "릴리스" 버튼은 사라지고 "되돌리기"/"보관" 버튼이 노출됨
  // ───────────────────────────────────────────────────────────────────────────
  test('T1 릴리스 — UNRELEASED → RELEASED 뱃지 변화 + 버튼 교체 확인', async ({ page }) => {
    const versionName = 'T1-1.0.0'

    // 사전조건: UNRELEASED 버전 생성
    await createVersion(page, versionName)

    // 생성 직후 "미출시" 뱃지 확인 — 행 컨테이너 한정
    const versionRow = page.getByText(versionName, { exact: true }).locator('xpath=ancestor::li[1]')
    await expect(versionRow.getByText(labels.badgeUnreleased)).toBeVisible()

    // "릴리스" 전환 버튼 — aria-label="T1-1.0.0 릴리스" (playwright-getbyrole-exact-strict-mode)
    await page.getByRole('button', { name: `${versionName} ${labels.releaseButton}` }).click()

    // msw-mutation-stateful-refetch 교훈 — refetch 후 뱃지 변화 확인
    await expect(versionRow.getByText(labels.badgeReleased)).toBeVisible()
    // "미출시" 뱃지 사라짐
    await expect(versionRow.getByText(labels.badgeUnreleased)).toHaveCount(0)

    // RELEASED 상태에서는 "릴리스" 버튼 미노출, "되돌리기"/"보관" 버튼 노출
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.releaseButton}` }),
    ).toHaveCount(0)
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.unreleaseButton}` }),
    ).toBeVisible()
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.archiveButton}` }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // T2 — 보관 (UNRELEASED → ARCHIVED) + 읽기 전용 확인 (S3, S6)
  //
  // Given  "T2-2.0.0" 버전이 UNRELEASED 상태로 목록에 있음
  // When   행의 "T2-2.0.0 보관" 버튼 클릭
  // Then   상태 뱃지가 "보관됨"으로 변경됨
  //        수정/삭제 버튼이 disabled 상태
  //        "보관 해제" 버튼만 전환 버튼으로 노출됨
  // ───────────────────────────────────────────────────────────────────────────
  test('T2 보관 — UNRELEASED → ARCHIVED 뱃지 변화 + 수정/삭제 disabled 확인', async ({ page }) => {
    const versionName = 'T2-2.0.0'

    // 사전조건: UNRELEASED 버전 생성
    await createVersion(page, versionName)

    // "보관" 전환 버튼 클릭
    await page.getByRole('button', { name: `${versionName} ${labels.archiveButton}` }).click()

    // 행 컨테이너 한정 셀렉터
    const versionRow = page.getByText(versionName, { exact: true }).locator('xpath=ancestor::li[1]')

    // msw-mutation-stateful-refetch 교훈 — 뱃지 "보관됨" 확인
    await expect(versionRow.getByText(labels.badgeArchived)).toBeVisible()
    await expect(versionRow.getByText(labels.badgeUnreleased)).toHaveCount(0)

    // ARCHIVED: 수정 버튼 disabled (S6 — 읽기 전용)
    const editButton = page.getByRole('button', { name: `${versionName} ${labels.editButton}` })
    await expect(editButton).toBeVisible()
    await expect(editButton).toBeDisabled()

    // ARCHIVED: 삭제 버튼 disabled (S6 — 읽기 전용)
    const deleteButton = page.getByRole('button', { name: `${versionName} ${labels.deleteButton}` })
    await expect(deleteButton).toBeVisible()
    await expect(deleteButton).toBeDisabled()

    // ARCHIVED: "보관 해제" 버튼만 전환 버튼으로 노출
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.unarchiveButton}` }),
    ).toBeVisible()
    // "릴리스" / "보관" 버튼 미노출
    // exact:true 필수 — "보관 해제" 버튼이 "보관" 부분 문자열로 매칭되는 것을 방지
    // (playwright-getbyrole-exact-strict-mode 교훈)
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.releaseButton}`, exact: true }),
    ).toHaveCount(0)
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.archiveButton}`, exact: true }),
    ).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // T3 — 보관 해제 (ARCHIVED → UNRELEASED)
  //
  // Given  "T3-3.0.0" 버전이 ARCHIVED 상태
  // When   행의 "T3-3.0.0 보관 해제" 버튼 클릭
  // Then   상태 뱃지가 "미출시"로 변경됨
  //        수정/삭제 버튼이 다시 enabled 상태
  //        "릴리스"/"보관" 버튼이 다시 노출됨
  // ───────────────────────────────────────────────────────────────────────────
  test('T3 보관 해제 — ARCHIVED → UNRELEASED 뱃지 복귀 + 수정/삭제 enabled 확인', async ({ page }) => {
    const versionName = 'T3-3.0.0'

    // 사전조건: UNRELEASED 버전 생성 → 보관 전환
    await createVersion(page, versionName)
    await page.getByRole('button', { name: `${versionName} ${labels.archiveButton}` }).click()

    // 보관됨 뱃지 확인 후 보관 해제
    const versionRow = page.getByText(versionName, { exact: true }).locator('xpath=ancestor::li[1]')
    await expect(versionRow.getByText(labels.badgeArchived)).toBeVisible()

    // "보관 해제" 버튼 클릭
    await page.getByRole('button', { name: `${versionName} ${labels.unarchiveButton}` }).click()

    // msw-mutation-stateful-refetch 교훈 — 뱃지 "미출시" 복귀 확인
    await expect(versionRow.getByText(labels.badgeUnreleased)).toBeVisible()
    await expect(versionRow.getByText(labels.badgeArchived)).toHaveCount(0)

    // 수정/삭제 버튼 enabled 복귀 (ARCHIVED 해제됨)
    const editButton = page.getByRole('button', { name: `${versionName} ${labels.editButton}` })
    await expect(editButton).toBeVisible()
    await expect(editButton).not.toBeDisabled()

    const deleteButton = page.getByRole('button', { name: `${versionName} ${labels.deleteButton}` })
    await expect(deleteButton).toBeVisible()
    await expect(deleteButton).not.toBeDisabled()

    // UNRELEASED 전환 버튼 복귀 확인
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.releaseButton}` }),
    ).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // T4 — 불허 전환 거부 (S5): ARCHIVED 행에서 릴리스 버튼 미노출
  //
  // Given  "T4-4.0.0" 버전이 ARCHIVED 상태
  // When   행의 전환 버튼 목록 확인
  // Then   "릴리스" 버튼이 미노출 (ARCHIVED → RELEASED 전환 그래프에 없음)
  //        "보관 해제" 버튼만 노출됨
  //
  // 전환 버튼 자체가 렌더되지 않는 방식으로 불허 전환을 거부한다.
  // (버튼이 없으면 클릭 자체가 불가 — API 409 응답 경로보다 더 강한 보호)
  // ───────────────────────────────────────────────────────────────────────────
  test('T4 불허 전환 — ARCHIVED 행에서 릴리스 버튼 미노출 (S5)', async ({ page }) => {
    const versionName = 'T4-4.0.0'

    // 사전조건: UNRELEASED 버전 생성 → 보관 전환
    await createVersion(page, versionName)
    await page.getByRole('button', { name: `${versionName} ${labels.archiveButton}` }).click()

    // ARCHIVED 뱃지 노출 대기 (refetch 완료 확인)
    const versionRow = page.getByText(versionName, { exact: true }).locator('xpath=ancestor::li[1]')
    await expect(versionRow.getByText(labels.badgeArchived)).toBeVisible()

    // 불허 전환: ARCHIVED → RELEASED 버튼 미노출
    // exact:true 필수 — 부분 문자열 매칭 방지 (playwright-getbyrole-exact-strict-mode)
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.releaseButton}`, exact: true }),
    ).toHaveCount(0)

    // 불허 전환: ARCHIVED → ARCHIVED 버튼 미노출 (self-transition)
    // "보관 해제" 버튼을 "보관" 부분 문자열로 잡는 것을 방지
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.archiveButton}`, exact: true }),
    ).toHaveCount(0)

    // 허용 전환: "보관 해제" 버튼만 노출됨
    await expect(
      page.getByRole('button', { name: `${versionName} ${labels.unarchiveButton}` }),
    ).toBeVisible()
  })
})
