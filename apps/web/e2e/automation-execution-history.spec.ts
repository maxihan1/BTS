// FR-AT-05 D6/D7 E2E — 자동화 룰 실행 이력 조회/재실행 흐름 검증(목록·펼침 trace·재실행 성공/실패·빈 상태) — plan Task 9
//
// 선례: automation-rules.spec.ts(FR-AT-01 D7)/automation-conflict-warning.spec.ts(FR-AT-04 D6/D7) —
// MSW 시나리오/셀렉터 패턴을 그대로 따른다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - e2e-msw-scenario-toggle-localstorage-flag: 빈 목록/재실행 실패 시나리오는 addInitScript + localStorage
//     플래그(automation-execution-fixtures.ts SCENARIO_KEY.EMPTY_EXECUTIONS / RULE_UNAVAILABLE)로 분기한다 —
//     핸들러 임시 교체 대신 페이지 로드 전 localStorage 토글.
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: automation-execution-handlers.ts는
//     stateful 공유 store(executionStore)를 사용한다. 각 테스트는 독립된 브라우저 컨텍스트(신규 페이지 로드마다
//     automation-execution-fixtures.ts 모듈이 재평가되어 기본 시드 5건으로 리셋)를 받으므로 데이터 격리가
//     보장된다. 재실행(replay)은 목록 캐시를 무효화(refetch)하지 않고 클라이언트 캐시에 직접 prepend하므로
//     (useAutomationExecutions.ts prependReplayedExecutionSummary), 새 실행은 즉시 목록 맨 위에 나타난다.
//   - playwright-getbyrole-exact-strict-mode: 실행 행별 재실행/확정/취소 버튼은 li 컨테이너(executionRow)로
//     스코프해 여러 행에 걸친 동일 텍스트 버튼의 strict mode violation을 회피한다.
//   - ui-pr-defer-e2e-regression-latent: 기존 automation-rules.spec.ts/automation-conflict-warning.spec.ts와
//     함께 실행해 같은 페이지(자동화 설정)의 기존 흐름에 회귀가 없는지 확인한다(보고 시 별도 실행 결과 첨부).
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — automation-execution-fixtures.ts / automation-rule-fixtures.ts 시드와 동일 리터럴
// (직접 import 대신 리터럴 고정 — E2E는 프로덕션 화면 계약만 참조하고 mock 내부 상수에 최소 의존한다는
// 기존 관례, automation-rules.spec.ts LS_EMPTY_LIST 동형)
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/** automation-rule-fixtures.ts SEED_AUTOMATION_RULE_IDS.scheduled — "매일 오전 스캔"(실행 이력 4건) */
const RULE_ID_SCHEDULED = 'a1000000-0000-4000-8000-000000000002'
const SCHEDULED_RULE_NAME = '매일 오전 스캔'

/** automation-rule-fixtures.ts SEED_AUTOMATION_RULE_IDS.issueCreated — "이슈 생성 알림"(실행 이력 1건) */
const RULE_ID_ISSUE_CREATED = 'a1000000-0000-4000-8000-000000000001'

/** automation-execution-fixtures.ts SCENARIO_KEY.EMPTY_EXECUTIONS 와 동일 문자열 리터럴 */
const LS_EMPTY_EXECUTIONS = 'msw:automation-execution:empty-executions'
/** automation-execution-fixtures.ts SCENARIO_KEY.RULE_UNAVAILABLE 와 동일 문자열 리터럴 */
const LS_RULE_UNAVAILABLE = 'msw:automation-execution:rule-unavailable'

