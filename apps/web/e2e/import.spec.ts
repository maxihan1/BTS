// FR-IM-01 D6/D7 E2E — CSV/JSON Import 폼 시나리오 (S1 렌더 / S2 dryRun→실제 Import happy path / S3 FAILED)
//
// 시나리오 개요.
//   S1. /projects/ATLAS/settings/import 진입 → format 라디오·파일 입력 렌더 확인 (골든 패스 진입점)
//   S2. CSV 파일 setInputFiles → [검증만 실행] → 진행률(progressbar) → "검증 완료"
//       → [이 파일로 실제 Import] → 진행률 재표시 → "Import 완료"
//   S3. addInitScript로 LS_KEY_IMPORT_FAIL='true' 심기 → 첫 폴링에서 즉시 FAILED 수신
//       → 에러 메시지("파일을 파싱하지 못했습니다...") + [다시 시도] 표시
//
// S3 히스토리 (Task 6b로 해소됨).
//   최초 시도한 page.route('**/api/v1/imports/*', ...) GET 오버라이드는 MSW Service Worker가
//   요청을 먼저 가로채 응답해버려 무효화됨을 실측으로 확인했었다(첫 커밋 1675184de, test.fixme로
//   BLOCKED 보고). 이후 import-handlers.ts(Task 6b, src/)에 LS_KEY_IMPORT_FAIL localStorage
//   토글이 추가되어(getImportStatusHandler가 플래그 감지 시 해당 job을 즉시 status='FAILED'로
//   전환) e2e-msw-scenario-toggle-localstorage-flag 패턴으로 재현 가능해졌다 — 이 파일은 그
//   토글을 addInitScript로 심는 방식으로 S3를 활성화한다(page.route 코드는 제거).
//
// 설계 결정 (S1/S2).
//   - ImportForm은 MSW import-handlers.ts(stateful store, pollCount 기반 PENDING→RUNNING→COMPLETED
//     3단계 진행)를 사용한다.
//   - 폴링 간격 IMPORT_POLL_INTERVAL_MS=1500ms, MSW pollCount 진행: 0(PENDING)→1(RUNNING,50%)
//     →2+(COMPLETED,100%) — done 전환까지 최대 약 3000ms + 렌더 지연. 여유 있는 timeout 부여.
//
// 설계 결정 (S3).
//   - LS_KEY_IMPORT_FAIL='__bts_e2e_import_fail' 상수는 import-handlers.ts 정본 값을 이 파일에
//     하드코딩 미러한다(board-kanban.spec.ts LS_KEY_BOARD_CONFLICT 선례 동일 패턴 — src 상수를
//     E2E 파일에서 직접 import하지 않고 값만 동기화, 주석으로 출처 명시).
//   - addInitScript는 loginAsAlice 이후·goto 이전에 등록해야 첫 페이지 로드부터 플래그가 적용된다
//     (e2e-msw-scenario-toggle-localstorage-flag 교훈).
//   - Playwright는 테스트마다 격리된 BrowserContext(및 그 안의 localStorage)를 새로 생성하므로
//     S3에서 설정한 플래그가 S1/S2로 새지 않는다 — 별도 정리(cleanup) 코드 불필요.
//
// MSW 핸들러 확인 (apps/web/src/mocks/import-handlers.ts).
//   - POST /api/v1/imports            → 202 + {jobId, status:"PENDING", dryRun}
//   - GET  /api/v1/imports/:id        → stateful 폴링 (pollCount 0/1/2+ 단계 진행)
//                                        LS_KEY_IMPORT_FAIL='true'면 즉시 FAILED로 강제 전환
//   - GET  /api/v1/imports/:id/errors → text/csv 에러 로그 (errorLogReady 무관하게 반환)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 전역 차단 절대 금지
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그 (S3)
//   - playwright-getbyrole-exact-strict-mode: 버튼/라디오 텍스트는 exact:true로 한정
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 수정 없음 — 이 파일만 신규 추가
//   - e2e-orphan-vite-after-worktree-remove: 실행 후 5173 orphan 프로세스 정리 (실행 절차 참고)

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** Import 설정 페이지 URL — projects.$projectKey.settings.import 라우트 */
const IMPORT_URL = '/projects/ATLAS/settings/import'

/** done 단계 전환 대기 타임아웃(ms) — 폴링 2회 간격(~3000ms) + 렌더 지연 여유 마진 */
const DONE_PHASE_TIMEOUT_MS = 10_000

/** setInputFiles로 주입할 최소 CSV 파일 내용 */
const MINIMAL_CSV_BUFFER = Buffer.from('summary,issueType\nE2E 테스트 이슈,Task\n')

/** import-handlers.ts LS_KEY_IMPORT_FAIL 값과 동기화 (board-kanban.spec.ts LS_KEY_BOARD_CONFLICT 선례) */
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
 * "가져올 파일" input에 CSV 파일을 주입한다.
 *
 * @param page Playwright Page 객체
 */
async function uploadCsvFile(page: Page): Promise<void> {
  await page.getByLabel('가져올 파일', { exact: true }).setInputFiles({
    name: 'issues.csv',
    mimeType: 'text/csv',
    buffer: MINIMAL_CSV_BUFFER,
  })
}

