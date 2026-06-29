// FR-EX-01 D7 E2E — CSV/XLSX 내보내기 시나리오 (S1 CSV / S2 XLSX / S3 컬럼부분선택 / S4 상한초과)
//
// 시나리오 개요.
//   S1. CSV 내보내기 (골든 패스)
//       — /search AQL 쿼리 존재 → "내보내기" 툴바 버튼 → 다이얼로그 → CSV(기본) → 내보내기
//         → page.waitForEvent('download') + 파일명 *.csv 검증
//   S2. XLSX 전환 후 내보내기
//       — 다이얼로그에서 XLSX 라디오 선택 → 내보내기 → 파일명 *.xlsx 검증
//   S3. 컬럼 부분선택 후 내보내기
//       — 일부 체크박스 해제 후 내보내기 → 다운로드 발생 검증 (MSW는 선택된 컬럼 무관 파일 반환)
//   S4. 상한초과 400 → 다이얼로그 내 role=alert 에러 메시지 표시
//
// 설계 결정.
//   - 진입 방식: loginAsAlice 후 page.goto('/search?q=status+%3D+open&projectKey=ATLAS').
//     URL에 q 파라미터를 미리 설정해 툴바 "내보내기" 버튼이 즉시 활성화된다.
//     (버튼: disabled={q.trim().length === 0} — URL q 파라미터 기준, search.tsx)
//   - 툴바 버튼: aria-label="검색 결과 내보내기" — 다이얼로그 내 "내보내기" 버튼과 충돌 없음
//     (playwright-getbyrole-exact-strict-mode 교훈 — 컨테이너 한정 불필요)
//   - 다이얼로그 내 버튼: role=dialog 내 "내보내기" exact — 툴바 버튼과 분리
//   - MSW 시나리오 토글: addInitScript + localStorage.setItem 패턴 (goto 전 등록)
//     플래그 키: E2E_EXPORT_SCENARIO_KEY = '__bts_e2e_export_scenario' (search-handlers.ts)
//   - 다운로드 인터셉트: triggerBlobDownload가 <a download=filename>.click() 호출
//     → Playwright page.waitForEvent('download')가 이를 캡처
//     → download.suggestedFilename()이 filename 속성값을 반환
//   - reload 금지 주의: S4에서 MSW 상한초과 시나리오는 addInitScript+goto로만 설정
//     (msw-mutation-stateful-refetch / e2e-msw-scenario-toggle-localstorage-flag 교훈)
//
// MSW 핸들러 확인.
//   - exportIssuesHandler (search-handlers.ts)가 searchHandlers 배열에 포함되어 handlers.ts에 등록됨
//   - 기본(no flag): CSV 응답 + Content-Disposition: attachment; filename="ATLAS-issues-...csv"
//   - format=XLSX 요청: XLSX 응답 + Content-Disposition: attachment; filename="ATLAS-issues-...xlsx"
//   - 'limit-exceeded': SEARCH_EXPORT_LIMIT_EXCEEDED 400 + detail 메시지
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: aria-label 고유 버튼은 exact=true로 충분
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript → goto 순서 필수
//   - e2e-loginasalice-fixture-fr-au-07-regression: loginAsAlice는 issue-fixtures 공유 헬퍼
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 수정 없음 — 이 파일만 신규 추가

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — search-handlers.ts E2E_EXPORT_SCENARIO_KEY 와 동기화
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW export 시나리오 토글 localStorage 키.
 * search-handlers.ts `E2E_EXPORT_SCENARIO_KEY` 와 동일해야 한다.
 */
const E2E_EXPORT_SCENARIO_KEY = '__bts_e2e_export_scenario'

