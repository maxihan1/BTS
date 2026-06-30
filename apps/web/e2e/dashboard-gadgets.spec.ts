// FR-DB-02 D7 E2E — 가젯 추가·렌더·카탈로그 게이팅·읽기전용 시나리오 + 무회귀 가드
//
// 시나리오 개요.
//   S1. 가젯 추가 (text_widget) — 카탈로그 모달 → 설정 폼 → 추가 → 저장 → 성공 토스트 (C6: 헤더 "텍스트")
//   S2. 이슈 가젯 렌더 (recently_created) — 추가 후 MSW 이슈 목록 렌더 (C6: 헤더 "최근 생성")
//   S4. 정적 가젯 (link_list) — 추가 후 링크 렌더 확인 (C6: 헤더 "링크 목록")
//   S6. enabled=false 게이팅 — 카탈로그에서 Pie Chart 비활성·"준비 중" 확인·클릭 불가
//   S7. 비소유 읽기전용 — bob 소유 ORG 대시보드 → "가젯 추가" 버튼 부재
//
// 설계 결정.
//   - serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block) — MSW 핸들러 추가가 정석.
//   - sleep / page.waitForTimeout 금지 → await expect(...).toBeVisible() 사용.
//   - reload 금지 (msw-derived-behavior-shared-store-e2e) — MSW store 리셋=가짜그린.
//     SPA 내부 goto/click으로 이동.
//   - C4: "위젯 추가" 버튼 제거 — "가젯 추가" 단일 버튼으로 일원화.
//     헤더 컨테이너 한정 + exact:true (playwright-getbyrole-exact-strict-mode).
//   - RGL 드래그/리사이즈 headless 제약으로 SKIP (기존 dashboard.spec.ts S3b 동일 이유).
//   - assigned_to_me는 whoami userId(00000000-...-001)↔ISSUE_FILTER_ALICE_ID 불일치로
//     recently_created(projectKey=ATLAS, assignee 필터 없음)로 대체
//     (e2e-fixture-whoami-userid-alignment — buildFilteredPage는 assignee 미전달 시 전건 반환).
//   - dashboard-handlers.ts 보강 불필요 — 카탈로그·CRUD·이슈 핸들러 모두 기존 존재.
//   - 각 테스트는 독립 Playwright context(새 ServiceWorker)에서 실행 → store 격리 보장.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — dashboard-fixtures.ts와 인라인 동기화
//
// dashboard-fixtures.ts는 import.meta.env.MODE를 모듈 레벨에서 참조하므로
// Playwright Node.js 런타임에서 직접 import 시 import.meta 접근 오류가 발생한다.
// (dashboard.spec.ts 동일 패턴 — 설계 결정 인라인 주석 참조)
// ─────────────────────────────────────────────────────────────────────────────

/** dashboard-fixtures.ts DEFAULT_DASHBOARD.id와 동기화 */
const DEFAULT_DASHBOARD_ID = 'a0000000-0000-4000-8000-000000000001'

/** dashboard-fixtures.ts OTHER_DASHBOARD.id(bob 소유 ORG)와 동기화 */
const OTHER_DASHBOARD_ID = 'b0000000-0000-4000-8000-000000000001'

/** DEFAULT_DASHBOARD 상세 URL */
const DEFAULT_DASHBOARD_URL = `/dashboards/${DEFAULT_DASHBOARD_ID}`

