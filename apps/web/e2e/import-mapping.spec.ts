// FR-IM-02 D6/D7 E2E — Import 매핑 마법사 시나리오 (S1 CSV 전체 흐름 / S5 JSON 필드매핑 스킵 / S6 에러·차단)
//
// 시나리오 개요.
//   S1. "매핑하며 가져오기" 모드 진입 → CSV 업로드/분석 → 필드 매핑(summary 대상 지정) → [다음]
//       → 사용자 매핑 → [다음] → 값 매핑 → [다음] → 검토 [가져오기 실행] → 진행률 → 완료(성공/실패 건수)
//   S5. JSON 업로드/분석 → 필드 매핑 단계를 건너뛰고 사용자 매핑으로 바로 진입 → 값 매핑 → 완료
//   S6. (a) 필드 매핑에서 summary 미지정 시 [다음] 차단(에러 노출, 여전히 필드 매핑 단계에 머무름)
//       (b) LS_KEY_IMPORT_FAIL 토글로 확정 후 FAILED — 에러 안내 + [다시 시도]
//
// MSW 핸들러 확인 (apps/web/src/mocks/import-handlers.ts, FR-IM-02 D6/D7 Task-8).
//   - POST /api/v1/imports/analyze              → AWAITING_MAPPING + 고정 sourceFields/sampleRows/targetFields
//   - POST /api/v1/imports/:id/mapping/validate → fieldMappings 기반 valid/errors(summary 미매핑 시 SUMMARY_NOT_MAPPED)
//   - POST /api/v1/imports/:id/mapping/users    → 고정 users(추천 有/無 혼합, alice/bob 추천 有 · carol 추천 無)
//   - POST /api/v1/imports/:id/mapping/values   → 고정 fields(STATUS/TYPE/PRIORITY)
//   - POST /api/v1/imports/:id/mapping (confirm) → 기존 importJobStore(PENDING 등록) 재사용 →
//     기존 GET /api/v1/imports/:id 폴링(pollCount 0/1/2+ 진행)으로 이어짐(import.spec.ts와 동일 store)
//   - LS_KEY_IMPORT_FAIL='true'면 첫 폴링에서 즉시 FAILED로 강제 전환(import.spec.ts S3와 동일 토글)
//
// 필드 매핑 초기 추천(field-mapping-suggest.ts) 참고.
//   - 고정 소스 헤더(Summary/Description/Status/Priority/Reporter/Assignee/Labels)는 대상 카탈로그
//     key와 대소문자 무관 그대로 일치하므로, analyze 직후 이미 summary가 자동으로 매핑돼 있다.
//     S1은 그럼에도 "Summary 매핑 대상" 콤보박스를 명시적으로 재선택해(idempotent) summary 지정
//     행위를 실제 상호작용으로 검증한다(휴리스틱 변경에도 견고).
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 전역 차단 금지(playwright.config.ts 기본 설정 그대로 사용)
//   - e2e-msw-scenario-toggle-localstorage-flag: LS_KEY_IMPORT_FAIL은 addInitScript로 goto 전에 심는다
//   - msw-derived-behavior-shared-store-e2e: confirm이 만든 잡을 GET 폴링이 그대로 이어받는 공유 store를 그대로 소비
//   - playwright-getbyrole-exact-strict-mode: 버튼/라디오/콤보박스는 exact:true 또는 컨테이너로 한정
//   - ui-pr-defer-e2e-regression-latent: 기존 import.spec.ts 수정 없음 — 이 파일만 신규 추가
//
// ⚠ 알려진 실패(BLOCKED, src/ 구현 결함 — qa-engineer는 src/ 수정 권한이 없어 보고만 함).
//   S1/S5/S6b는 실제 크로미움 실행에서 progressbar/"Import 완료"/FAILED 사유 대신 "Import 상태를
//   조회하지 못했습니다. 잠시 후 다시 시도하세요." alert에서 멈춘다(S6a는 통과 — tracking 단계에
//   도달하지 않아 아래 결함의 영향을 받지 않는다).
//   근본 원인 (ImportMappingWizard.tsx `useImportJobPolling(jobId, true)` 호출부, enabled 인자가
//   상수 true) — analyze 성공 직후(필드/사용자/값 매핑 단계를 지나기 전) jobId가 이미 set되어
//   폴링 쿼리가 즉시 활성화된다. 그러나 이 시점엔 confirmMapping(POST .../mapping)이 아직 호출되지
//   않아 importJobStore에 해당 jobId 레코드가 없으므로 GET /api/v1/imports/{jobId}가 404를
//   반환한다. main.tsx QueryClient 전역 설정이 `retry:false`라 이 첫 실패로 즉시 react-query
//   status='error'가 되고, useImportJobPolling의 refetchInterval 콜백은
//   `if (query.state.status === 'error') return false`로 이후 폴링을 영구 중단시킨다. 나중에
//   review 단계에서 confirmMapping이 같은 jobId로 실제 레코드를 만들어도, 이미 죽어버린 폴링
//   쿼리를 재개시키는 invalidateQueries/refetch 트리거가 코드 어디에도 없어 tracking/done 화면이
//   영원히 위 alert에 머문다. 네트워크 트레이스로 실측 확인(RES 200 analyze → RES 200 mapping/users
//   → RES 404 /imports/{jobId} 모두 같은 밀리초에 발생, confirm 이전).
//   제안 수정 방향(적용은 frontend-engineer 담당) — `useImportJobPolling(jobId, step === 'tracking')`
//   로 gate하거나, confirm 성공 이후에만 폴링용 jobId를 세팅하도록 상태를 분리한다.
//   이 스펙 파일은 의도된 사용자 경험(정상 동작 시 기대 동작)을 그대로 표현하며, 위 결함이
//   수정되면 별도 수정 없이 그대로 통과해야 한다.

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** Import 설정 페이지 URL — projects.$projectKey.settings.import 라우트 */
const IMPORT_URL = '/projects/ATLAS/settings/import'