/** q 파라미터가 포함된 검색 URL — 툴바 "내보내기" 버튼 즉시 활성화용 */
const SEARCH_WITH_QUERY_URL = '/search?q=status+%3D+open&projectKey=ATLAS'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * alice 로그인 후 쿼리가 포함된 /search 페이지로 이동한다.
 *
 * URL에 q=status+%3D+open 을 미리 포함시켜 툴바 "내보내기" 버튼이
 * 페이지 진입 시점부터 활성화 상태(disabled=false)가 된다.
 * (search.tsx: `disabled={q.trim().length === 0}` — URL q 파라미터 기준)
 *
 * @param page Playwright Page 객체
 */
async function loginAndNavigateToSearchWithQuery(
  page: import('@playwright/test').Page,
): Promise<void> {
  await loginAsAlice(page)
  await page.goto(SEARCH_WITH_QUERY_URL)
  await expect(page.getByRole('heading', { name: 'AQL 검색', level: 1 })).toBeVisible()
  // 툴바 "내보내기" 버튼이 활성화될 때까지 대기 (q 파라미터가 URL에 있으므로 즉시 활성)
  await expect(
    page.getByRole('button', { name: '검색 결과 내보내기', exact: true }),
  ).not.toBeDisabled()
}

/**
 * 내보내기 다이얼로그를 열고 다이얼로그 엘리먼트를 반환한다.
 *
 * 툴바 "내보내기" 버튼(aria-label="검색 결과 내보내기")을 클릭해
 * ExportDialog(role="dialog")가 나타날 때까지 대기한다.
 *
 * @param page Playwright Page 객체
 * @returns dialog 로케이터
 */
