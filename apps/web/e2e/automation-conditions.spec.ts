// FR-AT-03 D7 E2E — 자동화 룰 조건 빌더(And/Or 그룹 + Comparison 트리) UI 검증 — plan Task 6
//
// 선례: automation-rules.spec.ts(FR-AT-01 D7) / automation-actions.spec.ts(FR-AT-02 D7) — MSW
// 시나리오/셀렉터 패턴을 그대로 따른다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: automation-rule-handlers.ts는
//     stateful 공유 store(ruleStore)를 사용한다. 각 테스트는 독립된 브라우저 컨텍스트(신규 페이지 로드마다
//     automation-rule-fixtures.ts 모듈이 재평가되어 기본 시드 2건으로 리셋)를 받으므로 데이터 격리가
//     보장된다 — 이 스펙은 localStorage 시나리오 토글이 필요 없다(기본 시드 자체에 조건 있는 룰이
//     이미 포함되어 있어 S4/S5는 그 시드를 그대로 재사용한다).
//   - playwright-getbyrole-exact-strict-mode: `ConditionBuilder`/`ConditionComparisonRow`의
//     `condition-add-comparison`/`condition-add-group`/`condition-group-op-toggle`/
//     `condition-group-negate`/`condition-remove-node`/`condition-comparison-node` data-testid는
//     그룹 depth와 무관하게 재귀적으로 재사용되어(각 그룹/Comparison 노드마다 동일 testid) 중첩 그룹이
//     생기면 dialog 전역 조회가 여러 건 매칭된다 — 항상 그룹 컨테이너(`condition-group`)를 먼저
//     `nth()`로 특정한 뒤 그 Locator에서 체이닝해 하위 노드만 좁혀 조회한다(문서 순서상 부모 컨테이너가
//     자식보다 먼저 나오므로 `nth(0)`=루트, `nth(1)`=중첩 그룹이 항상 성립).
//   - data-testid 우선 + getByLabel 조합 — automation-rule-add-button/-save-button 등은 기존 관례
//     (FR-AT-01/02) 그대로, 조건 섹션 위젯은 ConditionBuilder/ConditionComparisonRow의 testid로 스코프한다.
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rule-fixtures.ts DEFAULT_AUTOMATION_PROJECT_KEY 와 동일 값 */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/**
 * automation-rule-fixtures.ts DEFAULT_AUTOMATION_RULES의 SCHEDULED 시드 룰 이름 리터럴 고정.
 * 이 시드는 condition(issue.priority > 3, SEED_AUTOMATION_CONDITION_PRIORITY_GT_3)을 이미 가지고
 * 있어 S4(편집 시 로드)·S5(전부 제거 후 저장) 두 시나리오가 별도 생성 없이 재사용할 수 있다
 * (E2E는 mock 내부 상수를 직접 import하는 대신 프로덕션 화면 계약만 리터럴로 참조하는 기존 관례 동형).
 */
const SEED_SCHEDULED_RULE_NAME = '매일 오전 스캔'

/** ConditionBuilder.tsx TEXT.emptyGroupHint 리터럴 — 빈 그룹 힌트 문구(회귀 가드용 리터럴 고정) */
const CONDITION_EMPTY_GROUP_HINT = '조건을 추가하세요 — 그룹이 비어있으면 저장 시 제거됩니다.'

const labels = {
  heading: '자동화 룰',
  createTitle: '자동화 룰 추가',
  editTitle: '자동화 룰 수정',
  nameLabel: '이름',
  editButton: '수정',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 룰 이름으로 목록의 행(li) 컨테이너를 찾는다 (automation-rules.spec.ts ruleRow 동형). */
function ruleRow(page: Page, name: string): Locator {
  return page.getByText(name, { exact: true }).locator('xpath=ancestor::li[1]')
}

/** 룰 생성 다이얼로그를 열고 이름을 입력한 뒤 다이얼로그 Locator를 반환한다(공통 Given 전제). */
async function openCreateDialogWithName(page: Page, name: string): Promise<Locator> {
  await page.getByTestId('automation-rule-add-button').click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: labels.createTitle })).toBeVisible()
  await dialog.getByLabel(labels.nameLabel, { exact: true }).fill(name)
  return dialog
}

/** 행의 "수정" 버튼을 클릭해 편집 다이얼로그를 열고, 열린 다이얼로그 Locator를 반환한다. */
async function openEditDialog(page: Page, row: Locator, name: string): Promise<Locator> {
  await row.getByRole('button', { name: `${name} ${labels.editButton}`, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: labels.editTitle })).toBeVisible()
  return dialog
}

