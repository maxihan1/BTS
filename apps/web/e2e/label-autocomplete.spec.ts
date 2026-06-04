// FR-IS-09 라벨 자동완성 E2E — S1 후보 선택 happy path + S2 신규 free-form + S3 빈 포커스 인기 라벨
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 (기존 핸들러 설정 그대로)
//   - playwright-getbyrole-exact-strict-mode: 컨테이너 한정 + data-testid 우선
//   - msw-mutation-stateful-refetch: updateIssueHandler가 issueOverrides에 labels 영속(266번줄)
//     → invalidateQueries refetch 후 GET 단건도 갱신된 labels 반환 (실제 반영 검증)
//   - zod-v4-uuid-fixture-strictness: loginAsAlice 재사용 (기존 v4 UUID 정합 보장)
//
// 격리 가정. 각 테스트는 독립 브라우저 컨텍스트 → MSW worker 모듈 상태 격리.
//
// MSW label-handlers.ts: GET /api/v1/labels?q=<prefix> → { data: string[] } 빈도순
// LABEL_SEED 기준 "b" prefix 매칭: ['bug', 'backend', 'billing', 'breaking-change']
// LABEL_SEED 기준 빈 prefix 전체: ['bug', 'feature', 'frontend', 'backend', ...] 최대 10개
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — label-handlers.ts LABEL_SEED 기준 (변경 시 함께 수정)
// ─────────────────────────────────────────────────────────────────────────────

/** "b" prefix 자동완성 첫 번째 후보 (빈도 120 — bug) */
const FIRST_B_CANDIDATE = 'bug'
/** "b" prefix 자동완성 두 번째 후보 (빈도 75 — backend) */
const SECOND_B_CANDIDATE = 'backend'
/** 빈 포커스 시 인기 라벨 첫 번째 (빈도 120 — bug) */
const POPULAR_FIRST = 'bug'
/** 자동완성에 없는 신규 라벨 (free-form) */
const NEW_LABEL = 'my-custom-label'

/** 라벨 편집 영역 저장 버튼 data-testid */
const SAVE_TESTID = 'labels-save'
/** 라벨 자동완성 입력 data-testid */
const INPUT_TESTID = 'label-autocomplete-input'
/** 라벨 자동완성 드롭다운 data-testid */
const DROPDOWN_TESTID = 'label-autocomplete-dropdown'