/**
 * MSW LS_KEY_IMPORT_FAIL 시나리오 플래그를 심는다.
 *
 * goto 전에 호출해야 한다 (e2e-msw-scenario-toggle-localstorage-flag 교훈 — addInitScript는
 * 페이지가 로드되기 전에 스크립트를 등록해 첫 로드 시점부터 localStorage 값이 존재하게 한다).
 * 플래그가 설정되면 import-handlers.ts의 getImportStatusHandler가 폴링 응답을 즉시
 * status="FAILED"(errorCode="IMPORT_PARSE_FAILED")로 강제 전환한다.
 *
 * @param page Playwright Page 객체
 */
async function forceImportFailScenario(page: Page): Promise<void> {
  await page.addInitScript((lsKey: string) => {
    window.localStorage.setItem(lsKey, 'true')
  }, LS_KEY_IMPORT_FAIL)
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-IM-01 D6/D7 CSV/JSON Import (S1 렌더 / S2 happy path / S3 FAILED)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 렌더 확인
  //
  // Given  alice 로그인
  // When   /projects/ATLAS/settings/import 진입
  // Then   format 라디오(CSV 기본 선택)·JSON 라디오·파일 입력 렌더 확인
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 진입 — format 라디오·파일 입력 렌더', async ({ page }) => {
    // Given + When. alice 로그인 + Import 페이지 진입
    await loginAndNavigateToImportPage(page)

    // Then. format 라디오 — CSV 기본 선택, JSON 미선택
    await expect(page.getByRole('radio', { name: 'CSV', exact: true })).toBeChecked()
    await expect(page.getByRole('radio', { name: 'JSON', exact: true })).not.toBeChecked()

    // Then. 파일 입력 렌더
    await expect(page.getByLabel('가져올 파일', { exact: true })).toBeVisible()

    // Then. 제출 버튼 렌더 — 파일 미선택 상태이므로 비활성
    const dryRunButton = page.getByRole('button', { name: '검증만 실행', exact: true })
    await expect(dryRunButton).toBeVisible()
    await expect(dryRunButton).toBeDisabled()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. dryRun → 실제 Import happy path
  //
  // Given  alice 로그인 + Import 페이지 진입
  // When   CSV 파일 주입 → [검증만 실행] → 진행률 폴링(PENDING→RUNNING→COMPLETED)
  // Then   "검증 완료" 표시
  // When   [이 파일로 실제 Import] 클릭 → 진행률 재폴링
  // Then   "Import 완료" 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 CSV dryRun → 실제 Import — "검증 완료" 후 "Import 완료"', async ({ page }) => {
    // Given. alice 로그인 + Import 페이지 진입
    await loginAndNavigateToImportPage(page)

    // When. CSV 파일 주입
    await uploadCsvFile(page)

    // When. "검증만 실행" 클릭 (dryRun=true 접수)
    const dryRunButton = page.getByRole('button', { name: '검증만 실행', exact: true })
    await expect(dryRunButton).not.toBeDisabled()
    await dryRunButton.click()

    // Then. tracking 단계 — 진행률(progressbar) 표시
    await expect(page.getByRole('progressbar')).toBeVisible()

    // Then. done 단계 — "검증 완료" 표시 (MSW 폴링 PENDING→RUNNING→COMPLETED)
    await expect(page.getByRole('status')).toContainText('검증 완료', {
      timeout: DONE_PHASE_TIMEOUT_MS,
    })

    // When. "이 파일로 실제 Import" 클릭 (같은 파일로 dryRun=false 재접수)
    await page.getByRole('button', { name: '이 파일로 실제 Import', exact: true }).click()

    // Then. tracking 단계 재진입 — 진행률(progressbar) 재표시
    await expect(page.getByRole('progressbar')).toBeVisible()

    // Then. done 단계 — "Import 완료" 표시
    await expect(page.getByRole('status')).toContainText('Import 완료', {
      timeout: DONE_PHASE_TIMEOUT_MS,
    })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. FAILED(IMPORT_PARSE_FAILED) — 에러 메시지 + 다시 시도
  //
  // Given  addInitScript로 LS_KEY_IMPORT_FAIL='true' 심기 (goto 전 등록)
  //        alice 로그인 + Import 페이지 진입
  // When   CSV 파일 주입 → [검증만 실행] → 첫 폴링에서 즉시 FAILED 수신
  // Then   role=alert "파일을 파싱하지 못했습니다..." 에러 메시지 표시
  //        [다시 시도] 버튼 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 Import 실패(IMPORT_PARSE_FAILED) — 에러 메시지 + 다시 시도 버튼', async ({ page }) => {
    // Given. addInitScript로 FAILED 시나리오 플래그 심기 — goto 전 등록 필수
    await forceImportFailScenario(page)

    // Given. alice 로그인 + Import 페이지 진입
    await loginAndNavigateToImportPage(page)

    // When. CSV 파일 주입 + "검증만 실행" 클릭
    await uploadCsvFile(page)
    await page.getByRole('button', { name: '검증만 실행', exact: true }).click()

    // Then. done(FAILED) 단계 — importFailureMessage(IMPORT_PARSE_FAILED) 한글 메시지 표시
    await expect(page.getByRole('alert')).toContainText('파일을 파싱하지 못했습니다', {
      timeout: DONE_PHASE_TIMEOUT_MS,
    })

    // Then. "다시 시도" 버튼 표시 (form 단계로 복귀)
    await expect(page.getByRole('button', { name: '다시 시도', exact: true })).toBeVisible()
  })
})
