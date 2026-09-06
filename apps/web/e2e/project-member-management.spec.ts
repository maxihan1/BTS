// FR-PM-01 E2E — 프로젝트 멤버 관리 시나리오 S1~S6
//
// 앱은 MSW(project-member-handlers.ts + users-handlers.ts) 위에서 동작한다.
// 각 테스트 시작 전 X-MSW-Reset-Members 헤더로 stateful 저장소를 초기화한다.
//
// 교훈 반영.
//   - msw-mutation-stateful-refetch: mutation 후 refetch 반영 검증 (setQueryData 위 가짜 그린 금지)
//   - playwright strict mode: 반복 버튼은 row 컨테이너 한정 + aria-label exact 매칭
//   - ui-pr-defer-e2e-regression-latent: 기존 E2E 함께 실행으로 회귀 확인
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW project-member-handlers 의 stateful memberStore 를 초기화한다.
 *
 * GET /api/v1/projects/ATLAS/members 에 X-MSW-Reset-Members: true 헤더를 포함해 호출하면
 * 핸들러가 내부 Map 을 buildInitialState() 로 재구성한다.
 * 각 테스트의 beforeEach 에서 호출해 테스트 격리를 보장한다.
 */
async function resetMemberHandlerState(page: import('@playwright/test').Page): Promise<void> {
  await page.evaluate(async () => {
    await fetch('/api/v1/projects/ATLAS/members', {
      headers: { 'X-MSW-Reset-Members': 'true' },
    })
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 beforeEach — 로그인 + MSW 초기화 + 페이지 진입
// ─────────────────────────────────────────────────────────────────────────────

test.describe('프로젝트 멤버 관리 (FR-PM-01)', () => {
  test.beforeEach(async ({ page }) => {
    // 1. 로그인 (loginAsAlice → /dashboard)
    await loginAsAlice(page)

    // 2. MSW stateful 저장소 초기화 — 로그인 후 페이지가 로드된 상태에서 실행해야
    //    MSW service worker 가 활성화되어 fetch 를 인터셉트한다.
    await resetMemberHandlerState(page)

    // 3. 멤버 설정 페이지 직접 진입 (workflow-scheme E2E 와 동일 패턴 — 네비 링크 없음)
    await page.goto('/projects/ATLAS/settings/members')
    await expect(page.getByRole('heading', { name: '멤버 설정', level: 2 })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S1 — 목록 조회
  //
  // Given  alice(앨리스, PROJECT_ADMIN)로 로그인 후 ATLAS 멤버 설정 페이지 진입
  // When   페이지 렌더 완료
  // Then   앨리스(관리자 배지)와 밥(멤버 배지) 표시, UUID 비노출
  // ─────────────────────────────────────────────────────────────────────────────
  test('S1 목록 조회 — 앨리스(관리자)/밥(멤버) 표시, UUID 비노출', async ({ page }) => {
    // "프로젝트 멤버" 섹션 헤딩 확인
    // CardTitle 은 div 로 렌더되므로 getByRole('heading') 대신 getByText 사용
    await expect(page.getByText('프로젝트 멤버', { exact: true })).toBeVisible()

    // 앨리스 행 — 이름 + 관리자 배지
    await expect(page.getByText('앨리스', { exact: true })).toBeVisible()
    await expect(page.getByText('관리자').first()).toBeVisible()

    // 밥 행 — 이름 + 멤버 배지
    await expect(page.getByText('밥')).toBeVisible()
    await expect(page.getByText('멤버').first()).toBeVisible()

    // UUID 가 화면에 노출되지 않음 (auth-fixtures userId v4 형식과 일치 — 옛 all-zeros는 vacuous)
    await expect(page.getByText('00000000-0000-4000-8000-000000000001')).toHaveCount(0)
    await expect(page.getByText('00000000-0000-4000-8000-000000000002')).toHaveCount(0)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S2 — 멤버 추가
  //
  // Given  ATLAS 멤버 설정 페이지 (alice=관리자, bob=멤버)
  // When   "멤버 추가" → 다이얼로그에서 "캐럴" 검색 → 선택 → 역할 MEMBER → 추가
  // Then   목록에 캐럴이 추가됨 (refetch 후 실제 목록 반영 검증)
  // ─────────────────────────────────────────────────────────────────────────────
  test('S2 멤버 추가 — 캐럴 검색→선택→추가 → 목록 반영', async ({ page }) => {
    // 추가 전 멤버 행 수 확인 (alice + bob = 2행)
    const memberRows = page.locator('ul.space-y-2 > li')
    await expect(memberRows).toHaveCount(2)

    // 멤버 추가 버튼 클릭
    await page.getByRole('button', { name: '멤버 추가' }).click()

    // 다이얼로그 제목 표시
    // radix Dialog.Title 은 div[role="dialog"] 안에 있으므로 getByRole('dialog') 한정 안에서 찾음
    await expect(page.getByRole('dialog').getByText('새 멤버 추가')).toBeVisible()

    // 사용자 검색 — "캐럴" 입력 (2자 미만 시 결과 안 나오므로 최소 2자 입력)
    await page.getByLabel('사용자 검색').fill('캐럴')

    // 검색 결과 목록에서 캐럴 클릭
    await expect(page.getByRole('button', { name: /캐럴/ })).toBeVisible()
    await page.getByRole('button', { name: /캐럴/ }).click()

    // 선택됨 안내 텍스트 확인
    await expect(page.getByText(/선택됨/)).toBeVisible()

    // 역할은 기본값 MEMBER 유지 (별도 변경 없음)

    // 추가 버튼 클릭
    await page.getByRole('button', { name: '추가' }).click()

    // 다이얼로그 닫힘 확인
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch 로 목록 반영 검증
    // (setQueryData 위에서만 통과하는 가짜 그린 방지)
    // MSW POST 핸들러는 displayName: null 로 새 멤버를 생성하므로
    // 멤버 행 수 증가(3행)로 실제 목록 반영을 검증한다.
    await expect(memberRows).toHaveCount(3)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S3 — 역할 변경
  //
  // Given  ATLAS 멤버 설정 페이지 (alice=관리자, bob=멤버)
  // When   밥의 역할 Select를 "관리자"로 변경
  // Then   밥 행에 관리자 배지가 갱신됨 (refetch 후 실제 목록 반영 검증)
  // ─────────────────────────────────────────────────────────────────────────────
  test('S3 역할 변경 — 밥을 관리자로 변경 → 배지 갱신', async ({ page }) => {
    // 밥 행 컨테이너 한정 — strict mode violation 방지 (동일 aria-label 중복 방지)
    const bobRow = page.getByText('밥').locator('xpath=ancestor::li[1]')

    // 밥 행의 역할 Select 트리거 (aria-label="역할 변경")
    const roleSelectTrigger = bobRow.getByRole('combobox', { name: '역할 변경' })
    await roleSelectTrigger.click()

    // "관리자" 옵션 선택
    await page.getByRole('option', { name: '관리자' }).click()

    // msw-mutation-stateful-refetch 교훈 — refetch 후 실제 배지 갱신 검증
    // 밥 행의 역할 배지 span(rounded-full) 이 "관리자"로 갱신돼야 함
    // Select 값 span 과 배지 span 두 곳이 동시에 "관리자"를 포함하므로 배지 클래스로 한정
    await expect(bobRow.locator('span.rounded-full')).toHaveText('관리자')
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S4 — 멤버 제거
  //
  // Given  ATLAS 멤버 설정 페이지 (alice=관리자, bob=멤버)
  // When   밥의 "밥 멤버 제거" 버튼 클릭
  // Then   목록에서 밥이 사라짐 (refetch 후 실제 목록 반영 검증)
  // ─────────────────────────────────────────────────────────────────────────────
  test('S4 멤버 제거 — 밥 제거 → 목록에서 사라짐', async ({ page }) => {
    // 밥 행 컨테이너 한정 — 제거 버튼은 aria-label="밥 멤버 제거" (exact)
    const bobRow = page.getByText('밥').locator('xpath=ancestor::li[1]')
    const removeButton = bobRow.getByRole('button', { name: '밥 멤버 제거' })
    await expect(removeButton).toBeVisible()
    await removeButton.click()

    // msw-mutation-stateful-refetch 교훈 — refetch 후 실제 제거 반영 검증
    await expect(page.getByText('밥')).toHaveCount(0)

    // 앨리스는 여전히 표시
    await expect(page.getByText('앨리스', { exact: true })).toBeVisible()
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S5 — 마지막 관리자 보호
  //
  // Given  ATLAS 멤버 설정 페이지 (alice=유일한 관리자)
  // When   앨리스의 역할 Select를 "멤버"로 강등 시도
  // Then   에러 토스트("마지막 관리자는 제거하거나 강등할 수 없습니다") 표시
  //        앨리스는 여전히 관리자 배지 유지 (목록 불변)
  // ─────────────────────────────────────────────────────────────────────────────
  test('S5 마지막 관리자 보호 — 앨리스 강등 시도 → 에러 토스트 + 목록 불변', async ({ page }) => {
    // 앨리스 행 컨테이너 한정
    const aliceRow = page.getByText('앨리스', { exact: true }).locator('xpath=ancestor::li[1]')

    // 앨리스 역할 Select를 "멤버"로 변경 시도
    const roleSelectTrigger = aliceRow.getByRole('combobox', { name: '역할 변경' })
    await roleSelectTrigger.click()
    await page.getByRole('option', { name: '멤버' }).click()

    // 에러 토스트 표시 확인 — project-member-error.ts 의 last_admin_protected 메시지
    await expect(page.getByText('마지막 관리자는 제거하거나 강등할 수 없습니다')).toBeVisible()

    // 앨리스는 여전히 관리자 배지 유지 (목록 롤백)
    // Select 값 span 과 배지 span 두 곳이 모두 "관리자"를 포함하므로 배지 클래스로 한정
    await expect(aliceRow.locator('span.rounded-full')).toHaveText('관리자')
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // S6 — 비멤버/미존재 프로젝트
  //
  // Given  alice로 로그인
  // When   존재하지 않는 프로젝트 키로 멤버 설정 페이지 진입
  // Then   "접근 권한이 없습니다" 안내 화면 표시
  // ─────────────────────────────────────────────────────────────────────────────
  test('S6 미존재 프로젝트 → "접근 권한이 없습니다" 화면', async ({ page }) => {
    // beforeEach 에서 ATLAS 로 진입했으므로 별도로 미존재 키로 재진입
    await page.goto('/projects/NONEXISTENT/settings/members')

    // MSW project-member-handlers 는 KNOWN_PROJECT_KEYS 에 없는 키에 대해 404 반환.
    // 라우트 컴포넌트 (ProjectMembersSettingsPage) 가 project_not_found 에러를
    // 수신하면 ProjectNotFoundScreen 을 렌더한다.
    // exact: true 로 substring 매칭 방지 (두 번째 p 에도 동일 substring 포함됨)
    await expect(page.getByText('접근 권한이 없습니다', { exact: true })).toBeVisible()
    await expect(page.getByText('해당 프로젝트가 존재하지 않거나 접근 권한이 없습니다.')).toBeVisible()
  })
})
