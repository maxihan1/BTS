// FR-IM-01 D6/D7 E2E — CSV/JSON Import 폼 시나리오 (S1 렌더 / S2 dryRun→실제 Import happy path / S3 FAILED)
//
// 시나리오 개요.
//   S1. /projects/ATLAS/settings/import 진입 → format 라디오·파일 입력 렌더 확인 (골든 패스 진입점)
//   S2. CSV 파일 setInputFiles → [검증만 실행] → 진행률(progressbar) → "검증 완료"
//       → [이 파일로 실제 Import] → 진행률 재표시 → "Import 완료"
//   S3. FAILED(IMPORT_PARSE_FAILED) — test.fixme (BLOCKED, 아래 참고)
//
// S3 BLOCKED 사유 (실측 확인 완료).
//   ImportForm이 FAILED(에러 메시지+[다시 시도]) 단계를 렌더하려면 GET /api/v1/imports/{id}
//   폴링 응답이 status="FAILED"여야 한다. import-handlers.ts(mocks, src/)는 이 상태에 도달하는
//   유일한 수단으로 seedImportJob을 노출하지만, 이는 vitest(msw/node) 단위 테스트 전용이다 —
//   브라우저에서 실행되는 msw/browser 워커의 store는 Playwright(Node) 프로세스에서 직접 호출할
//   수 없다 (msw-derived-behavior-shared-store-e2e 교훈: 시드는 "브라우저에서 seed 가능한 공유
//   store"를 통해야 함). 다른 핸들러 파일들(board-handlers.ts 등)이 쓰는 localStorage 시나리오
//   토글(e2e-msw-scenario-toggle-localstorage-flag 패턴)이 import-handlers.ts에는 아직 없다.
//
//   대안으로 page.route('**/api/v1/imports/*', ...)를 GET에 한정해 FAILED 응답으로 오버라이드를
//   시도했으나(POST는 패턴에서 제외 — serviceWorkers 전역 차단 아님, e2e-msw-serviceworker-block과
//   무관), 실제 실행 결과 MSW의 Service Worker가 요청을 먼저 가로채 응답하고 Playwright route가
//   개입하지 못했다 — 최종 화면에 "검증 완료"(정상 COMPLETED 진행)가 표시됨을 실측으로 확인
//   (test-results 아티팩트에 role=status "검증 완료" 렌더 확정). 즉 src/ 수정 없이는 재현 불가.
//
//   해결책(qa 범위 밖 — src/ 수정 필요): import-handlers.ts의 getImportStatusHandler에 다른
//   핸들러와 동일한 `globalThis.localStorage?.getItem(LS_KEY_IMPORT_FAILED_SCENARIO)` 분기를
//   추가하면 addInitScript+localStorage 패턴으로 FAILED를 결정적으로 재현할 수 있다. 아래
//   test.fixme 본문은 그 분기가 추가된 뒤 그대로 활성화(test.fixme→test)하면 되도록 작성해 두었다.
//
// 설계 결정 (S1/S2).
//   - ImportForm은 MSW import-handlers.ts(stateful store, pollCount 기반 PENDING→RUNNING→COMPLETED
//     3단계 진행)를 사용한다.
//   - 폴링 간격 IMPORT_POLL_INTERVAL_MS=1500ms, MSW pollCount 진행: 0(PENDING)→1(RUNNING,50%)
//     →2+(COMPLETED,100%) — done 전환까지 최대 약 3000ms + 렌더 지연. 여유 있는 timeout 부여.
//
// MSW 핸들러 확인 (apps/web/src/mocks/import-handlers.ts).
//   - POST /api/v1/imports            → 202 + {jobId, status:"PENDING", dryRun}
//   - GET  /api/v1/imports/:id        → stateful 폴링 (pollCount 0/1/2+ 단계 진행)
//   - GET  /api/v1/imports/:id/errors → text/csv 에러 로그 (errorLogReady 무관하게 반환)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 전역 차단 절대 금지
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
 * GET /api/v1/imports/{id} 요청만 선별적으로 가로채 FAILED(IMPORT_PARSE_FAILED) 응답으로
 * 고정 시도한다. POST(작업 접수)·GET .../errors(다른 경로 세그먼트)는 매칭되지 않아 MSW가
 * 그대로 처리한다 — MSW 워커 자체(serviceWorkers 옵션)는 건드리지 않는다.
 *
 * 실측 결과: MSW Service Worker가 요청을 먼저 가로채 이 route보다 우선 응답하여 효과가 없음을
 * 확인했다(파일 상단 "S3 BLOCKED 사유" 참고) — 아래 S3 test.fixme는 이 함수를 유지한 채
 * import-handlers.ts에 localStorage 시나리오 토글이 추가되면(권장: 이 함수를
 * addInitScript+localStorage 방식으로 교체) 활성화할 수 있도록 시나리오 형태만 보존한다.
 *
 * @param page Playwright Page 객체
 */
async function forceImportStatusFailed(page: Page): Promise<void> {
  await page.route('**/api/v1/imports/*', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        jobId: '11111111-0000-4000-a000-000000000001',
        status: 'FAILED',
        progress: 0,
        succeededRows: 0,
        failedRows: 0,
        errorCode: 'IMPORT_PARSE_FAILED',
        errorLogReady: false,
        dryRun: true,
      }),
    })
  })
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
  // BLOCKED — test.fixme (파일 상단 "S3 BLOCKED 사유" 참고).
  //   import-handlers.ts(src/, qa 범위 밖)에 FAILED를 결정적으로 재현할 localStorage 시나리오
  //   토글이 아직 없다. page.route() 오버라이드는 실측으로 무효화됨을 확인했다(MSW Service
  //   Worker가 우선 응답). 아래 시나리오 본문은 토글이 추가된 뒤 그대로 활성화할 목적으로
  //   Given/When/Then을 보존해 두었다.
  //
  // Given  page.route로 GET /api/v1/imports/{id} 응답을 FAILED(IMPORT_PARSE_FAILED)로 고정
  //        alice 로그인 + Import 페이지 진입
  // When   CSV 파일 주입 → [검증만 실행] → 첫 폴링에서 즉시 FAILED 수신
  // Then   role=alert "파일을 파싱하지 못했습니다..." 에러 메시지 표시
  //        [다시 시도] 버튼 표시
  // ───────────────────────────────────────────────────────────────────────────
  test.fixme('S3 Import 실패(IMPORT_PARSE_FAILED) — 에러 메시지 + 다시 시도 버튼', async ({ page }) => {
    // Given. GET 폴링 응답을 FAILED로 고정 (POST는 라우트 미매칭 — MSW가 정상 접수 처리)
    await forceImportStatusFailed(page)

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
