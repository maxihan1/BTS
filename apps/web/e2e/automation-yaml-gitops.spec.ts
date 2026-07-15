// FR-AT-06 D6/D7 E2E — 자동화 룰 YAML GitOps 내보내기/가져오기 흐름 검증
//   (E1 내보내기 다운로드 / E2 가져오기 성공 / E3 failedIndex 실패 / E4 웹훅 토큰 1회 노출 + ESC 2단계 확인)
//
// 선례: automation-rules.spec.ts(FR-AT-01 D7)/automation-conflict-warning.spec.ts(FR-AT-04 D6/D7)/
// automation-execution-history.spec.ts(FR-AT-05 D6/D7) — 로그인/진입/셀렉터 패턴을 그대로 따른다.
// export.spec.ts(FR-EX-01/02 D7)/import.spec.ts(FR-IM-01 D6/D7) — 다운로드 인터셉트/파일 업로드 패턴을
// 그대로 따른다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - e2e-msw-scenario-toggle-localstorage-flag: failedIndex/webhookTokens 시나리오는 addInitScript +
//     localStorage 플래그(automation-rule-fixtures.ts SCENARIO_KEY.IMPORT_*)로 분기한다 — 핸들러 임시
//     교체 대신 페이지 로드 전 localStorage 토글. addInitScript는 반드시 goto 이전에 등록한다.
//   - msw-derived-behavior-shared-store-e2e / msw-mutation-stateful-refetch: automation-rule-handlers.ts의
//     importRulesHandler는 성공 시에도 ruleStore에 실제로 반영하지 않는다(요청 본문의 rules: 항목 수만
//     세어 created/total을 산출 — 파일 주석 "정교한 upsert 시뮬레이션은 이 mock의 책임 밖" 참고). 따라서
//     "목록 갱신"은 화면에 새 룰 이름이 나타나는 것으로 검증할 수 없고, invalidateQueries(FR9)가 실제로
//     목록 GET을 재발동시키는지(waitForRequest)로 검증한다.
//   - playwright-getbyrole-exact-strict-mode: 다이얼로그 내부 버튼/라벨은 role=dialog 컨테이너(testid)로
//     스코프해 페이지 전역 동일 텍스트와의 strict mode violation을 회피한다.
//   - ui-pr-defer-e2e-regression-latent: 기존 automation-rules.spec.ts/automation-conflict-warning.spec.ts/
//     automation-execution-history.spec.ts와 함께 실행해 같은 페이지(자동화 설정)의 기존 흐름에 회귀가
//     없는지 확인한다(보고 시 별도 실행 결과 첨부).
import { test, expect } from '@playwright/test'
import type { Page, Locator } from '@playwright/test'
import { loginAsAlice } from './fixtures/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — automation-rule-fixtures.ts 시드와 동일 리터럴
// (직접 import 대신 리터럴 고정 — E2E는 프로덕션 화면 계약만 참조하고 mock 내부 상수에 최소 의존한다는
// 기존 관례, automation-rules.spec.ts LS_EMPTY_LIST / automation-conflict-warning.spec.ts LS_WITH_CONFLICTS 동형)
// ─────────────────────────────────────────────────────────────────────────────

/** automation-rule-fixtures.ts DEFAULT_AUTOMATION_PROJECT_KEY 와 동일 값 */
const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/automation`

/** automation-rule-fixtures.ts SCENARIO_KEY.IMPORT_FAILED_INDEX 와 동일 문자열 리터럴 */
const LS_IMPORT_FAILED_INDEX = 'msw:automation-rule:import-failed-index'
/** automation-rule-fixtures.ts SCENARIO_KEY.IMPORT_WEBHOOK_TOKENS 와 동일 문자열 리터럴 */
const LS_IMPORT_WEBHOOK_TOKENS = 'msw:automation-rule:import-webhook-tokens'

const labels = {
  heading: '자동화 룰',
  exportButton: 'YAML 내보내기',
  importButton: 'YAML 가져오기',
  dialogTitle: 'YAML 가져오기',
  fileInputLabel: 'YAML 파일',
  applyButton: '적용',
  confirmButton: '확정',
  /** AutomationYamlImportDialog.tsx labels.rollbackNote */
  rollbackNote: '적용된 변경이 없습니다(전량 취소).',
  /** failedIndex=2(0-based) → 표시는 +1 해 "3번째 룰" (S6/FR10) */
  failedRulePrefix: '3번째 룰에서 실패했습니다.',
  /** AutomationYamlImportDialog.tsx labels.closeConfirm */
  tokenCloseConfirm: '토큰은 다시 볼 수 없습니다. 닫을까요?',
  /** automation-rule-handlers.ts buildImportedWebhookToken name 리터럴 */
  webhookTokenName: '웹훅 룰',
} as const

/** GitOps YAML v1 문서 — 룰 1건(ISSUE_CREATED, 액션 없음). automation-rule-fixtures.ts
 *  VALID_GITOPS_YAML 구조를 스펙 §YAML 스키마 v1 구조 그대로 이 파일에서 리터럴로 재작성한다
 *  (E2E는 mocks 소스를 import하지 않는 기존 관례 — 프로덕션 화면 계약만 참조). MSW import 핸들러는
 *  YAML을 파싱하지 않고 최상위 `rules:` 항목(2-space 들여쓰기 + "- ")만 줄 단위로 세므로, 이 문서는
 *  정확히 1건으로 집계되어 "생성 1 · 갱신 0 · 총 1"을 만든다.
 */
const MINIMAL_YAML_BUFFER = Buffer.from(
  `version: 1
