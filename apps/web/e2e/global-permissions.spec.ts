// FR-PM-10 D7 E2E — 전역 권한 부여/회수 관리 화면 시나리오 S1~S5
//
// 앱은 MSW(global-permission-handlers.ts, group-handlers.ts, user-handlers.ts) 위에서 동작한다.
// globalPermissionStore는 모듈 스코프 stateful — full page 이동(page.goto)은 MSW 핸들러 모듈을
// 재로드시켜 store를 초기화한다(msw-mutation-stateful-refetch / e2e-msw-scenario-toggle-localstorage-flag
// 선례, field-permissions.spec.ts와 동일). 그래서 로그인 이후에는 항상 관리 메뉴 nav 링크 클릭으로
// SPA 내부 이동만 사용하고, 시드는 페이지 이동 "전"에 같은 실행 컨텍스트에서 fetch로 주입한다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: "전역 권한"은 nav 링크·"전역 권한 관리"는 h1이라 텍스트가
//     달라 자연히 구분되지만, nav 링크는 관리 메뉴 컨테이너로 한정해 안전하게 조회한다.
//   - msw-mutation-stateful-refetch: mutation 후 refetch로 화면 갱신 검증 (가짜 그린 방지)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 SYSTEM_ADMIN 토글
//   - msw-derived-behavior-shared-store-e2e: X-MSW-Seed-GlobalPermissions 헤더로 브라우저 시드
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001)를 SYSTEM_ADMIN으로 사용
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { gotoAdminPage } from './fixtures/admin-hub'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — localStorage 플래그 키 (auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY와 동일 문자열)
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY — audit-logs.spec.ts와 동일 상수 */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — group-handlers.ts / user-fixtures.ts 기본 시드 데이터
// ─────────────────────────────────────────────────────────────────────────────

/** group-handlers.ts DEFAULT_GROUPS — 개발팀 그룹 ID */
const GROUP_ID_DEV = '11111111-0000-4000-8000-000000000001'

/** group-handlers.ts DEFAULT_GROUPS — 기획팀 그룹 ID */
const GROUP_ID_PLANNING = '11111111-0000-4000-8000-000000000002'
const GROUP_NAME_PLANNING = '기획팀'

/** user-fixtures.ts userAliceFixture — displayName '김앨리스' (부여자 표시용) */
const USER_ID_ALICE_DIRECTORY = '00000000-0000-4000-8000-000000000001'

/** user-fixtures.ts userBobFixture — displayName 없음(username 'bob' 폴백) */
const USER_ID_BOB_DIRECTORY = '00000000-0000-4000-8000-000000000002'

