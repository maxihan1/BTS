// FR-DB-02 D7 E2E — 가젯 추가·렌더·카탈로그 게이팅·읽기전용 시나리오 + 무회귀 가드
//
// 시나리오 개요.
//   S1. 가젯 추가 (text_widget) — 카탈로그 모달 → 설정 폼 → 추가 → 저장 → 성공 토스트 (C6: 헤더 "텍스트")
//   S2. 이슈 가젯 렌더 (recently_created) — 추가 후 MSW 이슈 목록 렌더 (C6: 헤더 "최근 생성")
//   S4. 정적 가젯 (link_list) — 추가 후 링크 렌더 확인 (C6: 헤더 "링크 목록")
//   S6. enabled=false 게이팅 — 카탈로그에서 Comments Recent 비활성·"준비 중" 확인·클릭 불가
//   S7. 비소유 읽기전용 — bob 소유 ORG 대시보드 → "가젯 추가" 버튼 부재
//   S8. 신규 4종 렌더 (A8) — CHART·ACTIVITY 의 enabled 항목을 카탈로그에서 읽어 순회
//   S9. 편집 모드 게이팅 (A7′) — 보기 모드에는 편집 UI 가 없고 편집 모드에만 있다
//   S10. 선택기 계단식 (A6) — 프로젝트를 고르기 전 보드는 비활성, 고르면 그 프로젝트 보드만
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
// ★문구를 손으로 베끼지 않는다. 베낀 문구는 i18n 이 바뀌어도 안 따라오고, 실제로
//   「먼저 프로젝트를 선택하세요」를 「프로젝트를 먼저 선택하세요」로 잘못 적어 red 를 봤다.
//   `already-authed.spec.ts` 가 같은 파일을 이미 이렇게 import 한다(순수 상수 · import.meta 없음).
import { dashboardModeLabels, gadgetPickerLabels } from '../src/i18n/dashboard-labels'

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

/** 대시보드 헤더 — 액션 버튼이 사는 자리 */
function headerOf(page: import('@playwright/test').Page) {
  return page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
}

/**
 * 편집 모드로 들어간다.
 *
 * ★Jira 패리티 JD-1 로 동선이 바뀌었다 — 「가젯 추가」·타일 `⋯`·드래그가 전부 **편집 모드
 * 안에만** 있다. 보기 모드에서 바로 「가젯 추가」를 찾으면 없다.
 */
async function enterEditMode(page: import('@playwright/test').Page): Promise<void> {
  await headerOf(page)
    .getByRole('button', { name: dashboardModeLabels.enterEdit, exact: true })
    .click()
}

/**
 * 카탈로그 모달에서 **한 카테고리의 활성 가젯 라벨**을 읽는다.
 *
 * ★타입 목록을 이 파일에 손으로 적지 않기 위한 장치다. 적으면 그 목록이
 * 「백엔드 enabled」·「렌더러 case」·「MSW 픽스처」에 이은 **네 번째 목록**이 되고,
 * 아무도 그것을 검사하지 않는다 — 이 저장소가 이름 붙인 지배 결함 양식 그대로다.
 * 화면이 실제로 그린 것을 읽으므로 카탈로그가 늘면 순회도 저절로 는다.
 */
async function enabledLabelsInCategory(
  page: import('@playwright/test').Page,
  category: string,
): Promise<string[]> {
  const section = page
    .locator('section')
    .filter({ has: page.getByRole('heading', { name: category, exact: true }) })
  const buttons = section.getByRole('button')
  await expect(buttons.first()).toBeVisible()

  const total = await buttons.count()
  const labels: string[] = []
  for (let i = 0; i < total; i += 1) {
    const button = buttons.nth(i)
    if (!(await button.isEnabled())) continue
    const label = await button.getAttribute('aria-label')
    if (label !== null) labels.push(label)
  }
  return labels
}

/**
 * 설정 폼의 **드롭다운을 앞에서부터 순서대로** 첫 유효 옵션으로 채운다.
 *
 * 스코프성 필드는 전부 `<select>` 다(A10). 순서대로 채우는 것이 중요하다 —
 * 보드 선택기는 프로젝트 드롭다운을 **앞에** 내장하고 있어서, 프로젝트를 먼저 고르지 않으면
 * 보드 목록이 비어 있다(E8). 그래서 옵션이 실릴 때까지 기다린 뒤 고른다.
 */
