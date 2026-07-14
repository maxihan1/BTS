// FR-AT-04 D6/D7 E2E — 규칙 충돌 경고 모달 흐름 검증(충돌 저장→모달 노출/미표시/닫기/다중나열) — plan Task 6
//
// 선례: automation-rules.spec.ts(FR-AT-01 D7)/automation-actions.spec.ts(FR-AT-02 D7)/
// automation-conditions.spec.ts(FR-AT-03 D7) — MSW 시나리오/셀렉터 패턴을 그대로 따른다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - e2e-msw-scenario-toggle-localstorage-flag: 충돌 시나리오는 addInitScript + localStorage 플래그
//     (automation-rule-fixtures.ts SCENARIO_KEY.WITH_CONFLICTS='msw:automation-rule:with-conflicts',
//     automation-rule-handlers.ts withConflictsFlag()가 이 값을 읽어 create/patch 응답에 결정적 충돌
//     buildSeededConflicts를 실어보낸다) — 핸들러 임시 교체 대신 localStorage 토글로 분기한다.
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: automation-rule-handlers.ts는
//     stateful 공유 store(ruleStore)를 사용한다. 각 테스트는 독립된 브라우저 컨텍스트(신규 페이지 로드마다
//     automation-rule-fixtures.ts 모듈이 재평가되어 기본 시드 2건으로 리셋)를 받으므로 데이터 격리가 보장된다.
//   - playwright-getbyrole-exact-strict-mode: 룰 생성 폼 다이얼로그와 충돌 경고 모달이 순차로 열리므로
//     role=dialog 전역 조회 대신 충돌 모달은 고유 data-testid(rule-conflict-warning-modal)로 스코프해
//     strict mode violation을 회피한다.
//   - ui-pr-defer-e2e-regression-latent: 기존 automation-rules.spec.ts와 함께 실행해 같은 페이지의
//     기존 흐름(생성/토글/삭제/OCC 409)에 회귀가 없는지 확인한다(보고 시 별도 실행 결과 첨부).
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rule-fixtures.ts DEFAULT_AUTOMATION_PROJECT_KEY 와 동일 값 */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/** automation-rule-fixtures.ts SCENARIO_KEY.WITH_CONFLICTS 와 동일 문자열 리터럴(automation-rules.spec.ts
 *  LS_EMPTY_LIST 관례 동형 — E2E는 프로덕션 화면 계약만 참조하고 mock 내부 상수에 최소 의존한다) */
const LS_WITH_CONFLICTS = 'msw:automation-rule:with-conflicts'

const labels = {
  heading: '자동화 룰',
  addButton: '룰 추가',
  createTitle: '자동화 룰 추가',
  nameLabel: '이름',
  saveButton: '저장',
  /** RuleConflictWarningModal.tsx CONFLICT_TYPE_LABELS 리터럴 — buildSeededConflicts가 항상 CYCLE+PERMISSION_MISSING 2건 */
  conflictTypeCycle: '순환 참조',
  conflictTypePermission: '권한 부족',
  /** automation-rule-fixtures.ts buildSeededConflicts detail 리터럴 */
  conflictDetailCycle: '이 룰과 "매일 오전 스캔" 룰이 서로를 트리거하는 순환 구조입니다.',
  conflictDetailPermission: '이 룰의 실행자에게 프로젝트 자동화 실행 권한이 없습니다.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** WITH_CONFLICTS 플래그를 addInitScript로 심는다(페이지 로드 전 localStorage 세팅). */
async function enableConflictScenario(page: Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_WITH_CONFLICTS)
}