/** OTHER_DASHBOARD(bob 소유 ORG) 상세 URL */
const OTHER_DASHBOARD_URL = `/dashboards/${OTHER_DASHBOARD_ID}`

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-DB-02 가젯 시스템 (카탈로그/추가/렌더/게이팅/읽기전용)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 가젯 추가 (text_widget) — golden path (C6: 타일 헤더 "텍스트" 한국어 라벨)
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입 (빈 그리드)
  // When   헤더 "가젯 추가" 버튼 클릭 → GadgetCatalogModal 열림 (Step 1 카탈로그)
  //        "Text Widget" 가젯 선택 → Step 2 설정 폼 전환
  //        "Markdown" 필드에 "## E2E 테스트 메모" 입력
  //        "추가" 버튼 클릭 → 모달 닫힘 + 가젯 타일 그리드 추가
  //        "저장" 버튼 클릭 → PATCH 성공
  // Then   그리드 타일 헤더에 "텍스트" 표시 (C6: gadgetLabels 한국어 라벨)
  //        타일 본문에 "## E2E 테스트 메모" plain text 렌더
  //        성공 토스트 "대시보드가 저장되었습니다." 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 text_widget 가젯 추가 + 저장 — golden path (C6 한국어 라벨)', async ({ page }) => {
    // Given. alice 로그인 + 빈 그리드 상태 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()
    // C4: emptyGrid 문구 업데이트
    await expect(page.getByText('가젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()

    // When. 헤더 "가젯 추가" 버튼 클릭 (C4: 단일 가젯 추가 버튼으로 일원화)
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // Then. GadgetCatalogModal Step 1 열림 — 카탈로그 제목 확인 (MSW catalog 응답 대기)
    await expect(page.getByRole('heading', { name: '가젯 추가' })).toBeVisible()

    // Then. 카탈로그 로드 확인 — "Text Widget" 버튼이 표시될 때까지 대기
    await expect(page.getByRole('button', { name: 'Text Widget', exact: true })).toBeVisible()

    // When. "Text Widget" 선택 → Step 2 설정 폼 전환
    await page.getByRole('button', { name: 'Text Widget', exact: true }).click()

    // Then. Step 2 전환 확인 — 설정 폼 제목
    await expect(page.getByText('가젯 설정 — Text Widget')).toBeVisible()

    // When. Markdown 텍스트 입력 (label 연결 textarea — htmlFor/id 쌍)
    await page.getByLabel('Markdown').fill('## E2E 테스트 메모')

    // When. "추가" 버튼 클릭 → 설정 검증 통과 → 모달 닫힘
    await page.getByRole('button', { name: '추가', exact: true }).click()

    // Then. 모달 닫힘 — dialog가 DOM에서 제거됨 (조건부 마운트 패턴)
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Then. 그리드 타일 헤더에 한국어 가젯 라벨 "텍스트" 표시 (C6: gadgetLabels 매핑)
    await expect(page.getByText('텍스트')).toBeVisible()

    // Then. 타일 본문에 markdown plain text 표시 (TextWidgetGadget.tsx — dangerouslySetInnerHTML 없음)
    await expect(page.getByText('## E2E 테스트 메모')).toBeVisible()

    // Then. 미저장 변경 인디케이터 표시 (dirty=true)
    await expect(page.getByText('저장되지 않은 변경 사항이 있습니다')).toBeVisible()

    // When. "저장" 버튼 클릭 → PATCH layout+version → MSW patchDashboardHandler 처리
    await page.getByRole('button', { name: '저장', exact: true }).click()

    // Then. 성공 토스트 표시
    await expect(page.getByText('대시보드가 저장되었습니다.')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 이슈 가젯 렌더 (recently_created)
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입
  // When   "가젯 추가" → "Recently Created" 선택 → projectKey "ATLAS" 입력 → "추가"
  //        모달 닫힘 + recently_created 타일 그리드 추가
  //        useGadgetData('recently_created', {projectKey:'ATLAS'}) →
  //        GET /api/v1/issues?projectKey=ATLAS&page=0&size=10 (MSW listIssuesHandler)
  // Then   이슈 목록 렌더 — "ATLAS-1" 이슈 키 링크 표시 (issuePageFixture 4건)
  //        타일 헤더에 "최근 생성" 표시 (C6: gadgetLabels 한국어 라벨)
  // Then   이슈 목록 렌더 — "ATLAS-1" 이슈 키 링크 표시 (issuePageFixture 4건)
  //
  // 주의.
  //   - assigned_to_me 대신 recently_created 사용:
  //     whoami userId(00000000-...-001)≠ISSUE_FILTER_ALICE_ID(c3d4e5f6-...),
  //     assigned_to_me assignee 필터링 시 0건 반환 (e2e-fixture-whoami-userid-alignment)
  //   - reload 금지 — goto 이후 SPA 내 상태로 동작, MSW store 유지
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 이슈 가젯 렌더 (recently_created) — MSW 이슈 목록 표시', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()

    // When. "가젯 추가" → GadgetCatalogModal 열기
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // When. 카탈로그 로드 대기 + "Recently Created" 선택 (ISSUE 카테고리)
    await expect(page.getByRole('button', { name: 'Recently Created', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Recently Created', exact: true }).click()

    // Then. Step 2 설정 폼 전환 확인
    await expect(page.getByText('가젯 설정 — Recently Created')).toBeVisible()

    // When. projectKey 필드에 "ATLAS" 입력 (STRING 타입 → label 연결 input)
    await page.getByLabel('Project Key').fill('ATLAS')

    // When. "추가" → 모달 닫힘 + recently_created 타일 추가
    await page.getByRole('button', { name: '추가', exact: true }).click()

    // Then. 모달 닫힘
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Then. 타일 헤더에 한국어 라벨 "최근 생성" 표시 (C6: gadgetLabels 매핑)
    await expect(page.getByText('최근 생성')).toBeVisible()

    // Then. 이슈 목록 렌더 대기 — useGadgetData가 MSW listIssuesHandler 호출 후 응답
    //   issuePageFixture 4건(ATLAS-1/2/3/5) 중 ATLAS-1 이슈 키 링크 확인
    //   (Link to="/issues/$key" → <a>ATLAS-1</a>)
    await expect(page.getByRole('link', { name: 'ATLAS-1' })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S4. 정적 가젯 렌더 (link_list)
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입
  // When   "가젯 추가" → "Link List" 선택 → 초기 1행에 label="BTS Portal" url="https://bts.local"
  //        "추가" → 모달 닫힘 + link_list 타일 그리드 추가
  // Then   타일 본문에 "BTS Portal" 링크 표시 (http/https 스킴 허용·XSS 차단)
  //        링크 rel="noopener noreferrer" (외부 탭 내킹 방지)
  //
  // 주의.
  //   - ARRAY 타입 필드는 <span> 레이블 사용(label 아님) → 서브 필드는 placeholder로 탐색
  //   - 초기 1행 자동 생성 (GadgetConfigForm arrVals 초기값) → 항목 추가 버튼 불필요
  // ───────────────────────────────────────────────────────────────────────────
  test('S4 link_list 정적 가젯 렌더 — 링크 표시 + noopener 확인', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()

    // When. "가젯 추가" → GadgetCatalogModal 열기
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // When. 카탈로그 로드 대기 + "Link List" 선택 (STATIC 카테고리)
    await expect(page.getByRole('button', { name: 'Link List', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Link List', exact: true }).click()

    // Then. Step 2 설정 폼 전환 확인
    await expect(page.getByText('가젯 설정 — Link List')).toBeVisible()

    // When. 초기 1행 서브 필드 입력 (ARRAY 필드 — placeholder로 탐색)
    //   label 서브 필드: placeholder="label"
    //   url 서브 필드: placeholder="url", type="url"
    await page.getByPlaceholder('label').fill('BTS Portal')
    await page.getByPlaceholder('url').fill('https://bts.local')

    // When. "추가" → 모달 닫힘 + link_list 타일 추가
    await page.getByRole('button', { name: '추가', exact: true }).click()

    // Then. 모달 닫힘
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Then. 타일 헤더에 한국어 라벨 "링크 목록" 표시 (C6: gadgetLabels 매핑)
    await expect(page.getByText('링크 목록')).toBeVisible()

    // Then. 타일 본문에 "BTS Portal" 링크 표시
    //   LinkListGadget.tsx: isSafeUrl("https://bts.local")=true → <a href="https://bts.local">BTS Portal</a>
    const link = page.getByRole('link', { name: 'BTS Portal' })
    await expect(link).toBeVisible()

    // Then. 외부 링크 보안 속성 확인
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    await expect(link).toHaveAttribute('target', '_blank')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6. enabled=false 카탈로그 게이팅
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입
  // When   헤더 "가젯 추가" → GadgetCatalogModal 열림
  // Then   CHART 카테고리 "Pie Chart" 버튼이 비활성(disabled)
  //        "준비 중" 텍스트가 Pie Chart 버튼 내부에 표시
  //        비활성 버튼 클릭 시 Step 2로 전환되지 않음 (cursor-not-allowed)
  //
  // 근거.
  //   GadgetCatalogModal.tsx L154: disabled={!entry.enabled}
  //   GadgetType.kt: PIE_CHART enabled=false (MVP 미포함)
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 enabled=false 카탈로그 게이팅 — Pie Chart 비활성·준비 중 표시', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()

    // When. "가젯 추가" → GadgetCatalogModal 열기
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // Then. 카탈로그 로드 대기 (enabled=true 가젯 표시 확인으로 로드 완료 검증)
    await expect(page.getByRole('button', { name: 'Text Widget', exact: true })).toBeVisible()

    // Then. "Pie Chart" 버튼 비활성 확인 (CHART 카테고리, enabled=false)
    const piechartBtn = page.getByRole('button', { name: 'Pie Chart', exact: true })
    await expect(piechartBtn).toBeVisible()
    await expect(piechartBtn).toBeDisabled()

    // Then. "준비 중" 텍스트가 Pie Chart 버튼 내부에 표시
    await expect(piechartBtn).toContainText('준비 중')

    // Then. 비활성 버튼 클릭 시 Step 2 전환 안 됨 (카탈로그 제목 유지)
    //   disabled 속성으로 onClick이 방화, selectedEntry=null 유지
    await piechartBtn.click({ force: true })
    await expect(page.getByRole('heading', { name: '가젯 추가' })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7. 비소유자 읽기전용 — "가젯 추가" 버튼 부재 (C4: 위젯 추가 일원화)
  //
  // Given  alice 로그인 + OTHER_DASHBOARD(bob 소유 ORG) 상세 진입
  //        canEditDashboard(otherDashboard, aliceId) = false (ownerId ≠ aliceId)
  // When   상세 페이지 렌더
  // Then   "가젯 추가" 버튼 부재 (편집 전용 UI 전체 숨김)
  //        대시보드 이름 "Bob의 팀 대시보드" 표시 (읽기는 가능)
  //
  // 회귀 가드.
  //   dashboard.spec.ts S7(가젯 추가/저장/설정 버튼 부재)의 가젯 전용 버전.
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 비소유자 읽기전용 — "가젯 추가" 버튼 부재 (C4)', async ({ page }) => {
    // Given. alice 로그인 + bob 소유 ORG 대시보드 상세 진입
    await loginAsAlice(page)
    await page.goto(OTHER_DASHBOARD_URL)

    // Given. 페이지 완전 로드 확인 (대시보드 이름 표시)
    await expect(page.getByRole('heading', { name: 'Bob의 팀 대시보드' })).toBeVisible()

    // Then. 편집 전용 버튼 없음 — "가젯 추가" (C4: 단일 가젯 추가 버튼으로 일원화)
    await expect(page.getByRole('button', { name: '가젯 추가', exact: true })).not.toBeVisible()

    // Then. 카탈로그 모달 미열림 확인 (dialog 없음)
    await expect(page.getByRole('dialog')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // RGL 드래그/리사이즈 — SKIP
  //
  // react-grid-layout은 headless Chromium에서 containerWidth=0으로 초기화되어
  // 드래그 좌표가 비정상적이다. (기존 dashboard.spec.ts S3b와 동일 제약)
  // onLayoutChange 콜백은 DashboardGrid.test.tsx 단위 테스트로 커버된다.
  // ───────────────────────────────────────────────────────────────────────────
  test.skip('S3b 드래그/리사이즈 — RGL headless 제약으로 SKIP (단위 테스트 커버)', () => {
    // SKIP: headless Chromium에서 RGL containerWidth=0 초기화로 드래그 좌표 비정상.
  })
})