const labels = {
  pageHeading: '자동화 룰',
  dialogTitleSuffix: ' 실행 이력',
  emptyMessage: '실행 이력이 없습니다.',
  outcomesHeading: '액션 결과',
  triggerEventHeading: '트리거 이벤트',
  replayButton: '재실행',
  replayConfirmButton: '확정',
  /** RuleExecutionHistoryDialog.tsx labels.replaySuccessToast */
  replaySuccessToast: '재실행이 완료되었습니다.',
  /** RuleExecutionHistoryDialog.tsx labels.replayUnavailableToast (마침표 없음) */
  replayUnavailableToast: '재실행 대상 자동화 룰을 더 이상 사용할 수 없습니다',
  replayedBadge: '재실행됨',
  noIssue: '이슈 없음',
  /** RuleExecutionTraceRow.tsx STATUS_BADGE 라벨 */
  statusSuccess: '성공',
  statusPartial: '부분 성공',
  statusFailed: '실패',
  statusSkipped: '조건 불충족',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 룰의 "이력" 버튼(automation-rule-history-{ruleId})을 클릭해 실행 이력 Dialog를 연다. */
async function openHistoryDialog(page: Page, ruleId: string): Promise<Locator> {
  await page.getByTestId(`automation-rule-history-${ruleId}`).click()
  const dialog = page.getByTestId('rule-execution-history-dialog')
  await expect(dialog).toBeVisible()
  return dialog
}

/**
 * issueKey 텍스트(또는 "이슈 없음")로 실행 이력 목록의 행(li) 컨테이너를 찾는다
 * (automation-rules.spec.ts ruleRow 동형 ancestor 스코프).
 */
function executionRow(dialog: Locator, issueKeyOrFallbackText: string): Locator {
  return dialog.getByText(issueKeyOrFallbackText, { exact: true }).locator('xpath=ancestor::li[1]')
}

/**
 * 목록의 모든 요약 행 토글 버튼(aria-expanded)을 반환한다.
 * 펼친 행 내부 outcomes의 중첩 `<li>`는 aria-expanded를 갖지 않으므로, 이 셀렉터는 항상 상위
 * 요약 행 개수만 정확히 센다(RuleExecutionHistoryDialog.test.tsx countExecutionRows 동형).
 */
function rowToggles(dialog: Locator): Locator {
  return dialog.locator('[aria-expanded]')
}

/** 실행 이력 행을 펼친다(요약 행 토글 버튼 클릭). */
async function toggleRow(row: Locator): Promise<void> {
  await row.locator('[aria-expanded]').click()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 이력 버튼 클릭 → Dialog 오픈 + 실행 이력 목록(최신순) 표시
//
// Given   alice 로그인 + 자동화 설정 페이지 진입
// When    "매일 오전 스캔" 룰 행의 "이력" 버튼 클릭
// Then    실행 이력 Dialog가 열리고, 시드된 4건이 startedAt 최신순으로 표시된다
//         (07-13 SKIPPED → 07-12 FAILED → 07-11 PARTIAL → 07-10 SUCCESS)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 이력 버튼 클릭 → 실행 이력 목록 표시 (FR-AT-05 D6/D7)', () => {
  test('Given 자동화 설정 페이지 When 룰 행의 이력 버튼 클릭 Then Dialog가 열리고 실행 이력이 최신순으로 표시된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: labels.pageHeading })).toBeVisible()

    // When. "매일 오전 스캔" 행의 "이력" 버튼 클릭
    const dialog = await openHistoryDialog(page, RULE_ID_SCHEDULED)

    // Then. Dialog 제목 + 4건이 최신순으로 표시된다
    await expect(
      dialog.getByRole('heading', { name: `${SCHEDULED_RULE_NAME}${labels.dialogTitleSuffix}` }),
    ).toBeVisible()

    const rows = rowToggles(dialog)
    await expect(rows).toHaveCount(4)
    await expect(rows.nth(0)).toContainText(labels.statusSkipped)
    await expect(rows.nth(0)).toContainText(labels.noIssue)
    await expect(rows.nth(1)).toContainText(labels.statusFailed)
    await expect(rows.nth(1)).toContainText('ATLAS-103')
    await expect(rows.nth(2)).toContainText(labels.statusPartial)
    await expect(rows.nth(2)).toContainText('ATLAS-102')
    await expect(rows.nth(3)).toContainText(labels.statusSuccess)
    await expect(rows.nth(3)).toContainText('ATLAS-101')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 실행 행 클릭 → trace 펼침(액션별 결과 + triggerEvent JSON 표시)
//
// Given   alice 로그인 + 실행 이력 Dialog가 열린 상태(PARTIAL 실행 행 포함)
// When    PARTIAL 실행 행(ATLAS-102) 클릭
// Then    액션별 결과(SET_FIELD 성공·ASSIGN 실패+에러코드)와 triggerEvent JSON(cron)이 펼쳐진다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 실행 행 클릭 → trace 펼침 (FR-AT-05 D6/D7)', () => {
  test('Given 실행 이력 목록 When PARTIAL 실행 행 클릭 Then 액션별 결과와 triggerEvent JSON이 펼쳐진다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 + Dialog 오픈
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    const dialog = await openHistoryDialog(page, RULE_ID_SCHEDULED)

    // When. PARTIAL 실행 행(ATLAS-102) 클릭
    const row = executionRow(dialog, 'ATLAS-102')
    await toggleRow(row)

    // Then. 액션별 결과 — SET_FIELD(성공)·ASSIGN(실패, PERMISSION_DENIED)
    await expect(row.getByText(labels.outcomesHeading, { exact: true })).toBeVisible()
    await expect(row.getByText('SET_FIELD', { exact: true })).toBeVisible()
    await expect(row.getByText('ASSIGN', { exact: true })).toBeVisible()
    await expect(row.getByText('PERMISSION_DENIED', { exact: true })).toBeVisible()

    // Then. triggerEvent JSON(cron 필드) pretty-print가 표시된다
    await expect(row.getByText(labels.triggerEventHeading, { exact: true })).toBeVisible()
    await expect(row.locator('pre')).toContainText('cron')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 재실행 → 인라인 확인 "확정" → 새 실행이 목록 맨 위 추가 + 성공 토스트
//
// Given   alice 로그인 + 실행 이력 Dialog가 열리고 SUCCESS 실행 행(ATLAS-101)이 펼쳐진 상태
// When    "재실행" → 인라인 확인 "확정" 클릭
// Then    성공 토스트가 뜨고, 새 실행이 목록 맨 위(재실행됨 배지)에 추가되어 총 5건이 된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 재실행 확정 → 목록 맨 위 추가 + 성공 토스트 (FR-AT-05 D6/D7)', () => {
  test('Given 펼쳐진 실행 행 When 재실행 확정 Then 새 실행이 목록 맨 위에 추가되고 성공 토스트가 뜬다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 + Dialog 오픈 + SUCCESS 행(ATLAS-101) 펼침
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    const dialog = await openHistoryDialog(page, RULE_ID_SCHEDULED)
    const row = executionRow(dialog, 'ATLAS-101')
    await toggleRow(row)
    await expect(row.getByText(labels.outcomesHeading, { exact: true })).toBeVisible()

    // When. "재실행" → 인라인 확인 "확정"
    await row.getByRole('button', { name: labels.replayButton, exact: true }).click()
    await row.getByRole('button', { name: labels.replayConfirmButton, exact: true }).click()

    // Then. 성공 토스트가 뜬다
    await expect(page.getByText(labels.replaySuccessToast, { exact: true })).toBeVisible()

    // Then. 새 실행이 목록 맨 위(재실행됨 배지)에 추가되어 총 5건이 된다
    const rows = rowToggles(dialog)
    await expect(rows).toHaveCount(5)
    await expect(rows.first()).toContainText(labels.replayedBadge)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — rule-unavailable 시나리오 → 재실행 확정 → "사용할 수 없습니다" 토스트, 목록 불변
//
// Given   alice 로그인 + RULE_UNAVAILABLE 플래그 on + 실행 이력 Dialog가 열리고 실행 행이 펼쳐진 상태
// When    "재실행" → 인라인 확인 "확정" 클릭
// Then    재실행 대상 룰을 사용할 수 없다는 토스트가 뜨고, 목록은 4건 그대로 유지되며 재실행됨 배지가 없다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 rule-unavailable 시나리오 → 재실행 실패 토스트 + 목록 불변 (FR-AT-05 D6/D7)', () => {
  test('Given RULE_UNAVAILABLE 플래그 on When 재실행 확정 Then 사용할 수 없다는 토스트가 뜨고 목록은 변하지 않는다', async ({ page }) => {
    // Given. RULE_UNAVAILABLE 플래그를 addInitScript로 심는다 — 핸들러 임시 교체 대신 localStorage 토글
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_RULE_UNAVAILABLE)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)
    const dialog = await openHistoryDialog(page, RULE_ID_SCHEDULED)
    const row = executionRow(dialog, 'ATLAS-101')
    await toggleRow(row)
    await expect(row.getByText(labels.outcomesHeading, { exact: true })).toBeVisible()

    // When. "재실행" → 인라인 확인 "확정"
    await row.getByRole('button', { name: labels.replayButton, exact: true }).click()
    await row.getByRole('button', { name: labels.replayConfirmButton, exact: true }).click()

    // Then. "사용할 수 없습니다" 토스트가 뜬다
    await expect(page.getByText(labels.replayUnavailableToast, { exact: true })).toBeVisible()

    // Then. 목록은 4건 그대로 유지되며 재실행됨 배지는 어디에도 없다
    const rows = rowToggles(dialog)
    await expect(rows).toHaveCount(4)
    await expect(dialog.getByText(labels.replayedBadge, { exact: true })).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — empty-executions 시나리오 → "이력" 클릭 → "실행 이력이 없습니다" 빈 상태
//
// Given   alice 로그인 + EMPTY_EXECUTIONS 플래그 on + 자동화 설정 페이지 진입
// When    "이슈 생성 알림" 룰 행의 "이력" 버튼 클릭
// Then    실행 이력 Dialog가 열리고 빈 상태 문구가 표시된다
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 empty-executions 시나리오 → 빈 상태 (FR-AT-05 D6/D7)', () => {
  test('Given EMPTY_EXECUTIONS 플래그 on When 이력 버튼 클릭 Then 실행 이력이 없습니다 빈 상태가 표시된다', async ({ page }) => {
    // Given. EMPTY_EXECUTIONS 플래그를 addInitScript로 심는다
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_EMPTY_EXECUTIONS)
    await loginAsAlice(page)
    await page.goto(SETTINGS_URL)

    // When. "이슈 생성 알림" 행의 "이력" 버튼 클릭
    const dialog = await openHistoryDialog(page, RULE_ID_ISSUE_CREATED)

    // Then. 빈 상태 문구가 표시된다
    await expect(dialog.getByText(labels.emptyMessage, { exact: true })).toBeVisible()
  })
})
