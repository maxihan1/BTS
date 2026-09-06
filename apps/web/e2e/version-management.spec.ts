// FR-VR-01 E2E — 버전 관리 시나리오 S1·S2·S4·S5·S6
//
// 앱은 MSW(version-handlers.ts) 위에서 동작한다.
// versionStore 는 모듈 스코프 stateful — 테스트마다 고유 이름 접두사를 써서
// 저장소 오염(중복 409) 없이 격리한다 (리셋 헤더가 없으므로).
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(버전명 포함) 한정
//   - msw-mutation-stateful-refetch: mutation 후 refetch 로 화면 갱신 검증 (가짜 그린 방지)
//   - zod-v4-uuid-fixture-strictness: 시드 UUID는 코드 내 생성 — 핸들러가 crypto.randomUUID 사용
//   - ui-pr-defer-e2e-regression-latent: UI PR이 기존 E2E 셀렉터를 깨지 않는지 전체 실행으로 확인
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 프로젝트 키 + i18n 라벨
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/versions`

/** versionLabels 에서 가져온 한국어 라벨 (라이브러리 import 없이 문자열 상수로 관리) */
const labels = {
  pageHeading: '버전 관리',
  addButton: '버전 추가',
  editButton: '수정',
  deleteButton: '삭제',
  saveButton: '저장',
  cancelButton: '취소',
  deleteConfirm: '정말 삭제하시겠습니까?',
  emptyMessage: '아직 버전이 없습니다.',
  /** VersionFormDialog 생성 모드 제목 */
  dialogCreateTitle: '버전 추가',
  /** VersionFormDialog 수정 모드 제목 */
  dialogEditTitle: '버전 수정',
  /** 폼 필드 aria-label */
  nameLabel: '이름',
  startDateLabel: '시작일',
  releaseDateLabel: '릴리즈 예정일',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 버전 추가 (다른 시나리오의 사전조건 세팅용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * VersionFormDialog를 통해 버전을 생성하고 다이얼로그가 닫힐 때까지 대기한다.
 * 생성 후 목록에 해당 이름이 보여야 반환 (stateful refetch 포함).
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
  // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch 된 목록에 이름이 보여야 함
  await expect(page.getByText(name, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('버전 관리 (FR-VR-01)', () => {
  test.beforeEach(async ({ page }) => {
    // 로그인 → /dashboard
    await loginAsAlice(page)
    // 버전 설정 페이지 직접 진입
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 2 })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 목록 조회
  //
  // Given  alice 로 로그인 후 ATLAS 버전 설정 페이지 진입
  // When   페이지 렌더 완료
  // Then   h1 "버전 관리", "버전 추가" 버튼 표시
  //        (빈 상태 안내 또는 기존 버전 목록 중 하나가 렌더됨)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 목록 조회 — 헤더 + 버전 추가 버튼 표시', async ({ page }) => {
    // "버전 추가" 버튼은 목록이 비어있든 채워져 있든 항상 표시된다
    await expect(page.getByRole('button', { name: labels.addButton })).toBeVisible()

    // 빈 상태 안내 OR 목록 중 하나가 보이면 정상
    // (병렬 테스트 실행 시 선행 테스트의 데이터가 남아있을 수 있으므로 두 분기를 허용)
    const emptyMsg = page.getByText(labels.emptyMessage)
    const versionList = page.locator('ul.space-y-2')
    const isEmptyOrHasList = (await emptyMsg.isVisible()) || (await versionList.count()) > 0
    expect(isEmptyOrHasList).toBe(true)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 버전 생성
  //
  // Given  ATLAS 버전 설정 페이지
  // When   "버전 추가" → Dialog → 이름 "S2-1.0.0" 입력 → 저장
  // Then   다이얼로그 닫힘, 목록에 "S2-1.0.0" 표시 (refetch 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 생성 — Dialog에 이름 입력 후 저장 → 목록 반영', async ({ page }) => {
    const versionName = 'S2-1.0.0'

    // "버전 추가" 버튼 클릭
    await page.getByRole('button', { name: labels.addButton }).click()

    // 다이얼로그 열림 + 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogCreateTitle)).toBeVisible()

    // 이름 입력
    await page.getByLabel(labels.nameLabel).fill(versionName)

    // 저장 클릭
    await page.getByRole('button', { name: labels.saveButton }).click()

    // 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch 로 목록 갱신 확인
    await expect(page.getByText(versionName, { exact: true })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 — 버전 수정
  //
  // Given  "S4-2.0.0" 버전이 목록에 있음
  // When   행의 "S4-2.0.0 수정" 버튼 → Dialog prefill 확인
  //        → 이름 "S4-2.0.0-수정됨" + 날짜(시작일·릴리즈 예정일) 변경 → 저장
  // Then   다이얼로그 닫힘, 목록에 "S4-2.0.0-수정됨" 표시 (refetch 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 수정 — Dialog prefill → 이름 + 날짜 변경 → 저장 → 갱신 반영', async ({ page }) => {
    const originalName = 'S4-2.0.0'
    const updatedName = 'S4-2.0.0-수정됨'

    // 사전조건: 버전 생성
    await createVersion(page, originalName)

    // 행 컨테이너 한정 — playwright-getbyrole-exact-strict-mode 교훈
    // aria-label="S4-2.0.0 수정" (VersionRow: `${version.name} ${actions.editButton}`)
    await page.getByRole('button', { name: `${originalName} ${labels.editButton}` }).click()

    // 수정 다이얼로그 열림 + 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogEditTitle)).toBeVisible()

    // prefill 확인 — 이름 input에 기존 값이 채워져 있어야 함
    await expect(page.getByLabel(labels.nameLabel)).toHaveValue(originalName)

    // 이름 변경
    await page.getByLabel(labels.nameLabel).clear()
    await page.getByLabel(labels.nameLabel).fill(updatedName)

    // 날짜 변경 (시작일·릴리즈 예정일)
    await page.getByLabel(labels.startDateLabel).fill('2026-01-01')
    await page.getByLabel(labels.releaseDateLabel).fill('2026-06-30')

    // 저장
    await page.getByRole('button', { name: labels.saveButton }).click()

    // 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // msw-mutation-stateful-refetch 교훈 — 갱신된 이름이 목록에 표시돼야 함
    await expect(page.getByText(updatedName, { exact: true })).toBeVisible()
    // 원래 이름은 사라져야 함
    await expect(page.getByText(originalName, { exact: true })).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5 — 날짜 해제
  //
  // Given  "S5-3.0.0" 버전이 시작일 2026-03-01, 릴리즈 예정일 2026-09-01로 생성됨
  // When   행의 수정 → 날짜 필드를 모두 빈값('')으로 변경 → 저장
  // Then   다이얼로그 닫힘, 해당 행의 날짜 열이 "—"로 표시됨 (null 정규화 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 날짜 해제 — 날짜 필드 비워 저장 → 날짜 해제 반영', async ({ page }) => {
    const versionName = 'S5-3.0.0'

    // "버전 추가" 버튼 클릭
    await page.getByRole('button', { name: labels.addButton }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // 이름 + 날짜 입력
    await page.getByLabel(labels.nameLabel).fill(versionName)
    await page.getByLabel(labels.startDateLabel).fill('2026-03-01')
    await page.getByLabel(labels.releaseDateLabel).fill('2026-09-01')
    await page.getByRole('button', { name: labels.saveButton }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByText(versionName, { exact: true })).toBeVisible()

    // 수정 Dialog 열기
    await page.getByRole('button', { name: `${versionName} ${labels.editButton}` }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // 날짜 필드 비우기 ('' → null 정규화 경로 검증)
    await page.getByLabel(labels.startDateLabel).fill('')
    await page.getByLabel(labels.releaseDateLabel).fill('')

    // 저장
    await page.getByRole('button', { name: labels.saveButton }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // 해당 버전 행 컨테이너 한정 — "—"가 두 개 표시돼야 함
    // VersionRow: formatDate(null) = "—"
    const versionRow = page.getByText(versionName, { exact: true }).locator('xpath=ancestor::li[1]')
    const dashCells = versionRow.getByText('—')
    await expect(dashCells).toHaveCount(2)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6 — 버전 삭제
  //
  // Given  "S6-4.0.0" 버전이 목록에 있음
  // When   행의 "S6-4.0.0 삭제" 버튼 → 인라인 확인 UI(DeleteConfirm) 표시
  //        → 해당 컨테이너의 "삭제" 확인 클릭
  // Then   목록에서 "S6-4.0.0"이 사라짐 (refetch 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 삭제 — 인라인 확인 후 삭제 → 목록에서 사라짐', async ({ page }) => {
    const versionName = 'S6-4.0.0'

    // 사전조건: 버전 생성
    await createVersion(page, versionName)

    // 삭제 버튼 클릭 — aria-label="S6-4.0.0 삭제" (VersionRow)
    await page.getByRole('button', { name: `${versionName} ${labels.deleteButton}` }).click()

    // 인라인 확인 UI 표시 확인 (DeleteConfirm 컴포넌트)
    await expect(page.getByText(labels.deleteConfirm)).toBeVisible()

    // 확인 "삭제" 버튼 클릭
    // DeleteConfirm 안의 "삭제" 버튼 — variant="destructive"
    // 인라인 확인 컨테이너 한정으로 찾아야 strict mode violation 방지
    const confirmContainer = page.getByText(labels.deleteConfirm).locator('xpath=ancestor::div[1]')
    await confirmContainer.getByRole('button', { name: labels.deleteButton }).click()

    // msw-mutation-stateful-refetch 교훈 — DELETE 후 refetch 로 목록에서 제거됨 확인
    await expect(page.getByText(versionName, { exact: true })).toHaveCount(0)
  })
})