/** done 단계 전환 대기 타임아웃(ms) — 폴링 2회 간격(~3000ms) + 렌더 지연 여유 마진 (import.spec.ts DONE_PHASE_TIMEOUT_MS 동일값) */
const DONE_PHASE_TIMEOUT_MS = 10_000

/** setInputFiles로 주입할 최소 CSV 파일 내용 — 실제 파싱은 mock되므로 내용은 무관하다 */
const MINIMAL_CSV_BUFFER = Buffer.from('Summary,Status\nE2E 테스트 이슈,Open\n')

/** setInputFiles로 주입할 최소 JSON 파일 내용 — 실제 파싱은 mock되므로 내용은 무관하다 */
const MINIMAL_JSON_BUFFER = Buffer.from('[{"summary":"E2E 테스트 이슈"}]')

/** import-handlers.ts LS_KEY_IMPORT_FAIL 값과 동기화 (import.spec.ts 선례와 동일 하드코딩 미러) */
const LS_KEY_IMPORT_FAIL = '__bts_e2e_import_fail'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로그인 후 Import 설정 페이지로 이동하고 헤더가 보일 때까지 대기한다.
 *
 * @param page Playwright Page 객체
 */
async function loginAndNavigateToImportPage(page: Page): Promise<void> {
  await loginAsAlice(page)
  await page.goto(IMPORT_URL)
  await expect(page.getByRole('heading', { name: '가져오기(Import)', level: 1 })).toBeVisible()
}

/**
 * MSW LS_KEY_IMPORT_FAIL 시나리오 플래그를 심는다. goto 전에 호출해야 한다
 * (e2e-msw-scenario-toggle-localstorage-flag — addInitScript는 페이지 로드 전에 등록해야
 * 첫 로드 시점부터 localStorage 값이 존재하게 한다).
 *
 * @param page Playwright Page 객체
 */