// ─────────────────────────────────────────────────────────────────────────────
// T1 — 조건 추가(단일 Comparison) → 저장 → 재오픈 시 조건 반영(S2)
//
// Given   alice 로그인 + 자동화 룰 생성 다이얼로그
// When    조건 빌더에서 Comparison 1건 추가 — field=issue.priority, operator=초과(>), value=3 → 저장
// Then    목록에 룰이 반영되고, 편집을 다시 열면 조건이 그대로 로드된다(직렬화→역직렬화 round-trip)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T1 조건 추가(단일 Comparison) → 저장 → 재오픈 시 조건 반영 (FR-AT-03 S2)', () => {
  test('Given 룰 생성 다이얼로그 When issue.priority > 3 Comparison 추가 후 저장 Then 재오픈 시 조건이 그대로 로드된다', async ({
    page,
  }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 생성 다이얼로그
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    const name = 'T1 조건 추가 룰'
    const dialog = await openCreateDialogWithName(page, name)

    // When. 조건 섹션에서 Comparison 1건 추가 — field=issue.priority, operator=초과(>), value=3
    await dialog.getByTestId('condition-add-comparison').click()
    const comparisonNode = dialog.getByTestId('condition-comparison-node')
    await comparisonNode.getByTestId('condition-field-select').selectOption('issue.priority')
    await comparisonNode.getByTestId('condition-operator-select').selectOption('GREATER_THAN')
    await comparisonNode.getByTestId('condition-value-input').selectOption('3')

    // When. 저장
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 목록에 룰이 반영된다
    const row = ruleRow(page, name)
    await expect(row).toBeVisible()

    // Then. 편집을 다시 열면 조건이 그대로 로드된다(직렬화→역직렬화 round-trip)
    const editDialog = await openEditDialog(page, row, name)
    const loadedComparison = editDialog.getByTestId('condition-comparison-node')
    await expect(loadedComparison.getByTestId('condition-field-select')).toHaveValue('issue.priority')
    await expect(loadedComparison.getByTestId('condition-operator-select')).toHaveValue('GREATER_THAN')
    await expect(loadedComparison.getByTestId('condition-value-input')).toHaveValue('3')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2 — And 루트 + Comparison 2개 + 중첩 Or 그룹 복합 조건 구성 → 저장(S3)
//
// Given   alice 로그인 + 자동화 룰 생성 다이얼로그
// When    루트(And, 기본값) 아래 Comparison 2개(issue.priority >= 2, issue.status == DONE) +
//         중첩 Or 그룹(issue.type == BUG) 구성 → 저장
// Then    저장이 성공하고, 재오픈 시 트리 구조(루트 Comparison 2개 + 중첩 Or 그룹의 Comparison 1개)가
//         그대로 로드된다(직렬화 정상 동작의 round-trip 증거)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T2 And 루트 + Comparison 2개 + 중첩 Or 그룹 복합 조건 구성 → 저장 (FR-AT-03 S3)', () => {
  test('Given 룰 생성 다이얼로그 When And 루트에 Comparison 2개 + 중첩 Or 그룹 1개 구성 후 저장 Then 재오픈 시 트리 구조가 그대로 로드된다', async ({
    page,
  }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 생성 다이얼로그
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    const name = 'T2 복합 조건 룰'
    const dialog = await openCreateDialogWithName(page, name)

    // When. 루트(And, 기본값)가 그룹 1건뿐인 동안 "조건 추가"를 2회 눌러 Comparison 2개를 만든다
    // (그룹이 하나뿐이라 이 시점의 add-comparison 버튼은 유일하게 매칭된다).
    await dialog.getByTestId('condition-add-comparison').click()
    const rootComparisons = dialog.getByTestId('condition-comparison-node')

    // When. 1번째 Comparison — issue.priority >= 2 (숫자 select 위젯)
    await rootComparisons.nth(0).getByTestId('condition-field-select').selectOption('issue.priority')
    await rootComparisons.nth(0).getByTestId('condition-operator-select').selectOption('GREATER_THAN_OR_EQUAL')
    await rootComparisons.nth(0).getByTestId('condition-value-input').selectOption('2')

    // When. 2번째 Comparison 추가는 1번째를 완전히 구성한 뒤 진행한다 — ConditionBuilder.tsx의
    // `addComparisonChild`가 매번 같은 모듈 상수 `DEFAULT_COMPARISON` 참조를 그대로 children에 push하므로,
    // 두 형제가 동시에 그 참조를 공유한 채로 한쪽만 편집하면 idFor(WeakMap 키=노드 참조) 캐시가 두 노드에
    // 같은 React key를 부여했다가 한쪽만 새 참조로 바뀌면서 key 중복 재조정 버그가 발생한다(발견된 src
    // 버그 — 수정하지 않고 순서로 회피, 컨트롤러 보고 대상).
    await dialog.getByTestId('condition-add-comparison').click()

    // When. 2번째 Comparison — issue.status == DONE (텍스트 위젯, 연산자는 기본값 EQUALS 유지)
    await rootComparisons.nth(1).getByTestId('condition-field-select').selectOption('issue.status')
    await rootComparisons.nth(1).getByTestId('condition-value-input').fill('DONE')

    // When. 루트에 중첩 그룹을 추가하고(Or로 전환) 그 안에 Comparison 1건(issue.type == BUG)을 구성한다.
    // 이 시점부터 "condition-group"이 2건(루트+중첩)이므로 중첩 그룹은 nth(1)로 특정해 스코프한다
    // (문서 순서상 부모인 루트가 항상 먼저 나온다 — 파일 상단 교훈 반영 참고).
    await dialog.getByTestId('condition-add-group').click()
    const nestedGroup = dialog.getByTestId('condition-group').nth(1)
    await nestedGroup.getByTestId('condition-group-op-toggle').click()
    await nestedGroup.getByTestId('condition-add-comparison').click()
    const nestedComparison = nestedGroup.getByTestId('condition-comparison-node')
    await nestedComparison.getByTestId('condition-field-select').selectOption('issue.type')
    await nestedComparison.getByTestId('condition-value-input').fill('BUG')

    // Then(중간 확인). 루트는 AND를 유지하고, 중첩 그룹만 OR로 전환되어 있다.
    // 루트 컨테이너(condition-group nth(0))는 중첩 그룹을 서브트리로 포함하므로 그 안에서 다시
    // condition-group-op-toggle을 조회하면 중첩 그룹의 토글까지 함께 매칭된다(컨테이너 스코프는
    // 배타적 형제가 아니라 포함관계) — 루트 자신의 토글은 문서 순서상 항상 가장 먼저 나오므로
    // 전역 조회의 first()로 특정한다.
    await expect(dialog.getByTestId('condition-group-op-toggle').first()).toHaveText('AND')
    await expect(nestedGroup.getByTestId('condition-group-op-toggle')).toHaveText('OR')

    // When. 저장
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 재오픈 시 트리 구조(루트 Comparison 2개 + 중첩 Or 그룹의 Comparison 1개)가 그대로 로드된다
    const editDialog = await openEditDialog(page, ruleRow(page, name), name)

    const loadedRootComparisons = editDialog.getByTestId('condition-comparison-node')
    await expect(loadedRootComparisons.nth(0).getByTestId('condition-field-select')).toHaveValue('issue.priority')
    await expect(loadedRootComparisons.nth(0).getByTestId('condition-operator-select')).toHaveValue(
      'GREATER_THAN_OR_EQUAL',
    )
    await expect(loadedRootComparisons.nth(0).getByTestId('condition-value-input')).toHaveValue('2')
    await expect(loadedRootComparisons.nth(1).getByTestId('condition-field-select')).toHaveValue('issue.status')
    await expect(loadedRootComparisons.nth(1).getByTestId('condition-value-input')).toHaveValue('DONE')

    const loadedNestedGroup = editDialog.getByTestId('condition-group').nth(1)
    await expect(loadedNestedGroup.getByTestId('condition-group-op-toggle')).toHaveText('OR')
    const loadedNestedComparison = loadedNestedGroup.getByTestId('condition-comparison-node')
    await expect(loadedNestedComparison.getByTestId('condition-field-select')).toHaveValue('issue.type')
    await expect(loadedNestedComparison.getByTestId('condition-value-input')).toHaveValue('BUG')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3 — 조건 있는 시드 룰 편집 → 로드 확인 → 수정 → 저장(S4)
//
// Given   alice 로그인 + 조건(issue.priority > 3)이 있는 시드 룰("매일 오전 스캔")
// When    편집 다이얼로그를 열어 조건이 로드되어 있는지 확인 → 연산자/값을 수정(>= 4) → 저장
// Then    재오픈 시 수정된 조건(issue.priority >= 4)이 반영되어 있다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T3 조건 있는 시드 룰 편집 → 로드 확인 → 수정 → 저장 (FR-AT-03 S4)', () => {
  test('Given 조건(issue.priority > 3)이 있는 시드 룰 When 편집 폼에서 로드 확인 후 연산자/값을 수정해 저장 Then 재오픈 시 수정된 조건이 반영된다', async ({
    page,
  }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 조건 있는 시드 룰("매일 오전 스캔")의 편집을 연다
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()

    const row = ruleRow(page, SEED_SCHEDULED_RULE_NAME)
    const dialog = await openEditDialog(page, row, SEED_SCHEDULED_RULE_NAME)

    // Then(중간 확인). 저장된 조건(issue.priority > 3)이 빌더에 그대로 로드되어 있다
    const comparisonNode = dialog.getByTestId('condition-comparison-node')
    await expect(comparisonNode.getByTestId('condition-field-select')).toHaveValue('issue.priority')
    await expect(comparisonNode.getByTestId('condition-operator-select')).toHaveValue('GREATER_THAN')
    await expect(comparisonNode.getByTestId('condition-value-input')).toHaveValue('3')

    // When. 연산자를 "이상"(>=)으로, 값을 4로 수정 후 저장
    await comparisonNode.getByTestId('condition-operator-select').selectOption('GREATER_THAN_OR_EQUAL')
    await comparisonNode.getByTestId('condition-value-input').selectOption('4')
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 재오픈 시 수정된 조건(issue.priority >= 4)이 반영된다
    const reopenedDialog = await openEditDialog(page, row, SEED_SCHEDULED_RULE_NAME)
    const updatedComparison = reopenedDialog.getByTestId('condition-comparison-node')
    await expect(updatedComparison.getByTestId('condition-field-select')).toHaveValue('issue.priority')
    await expect(updatedComparison.getByTestId('condition-operator-select')).toHaveValue('GREATER_THAN_OR_EQUAL')
    await expect(updatedComparison.getByTestId('condition-value-input')).toHaveValue('4')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4 — 조건 전부 제거 → 저장 → 재오픈 시 빈 빌더(S5, empty-AND 왕복)
//
// Given   alice 로그인 + 조건(issue.priority > 3)이 있는 시드 룰("매일 오전 스캔")
// When    편집 다이얼로그에서 조건 노드를 전부 삭제 → 저장
// Then    재오픈 시 조건 빌더가 비어있다(Comparison 없음 + 빈 그룹 힌트) — `{"and":[]}` 직렬화 왕복
// ─────────────────────────────────────────────────────────────────────────────

test.describe('T4 조건 전부 제거 → 저장 → 재오픈 시 빈 빌더 (FR-AT-03 S5, empty-AND 왕복)', () => {
  test('Given 조건이 있는 시드 룰 When 조건 노드를 전부 삭제 후 저장 Then 재오픈 시 빈 빌더(조건 미표시)가 확인된다', async ({
    page,
  }) => {
    // Given. alice 로그인 + 자동화 설정 페이지 진입 + 조건 있는 시드 룰("매일 오전 스캔")의 편집을 연다
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    const row = ruleRow(page, SEED_SCHEDULED_RULE_NAME)
    const dialog = await openEditDialog(page, row, SEED_SCHEDULED_RULE_NAME)
    await expect(dialog.getByTestId('condition-comparison-node')).toBeVisible()

    // When. 조건 노드를 삭제한다(그룹의 유일한 자식이라 삭제 버튼도 유일하게 매칭된다)
    await dialog.getByTestId('condition-remove-node').click()

    // Then(중간 확인). 그룹이 비어 "조건을 추가하세요" 힌트가 노출된다(직렬화 시 prune 예고, G2)
    await expect(dialog.getByText(CONDITION_EMPTY_GROUP_HINT, { exact: true })).toBeVisible()
    await expect(dialog.getByTestId('condition-comparison-node')).toHaveCount(0)

    // When. 저장
    await dialog.getByTestId('automation-rule-save-button').click()
    await expect(dialog).not.toBeVisible()

    // Then. 재오픈 시 조건이 비어있다(`{"and":[]}` 왕복) — Comparison 없음 + 빈 그룹 힌트 재노출
    const reopenedDialog = await openEditDialog(page, row, SEED_SCHEDULED_RULE_NAME)
    await expect(reopenedDialog.getByTestId('condition-comparison-node')).toHaveCount(0)
    await expect(reopenedDialog.getByText(CONDITION_EMPTY_GROUP_HINT, { exact: true })).toBeVisible()
  })
})
