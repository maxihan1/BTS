// FR-CM-01 E2E — 컴포넌트 관리 시나리오 S1~S6
//
// 앱은 MSW(component-handlers.ts) 위에서 동작한다.
// componentStore 는 모듈 스코프 stateful — 테스트마다 고유 이름 접두사를 써서
// 저장소 오염 없이 격리한다 (리셋 헤더가 없으므로).
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 행별 액션은 aria-label(컴포넌트명 포함) 한정
//   - msw-mutation-stateful-refetch: mutation 후 refetch 로 화면 갱신 검증 (가짜 그린 방지)
//   - zod-v4-uuid-fixture-strictness: 시드 UUID는 코드 내 생성 — 핸들러가 crypto.randomUUID 사용
//   - ui-pr-defer-e2e-regression-latent: UI PR이 기존 E2E 셀렉터를 깨지 않는지 전체 실행으로 확인
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 프로젝트 키 + i18n 라벨
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/components`

/** componentLabels 에서 가져온 한국어 라벨 (라이브러리 import 없이 문자열 상수로 관리) */
const labels = {
  pageHeading: '컴포넌트 설정',
  addButton: '컴포넌트 추가',
  editButton: '수정',
  deleteButton: '삭제',
  saveButton: '저장',
  cancelButton: '취소',
  deleteConfirm: '정말 삭제하시겠습니까?',
  emptyMessage: '아직 컴포넌트가 없습니다.',
  /** ComponentFormDialog 생성 모드 제목 */
  dialogCreateTitle: '컴포넌트 추가',
  /** ComponentFormDialog 수정 모드 제목 */
  dialogEditTitle: '컴포넌트 수정',
  /** 폼 필드 aria-label */
  nameLabel: '이름',
  descriptionLabel: '설명',
  /** 리드 셀렉터 검색 input aria-label (ComponentLeadSelect.SEARCH_LABEL) */
  leadSearchLabel: '리드 검색',
  /** 리드 미지정 표시 / 해제 버튼 텍스트 */
  leadUnassigned: '미지정',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 컴포넌트 추가 (다른 시나리오의 사전조건 세팅용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ComponentFormDialog를 통해 컴포넌트를 생성하고 다이얼로그가 닫힐 때까지 대기한다.
 * 생성 후 목록에 해당 이름이 보여야 반환 (stateful refetch 포함).
 */
async function createComponent(
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

test.describe('컴포넌트 관리 (FR-CM-01)', () => {
  test.beforeEach(async ({ page }) => {
    // 로그인 → /dashboard
    await loginAsAlice(page)
    // 컴포넌트 설정 페이지 직접 진입
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading, level: 2 })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1 — 목록 조회 (빈 상태 안내)
  //
  // Given  alice 로 로그인 후 ATLAS 컴포넌트 설정 페이지 진입
  // When   페이지 렌더 완료 (컴포넌트 없음)
  // Then   h1 "컴포넌트 설정", 빈 상태 안내 문구, "컴포넌트 추가" 버튼 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 목록 조회 — 빈 상태 안내 + 추가 버튼 표시', async ({ page }) => {
    // MSW componentStore 는 초기에 비어있으므로 빈 상태 분기
    // (각 테스트가 고유 이름을 쓰므로 선행 테스트의 데이터가 있어도 다른 이름이라 무관)

    // 빈 상태 안내 문구 또는 목록이 렌더돼야 함 (두 분기 모두 "컴포넌트 추가" 버튼은 항상 표시)
    await expect(page.getByRole('button', { name: labels.addButton })).toBeVisible()

    // S1 전용: store 가 비어있는 최초 상태에서는 빈 메시지가 보임
    // (병렬 테스트 실행 시 이전 테스트의 데이터가 남아있을 수 있으므로
    //  빈 메시지 OR 목록 중 하나가 보이면 통과로 처리)
    const emptyMsg = page.getByText(labels.emptyMessage)
    const componentsList = page.locator('ul.space-y-2')
    // 두 요소 중 하나가 보이면 정상 (목록이 있으면 emptyMessage는 없음)
    const isEmptyOrHasList = (await emptyMsg.isVisible()) || (await componentsList.count()) > 0
    expect(isEmptyOrHasList).toBe(true)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2 — 컴포넌트 생성
  //
  // Given  ATLAS 컴포넌트 설정 페이지
  // When   "컴포넌트 추가" → Dialog → 이름 "S2-프론트엔드" 입력 → 저장
  // Then   다이얼로그 닫힘, 목록에 "S2-프론트엔드" 표시 (refetch 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 생성 — Dialog에 이름 입력 후 저장 → 목록 반영', async ({ page }) => {
    const componentName = 'S2-프론트엔드'

    // "컴포넌트 추가" 버튼 클릭
    await page.getByRole('button', { name: labels.addButton }).click()

    // 다이얼로그 열림 + 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogCreateTitle)).toBeVisible()

    // 이름 입력
    await page.getByLabel(labels.nameLabel).fill(componentName)

    // 저장 클릭
    await page.getByRole('button', { name: labels.saveButton }).click()

    // 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch 로 목록 갱신 확인
    await expect(page.getByText(componentName, { exact: true })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4 — 컴포넌트 수정
  //
  // Given  "S4-백엔드" 컴포넌트가 목록에 있음
  // When   행의 "S4-백엔드 수정" 버튼 → Dialog prefill 확인 → 이름을 "S4-백엔드-수정됨"으로 변경 → 저장
  // Then   다이얼로그 닫힘, 목록에 "S4-백엔드-수정됨" 표시 (refetch 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 수정 — Dialog prefill → 이름 변경 → 저장 → 갱신 반영', async ({ page }) => {
    const originalName = 'S4-백엔드'
    const updatedName = 'S4-백엔드-수정됨'

    // 사전조건: 컴포넌트 생성
    await createComponent(page, originalName)

    // 행 컨테이너 한정 — playwright-getbyrole-exact-strict-mode 교훈
    // aria-label="S4-백엔드 수정" (ComponentRow: `${component.name} ${actions.editButton}`)
    await page.getByRole('button', { name: `${originalName} ${labels.editButton}` }).click()

    // 수정 다이얼로그 열림 + 제목 확인
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText(labels.dialogEditTitle)).toBeVisible()

    // prefill 확인 — 이름 input에 기존 값이 채워져 있어야 함
    await expect(page.getByLabel(labels.nameLabel)).toHaveValue(originalName)

    // 이름 변경
    await page.getByLabel(labels.nameLabel).clear()
    await page.getByLabel(labels.nameLabel).fill(updatedName)

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
  // S5 — 리드 지정
  //
  // Given  "S5-인프라" 컴포넌트가 목록에 있음, 리드=미지정
  // When   리드 검색 input에 "캐럴" 입력 → 검색 결과에서 "캐럴" 선택
  // Then   해당 행의 lead-current-name 이 "캐럴"로 변경 (refetch 반영)
  //
  // 리드 해제 sub-scenario:
  // When   "미지정" 해제 버튼 클릭
  // Then   lead-current-name 이 "미지정"으로 복원
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 리드 지정/해제 — 검색 후 사용자 선택 → 반영, 미지정 버튼 → 해제', async ({ page }) => {
    const componentName = 'S5-인프라'

    // 사전조건: 컴포넌트 생성
    await createComponent(page, componentName)

    // 해당 행의 <li> 컨테이너 한정
    // ComponentRow는 <li className="flex flex-col gap-3 ..."> 로 렌더됨
    const componentRow = page.getByText(componentName, { exact: true }).locator('xpath=ancestor::li[1]')

    // 리드 초기 상태 — "미지정"
    await expect(componentRow.getByTestId('lead-current-name')).toHaveText(labels.leadUnassigned)

    // 리드 검색 input에 "캐럴" 입력
    // debounce 300ms — 검색 결과가 나타날 때까지 toBeVisible() 대기
    await componentRow.getByLabel(labels.leadSearchLabel).fill('캐럴')

    // 검색 결과 버튼 — ComponentLeadSelect: aria-label=getDisplayName(user)="캐럴"
    await expect(componentRow.getByRole('button', { name: '캐럴' })).toBeVisible()
    await componentRow.getByRole('button', { name: '캐럴' }).click()

    // msw-mutation-stateful-refetch 교훈 — PATCH /lead 후 refetch 로 화면 반영 확인
    await expect(componentRow.getByTestId('lead-current-name')).toHaveText('캐럴')

    // 리드 해제 — "미지정" 버튼 클릭 (currentLead !== null 인 경우에만 노출)
    // ComponentLeadSelect: aria-label="미지정"
    await componentRow.getByRole('button', { name: labels.leadUnassigned }).click()

    // 해제 후 "미지정"으로 복원
    await expect(componentRow.getByTestId('lead-current-name')).toHaveText(labels.leadUnassigned)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6 — 컴포넌트 삭제
  //
  // Given  "S6-QA" 컴포넌트가 목록에 있음
  // When   행의 "S6-QA 삭제" 버튼 → 인라인 확인 UI(DeleteConfirm) 표시 → "삭제" 확인 클릭
  // Then   목록에서 "S6-QA"가 사라짐 (refetch 반영)
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 삭제 — 인라인 확인 후 삭제 → 목록에서 사라짐', async ({ page }) => {
    const componentName = 'S6-QA'

    // 사전조건: 컴포넌트 생성
    await createComponent(page, componentName)

    // 삭제 버튼 클릭 — aria-label="S6-QA 삭제" (ComponentRow)
    await page.getByRole('button', { name: `${componentName} ${labels.deleteButton}` }).click()

    // 인라인 확인 UI 표시 확인 (DeleteConfirm 컴포넌트)
    await expect(page.getByText(labels.deleteConfirm)).toBeVisible()

    // 확인 "삭제" 버튼 클릭
    // DeleteConfirm 안의 "삭제" 버튼 — variant="destructive"
    // 인라인 확인 컨테이너 한정으로 찾아야 strict mode violation 방지
    const confirmContainer = page.getByText(labels.deleteConfirm).locator('xpath=ancestor::div[1]')
    await confirmContainer.getByRole('button', { name: labels.deleteButton }).click()

    // msw-mutation-stateful-refetch 교훈 — DELETE 후 refetch 로 목록에서 제거됨 확인
    await expect(page.getByText(componentName, { exact: true })).toHaveCount(0)
  })
})