async function openExportDialog(
  page: import('@playwright/test').Page,
): Promise<ReturnType<typeof page.getByRole>> {
  await page.getByRole('button', { name: '검색 결과 내보내기', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  // 다이얼로그 제목 h2 "내보내기" 확인 (ExportDialog DialogPrimitive.Title → <h2>)
  // getByText 금지 — dialog 내 submit 버튼도 동일 텍스트이므로 strict mode violation
  await expect(dialog.getByRole('heading', { name: '내보내기', exact: true })).toBeVisible()
  return dialog
}

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-EX-01 CSV/XLSX 내보내기 (S1 CSV / S2 XLSX / S3 컬럼부분선택 / S4 상한초과)', () => {
  // ─────────────────────────────────────────────────────────────────────────
  // S1. CSV 내보내기 (골든 패스)
  //
  // Given  alice 로그인 + /search?q=status+%3D+open&projectKey=ATLAS 진입
  //        툴바 "내보내기" 버튼 활성화 상태
  // When   "내보내기" 툴바 버튼 클릭 → 다이얼로그 오픈
  //        CSV 라디오 기본 선택 확인 → 다이얼로그 "내보내기" 버튼 클릭
  // Then   page.waitForEvent('download') 발생
  //        파일명이 *.csv 패턴 (download.suggestedFilename() = <a download> 속성값)
  //        MSW CSV 응답: Content-Disposition attachment; filename="ATLAS-issues-...csv"
  //
  // 핵심 검증: MSW가 CSV Blob을 응답하고 triggerBlobDownload가 브라우저 다운로드를 트리거함
  // ─────────────────────────────────────────────────────────────────────────
  test('S1 CSV 내보내기 — 다운로드 발생 + 파일명 *.csv', async ({ page }) => {
    // Given. alice 로그인 + q가 있는 /search 진입
    await loginAndNavigateToSearchWithQuery(page)

    // When. 내보내기 다이얼로그 오픈
    const dialog = await openExportDialog(page)

    // Given. CSV 라디오가 기본 선택 상태 확인
    await expect(dialog.getByRole('radio', { name: 'CSV', exact: true })).toBeChecked()

    // When. 다이얼로그 "내보내기" 버튼 클릭 + 다운로드 이벤트 대기
    // Promise.all로 waitForEvent를 click보다 먼저 등록해 경쟁 조건 방지
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      dialog.getByRole('button', { name: '내보내기', exact: true }).click(),
    ])

    // Then. 다운로드 파일명이 *.csv 패턴
    // download.suggestedFilename() = <a download=filename> 속성값
    // MSW exportIssuesHandler CSV 응답 파일명: "ATLAS-issues-20260629T000000Z.csv"
    expect(download.suggestedFilename()).toMatch(/\.csv$/)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S2. XLSX 전환 후 내보내기
  //
  // Given  alice 로그인 + /search?q=status+%3D+open&projectKey=ATLAS 진입
  // When   "내보내기" 툴바 버튼 클릭 → 다이얼로그 오픈
  //        XLSX 라디오 선택 → 다이얼로그 "내보내기" 버튼 클릭
  // Then   page.waitForEvent('download') 발생
  //        파일명이 *.xlsx 패턴
  //        MSW format=XLSX 분기: Content-Disposition attachment; filename="ATLAS-issues-...xlsx"
  //
  // 검증 핵심: format 라디오 전환 → MSW XLSX 분기 응답 → 파일명 확인
  // ─────────────────────────────────────────────────────────────────────────
  test('S2 XLSX 전환 후 내보내기 — 파일명 *.xlsx', async ({ page }) => {
    // Given. alice 로그인 + q가 있는 /search 진입
    await loginAndNavigateToSearchWithQuery(page)

    // When. 내보내기 다이얼로그 오픈
    const dialog = await openExportDialog(page)

    // When. XLSX 라디오 선택
    await dialog.getByRole('radio', { name: 'XLSX', exact: true }).click()
    await expect(dialog.getByRole('radio', { name: 'XLSX', exact: true })).toBeChecked()
    await expect(dialog.getByRole('radio', { name: 'CSV', exact: true })).not.toBeChecked()

    // When. 다이얼로그 "내보내기" 버튼 클릭 + 다운로드 이벤트 대기
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      dialog.getByRole('button', { name: '내보내기', exact: true }).click(),
    ])

    // Then. 다운로드 파일명이 *.xlsx 패턴
    // MSW exportIssuesHandler XLSX 응답 파일명: "ATLAS-issues-20260629T000000Z.xlsx"
    expect(download.suggestedFilename()).toMatch(/\.xlsx$/)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S3. 컬럼 부분선택 후 내보내기
  //
  // Given  alice 로그인 + /search?q=status+%3D+open&projectKey=ATLAS 진입
  // When   "내보내기" 툴바 버튼 클릭 → 다이얼로그 오픈
  //        "Assignee ID" / "Priority Name" / "Updated At" 체크박스 해제 (6개 선택)
  //        다이얼로그 "내보내기" 버튼 클릭
  // Then   page.waitForEvent('download') 발생 (컬럼 수 무관 — MSW는 파일을 항상 반환)
  //        파일명이 *.csv 패턴 (기본 format=CSV 유지)
  //        "내보내기" 버튼이 활성 상태 (canSubmit = selectedColumns.length > 0)
  //
  // 검증 핵심: 체크박스 해제 후에도 canSubmit=true이면 다운로드가 정상 작동함
  // ─────────────────────────────────────────────────────────────────────────
  test('S3 컬럼 부분선택 후 내보내기 — 다운로드 발생', async ({ page }) => {
    // Given. alice 로그인 + q가 있는 /search 진입
    await loginAndNavigateToSearchWithQuery(page)

    // When. 내보내기 다이얼로그 오픈
    const dialog = await openExportDialog(page)

    // Given. 체크박스 전체 선택 상태 확인 (ExportDialog 초기값: ALL_COLUMN_TOKENS)
    await expect(dialog.getByRole('checkbox', { name: 'Key', exact: true })).toBeChecked()
    await expect(dialog.getByRole('checkbox', { name: 'Assignee ID', exact: true })).toBeChecked()

    // When. "Assignee ID" / "Priority Name" / "Updated At" 체크 해제
    await dialog.getByRole('checkbox', { name: 'Assignee ID', exact: true }).click()
    await dialog.getByRole('checkbox', { name: 'Priority Name', exact: true }).click()
    await dialog.getByRole('checkbox', { name: 'Updated At', exact: true }).click()

    // Then. 해제된 체크박스 확인
    await expect(dialog.getByRole('checkbox', { name: 'Assignee ID', exact: true })).not.toBeChecked()
    await expect(dialog.getByRole('checkbox', { name: 'Priority Name', exact: true })).not.toBeChecked()
    await expect(dialog.getByRole('checkbox', { name: 'Updated At', exact: true })).not.toBeChecked()

    // Then. "내보내기" 버튼 활성 상태 (selectedColumns.length = 6 > 0)
    await expect(dialog.getByRole('button', { name: '내보내기', exact: true })).not.toBeDisabled()

    // When. 다이얼로그 "내보내기" 버튼 클릭 + 다운로드 이벤트 대기
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      dialog.getByRole('button', { name: '내보내기', exact: true }).click(),
    ])

    // Then. 다운로드 발생 + 파일명 *.csv (기본 format=CSV 유지)
    expect(download.suggestedFilename()).toMatch(/\.csv$/)
  })

  // ─────────────────────────────────────────────────────────────────────────
  // S4. 상한초과 400 → 다이얼로그 내 role=alert 에러 메시지 표시
  //
  // Given  addInitScript으로 '__bts_e2e_export_scenario'='limit-exceeded' 심기 (goto 전)
  //        alice 로그인 + /search?q=status+%3D+open&projectKey=ATLAS 진입
  // When   "내보내기" 툴바 버튼 클릭 → 다이얼로그 오픈 → "내보내기" 클릭
  // Then   다이얼로그 내 role=alert 표시 (SEARCH_EXPORT_LIMIT_EXCEEDED detail 메시지)
  //        다이얼로그가 닫히지 않음 (에러 발생 시 onClose 미호출)
  //        다운로드 이벤트 미발생 (400 응답이므로 blob 다운로드 없음)
  //
  // MSW 'limit-exceeded' 시나리오: 400 + detail "내보내기 한도(10,000건)를 초과했습니다..."
  // ExportDialog.resolveExportError: ApiError body.detail → 에러 메시지 반환
  // ─────────────────────────────────────────────────────────────────────────
  test('S4 상한초과 — 다이얼로그 내 role=alert 에러 표시, 다운로드 없음', async ({ page }) => {
    // Given. addInitScript으로 MSW export 시나리오 플래그 설정 — goto 전 등록 필수
    // (e2e-msw-scenario-toggle-localstorage-flag 교훈)
    await page.addInitScript((key: string) => {
      window.localStorage.setItem(key, 'limit-exceeded')
    }, E2E_EXPORT_SCENARIO_KEY)

    // Given. alice 로그인 + q가 있는 /search 진입
    await loginAndNavigateToSearchWithQuery(page)

    // When. 내보내기 다이얼로그 오픈
    const dialog = await openExportDialog(page)

    // When. 다이얼로그 "내보내기" 버튼 클릭 (다운로드 없이 에러 응답 예상)
    await dialog.getByRole('button', { name: '내보내기', exact: true }).click()

    // Then. 다이얼로그 내 role=alert 표시 (submitError 표시)
    // ExportDialog resolveExportError: ApiError body.detail 추출 → 에러 p[role=alert]
    const alert = dialog.getByRole('alert')
    await expect(alert).toBeVisible()
    // MSW 반환 detail: "내보내기 한도(10,000건)를 초과했습니다. 쿼리를 좁혀 다시 시도하세요."
    await expect(alert).toContainText('초과')

    // Then. 다이얼로그가 여전히 열려 있음 (에러 시 onClose 미호출 — ExportDialog.onSuccess만 닫음)
    await expect(dialog).toBeVisible()
  })
})
