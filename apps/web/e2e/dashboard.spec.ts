// FR-DB-01 D7 E2E — 대시보드 목록·생성·상세·권한·OCC 409·접근성·회귀
//
// 시나리오 개요.
//   S1. 목록 조회       — /dashboards 진입 → alice 소유 대시보드 카드 확인
//   S2. 대시보드 생성   — "대시보드 만들기" → 폼 입력 → 생성 → 상세 이동 확인
//   S3. 가젯 추가+저장  — 상세에서 "가젯 추가" → 모달 → 타일 생성 → "저장" → 성공 토스트 (C4)
//   S3b. 드래그/리사이즈 — RGL drag-handle 마우스 시퀀스 (jsdom/headless containerWidth=0 제약으로 SKIP, onLayoutChange는 DashboardGrid.test.tsx 단위 커버)
//   S6. 대시보드 삭제   — 삭제 버튼 → 확인 → 목록 이동 → 삭제 항목 사라짐
//   S7. 비소유자 읽기전용 — bob 소유 ORG 대시보드 → 가젯 추가/저장 버튼 부재 (C4)
//   S8. OCC 409 충돌    — addInitScript 플래그 → 가젯 추가 후 저장 시 409 토스트 + 로컬 변경 보존 (C4)
//   접근성. 키보드로 가젯 추가 → 저장 (C4 가젯 일원화)
//   회귀.  /dashboard 환영 경로 정상 + 네비게이션 "대시보드" 링크 동작
//
// 설계 결정.
//   - dashboard-fixtures.ts는 import.meta.env.MODE 참조로 Node.js 런타임 오류 유발 가능.
//     board-fixtures.ts 동일 패턴으로 필요한 상수를 인라인 동기화.
//   - loginAsAlice (issue-fixtures.ts) 재사용.
//   - DEFAULT_DASHBOARD 자동 시드: MSW 모듈 로드 시 (MODE!=='test') seedDashboard 호출.
//     OTHER_DASHBOARD(bob, ORG) 도 자동 시드됨.
//   - reload 금지(store 리셋 = 가짜그린). SPA goto/click으로 페이지 전환.
//   - addInitScript 플래그는 goto 전에 등록해야 첫 PATCH 시점부터 적용됨.
//   - 텍스트 중복 시 컨테이너 한정 또는 exact:true (playwright-getbyrole-exact-strict-mode).
//   - serviceWorkers:'block' 금지 (e2e-msw-serviceworker-block).

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — dashboard-fixtures.ts에서 import하지 않고 인라인 정의
//
// dashboard-fixtures.ts는 모듈 레벨에서 import.meta.env.MODE를 참조하므로
// Playwright Node.js 런타임에서 직접 import하면 import.meta 접근 오류가 발생한다.
// 필요한 상수를 dashboard-fixtures.ts와 동기화해 인라인 정의한다.
// ─────────────────────────────────────────────────────────────────────────────

/** dashboard-fixtures.ts의 DEFAULT_DASHBOARD.id와 동기화 */
const DEFAULT_DASHBOARD_ID = 'a0000000-0000-4000-8000-000000000001'

/** dashboard-fixtures.ts의 OTHER_DASHBOARD.id(bob 소유 ORG)와 동기화 */
const OTHER_DASHBOARD_ID = 'b0000000-0000-4000-8000-000000000001'

/** dashboard-fixtures.ts의 LS_KEY_DASHBOARD_CONFLICT와 동기화 */
const LS_KEY_DASHBOARD_CONFLICT = '__bts_e2e_dashboard_conflict'

/** DEFAULT_DASHBOARD 상세 경로 */
const DEFAULT_DASHBOARD_URL = `/dashboards/${DEFAULT_DASHBOARD_ID}`

/** OTHER_DASHBOARD(bob 소유) 상세 경로 */
const OTHER_DASHBOARD_URL = `/dashboards/${OTHER_DASHBOARD_ID}`

// ─────────────────────────────────────────────────────────────────────────────
// Suite
// ─────────────────────────────────────────────────────────────────────────────