async function fillScopeSelects(page: import('@playwright/test').Page): Promise<void> {
  const dialog = page.getByRole('dialog')
  const selects = dialog.getByRole('combobox')
  const total = await selects.count()

  for (let i = 0; i < total; i += 1) {
    const select = selects.nth(i)
    // 플레이스홀더 하나뿐이면 아직 목록이 안 실린 것이다.
    await expect.poll(async () => select.locator('option').count()).toBeGreaterThan(1)
    const values = await select
      .locator('option')
      .evaluateAll((options) => options.map((o) => (o as HTMLOptionElement).value))
    const first = values.find((v) => v !== '')
    if (first !== undefined) await select.selectOption(first)
  }
}

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
    // 동선 변경 — 「가젯 추가」는 편집 모드 안에만 있다 (JD-1).
    await enterEditMode(page)
    const header = headerOf(page)
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
    // 동선 변경 — 「가젯 추가」는 편집 모드 안에만 있다 (JD-1).
    await enterEditMode(page)
    const header = headerOf(page)
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // When. 카탈로그 로드 대기 + "Recently Created" 선택 (ISSUE 카테고리)
    await expect(page.getByRole('button', { name: 'Recently Created', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Recently Created', exact: true }).click()

    // Then. Step 2 설정 폼 전환 확인
    await expect(page.getByText('가젯 설정 — Recently Created')).toBeVisible()

    // When. projectKey 는 이제 **드롭다운**이다 (Maxi 결정 M-1).
    //   ★이 한 줄이 이 PR 이 고친 결함의 증거다 — 종전에는 fill() 로 프로젝트 키를
    //   손으로 타이핑했고, 스프린트 번다운 가젯에서는 그것이 보드 UUID 타이핑이었다.
    await page.getByLabel('Project Key').selectOption('ATLAS')

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
    // 동선 변경 — 「가젯 추가」는 편집 모드 안에만 있다 (JD-1).
    await enterEditMode(page)
    const header = headerOf(page)
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
  // Then   ACTIVITY 카테고리 "Comments Recent" 버튼이 비활성(disabled)
  //        "준비 중" 텍스트가 그 버튼 내부에 표시
  //        비활성 버튼 클릭 시 Step 2로 전환되지 않음 (cursor-not-allowed)
  //
  // ★대상이 Pie Chart 에서 Comments Recent 로 바뀐 이유.
  //   이 PR 이 PIE_CHART 를 enabled=true 로 켰다. 그대로 두면 이 가드가 red 가 되는데,
  //   **삭제하면 게이팅 회귀 가드 자체를 잃는다** — 아직 꺼져 있는 타입으로 옮긴다.
  //   백엔드 DashboardGadgetIntegrationTest 의 400 단언도 같은 처방을 받았다.
  //   대상을 여기 손으로 적는 것은 의도적이다. 「어떤 타입이 꺼져 있나」는 A2 판별식이
  //   백엔드·렌더러·MSW 세 꼭짓점으로 이미 강제하므로, 이 줄이 네 번째 목록이 되지 않는다.
  //
  // 근거.
  //   GadgetCatalogModal.tsx: disabled={!entry.enabled}
  //   GadgetType.kt: COMMENTS_RECENT enabled=false (issue-tracking 엔드포인트 미신설)
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 enabled=false 카탈로그 게이팅 — Comments Recent 비활성·준비 중 표시', async ({
    page,
  }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()

    // When. "가젯 추가" → GadgetCatalogModal 열기
    // 동선 변경 — 「가젯 추가」는 편집 모드 안에만 있다 (JD-1).
    await enterEditMode(page)
    const header = headerOf(page)
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // Then. 카탈로그 로드 대기 (enabled=true 가젯 표시 확인으로 로드 완료 검증)
    await expect(page.getByRole('button', { name: 'Text Widget', exact: true })).toBeVisible()

    // Then. "Comments Recent" 버튼 비활성 확인 (ACTIVITY 카테고리, enabled=false)
    const disabledBtn = page.getByRole('button', { name: 'Comments Recent', exact: true })
    await expect(disabledBtn).toBeVisible()
    await expect(disabledBtn).toBeDisabled()

    // Then. "준비 중" 텍스트가 그 버튼 내부에 표시
    await expect(disabledBtn).toContainText('준비 중')

    // Then. 비활성 버튼 클릭 시 Step 2 전환 안 됨 (카탈로그 제목 유지)
    //   disabled 속성으로 onClick이 방화, selectedEntry=null 유지
    await disabledBtn.click({ force: true })
    await expect(page.getByRole('heading', { name: '가젯 추가' })).toBeVisible()

    // Then. ★이 PR 이 켠 Pie Chart 는 반대로 **활성**이다.
    //   게이팅 가드를 옮기기만 하고 켠 쪽을 안 재면, 전부 disabled 인 카탈로그도 통과한다.
    const enabledBtn = page.getByRole('button', { name: 'Pie Chart', exact: true })
    await expect(enabledBtn).toBeEnabled()
    await expect(enabledBtn).not.toContainText('준비 중')
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
  // S8. 신규 4종 가젯 렌더 (A8)
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입 + 편집 모드
  // When   카탈로그의 CHART·ACTIVITY 카테고리에서 **활성 항목을 읽어** 하나씩 추가
  // Then   어느 것도 "지원되지 않는 가젯입니다" 로 뜨지 않는다
  //
  // 이 시나리오가 재는 것.
  //   A2 판별식은 소스를 **정적으로** 대조한다. 이 테스트는 같은 계약을 **실행 시점에**
  //   다시 잰다 — 카탈로그가 고를 수 있게 내주는 가젯은 전부 실제로 그려져야 한다.
  //   판별식의 파서가 언젠가 조용히 틀려도 여기서 걸린다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S8 신규 4종 가젯 — 카탈로그 활성 항목 순회 렌더 (A8)', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입 + 편집 모드
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()
    await enterEditMode(page)

    const header = headerOf(page)

    // When. 카탈로그를 한 번 열어 대상 라벨을 읽는다 (손으로 적지 않는다)
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Text Widget', exact: true })).toBeVisible()

    const targets = [
      ...(await enabledLabelsInCategory(page, 'CHART')),
      ...(await enabledLabelsInCategory(page, 'ACTIVITY')),
    ]

    // 비-공허 짝. 0건이면 순회가 아무것도 안 하고 통과한다.
    expect(targets.length).toBeGreaterThan(0)

    // 모달을 닫고 하나씩 다시 연다 (Step 2 에서 뒤로 가는 동선은 이 테스트의 관심 밖)
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).not.toBeVisible()

    for (const label of targets) {
      await header.getByRole('button', { name: '가젯 추가', exact: true }).click()
      await expect(page.getByRole('button', { name: label, exact: true })).toBeVisible()
      await page.getByRole('button', { name: label, exact: true }).click()

      // Then. Step 2 설정 폼 — 스코프 드롭다운을 순서대로 채운다
      await expect(page.getByText(`가젯 설정 — ${label}`)).toBeVisible()
      await fillScopeSelects(page)

      await page.getByRole('button', { name: '추가', exact: true }).click()
      await expect(page.getByRole('dialog')).not.toBeVisible()
    }

    // Then. 어떤 타일도 "지원되지 않는 가젯입니다" 로 뜨지 않는다 (EC6 안전 표시)
    await expect(page.getByText('지원되지 않는 가젯입니다')).toHaveCount(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S9. 편집 모드 게이팅 (A7′)
  //
  // Given  alice 로그인 + 소유 대시보드 + text_widget 타일 1개
  // When   보기 모드 / 편집 모드를 오간다
  // Then   보기 모드 — "가젯 추가"·타일 ⋯ 메뉴 부재, 드래그 비활성
  //        편집 모드 — 둘 다 존재
  //
  // 근거. JD-1 — Jira 는 Edit 을 눌러야 가젯 추가·배치가 열린다.
  //       드래그 자체는 RGL headless 제약으로 못 재므로 **편집 UI 의 존재**로 잰다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S9 편집 모드에만 편집 UI 가 있다 (A7′)', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()

    // Then. 보기 모드 — 편집 전용 UI 부재. "편집" 버튼만 있다.
    const header = headerOf(page)
    await expect(
      header.getByRole('button', { name: dashboardModeLabels.enterEdit, exact: true }),
    ).toBeVisible()
    await expect(header.getByRole('button', { name: '가젯 추가', exact: true })).toHaveCount(0)

    // When. 편집 모드 진입 + 타일 하나 추가
    await enterEditMode(page)
    await expect(header.getByRole('button', { name: '가젯 추가', exact: true })).toBeVisible()

    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()
    await page.getByRole('button', { name: 'Text Widget', exact: true }).click()
    await page.getByLabel('Markdown').fill('## 모드 확인')
    await page.getByRole('button', { name: '추가', exact: true }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Then. 편집 모드 — 타일 ⋯ 메뉴가 있다
    await expect(page.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel })).toHaveCount(
      1,
    )

    // When. 보기 모드 복귀
    await header.getByRole('button', { name: dashboardModeLabels.exitEdit, exact: true }).click()

    // Then. 보기 모드 — ⋯ 메뉴도 "가젯 추가"도 사라진다
    await expect(page.getByRole('button', { name: dashboardModeLabels.tileMenuAriaLabel })).toHaveCount(
      0,
    )
    await expect(header.getByRole('button', { name: '가젯 추가', exact: true })).toHaveCount(0)

    // Then. 타일 자체는 남는다 — 편집 UI 만 감춘 것이지 내용을 감춘 게 아니다
    await expect(page.getByText('## 모드 확인')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S10. 선택기 계단식 (A6 · E8)
  //
  // Given  alice 로그인 + 편집 모드 + 카탈로그에서 Sprint Burndown 선택
  // When   프로젝트를 고르기 전 / 고른 뒤
  // Then   고르기 전 — 보드 드롭다운 비활성 + 「먼저 프로젝트를 선택하세요」 안내
  //        고른 뒤 — 그 프로젝트의 보드가 옵션으로 뜬다
  //
  // 근거. 이 PR 이 고친 결함이 「보드 UUID 를 손으로 타이핑해야 한다」였다.
  //       그래서 재는 것은 값이 아니라 **입력 위젯이 드롭다운인가**다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S10 보드 선택기가 프로젝트에 종속된다 (A6·E8)', async ({ page }) => {
    // Given. alice 로그인 + 편집 모드 + 카탈로그 열기
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()
    await enterEditMode(page)
    await headerOf(page).getByRole('button', { name: '가젯 추가', exact: true }).click()

    // When. Sprint Burndown 선택 → Step 2
    await expect(page.getByRole('button', { name: 'Sprint Burndown', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Sprint Burndown', exact: true }).click()
    await expect(page.getByText('가젯 설정 — Sprint Burndown')).toBeVisible()

    // Then. 두 드롭다운이 있다 — 내장 프로젝트 선택기 + 보드 선택기.
    //   자유 입력(textbox)이면 이 PR 이 고치려던 상태 그대로다.
    const dialog = page.getByRole('dialog')
    const selects = dialog.getByRole('combobox')
    await expect(selects).toHaveCount(2)
    await expect(dialog.getByRole('textbox')).toHaveCount(0)

    const projectSelect = selects.nth(0)
    const boardSelect = selects.nth(1)

    // Then. 프로젝트를 고르기 전 보드는 비활성이다
    await expect(boardSelect).toBeDisabled()
    // ★`getByText(...).toBeVisible()` 로 재지 않는다. 닫힌 `<select>` 안의 `<option>` 은
    //   Playwright 가 **hidden** 으로 판정해 영원히 통과하지 않는다(실측 — "14 × locator
    //   resolved to <option> ... unexpected value hidden"). `toHaveText` 는 가시성을
    //   요구하지 않으므로 플레이스홀더 문구를 이렇게 잰다.
    await expect(boardSelect.locator('option').first()).toHaveText(
      gadgetPickerLabels.selectProjectFirst,
    )

    // When. 보드가 있는 프로젝트를 고른다
    await expect.poll(async () => projectSelect.locator('option').count()).toBeGreaterThan(1)
    await projectSelect.selectOption('ATLAS')

    // Then. 보드 드롭다운이 열리고 그 프로젝트의 보드가 옵션으로 뜬다
    await expect(boardSelect).toBeEnabled()
    await expect.poll(async () => boardSelect.locator('option').count()).toBeGreaterThan(1)
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
