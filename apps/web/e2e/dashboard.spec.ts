// FR-DB-01 D7 E2E — 대시보드 목록·생성·상세·권한·OCC 409·접근성·회귀
//
// 시나리오 개요.
//   S1. 목록 조회       — /dashboards 진입 → alice 소유 대시보드 카드 확인
//   S2. 대시보드 생성   — "대시보드 만들기" → 폼 입력 → 생성 → 상세 이동 확인
//   S3. 위젯 추가+저장  — 상세에서 "위젯 추가" → 타일 생성 → "저장" → 성공 토스트
//   S3b. 드래그/리사이즈 — RGL drag-handle 마우스 시퀀스 시도 (RGL 특성상 SKIP 가능)
//   S6. 대시보드 삭제   — SKIP: 구현 코드에 삭제 버튼 없음 (controller가 처리)
//   S7. 비소유자 읽기전용 — bob 소유 ORG 대시보드 → 위젯 추가/저장 버튼 부재
//   S8. OCC 409 충돌    — addInitScript 플래그 → 저장 시 409 토스트 + 로컬 변경 보존
//   접근성. 키보드로 위젯 추가 → 제목 편집(Enter) → 저장
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
    await expect(page.getByText('내 대시보드')).toBeVisible()

    // Then. bob 소유 ORG 대시보드 표시 (ORG — alice도 목록에서 조회 가능)
    await expect(page.getByText('Bob의 팀 대시보드')).toBeVisible()
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
  // S3. 위젯 추가 + 저장
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입
  //        타일 0개 (DEFAULT_DASHBOARD.layout='[]')
  // When   헤더 "위젯 추가" 버튼 클릭 → 타일 생성 (로컬 state 반영)
  //        "저장" 버튼 클릭 → PATCH 성공 (version+1)
  // Then   타일 "새 위젯" 표시
  //        성공 토스트 "대시보드가 저장되었습니다." 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('S3 위젯 추가 + 저장 — 타일 생성 → PATCH 성공 토스트', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 빈 그리드 상태 확인
    await expect(page.getByText('위젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()

    // When. 헤더 "위젯 추가" 버튼 클릭 (상단 헤더 버튼 — 컨테이너 한정으로 strict mode 방지)
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '위젯 추가', exact: true }).click()

    // Then. 타일 "새 위젯" 표시 (로컬 state 낙관적 추가)
    await expect(page.getByText('새 위젯')).toBeVisible()

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
  // Then   "위젯 추가" 버튼 부재
  //        "저장" 버튼 부재
  //        "설정" 버튼 부재
  //        대시보드 이름 "Bob의 팀 대시보드" 표시 (읽기 가능)
  // ───────────────────────────────────────────────────────────────────────────
  test('S7 비소유자 읽기 전용 — 위젯 추가/저장/설정 버튼 부재', async ({ page }) => {
    // Given. alice 로그인 + bob 소유 ORG 대시보드 상세 진입
    await loginAsAlice(page)
    await page.goto(OTHER_DASHBOARD_URL)

    // Given. 페이지 완전 로드 확인 (대시보드 이름 표시)
    await expect(page.getByRole('heading', { name: 'Bob의 팀 대시보드' })).toBeVisible()

    // Then. 편집 전용 버튼 없음 — "위젯 추가"
    await expect(page.getByRole('button', { name: '위젯 추가', exact: true })).not.toBeVisible()

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
  //        "위젯 추가" → 타일 생성 (로컬 tiles dirty)
  // When   "저장" 버튼 클릭 → PATCH → MSW가 409 반환
  // Then   충돌 토스트 표시 (OCC 메시지)
  //        로컬 타일("새 위젯")이 여전히 그리드에 표시됨 (tiles 보존)
  // ───────────────────────────────────────────────────────────────────────────
  test('S8 OCC 409 충돌 — 저장 시 충돌 토스트 + 로컬 변경 보존', async ({ page }) => {
    // Given. addInitScript로 충돌 플래그 심기 (goto 이전 등록 — 첫 PATCH 시점부터 적용)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_DASHBOARD_CONFLICT)

    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 빈 그리드 → "위젯 추가" 클릭 → 타일 생성
    await expect(page.getByText('위젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()
    const header = page.locator('.flex.items-center.justify-between.px-6.py-4.border-b').first()
    await header.getByRole('button', { name: '위젯 추가', exact: true }).click()
    await expect(page.getByText('새 위젯')).toBeVisible()

    // When. 저장 버튼 클릭 → PATCH 409 예상
    await page.getByRole('button', { name: '저장', exact: true }).click()

    // Then. 충돌 토스트 표시
    await expect(
      page.getByText('다른 사용자가 대시보드를 변경했습니다. 충돌을 해결한 후 다시 시도하세요.'),
    ).toBeVisible()

    // Then. 로컬 타일 보존 — "새 위젯" 여전히 그리드에 있음 (invalidate 금지)
    await expect(page.getByText('새 위젯')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 접근성: 키보드만으로 위젯 추가 → 제목 편집 → 저장
  //
  // Given  alice 로그인 + DEFAULT_DASHBOARD 상세 진입 (빈 그리드)
  // When   Tab으로 "위젯 추가" 버튼 포커스 → Enter (키보드 클릭)
  //        추가된 타일 제목 버튼 클릭 → 편집 input 활성
  //        새 제목 입력 → Enter 커밋
  //        Tab으로 "저장" 버튼 포커스 → Enter (키보드 클릭)
  // Then   저장 성공 토스트 "대시보드가 저장되었습니다." 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('접근성 — 키보드로 위젯 추가 → 제목 편집 → 저장', async ({ page }) => {
    // Given. alice 로그인 + 상세 진입
    await loginAsAlice(page)
    await page.goto(DEFAULT_DASHBOARD_URL)

    // Given. 빈 그리드 확인
    await expect(page.getByText('위젯 추가 버튼을 눌러 대시보드를 채워보세요')).toBeVisible()

    // When. "위젯 추가" 버튼 클릭(마우스로 포커스 후 키보드 Enter)
    //   aria-label="위젯 추가"가 헤더와 빈 그리드 안에 각 1개씩 있을 수 있으므로 헤더 한정
    const addWidgetBtn = page
      .locator('.flex.items-center.justify-between.px-6.py-4.border-b')
      .first()
      .getByRole('button', { name: '위젯 추가', exact: true })
    await addWidgetBtn.focus()
    await page.keyboard.press('Enter')

    // Then. 타일 "새 위젯" 표시
    await expect(page.getByText('새 위젯')).toBeVisible()

    // When. 제목 버튼 클릭 → 편집 input 활성
    await page.getByRole('button', { name: /새 위젯 — 클릭하여 제목 편집/ }).click()

    // When. 편집 input에 새 제목 입력 + Enter 커밋
    const titleInput = page.getByLabel('위젯 제목 편집')
    await expect(titleInput).toBeVisible()
    await titleInput.fill('접근성 위젯')
    await page.keyboard.press('Enter')

    // Then. 편집된 제목이 타일에 반영됨
    await expect(page.getByText('접근성 위젯')).toBeVisible()

    // When. "저장" 버튼 Enter
    const saveBtn = page.getByRole('button', { name: '저장', exact: true })
    await saveBtn.focus()
    await page.keyboard.press('Enter')

    // Then. 저장 성공 토스트
    await expect(page.getByText('대시보드가 저장되었습니다.')).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 회귀 1: /dashboard (환영) 경로 정상 동작
  //
  // Given  alice 로그인 → waitForURL('/dashboard')
  // Then   "환영합니다, alice" 텍스트 표시 (dashboard.tsx DashboardPage)
  // ───────────────────────────────────────────────────────────────────────────
  test('회귀 — /dashboard 환영 경로 정상 렌더', async ({ page }) => {
    // Given. alice 로그인 → 로그인 성공 후 /dashboard 이동
    await loginAsAlice(page)

    // loginAsAlice가 /dashboard로 이동하므로 이미 해당 경로에 있음
    await expect(page).toHaveURL(/\/dashboard$/)

    // Then. 환영 메시지 표시
    await expect(page.getByRole('heading', { name: /환영합니다/ })).toBeVisible()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 회귀 2: 네비게이션 "대시보드" 링크 동작
  //
  // Given  alice 로그인 + /dashboard 진입
  // When   Header nav의 "대시보드" 링크 클릭
  // Then   /dashboards 로 SPA 이동
  //        alice 소유 대시보드 목록 표시
  // ───────────────────────────────────────────────────────────────────────────
  test('회귀 — 네비게이션 "대시보드" 링크 → /dashboards 이동', async ({ page }) => {
    // Given. alice 로그인 → /dashboard 환영 페이지
    await loginAsAlice(page)
    await expect(page).toHaveURL(/\/dashboard$/)

    // When. 메인 메뉴 "대시보드" 링크 클릭
    await page.getByRole('navigation', { name: '메인 메뉴' }).getByRole('link', { name: '대시보드', exact: true }).click()

    // Then. /dashboards 로 이동
    await expect(page).toHaveURL(/\/dashboards$/)

    // Then. alice 소유 대시보드 목록 표시
    await expect(page.getByText('내 첫 대시보드')).toBeVisible()
  })
})