/** "룰 추가" 폼으로 이름만 입력한 ISSUE_CREATED(기본 트리거) 룰을 저장한다(Given 전제 데이터 생성). */
async function saveRuleViaUi(page: Page, name: string): Promise<void> {
  await page.getByTestId('automation-rule-add-button').click()
  const formDialog = page.getByRole('dialog')
  await expect(formDialog.getByRole('heading', { name: labels.createTitle })).toBeVisible()
  await formDialog.getByLabel(labels.nameLabel, { exact: true }).fill(name)
  await formDialog.getByTestId('automation-rule-save-button').click()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 충돌 있는 저장 → 충돌 경고 모달 노출(충돌 종류 라벨 + detail 텍스트)
//
// Given   alice 로그인 + WITH_CONFLICTS 플래그 on + 자동화 설정 페이지 진입
// When    "룰 추가" → 이름만 입력하고 저장
// Then    충돌 경고 모달(rule-conflict-warning-modal)이 노출되고, 충돌 종류 라벨(순환 참조/권한 부족)과
//         detail 텍스트가 모달 안에 표시된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 충돌 저장 → 경고 모달 노출 (FR-AT-04 D6/D7)', () => {
  test('Given WITH_CONFLICTS 플래그 on When 규칙 생성 저장 Then 충돌 경고 모달이 충돌 종류·상세를 표시한다', async ({ page }) => {
    // Given. WITH_CONFLICTS 플래그 on + alice 로그인 + 페이지 진입
    await enableConflictScenario(page)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    // When. 룰 생성 폼 저장
    await saveRuleViaUi(page, 'S1 충돌 감지 룰')

    // Then. 충돌 경고 모달이 노출된다
    const conflictModal = page.getByTestId('rule-conflict-warning-modal')
    await expect(conflictModal).toBeVisible()

    // Then. 충돌 종류 라벨 + detail 텍스트가 모달 안에 표시된다
    await expect(conflictModal.getByText(labels.conflictTypeCycle, { exact: true })).toBeVisible()
    await expect(conflictModal.getByText(labels.conflictDetailCycle, { exact: true })).toBeVisible()
    await expect(conflictModal.getByText(labels.conflictTypePermission, { exact: true })).toBeVisible()
    await expect(conflictModal.getByText(labels.conflictDetailPermission, { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 충돌 없는 저장 → 충돌 경고 모달 미표시
//
// Given   alice 로그인 + WITH_CONFLICTS 플래그 off(기본값) + 자동화 설정 페이지 진입
// When    "룰 추가" → 이름만 입력하고 저장
// Then    폼은 정상적으로 닫히지만, 충돌 경고 모달은 화면 어디에도 렌더되지 않는다(testid 부재)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 충돌 없음 → 경고 모달 미표시 (FR-AT-04 D6/D7)', () => {
  test('Given WITH_CONFLICTS 플래그 off When 규칙 생성 저장 Then 충돌 경고 모달이 표시되지 않는다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 (플래그 미설정 — 기본 off)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    // When. 룰 생성 폼 저장
    const formDialog = page.getByRole('dialog')
    await saveRuleViaUi(page, 'S2 충돌 없는 룰')

    // Then. 폼이 닫히고, 충돌 경고 모달은 부재한다(testid 자체가 렌더되지 않음)
    await expect(formDialog.getByRole('heading', { name: labels.createTitle })).not.toBeVisible()
    await expect(page.getByTestId('rule-conflict-warning-modal')).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 충돌 경고 모달 닫기 → 모달 소멸
//
// Given   S1과 동일 전제(WITH_CONFLICTS on)로 충돌 경고 모달이 열린 상태
// When    "확인"(rule-conflict-close-button) 클릭
// Then    모달이 사라진다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 충돌 경고 모달 닫기 (FR-AT-04 D6/D7)', () => {
  test('Given 충돌 경고 모달이 열린 상태 When 닫기 버튼 클릭 Then 모달이 사라진다', async ({ page }) => {
    // Given. WITH_CONFLICTS 플래그 on + 룰 생성 저장으로 충돌 경고 모달을 연다(S1과 동일 전제)
    await enableConflictScenario(page)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await saveRuleViaUi(page, 'S4 닫기 대상 룰')

    const conflictModal = page.getByTestId('rule-conflict-warning-modal')
    await expect(conflictModal).toBeVisible()

    // When. "확인" 버튼 클릭
    await conflictModal.getByTestId('rule-conflict-close-button').click()

    // Then. 모달이 사라진다
    await expect(conflictModal).not.toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 다중 충돌 나열 → 모달에 항목 2건 이상 렌더
//
// Given   alice 로그인 + WITH_CONFLICTS 플래그 on + 자동화 설정 페이지 진입
// When    "룰 추가" → 이름만 입력하고 저장
// Then    충돌 경고 모달에 충돌 항목(li)이 2건 이상 렌더된다
//         (buildSeededConflicts는 CYCLE+PERMISSION_MISSING 2건을 결정적으로 반환한다)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 다중 충돌 나열 (FR-AT-04 D6/D7)', () => {
  test('Given WITH_CONFLICTS 플래그 on When 규칙 생성 저장 Then 충돌 항목이 2건 이상 렌더된다', async ({ page }) => {
    // Given. WITH_CONFLICTS 플래그 on + alice 로그인 + 페이지 진입
    await enableConflictScenario(page)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    // When. 룰 생성 폼 저장
    await saveRuleViaUi(page, 'S5 다중 충돌 룰')

    // Then. 충돌 경고 모달에 항목이 2건 이상 렌더된다
    const conflictModal = page.getByTestId('rule-conflict-warning-modal')
    await expect(conflictModal).toBeVisible()
    const itemCount = await conflictModal.getByRole('listitem').count()
    expect(itemCount).toBeGreaterThanOrEqual(2)
  })
})