test.describe('FR-DB-01 대시보드 (목록/생성/상세/권한/OCC/접근성/회귀)', () => {
  // ───────────────────────────────────────────────────────────────────────────
  // S1. 목록 조회
  //
  // Given  alice 로그인 (ServiceWorker 활성)
  //        dashboard-fixtures 자동 시드 → DEFAULT_DASHBOARD(alice)·OTHER_DASHBOARD(bob, ORG)
  // When   /dashboards 진입
  // Then   alice 소유 대시보드 이름 "내 첫 대시보드" 표시
  //        "내 대시보드" 소유 배지 표시 (alice 소유 카드)
  //        bob 소유 ORG 대시보드 "Bob의 팀 대시보드"도 표시 (ORG = alice도 접근 가능)
  // ───────────────────────────────────────────────────────────────────────────
  test('S1 목록 조회 — alice 소유 대시보드 + ORG 대시보드 확인', async ({ page }) => {
    // Given. alice 로그인 → ServiceWorker 활성화
    await loginAsAlice(page)

    // When. /dashboards 진입 (SPA 내부 이동)
    await page.goto('/dashboards')

    // Then. alice 소유 대시보드 이름 표시
    await expect(page.getByText('내 첫 대시보드')).toBeVisible()

    // Then. "내 대시보드" 소유 배지 표시 (alice 소유 확인)
    // alice 소유 대시보드가 2개 이상일 수 있어(SHARE_DEMO_DASHBOARD 등) "내 대시보드" 텍스트가
    // 페이지 전역에 중복 노출된다 — "내 첫 대시보드" 카드 컨테이너로 한정한다
    // (S1b의 getByTestId('dashboard-card-link').filter 패턴 재사용, playwright-getbyrole-exact-strict-mode).
    const myFirstDashboardCard = page.getByTestId('dashboard-card-link').filter({ hasText: '내 첫 대시보드' })
    await expect(myFirstDashboardCard.getByText('내 대시보드')).toBeVisible()

    // Then. bob 소유 ORG 대시보드 표시 (ORG — alice도 목록에서 조회 가능)
    await expect(page.getByText('Bob의 팀 대시보드')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S1b. 카드 클릭 → 상세 이동 (BLOCKER 회귀 가드)
  //
  // Given  alice 로그인 + /dashboards 목록 진입
  //        DashboardCard가 <Link data-testid="dashboard-card-link"> 로 렌더됨
  // When   alice 소유 대시보드 카드 클릭
  // Then   /dashboards/$id 상세 페이지로 SPA 이동
  //        상세 헤더에 대시보드 이름 표시
  //
  // 회귀 근거: PR 코드리뷰 BLOCKER — 카드 클릭 네비게이션 없음.
  //   수정 후 카드 전체가 <Link to="/dashboards/$dashboardId">로 래핑됨.
  //   생성 직후(S2)뿐 아니라 기존 대시보드도 목록 → 상세로 열 수 있어야 한다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S1b 카드 클릭 → 상세 이동 — 목록에서 기존 대시보드 진입 (BLOCKER 회귀 가드)', async ({ page }) => {
    // Given. alice 로그인 + 목록 진입
    await loginAsAlice(page)
    await page.goto('/dashboards')

    // Given. alice 소유 카드 확인
    await expect(page.getByText('내 첫 대시보드')).toBeVisible()

    // When. alice 소유 대시보드 카드 클릭 (data-testid="dashboard-card-link" Link)
    //       목록에 카드가 여러 개 있을 수 있으므로 "내 첫 대시보드" 텍스트를 포함하는 카드 한정
    await page
      .getByTestId('dashboard-card-link')
      .filter({ hasText: '내 첫 대시보드' })
      .click()

    // Then. /dashboards/$id 상세 페이지로 SPA 이동
    await expect(page).toHaveURL(/\/dashboards\/[0-9a-f-]+$/)

    // Then. 상세 헤더에 대시보드 이름 표시
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S2. 대시보드 생성
  //
  // Given  alice 로그인 + /dashboards 진입 (DEFAULT_DASHBOARD 이미 시드됨 — 목록 있음)
  // When   "대시보드 만들기" 버튼 클릭 → 폼 표시
  //        이름 "E2E 테스트 대시보드" 입력
  //        공개 범위 "전체 공유(ORG)" 선택
  //        저장 버튼 클릭
  // Then   /dashboards/$newId URL로 이동 (상세 페이지)
  //        생성한 대시보드 이름 "E2E 테스트 대시보드" 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S2 대시보드 생성 — 폼 입력 → 생성 → 상세 이동', async ({ page }) => {
    // Given. alice 로그인 + 목록 진입
    await loginAsAlice(page)
    await page.goto('/dashboards')

    // Given. 목록 있는 상태 — "대시보드 만들기" 버튼이 헤더에 있음
    await expect(page.getByText('내 첫 대시보드')).toBeVisible()

    // When. "대시보드 만들기" 버튼 클릭 → 인라인 폼 표시
    await page.getByRole('button', { name: '대시보드 만들기', exact: true }).first().click()

    // When. 이름 입력
    await page.getByLabel('이름').fill('E2E 테스트 대시보드')

    // When. 공개 범위 "전체 공유" 선택
    await page.getByLabel('공개 범위').selectOption('ORG')

    // When. 저장 버튼 클릭 (DashboardForm 제출)
    await page.getByRole('button', { name: '저장', exact: true }).click()

    // Then. /dashboards/$newId 상세 페이지로 이동
    await expect(page).toHaveURL(/\/dashboards\/[0-9a-f-]+$/)

    // Then. 생성한 대시보드 이름이 상세 헤더에 표시
    await expect(page.getByRole('heading', { name: 'E2E 테스트 대시보드' })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3. 가젯 추가 + 저장 (C4: "위젯 추가" 버튼 제거 후 가젯 기반으로 갱신)
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입
  //        타일 0개 (DEFAULT_DASHBOARD.layout='[]')
  // When   헤더 "가젯 추가" 버튼 클릭 → GadgetCatalogModal 열림
  //        "Text Widget" 선택 → "## S3 테스트" 입력 → "추가"
  //        "저장" 버튼 클릭 → PATCH 성공 (version+1)
  // Then   타일 헤더에 "텍스트" 표시 (C6 한국어 라벨)
  //        성공 토스트 "대시보드가 저장되었습니다." 표시
  //
  // 주의: dashboard-gadgets.spec.ts S1이 golden path를 커버한다.
  //       S3는 FR-DB-01 저장 흐름만 검증한다 (E2E 중복 최소화).
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 가젯 추가 + 저장 — text_widget → PATCH 성공 토스트 (C4 가젯 일원화)', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 빈 그리드 상태 확인 (C4: emptyGrid 문구 업데이트 반영)
    await expect(page.getByText('가젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()

    // When. 헤더 "가젯 추가" 버튼 클릭
    const header = page.locator('.flex.items.justify-between.px-6.py-4.border-b, .flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()

    // When. GadgetCatalogModal Step 1 — "Text Widget" 선택
    await expect(page.getByRole('button', { name: 'Text Widget', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Text Widget', exact: true }).click()

    // When. Step 2 설정 폼 — markdown 입력
    await page.getByLabel('Markdown').fill('## S3 테스트')

    // When. "추가" → 모달 닫힘
    await page.getByRole('button', { name: '추가', exact: true }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible()

    // Then. 타일 헤더에 한국어 라벨 "텍스트" 표시 (C6)
    await expect(page.getByText('텍스트')).toBeVisible()

    // Then. 미저장 변경 사항 인디케이터 표시
    await expect(page.getByText('저장되지 않은 변경 사항이 있습니다')).toBeVisible()

    // When. 저장 버튼 클릭
    await page.getByRole('button', { name: '저장', exact: true }).click()

    // Then. 성공 토스트 표시
    await expect(page.getByText('대시보드가 저장되었습니다.')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S3b. 드래그/리사이즈 (RGL 특성상 SKIP)
  //
  // react-grid-layout은 자체 마우스 이벤트 핸들러를 사용하므로
  // Playwright mouse.move/down/up 시퀀스가 headless Chromium에서 일관되게 동작하지 않는다.
  //   - RGL WidthProvider는 jsdom/headless에서 containerWidth=0으로 초기화돼 레이아웃이 비정상
  //   - draggableHandle=".drag-handle" 경계 박스가 가용 공간 밖으로 잡힐 수 있음
  //   - board-kanban.spec.ts(PointerSensor)와 달리 RGL은 HTML5 DnD 이벤트 아님
  //
  // 위 이유로 드래그/리사이즈는 실렌더 E2E 범위에서 제외하고
  // react-grid-layout onLayoutChange 콜백 단위 테스트(DashboardGrid.test.tsx)로 커버한다.
  // ───────────────────────────────────────────────────────────────────────────
  test.skip('S3b 드래그/리사이즈 — RGL headless 제약으로 SKIP (onLayoutChange 단위 테스트 커버)', () => {
    // SKIP: RGL은 headless Chromium에서 containerWidth=0 초기화로 드래그 좌표가 비정상.
    // DashboardGrid.test.tsx에서 onLayoutChange 콜백 직접 호출로 검증됨.
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S6. 대시보드 삭제
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입 (alice 소유 → editable=true)
  // When   헤더 "삭제" 버튼 클릭 → 인라인 확인 UI 표시
  //        "확인" 버튼 클릭 → DELETE /api/v1/dashboards/:id → 204
  //        → navigate(/dashboards) 목록 이동
  // Then   /dashboards 목록 페이지로 이동
  //        삭제된 "내 첫 대시보드" 항목이 목록에서 사라짐
  //        (MSW stateful store: deletedAt 설정 → GET /api/v1/dashboards 에서 제외)
  //
  // 주의: 이 테스트는 독립 Playwright context(새 ServiceWorker)에서 실행되므로
  //       다른 시나리오 store 상태와 격리된다.
  // ───────────────────────────────────────────────────────────────────────────
  test('S6 대시보드 삭제 — 확인 → 목록 이동 → 삭제 항목 사라짐', async ({ page }) => {
    // Given. alice 로그인 + DEFAULT_DASHBOARD 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 상세 페이지 로드 확인 — 대시보드 이름 표시
    await expect(page.getByRole('heading', { name: '내 첫 대시보드' })).toBeVisible()

    // Given. 헤더 영역에 "삭제" 버튼 있음 (editable=true — alice 소유)
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    const deleteBtn = header.getByRole('button', { name: '삭제', exact: true })
    await expect(deleteBtn).toBeVisible()

    // When. "삭제" 버튼 클릭 → 인라인 확인 UI 표시
    await deleteBtn.click()

    // Then. 확인 문구 표시
    await expect(page.getByText('정말 삭제하시겠습니까?')).toBeVisible()

    // Then. 확인/취소 버튼 표시
    // aria-label="삭제 확인" / aria-label="삭제 취소" — accessible name 기준 탐색
    const confirmBtn = page.getByRole('button', { name: '삭제 확인', exact: true })
    const cancelBtn = page.getByRole('button', { name: '삭제 취소', exact: true })
    await expect(confirmBtn).toBeVisible()
    await expect(cancelBtn).toBeVisible()

    // When. "삭제 확인" 버튼 클릭 → DELETE API → /dashboards 이동
    await confirmBtn.click()

    // Then. /dashboards 목록으로 SPA 이동
    await expect(page).toHaveURL(/\/dashboards$/)

    // Then. 삭제된 대시보드 "내 첫 대시보드"가 목록에서 사라짐
    //       MSW store: deletedAt !== null → GET /api/v1/dashboards 필터링
    await expect(page.getByText('내 첫 대시보드')).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S7. 비소유자 읽기 전용
  //
  // Given  alice 로그인 + OTHER_DASHBOARD(bob 소유, ORG) 상세 진입
  //        canEditDashboard(dashboard, aliceId) = false (ownerId ≠ aliceId)
  // When   상세 페이지 렌더
  // Then   "가젯 추가" 버튼 부재 (C4: 가젯으로 일원화 후도 비소유자에겐 미노출)
  //        "저장" 버튼 부재
  //        "설정" 버튼 부재
  //        대시보드 이름 "Bob의 팀 대시보드" 표시 (읽기 가능)
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 비소유자 읽기 전용 — 가젯 추가/저장/설정 버튼 부재 (C4)', async ({ page }) => {
    // Given. alice 로그인 + bob 소유 ORG 대시보드 상세 진입
    await loginAsAlice(page)
    await page.goto(OTHER_DASHBOARD_URL)

    // Given. 페이지 완전 로드 확인 (대시보드 이름 표시)
    await expect(page.getByRole('heading', { name: 'Bob의 팀 대시보드' })).toBeVisible()

    // Then. 편집 전용 버튼 없음 — "가젯 추가" (C4: 가젯으로 일원화)
    await expect(page.getByRole('button', { name: '가젯 추가', exact: true })).not.toBeVisible()

    // Then. 편집 전용 버튼 없음 — "저장"
    await expect(page.getByRole('button', { name: '저장', exact: true })).not.toBeVisible()

    // Then. 편집 전용 버튼 없음 — "설정"
    await expect(page.getByRole('button', { name: '설정', exact: true })).not.toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // S8. OCC 409 충돌 — addInitScript로 localStorage 플래그 시드 → 저장 시 충돌 토스트
  //
  // Given  addInitScript로 LS_KEY_DASHBOARD_CONFLICT='true' 심기 (goto 전 등록)
  //        alice 로그인 + DEFAULT_DASHBOARD 상세 진입
  //        "가젯 추가" → Text Widget → 추가 (로컬 tiles dirty, C4 가젯 일원화)
  // When   "저장" 버튼 클릭 → PATCH → MSW가 409 반환
  // Then   충돌 토스트 표시 (OCC 메시지)
  //        로컬 타일("텍스트")이 여전히 그리드에 표시됨 (tiles 보존)
  // ───────────────────────────────────────────────────────────────────────────
  test('S8 OCC 409 충돌 — 저장 시 충돌 토스트 + 로컬 변경 보존 (C4)', async ({ page }) => {
    // Given. addInitScript로 충돌 플래그 심기 (goto 이전 등록 — 첫 PATCH 시점부터 적용)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_DASHBOARD_CONFLICT)

    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 빈 그리드 확인 (C4: emptyGrid 문구 업데이트)
    await expect(page.getByText('가젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()

    // Given. 가젯 추가 → Text Widget 선택 → 추가 (로컬 tiles dirty, C4 가젯 일원화)
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '가젯 추가', exact: true }).click()
    await page.getByRole('button', { name: 'Text Widget', exact: true }).click()
    await page.getByLabel('Markdown').fill('S8 테스트 콘텐츠')
    await page.getByRole('button', { name: '추가', exact: true }).click()

    // Then. 가젯 타일 한국어 라벨 "텍스트" 표시 (C6)
    await expect(page.getByText('텍스트')).toBeVisible()

    // When. 저장 버튼 클릭 → PATCH 409 예상
    await page.getByRole('button', { name: '저장', exact: true }).click()

    // Then. 충돌 토스트 표시
    await expect(
      page.getByText('다른 사용자가 대시보드를 변경했습니다. 충돌을 해결한 후 다시 시도하세요.'),
    ).toBeVisible()

    // Then. 로컬 타일 보존 — "텍스트" 가젯 여전히 그리드에 있음 (invalidate 금지)
    await expect(page.getByText('텍스트')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 접근성: 키보드만으로 가젯 추가 → 저장 (C4: 가젯 일원화 후 갱신)
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입 (빈 그리드)
  // When   "가젯 추가" 버튼 포커스 → Enter (키보드 클릭) → 모달 열림
  //        "Text Widget" 버튼 클릭 → 설정 폼
  //        Markdown 입력 → Enter·Tab으로 "추가" 포커스 → Enter (추가)
  //        "저장" 버튼 포커스 → Enter (키보드 클릭)
  // Then   저장 성공 토스트 "대시보드가 저장되었습니다." 표시
  //
  // 주의: 가젯 타일은 inline 제목 편집 불가 (C4 설계 결정 — 가젯 헤더는 고정 한국어 라벨).
  // ───────────────────────────────────────────────────────────────────────────
  test('접근성 — 키보드로 가젯 추가 → 저장 (C4 가젯 일원화)', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 빈 그리드 확인 (C4: emptyGrid 문구 업데이트)
    await expect(page.getByText('가젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()

    // When. "가젯 추가" 버튼 포커스 → Enter (키보드로 모달 열기)
    const addGadgetBtn = page
      .locator('.flex.items-center.justify-between.px-6.py-4.border-b')
      .first()
      .getByRole('button', { name: '가젯 추가', exact: true })
    await addGadgetBtn.focus()
    await page.keyboard.press('Enter')

    // Then. GadgetCatalogModal 열림
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Text Widget', exact: true })).toBeVisible()

    // When. Text Widget 선택 → Step 2 설정 폼
    await page.getByRole('button', { name: 'Text Widget', exact: true }).click()

    // When. Markdown 입력 → "추가" 버튼 클릭
    await page.getByLabel('Markdown').fill('접근성 테스트 콘텐츠')
    await page.getByRole('button', { name: '추가', exact: true }).focus()
    await page.keyboard.press('Enter')

    // Then. 모달 닫힘 + 타일 한국어 라벨 "텍스트" 표시 (C6)
    await expect(page.getByRole('dialog')).not.toBeVisible()
    await expect(page.getByText('텍스트')).toBeVisible()

    // When. "저장" 버튼 포커스 → Enter (키보드 저장)
    const saveBtn = page.getByRole('button', { name: '저장', exact: true })
    await saveBtn.focus()
    await page.keyboard.press('Enter')

    // Then. 저장 성공 토스트
    await expect(page.getByText('대시보드가 저장되었습니다.')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 회귀 1: /dashboard (환영, 단수) 경로 정상 동작
  //
  // FR-PF-02로 로그인 직후 목적지는 /dashboards(복수, 목록)로 바뀌었지만, /dashboard(단수,
  // DashboardPage 환영 화면)는 폐기되지 않은 별개 라우트로 여전히 직접 방문 가능해야 한다.
  //
  // Given  alice 로그인(기본 startPage='dashboards'라 /dashboards 도착) → /dashboard 직접 방문
  // Then   "환영합니다, alice" 텍스트 표시 (dashboard.tsx DashboardPage)
  // ───────────────────────────────────────────────────────────────────────────
  test('회귀 — /dashboard 환영 경로 정상 렌더', async ({ page }) => {
    // Given. alice 로그인 (로그인 직후 목적지는 /dashboards — 이 테스트의 관심사 아님)
    await loginAsAlice(page)

    // When. /dashboard(단수) 경로를 명시적으로 방문 — 여전히 유효한 라우트인지 검증
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/dashboard$/)

    // Then. 환영 메시지 표시
    await expect(page.getByRole('heading', { name: /환영합니다/ })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 회귀 2: 네비게이션 "대시보드" 링크 동작
  //
  // Given  alice 로그인 (기본 startPage='dashboards'라 이미 /dashboards에 도착)
  // When   Header nav의 "대시보드" 링크 클릭
  // Then   /dashboards 로 SPA 이동 유지
  //        alice 소유 대시보드 목록 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('회귀 — 네비게이션 "대시보드" 링크 → /dashboards 이동', async ({ page }) => {
    // Given. alice 로그인 → 로그인 직후 목적지(기본값)가 이미 /dashboards
    await loginAsAlice(page)
    await expect(page).toHaveURL(/\/dashboards$/)

    // When. 메인 메뉴 "대시보드" 링크 클릭 (이미 /dashboards에 있어도 링크 자체의 동작 검증)
    await page.getByRole('navigation', { name: '메인 메뉴' }).getByRole('link', { name: '대시보드', exact: true }).click()

    // Then. /dashboards 로 이동(유지)
    await expect(page).toHaveURL(/\/dashboards$/)

    // Then. alice 소유 대시보드 목록 표시
    await expect(page.getByText('내 첫 대시보드')).toBeVisible()
  })
})