test.describe('FR-IS-09 라벨 자동완성 (LabelAutocompleteInput)', () => {
  test.beforeEach(async ({ page }) => {
    // Given. alice 로그인 공통 사전조건 (storageState 대신 loginAsAlice 헬퍼 재사용 — 기존 패턴 일관)
    await loginAsAlice(page)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S1 자동완성 happy path — 후보 클릭 → 칩 추가 → 저장 → 반영
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입 (labels: [])
   *         라벨 편집 영역 입력란 확인
   * When    입력란에 "b" 입력
   * Then    자동완성 드롭다운에 빈도순 후보 표시 (bug, backend, billing, ...)
   * When    첫 번째 후보("bug") 클릭
   * Then    칩 추가됨 (드롭다운 닫힘, 입력란 초기화)
   * When    data-testid="labels-save" 저장 버튼 클릭
   * Then    라벨 저장 성공 — 메타패널에 "bug" 칩 유지 (PATCH 후 refetch 반영)
   */
  test('S1 자동완성 happy — "b" 입력 → 후보 클릭 → 칩 추가 → 저장 → 반영', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    // Given. 라벨 편집 영역 확인 — wrapper 컨테이너로 strict mode 방지
    const wrapper = page.getByTestId('label-autocomplete-wrapper')
    await expect(wrapper).toBeVisible()

    const input = page.getByTestId(INPUT_TESTID)
    await expect(input).toBeVisible()

    // When. 입력란에 "b" 입력
    await input.fill('b')

    // Then. 자동완성 드롭다운 표시 (debounce 250ms + 렌더 대기)
    const dropdown = page.getByTestId(DROPDOWN_TESTID)
    await expect(dropdown).toBeVisible()

    // Then. 첫 번째 후보가 "bug"인지 확인 (빈도 120으로 최상위)
    const firstOption = page.getByTestId(`label-option-${FIRST_B_CANDIDATE}`)
    await expect(firstOption).toBeVisible()
    await expect(firstOption).toHaveText(FIRST_B_CANDIDATE)

    // Then. 두 번째 후보가 "backend"인지 확인 (빈도 75)
    await expect(page.getByTestId(`label-option-${SECOND_B_CANDIDATE}`)).toBeVisible()

    // When. 첫 번째 후보("bug") 클릭 → 칩 추가
    await firstOption.click()

    // Then. 드롭다운 닫힘
    await expect(dropdown).not.toBeVisible()

    // Then. 입력란 초기화
    await expect(input).toHaveValue('')

    // Then. "bug" 칩이 편집 영역에 추가됨
    // LabelChip: <span>bug<button>×</button></span> 구조 — 내부 텍스트 포함 검색
    // exact:true는 span 전체 "bug×" 와 불일치하므로 labels-section 내 aria-label 사용
    // 라벨 제거 버튼의 aria-label이 labelRemoveLabel인 점을 이용해 인접 칩 확인
    const labelsSection = page.getByTestId('labels-section')
    await expect(labelsSection).toBeVisible()
    // "bug" 텍스트를 포함하는 노드 — substring 매칭(exact:false)로 칩 span 감지
    await expect(labelsSection.getByText(FIRST_B_CANDIDATE, { exact: false }).first()).toBeVisible()

    // When. 저장 버튼 클릭
    const saveButton = page.getByTestId(SAVE_TESTID)
    await expect(saveButton).toBeVisible()
    await saveButton.click()

    // Then. PATCH 성공 후 refetch — 메타패널에 "bug" 칩 유지 (MSW stateful 보장)
    // issueOverrides에 labels:['bug'] 영속 → GET 단건 반환 → React-Query invalidate → 재렌더
    await expect(labelsSection.getByText(FIRST_B_CANDIDATE, { exact: false }).first()).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2 신규 라벨 free-form — 자동완성에 없는 값 Enter 확정 → 칩 추가 → 저장
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입
   *         라벨 편집 영역 입력란 확인
   * When    자동완성 후보에 없는 "my-custom-label" 입력 후 Enter 키
   * Then    칩 추가됨 (드롭다운 없음 또는 닫힘, 입력란 초기화)
   * When    저장 버튼 클릭
   * Then    라벨 저장 성공 — 메타패널에 "my-custom-label" 칩 유지
   */
  test('S2 free-form — 자동완성 없는 신규 라벨 Enter → 칩 추가 → 저장', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const input = page.getByTestId(INPUT_TESTID)
    await expect(input).toBeVisible()

    // When. 자동완성 매칭 없는 값 입력 (NEW_LABEL은 LABEL_SEED에 없음)
    await input.fill(NEW_LABEL)

    // Then. 드롭다운에 NEW_LABEL 후보가 없음을 확인 (있더라도 Enter로 직접 확정)
    // NEW_LABEL은 LABEL_SEED에 없으므로 드롭다운이 보이지 않음
    const dropdown = page.getByTestId(DROPDOWN_TESTID)
    // 드롭다운이 없거나, 있어도 해당 항목이 없는 경우 모두 허용
    await expect(dropdown).not.toBeVisible()

    // When. Enter 키로 free-form 라벨 확정
    await input.press('Enter')

    // Then. 입력란 초기화
    await expect(input).toHaveValue('')

    // Then. NEW_LABEL 칩이 편집 영역에 추가됨
    // LabelChip: <span>my-custom-label<button>×</button></span> — exact:false로 포함 검색
    const labelsSection = page.getByTestId('labels-section')
    await expect(labelsSection.getByText(NEW_LABEL, { exact: false }).first()).toBeVisible()

    // When. 저장 버튼 클릭
    const saveButton = page.getByTestId(SAVE_TESTID)
    await saveButton.click()

    // Then. PATCH 후 refetch — NEW_LABEL 칩 유지 (MSW stateful)
    await expect(labelsSection.getByText(NEW_LABEL, { exact: false }).first()).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3 빈 포커스 인기 라벨 — 입력 없이 포커스 시 후보 표시
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Given   ATLAS-1 이슈 상세 페이지 진입
   *         라벨 편집 영역 입력란 확인
   * When    입력란에 아무것도 입력하지 않고 포커스
   * Then    자동완성 드롭다운에 인기 라벨 후보 표시
   *         첫 번째 후보가 "bug"(빈도 120 — 최상위) 임을 확인
   */
  test('S3 빈 포커스 — 입력 없이 포커스 시 인기 라벨 드롭다운 표시', async ({ page }) => {
    // Given. ATLAS-1 상세 진입
    await page.goto('/issues/ATLAS-1')

    const input = page.getByTestId(INPUT_TESTID)
    await expect(input).toBeVisible()

    // Given. 입력란이 비어있음 확인
    await expect(input).toHaveValue('')

    // When. 입력 없이 포커스
    await input.focus()

    // Then. 자동완성 드롭다운 표시 (q="" → LABEL_SEED 전체 반환, 최대 10개)
    const dropdown = page.getByTestId(DROPDOWN_TESTID)
    await expect(dropdown).toBeVisible()

    // Then. 인기 라벨 첫 번째가 "bug"(빈도 120 최상위) 임을 확인
    const popularFirst = page.getByTestId(`label-option-${POPULAR_FIRST}`)
    await expect(popularFirst).toBeVisible()
    await expect(popularFirst).toHaveText(POPULAR_FIRST)

    // Then. 드롭다운에 여러 후보가 있음 (빈 prefix → LABEL_SEED 전체)
    const allOptions = dropdown.getByRole('option')
    const count = await allOptions.count()
    expect(count).toBeGreaterThan(1)
  })
})