async function forceImportFailScenario(page: Page): Promise<void> {
  await page.addInitScript((lsKey: string) => {
    window.localStorage.setItem(lsKey, 'true')
  }, LS_KEY_IMPORT_FAIL)
}

/**
 * 페이지 상단 모드 토글에서 "매핑하며 가져오기"를 선택해 ImportMappingWizard로 진입한다.
 *
 * @param page Playwright Page 객체
 */
async function enterMappingMode(page: Page): Promise<void> {
  await page.getByRole('button', { name: '매핑하며 가져오기', exact: true }).click()
  await expect(page.getByRole('radio', { name: 'CSV', exact: true })).toBeVisible()
}

/**
 * 업로드 단계에서 format(필요 시 JSON으로 변경) + 파일을 지정하고 [분석]을 클릭한다.
 *
 * @param page Playwright Page 객체
 * @param options format/파일명/MIME 타입/버퍼
 */
async function analyzeFile(
  page: Page,
  options: { format: 'CSV' | 'JSON'; fileName: string; mimeType: string; buffer: Buffer },
): Promise<void> {
  if (options.format === 'JSON') {
    await page.getByRole('radio', { name: 'JSON', exact: true }).check()
  }
  await page.getByLabel('분석할 파일', { exact: true }).setInputFiles({
    name: options.fileName,
    mimeType: options.mimeType,
    buffer: options.buffer,
  })
  const analyzeButton = page.getByRole('button', { name: '분석', exact: true })
  await expect(analyzeButton).not.toBeDisabled()
  await analyzeButton.click()
}

/** 필드 매핑 단계 — "Summary 매핑 대상" 콤보박스에서 대상 필드 "제목"(summary)을 명시적으로 지정한다 */
async function assignSummaryTarget(page: Page): Promise<void> {
  await page.getByRole('combobox', { name: 'Summary 매핑 대상', exact: true }).click()
  await page.getByRole('option', { name: /^제목/ }).click()
}

/** 필드 매핑 단계 — "Summary 매핑 대상" 콤보박스를 "매핑 안 함"으로 되돌려 summary 미지정 상태를 만든다 */
async function unassignSummaryTarget(page: Page): Promise<void> {
  await page.getByRole('combobox', { name: 'Summary 매핑 대상', exact: true }).click()
  await page.getByRole('option', { name: '매핑 안 함', exact: true }).click()
}

/** 필드 매핑 단계 — summary 대상 지정 후 [다음] 클릭(검증 통과 시 사용자 매핑으로 전이) */
async function proceedFieldsStep(page: Page): Promise<void> {
  await assignSummaryTarget(page)
  await page.getByRole('button', { name: '다음', exact: true }).click()
}

/** 사용자 매핑 단계 진입을 확인하고(추천값 기본 유지) [다음]을 클릭한다 */
async function proceedUsersStep(page: Page): Promise<void> {
  await expect(page.getByTestId('user-mapping-row-alice@example.com')).toBeVisible()
  await page.getByRole('button', { name: '다음', exact: true }).click()
}

/** 값 매핑 단계 진입을 확인하고(추천값 기본 유지) [다음]을 클릭한다 */
async function proceedValuesStep(page: Page): Promise<void> {
  await expect(page.getByLabel('상태 open 대상 값', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '다음', exact: true }).click()
}