/** user-fixtures.ts userCarolFixture — displayName '캐럴', 검색 대상 */
const USER_NAME_CAROL_QUERY = 'carol'
const USER_LABEL_CAROL = '캐럴'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SYSTEM_ADMIN alice로 로그인 (audit-logs.spec.ts와 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

async function loginAsSystemAdmin(page: Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_IS_SYSTEM_ADMIN)
  await loginAsAlice(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — CSRF 쿠키 (field-permissions.spec.ts와 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW global-permission-handlers가 X-XSRF-TOKEN 헤더 존재를 검증하진 않지만
 * 실제 API 클라이언트(readXsrfToken())가 XSRF-TOKEN 쿠키를 읽어 헤더에 싣는 경로이므로
 * 쿠키가 없으면 apiFetch 자체가 빈 문자열 헤더를 보낸다. 다른 admin 화면 E2E와 동일하게 심어둔다.
 *
 * @param page Playwright Page 객체
 */
async function seedXsrfCookie(page: Page): Promise<void> {
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'e2e-test-csrf-token',
      domain: 'localhost',
      path: '/',
      httpOnly: false,
      secure: false,
      sameSite: 'Lax',
    },
  ])
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 전역 권한 store 시드 (msw-derived-behavior-shared-store-e2e)
// ─────────────────────────────────────────────────────────────────────────────

interface GlobalPermissionSeed {
  id: string
  permission: string
  granteeType: 'USER' | 'GROUP'
  granteeId: string
  grantedBy?: string
  createdAt?: string
}

/**
 * X-MSW-Seed-GlobalPermissions 헤더로 globalPermissionStore를 시드한다.
 * 반드시 로그인 완료 직후, "관리 메뉴" nav 링크 클릭(SPA 이동) 전에 호출한다 —
 * page.goto는 MSW 핸들러 모듈을 재로드시켜 store를 초기화하므로 순서가 중요하다.
 *
 * @param page Playwright Page 객체
 * @param seeds 시드할 grant 배열
 */
async function seedGlobalPermissions(page: Page, seeds: GlobalPermissionSeed[]): Promise<void> {
  const header = encodeURIComponent(JSON.stringify(seeds))
  await page.evaluate(async (seedHeader: string) => {
    await fetch('/api/v1/admin/global-permissions', {
      headers: { 'X-MSW-Seed-GlobalPermissions': seedHeader },
    })
  }, header)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 관리 메뉴 nav 경유 진입 (NFR-4 계약, [[frontend-nav-aria-label-e2e-contract]])
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "관리 메뉴" nav의 "전역 권한" 링크를 클릭해 SPA 내부 이동으로 관리 화면에 진입한다.
 * page.goto가 아닌 nav 링크 클릭을 쓰는 이유 — 시드된 globalPermissionStore를 유지하기 위함(모듈 재로드 회피).
 *
 * @param page Playwright Page 객체
 */
async function navigateToGlobalPermissionsPage(page: Page): Promise<void> {
  await gotoAdminPage(page, '전역 권한')

  await page.waitForURL('**/admin/global-permissions')
  await expect(page.getByRole('heading', { name: '전역 권한 관리', level: 1 })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 목록 조회 (시드된 grant가 이름으로 표시)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 전역 권한 목록 조회 (FR-PM-10)', () => {
  // Given  SYSTEM_ADMIN alice 로그인 + 그룹/사용자 grant 각 1건 시드
  // When   관리 메뉴 > "전역 권한" 진입
  // Then   두 행 모두 grantee/부여자가 UUID가 아닌 표시 이름으로 보인다

  test('Given 시드된 그룹/사용자 grant When 목록 진입 Then 이름으로 표시', async ({ page }) => {
    // Given. SYSTEM_ADMIN 로그인
    await loginAsSystemAdmin(page)

    // Given. 그룹 grant 1건 + 사용자 grant 1건 시드 — grantedBy를 실제 존재하는 사용자로 지정해
    // 부여자 열이 "삭제된 사용자"로 orphan 폴백되지 않게 한다.
    await seedGlobalPermissions(page, [
      {
        id: '00000000-0000-4000-8000-000000000101',
        permission: 'CREATE_PROJECT',
        granteeType: 'GROUP',
        granteeId: GROUP_ID_DEV,
        grantedBy: USER_ID_ALICE_DIRECTORY,
        createdAt: '2026-01-01T00:00:00Z',
      },
      {
        id: '00000000-0000-4000-8000-000000000102',
        permission: 'CREATE_PROJECT',
        granteeType: 'USER',
        granteeId: USER_ID_BOB_DIRECTORY,
        grantedBy: USER_ID_ALICE_DIRECTORY,
        createdAt: '2026-01-02T00:00:00Z',
      },
    ])

    // When. 관리 메뉴 경유 진입
    await navigateToGlobalPermissionsPage(page)

    // Then. 두 행이 렌더됨 — 시드한 grant 2건과 일치
    const rows = page.getByTestId('global-permission-row')
    await expect(rows).toHaveCount(2)

    // Then. 그룹 grant 행 — 권한 한글 라벨 + 대상 이름 "개발팀" + 부여자 "김앨리스"(orphan 아님)
    const groupRow = rows.filter({ hasText: '개발팀' })
    await expect(groupRow).toHaveCount(1)
    await expect(groupRow.getByText('프로젝트 생성')).toBeVisible()
    await expect(groupRow.getByText('김앨리스')).toBeVisible()

    // Then. 사용자 grant 행 — displayName 없는 bob은 username 폴백 "bob" + 부여자 "김앨리스"
    const userRow = rows.filter({ hasText: 'bob' })
    await expect(userRow).toHaveCount(1)
    await expect(userRow.getByText('김앨리스')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 그룹에 부여
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 그룹에 전역 권한 부여 (FR-PM-10)', () => {
  // Given  SYSTEM_ADMIN alice 로그인 + 관리 화면 진입 (시드 없음, 빈 목록)
  // When   "권한 부여" → 대상 종류=그룹 → 기획팀 선택 → 제출
  // Then   201 → 목록에 "기획팀" 행 반영

  test('Given 빈 목록 When 기획팀에 부여 Then 목록에 반영', async ({ page }) => {
    // Given. SYSTEM_ADMIN 로그인 + CSRF 쿠키 + 관리 화면 진입
    await loginAsSystemAdmin(page)
    await seedXsrfCookie(page)
    await navigateToGlobalPermissionsPage(page)

    // Given. 빈 목록 안내 노출 확인 (FR-9)
    await expect(page.getByText('부여된 전역 권한이 없습니다')).toBeVisible()

    // When. "권한 부여" 다이얼로그 오픈
    await page.getByRole('button', { name: '권한 부여', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // When. 대상 종류 = 그룹 (기본값은 사용자이므로 전환)
    await page.getByRole('radio', { name: '그룹', exact: true }).check()

    // When. 그룹 드롭다운 로딩 대기 후 기획팀 선택
    const groupSelect = page.getByLabel('그룹 선택')
    await expect(groupSelect).not.toBeDisabled()
    await groupSelect.selectOption({ label: GROUP_NAME_PLANNING })

    // When. 제출 — "부여"는 "권한 부여" 버튼과 substring 충돌하므로 exact 필수
    // (playwright-getbyrole-exact-strict-mode)
    await page.getByRole('button', { name: '부여', exact: true }).click()

    // Then. 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 "기획팀" 행 반영 (invalidate 후 refetch)
    const rows = page.getByTestId('global-permission-row')
    await expect(rows.filter({ hasText: GROUP_NAME_PLANNING })).toHaveCount(1)
    await expect(page.getByText('부여된 전역 권한이 없습니다')).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 사용자에 부여 (검색)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 사용자에 전역 권한 부여 (FR-PM-10)', () => {
  // Given  SYSTEM_ADMIN alice 로그인 + 관리 화면 진입
  // When   "권한 부여" → 대상 종류=사용자(기본값) → "carol" 검색 → 결과에서 선택 → 제출
  // Then   201 → 목록에 "캐럴" 행 반영

  test('Given 빈 목록 When carol 검색 후 부여 Then 목록에 반영', async ({ page }) => {
    // Given. SYSTEM_ADMIN 로그인 + CSRF 쿠키 + 관리 화면 진입
    await loginAsSystemAdmin(page)
    await seedXsrfCookie(page)
    await navigateToGlobalPermissionsPage(page)

    // When. "권한 부여" 다이얼로그 오픈 — 대상 종류 기본값 사용자
    await page.getByRole('button', { name: '권한 부여', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('radio', { name: '사용자', exact: true })).toBeChecked()

    // When. 대상 검색 — 2자 이상 입력 시 typeahead 발동
    await page.getByLabel('대상 검색').fill(USER_NAME_CAROL_QUERY)

    // When. 검색 결과에서 캐럴 선택
    const carolOption = page.getByRole('button', { name: USER_LABEL_CAROL, exact: true })
    await expect(carolOption).toBeVisible()
    await carolOption.click()

    // When. 제출 — "부여"는 "권한 부여" 버튼과 substring 충돌하므로 exact 필수
    await page.getByRole('button', { name: '부여', exact: true }).click()

    // Then. 다이얼로그 닫힘
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 "캐럴" 행 반영
    const rows = page.getByTestId('global-permission-row')
    await expect(rows.filter({ hasText: USER_LABEL_CAROL })).toHaveCount(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 회수
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 전역 권한 회수 (FR-PM-10)', () => {
  // Given  기획팀 grant 1건이 목록에 있음
  // When   해당 행의 회수 버튼 → 인라인 "삭제하시겠습니까?" 확인 → 확인 클릭
  // Then   204 → 목록에서 사라짐

  test('Given 기획팀 grant 1건 When 회수 확인 Then 목록에서 제거', async ({ page }) => {
    // Given. SYSTEM_ADMIN 로그인 + CSRF 쿠키 + 기획팀 grant 시드
    await loginAsSystemAdmin(page)
    await seedXsrfCookie(page)
    await seedGlobalPermissions(page, [
      {
        id: '00000000-0000-4000-8000-000000000103',
        permission: 'CREATE_PROJECT',
        granteeType: 'GROUP',
        granteeId: GROUP_ID_PLANNING,
        grantedBy: USER_ID_ALICE_DIRECTORY,
        createdAt: '2026-01-03T00:00:00Z',
      },
    ])
    await navigateToGlobalPermissionsPage(page)
    const rows = page.getByTestId('global-permission-row')
    await expect(rows.filter({ hasText: GROUP_NAME_PLANNING })).toHaveCount(1)

    // When. 회수 버튼 클릭 — aria-label에 대상 이름 포함 (NFR-4)
    await page.getByRole('button', { name: `${GROUP_NAME_PLANNING} 전역 권한 회수`, exact: true }).click()

    // When. 인라인 확인 UI 노출
    await expect(page.getByText('삭제하시겠습니까?')).toBeVisible()

    // When. "확인" 클릭
    await page.getByRole('button', { name: '확인', exact: true }).click()

    // Then. 목록에서 제거됨 (refetch 반영) — 빈 목록 안내로 복귀
    await expect(rows.filter({ hasText: GROUP_NAME_PLANNING })).toHaveCount(0)
    await expect(page.getByText('부여된 전역 권한이 없습니다')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 중복 부여 거부
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 중복 전역 권한 부여 거부 (FR-PM-10)', () => {
  // Given  기획팀 grant 1건이 이미 목록에 있음
  // When   동일 조합(권한=프로젝트 생성, 대상=기획팀)을 다시 부여
  // Then   409 grant_already_exists → "이미 부여된 권한입니다" 폼 오류, 목록은 1건 그대로 불변

  test('Given 기획팀 grant 존재 When 동일 조합 재부여 Then 409 한글 오류 + 목록 불변', async ({ page }) => {
    // Given. SYSTEM_ADMIN 로그인 + CSRF 쿠키 + 기획팀 grant 시드
    await loginAsSystemAdmin(page)
    await seedXsrfCookie(page)
    await seedGlobalPermissions(page, [
      {
        id: '00000000-0000-4000-8000-000000000104',
        permission: 'CREATE_PROJECT',
        granteeType: 'GROUP',
        granteeId: GROUP_ID_PLANNING,
        grantedBy: USER_ID_ALICE_DIRECTORY,
        createdAt: '2026-01-04T00:00:00Z',
      },
    ])
    await navigateToGlobalPermissionsPage(page)
    const rows = page.getByTestId('global-permission-row')
    await expect(rows.filter({ hasText: GROUP_NAME_PLANNING })).toHaveCount(1)

    // When. 동일 조합(그룹=기획팀)으로 재부여 시도
    await page.getByRole('button', { name: '권한 부여', exact: true }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByRole('radio', { name: '그룹', exact: true }).check()
    const groupSelect = page.getByLabel('그룹 선택')
    await expect(groupSelect).not.toBeDisabled()
    await groupSelect.selectOption({ label: GROUP_NAME_PLANNING })
    await page.getByRole('button', { name: '부여', exact: true }).click()

    // Then. 409 한글 오류 — 다이얼로그 유지
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('이미 부여된 권한입니다')
    await expect(page.getByRole('dialog')).toBeVisible()

    // Then. 다이얼로그 닫고 목록이 1건 그대로인지 확인 (불변)
    await page.getByRole('button', { name: '취소', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(rows.filter({ hasText: GROUP_NAME_PLANNING })).toHaveCount(1)
  })
})