projectKey: ${PROJECT_KEY}
rules:
  - name: E2E 가져오기 테스트 룰
    enabled: true
    trigger: { type: ISSUE_CREATED, config: {} }
    actions: []
`,
)

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** alice 로그인 후 자동화 설정 페이지로 이동하고 헤딩이 보일 때까지 대기한다. */
async function loginAndNavigate(page: Page): Promise<void> {
  await loginAsAlice(page)
  await page.goto(SETTINGS_URL)
  await expect(page.getByRole('heading', { name: labels.heading })).toBeVisible()
}

/** "YAML 가져오기" 버튼을 클릭해 Dialog를 열고 Dialog 로케이터를 반환한다. */
async function openImportDialog(page: Page): Promise<Locator> {
  await page.getByTestId('automation-yaml-import-button').click()
  const dialog = page.getByTestId('automation-yaml-import-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('heading', { name: labels.dialogTitle })).toBeVisible()
  return dialog
}

/** Dialog에 최소 유효 YAML 파일을 주입한다. */
async function uploadMinimalYaml(dialog: Locator): Promise<void> {
  await dialog.getByLabel(labels.fileInputLabel, { exact: true }).setInputFiles({
    name: 'automation-rules.yaml',
    mimeType: 'application/x-yaml',
    buffer: MINIMAL_YAML_BUFFER,
  })
}

/** "적용" → 인라인 2단계 확인 "확정" 순서로 클릭해 import를 접수한다. */
async function applyAndConfirm(dialog: Locator): Promise<void> {
  await dialog.getByTestId('automation-yaml-import-apply-button').click()
  await dialog.getByTestId('automation-yaml-import-confirm-button').click()
}

// ─────────────────────────────────────────────────────────────────────────────
// E1 — YAML 내보내기 → 다운로드 발생 + 파일명 automation-rules-{projectKey}.yaml
//
// Given   alice 로그인 + 자동화 설정 페이지 진입
// When    "YAML 내보내기" 버튼 클릭
// Then    다운로드가 발생하고 제안 파일명이 automation-rules-ATLAS.yaml 이다 (S1)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E1 YAML 내보내기 → 다운로드 (FR-AT-06 D6/D7)', () => {
  test('Given 자동화 설정 페이지 When YAML 내보내기 클릭 Then automation-rules-ATLAS.yaml 이 다운로드된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입
    await loginAndNavigate(page)

    // When. "YAML 내보내기" 클릭 + 다운로드 이벤트 대기
    // Promise.all로 waitForEvent를 click보다 먼저 등록해 경쟁 조건 방지(export.spec.ts 동형)
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByTestId('automation-yaml-export-button').click(),
    ])

    // Then. 제안 파일명이 서버 Content-Disposition 기반 규칙과 일치한다
    expect(download.suggestedFilename()).toBe(`automation-rules-${PROJECT_KEY}.yaml`)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E2 — YAML 가져오기 성공 → 생성/갱신/총 표시 + 목록 invalidate(FR9)
//
// Given   alice 로그인 + 자동화 설정 페이지 진입 + 유효한 GitOps YAML 파일(룰 1건)
// When    "YAML 가져오기" → 파일 선택 → "적용" → 인라인 확인 "확정"
// Then    결과 요약("생성 1 · 갱신 0 · 총 1")이 표시되고, 성공 직후 목록 GET이 재발동한다
//         (invalidateQueries → refetch, S4/FR9)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E2 YAML 가져오기 성공 → 결과 표시 + 목록 갱신 (FR-AT-06 D6/D7)', () => {
  test('Given 유효한 YAML 파일 When 적용 확정 Then 생성/갱신/총 이 표시되고 목록이 갱신된다', async ({ page }) => {
    // Given. alice 로그인 + 페이지 진입 + 가져오기 Dialog 오픈 + 파일 선택
    await loginAndNavigate(page)
    const dialog = await openImportDialog(page)
    await uploadMinimalYaml(dialog)
    await dialog.getByTestId('automation-yaml-import-apply-button').click()

    // When. 인라인 확인 "확정" 클릭 + 목록 재조회 GET 대기(같은 페이지 컨텍스트, invalidateQueries 결과)
    // MSW import 핸들러는 ruleStore를 실제로 갱신하지 않으므로(automation-rule-handlers.ts 주석),
    // 화면에 새 룰 이름이 뜨는지 대신 refetch 요청 자체 발생으로 "목록 갱신"을 검증한다.
    const [refetchRequest] = await Promise.all([
      page.waitForRequest(
        (request) =>
          request.method() === 'GET' &&
          request.url().endsWith(`/api/v1/projects/${PROJECT_KEY}/automation/rules`),
      ),
      dialog.getByTestId('automation-yaml-import-confirm-button').click(),
    ])
    expect(refetchRequest.method()).toBe('GET')

    // Then. 결과 요약(생성/갱신/총)이 표시된다
    await expect(dialog.getByTestId('automation-yaml-import-summary')).toHaveText('생성 1 · 갱신 0 · 총 1')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E3 — IMPORT_FAILED_INDEX 시나리오 → "3번째 룰에서 실패" + 전량취소 문구
//
// Given   IMPORT_FAILED_INDEX 플래그 on(서버 400 + failedIndex=2, 0-based) + alice 로그인 + 페이지 진입
// When    파일 선택 → "적용" → 인라인 확인 "확정"
// Then    Dialog가 유지된 채 "3번째 룰에서 실패했습니다."(+1 표시, S6/FR10)와
//         "적용된 변경이 없습니다(전량 취소)."가 함께 표시된다(atomic fail-closed 안내)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E3 IMPORT_FAILED_INDEX 시나리오 → N번째 룰 실패 + 전량취소 (FR-AT-06 D6/D7)', () => {
  test('Given failedIndex=2 응답 When 적용 확정 Then 3번째 룰 실패 + 전량취소 문구가 표시된다', async ({ page }) => {
    // Given. IMPORT_FAILED_INDEX 플래그를 addInitScript로 심는다 — 반드시 goto 이전에 등록
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_IMPORT_FAILED_INDEX)
    await loginAndNavigate(page)

    // When. 가져오기 Dialog 오픈 + 파일 선택 + 적용 + 확정
    const dialog = await openImportDialog(page)
    await uploadMinimalYaml(dialog)
    await applyAndConfirm(dialog)

    // Then. 에러 영역에 failedIndex+1("3번째 룰") 접두 + 전량취소 문구가 함께 표시된다
    const errorAlert = dialog.getByRole('alert')
    await expect(errorAlert).toContainText(labels.failedRulePrefix)
    await expect(errorAlert).toContainText(labels.rollbackNote)

    // Then. Dialog는 닫히지 않고 유지된다(재시도를 위해 파일 선택 상태 보존)
    await expect(dialog).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E4 — IMPORT_WEBHOOK_TOKENS 시나리오 → 토큰 목록 표시 + ESC 로도 즉시 닫히지 않는 2단계 확인
//
// Given   IMPORT_WEBHOOK_TOKENS 플래그 on(신규 WEBHOOK 룰 토큰 1건 노출) + alice 로그인 + 페이지 진입
// When    파일 선택 → "적용" → 인라인 확인 "확정" → 토큰 목록이 표시된 상태에서 ESC 입력
// Then    토큰 목록(룰 이름 + whk_ 접두 토큰)이 표시되고, ESC를 눌러도 Dialog는 즉시 닫히지 않으며
//         "토큰은 다시 볼 수 없습니다. 닫을까요?" 2단계 확인이 표시된다(S10/EC7)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('E4 IMPORT_WEBHOOK_TOKENS 시나리오 → 토큰 1회 노출 + ESC 2단계 확인 (FR-AT-06 D6/D7)', () => {
  test('Given 신규 WEBHOOK 룰 토큰 응답 When 적용 확정 후 ESC Then 토큰 목록이 표시되고 즉시 닫히지 않는다', async ({ page }) => {
    // Given. IMPORT_WEBHOOK_TOKENS 플래그를 addInitScript로 심는다 — 반드시 goto 이전에 등록
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'true')
    }, LS_IMPORT_WEBHOOK_TOKENS)
    await loginAndNavigate(page)

    // When. 가져오기 Dialog 오픈 + 파일 선택 + 적용 + 확정
    const dialog = await openImportDialog(page)
    await uploadMinimalYaml(dialog)
    await applyAndConfirm(dialog)

    // Then. 토큰 목록(룰 이름 + whk_ 접두 원문 토큰)이 표시된다
    const tokensSection = dialog.getByTestId('automation-yaml-import-tokens-section')
    await expect(tokensSection).toBeVisible()
    await expect(tokensSection.getByText(labels.webhookTokenName, { exact: true })).toBeVisible()
    await expect(tokensSection.getByText(/^whk_/)).toBeVisible()

    // When. ESC 입력(닫기 시도) — Radix onEscapeKeyDown이 가로채 2단계 확인으로 전환한다(EC7)
    await page.keyboard.press('Escape')

    // Then. Dialog는 즉시 닫히지 않고 유지되며, 닫기 재확인 문구가 표시된다
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText(labels.tokenCloseConfirm, { exact: true })).toBeVisible()
  })
})