/** 검토 단계 진입을 확인하고 지정한 확정 버튼("검증만 실행" 또는 "가져오기 실행")을 클릭한다 */
async function confirmReviewStep(page: Page, buttonName: '검증만 실행' | '가져오기 실행'): Promise<void> {
  await expect(page.getByText('확정합니다', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: buttonName, exact: true }).click()
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-IM-02 D6/D7 Import 매핑 마법사 (S1 CSV 전체 흐름 / S5 JSON 스킵 / S6 에러·차단)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. CSV 전체 흐름 — 분석 → 필드 매핑(summary 지정) → 사용자 매핑 → 값 매핑 → 검토 → 완료
  //
  // Given  alice 로그인 + Import 페이지 진입 + "매핑하며 가져오기" 모드
  // When   CSV 파일 분석 → 필드 매핑 단계에서 summary 대상 지정 후 [다음]
  //        → 사용자 매핑 [다음] → 값 매핑 [다음] → 검토 [가져오기 실행]
  // Then   진행률 표시 → "Import 완료" + 성공/실패 건수 노출
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 CSV 업로드 — 필드/사용자/값 매핑 거쳐 [가져오기 실행] 후 완료(성공/실패 건수)', async ({ page }) => {
    // Given. alice 로그인 + Import 페이지 진입 + 매핑 모드
    await loginAndNavigateToImportPage(page)
    await enterMappingMode(page)

    // When. CSV 파일 분석
    await analyzeFile(page, {
      format: 'CSV',
      fileName: 'issues.csv',
      mimeType: 'text/csv',
      buffer: MINIMAL_CSV_BUFFER,
    })

    // Then. 필드 매핑 단계 진입 확인 + summary 대상 지정 후 다음
    await expect(page.getByTestId('field-mapping-row-Summary')).toBeVisible()
    await proceedFieldsStep(page)

    // Then. 사용자 매핑 단계 진입 확인 후 다음(추천값 기본 유지)
    await proceedUsersStep(page)

    // Then. 값 매핑 단계 진입 확인 후 다음(추천값 기본 유지)
    await proceedValuesStep(page)

    // When. 검토 단계 — [가져오기 실행]
    await confirmReviewStep(page, '가져오기 실행')

    // Then. 진행률 표시 → "Import 완료" + 성공/실패 건수 노출
    await expect(page.getByRole('progressbar')).toBeVisible()
    await expect(page.getByRole('status')).toContainText('Import 완료', { timeout: DONE_PHASE_TIMEOUT_MS })
    await expect(page.getByText(/성공\s*10건/)).toBeVisible()
    await expect(page.getByText(/실패\s*0건/)).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S5. JSON 업로드 — 필드 매핑 단계 스킵 확인 → 사용자/값 매핑 거쳐 완료
  //
  // Given  alice 로그인 + Import 페이지 진입 + "매핑하며 가져오기" 모드
  // When   JSON 파일 분석
  // Then   필드 매핑 단계(및 stepper의 "필드 매핑" 항목)를 건너뛰고 사용자 매핑으로 바로 진입
  // When   사용자 매핑 [다음] → 값 매핑 [다음] → 검토 [가져오기 실행]
  // Then   진행률 표시 → "Import 완료"
  // ───────────────────────────────────────────────────────────────────────────
  test('S5 JSON 업로드 — 필드 매핑 단계 스킵 후 사용자/값 매핑 거쳐 완료', async ({ page }) => {
    // Given. alice 로그인 + Import 페이지 진입 + 매핑 모드
    await loginAndNavigateToImportPage(page)
    await enterMappingMode(page)

    // When. JSON 파일 분석
    await analyzeFile(page, {
      format: 'JSON',
      fileName: 'issues.json',
      mimeType: 'application/json',
      buffer: MINIMAL_JSON_BUFFER,
    })

    // Then. 필드 매핑 단계를 건너뛰고 사용자 매핑으로 바로 진입 — 필드 매핑 행/스텝퍼 항목 모두 부재
    await expect(page.getByTestId('user-mapping-row-alice@example.com')).toBeVisible()
    await expect(page.getByTestId('field-mapping-row-Summary')).not.toBeVisible()
    await expect(page.getByRole('list', { name: 'Import 매핑 진행 단계' })).not.toContainText('필드 매핑')

    // When. 사용자 매핑 [다음]
    await page.getByRole('button', { name: '다음', exact: true }).click()

    // Then + When. 값 매핑 단계 진입 확인 후 다음
    await proceedValuesStep(page)

    // When. 검토 단계 — [가져오기 실행]
    await confirmReviewStep(page, '가져오기 실행')

    // Then. 진행률 표시 → "Import 완료"
    await expect(page.getByRole('progressbar')).toBeVisible()
    await expect(page.getByRole('status')).toContainText('Import 완료', { timeout: DONE_PHASE_TIMEOUT_MS })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6(a). 필드 매핑 — summary 미지정 시 [다음] 차단(에러 노출)
  //
  // Given  alice 로그인 + Import 페이지 진입 + 매핑 모드 + CSV 분석 완료(필드 매핑 단계)
  // When   "Summary 매핑 대상"을 "매핑 안 함"으로 되돌리고 [다음] 클릭
  // Then   role=alert로 SUMMARY_NOT_MAPPED 에러 메시지 노출 + 여전히 필드 매핑 단계에 머무름
  // ───────────────────────────────────────────────────────────────────────────
  test('S6a 필드 매핑 — summary 미지정 시 [다음] 차단(에러 노출)', async ({ page }) => {
    // Given. alice 로그인 + Import 페이지 진입 + 매핑 모드 + CSV 분석
    await loginAndNavigateToImportPage(page)
    await enterMappingMode(page)
    await analyzeFile(page, {
      format: 'CSV',
      fileName: 'issues.csv',
      mimeType: 'text/csv',
      buffer: MINIMAL_CSV_BUFFER,
    })
    await expect(page.getByTestId('field-mapping-row-Summary')).toBeVisible()

    // When. summary 매핑 해제 후 [다음] 클릭
    await unassignSummaryTarget(page)
    await page.getByRole('button', { name: '다음', exact: true }).click()

    // Then. 에러 노출 + 필드 매핑 단계에 그대로 머무름(사용자 매핑으로 전이하지 않음)
    await expect(page.getByRole('alert')).toContainText('summary(제목) 대상에 매핑된 소스 필드가 없습니다.')
    await expect(page.getByTestId('field-mapping-row-Summary')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6(b). 확정 후 FAILED — 에러 안내 + [다시 시도]
  //
  // Given  addInitScript로 LS_KEY_IMPORT_FAIL='true' 심기(goto 전 등록)
  //        alice 로그인 + Import 페이지 진입 + 매핑 모드 + JSON 분석(필드 매핑 스킵)
  // When   사용자 매핑 [다음] → 값 매핑 [다음] → 검토 [가져오기 실행] → 첫 폴링에서 즉시 FAILED 수신
  // Then   role=alert "파일을 파싱하지 못했습니다..." 에러 메시지 표시 + [다시 시도] 버튼 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S6b 확정 후 Import 실패(IMPORT_PARSE_FAILED) — 에러 안내 + 다시 시도 버튼', async ({ page }) => {
    // Given. FAILED 시나리오 플래그(goto 전 등록) + alice 로그인 + Import 페이지 진입 + 매핑 모드
    await forceImportFailScenario(page)
    await loginAndNavigateToImportPage(page)
    await enterMappingMode(page)

    // When. JSON 분석(필드 매핑 스킵) → 사용자 매핑 [다음] → 값 매핑 [다음] → 검토 [가져오기 실행]
    await analyzeFile(page, {
      format: 'JSON',
      fileName: 'issues.json',
      mimeType: 'application/json',
      buffer: MINIMAL_JSON_BUFFER,
    })
    await proceedUsersStep(page)
    await proceedValuesStep(page)
    await confirmReviewStep(page, '가져오기 실행')

    // Then. done(FAILED) 단계 — importFailureMessage(IMPORT_PARSE_FAILED) 한글 메시지 표시
    await expect(page.getByRole('alert')).toContainText('파일을 파싱하지 못했습니다', {
      timeout: DONE_PHASE_TIMEOUT_MS,
    })

    // Then. "다시 시도" 버튼 표시
    await expect(page.getByRole('button', { name: '다시 시도', exact: true })).toBeVisible()
  })
})
