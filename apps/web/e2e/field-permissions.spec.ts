// FR-PM-07 D7 E2E — 필드 권한 규칙 CRUD + 이슈 필드 제한 시나리오 S1~S4
//
// 앱은 MSW(field-permission-handlers.ts, group-handlers.ts) 위에서 동작한다.
// fieldPermissionStore는 모듈 스코프 stateful — 시나리오마다 고유 fieldKey 접두사를 써서
// 저장소 오염 없이 격리한다.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — 기존 playwright.config.ts 그대로
//   - playwright-getbyrole-exact-strict-mode: 삭제는 aria-label="{fieldKey} 규칙 삭제"로 한정
//   - msw-mutation-stateful-refetch: mutation 후 refetch로 화면 갱신 검증 (가짜 그린 방지)
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 시나리오 토글
//   - msw-derived-behavior-shared-store-e2e: X-MSW-Seed-FieldPermissions 헤더로 브라우저 시드
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001) adminProjectPermissions 정합
import { test, expect } from '@playwright/test'
import { loginAsAlice, loginAsBob } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — 프로젝트 키 + 라우트
// ─────────────────────────────────────────────────────────────────────────────

const PROJECT_KEY = 'ATLAS'
const SETTINGS_URL = `/projects/${PROJECT_KEY}/settings/field-permissions`
const ISSUE_URL = `/issues/ATLAS-1`

/** field-permission-handlers.ts의 LS_KEY_FIELD_PERMISSION_403 과 동일 */
const LS_KEY_FIELD_PERMISSION_403 = 'msw-field-permission-403'

/** issue-handlers.ts의 LS_KEY_FIELD_PERMISSION_SCENARIO 와 동일 */
const LS_KEY_FIELD_PERMISSION_SCENARIO = '__bts_e2e_field_permission_scenario'

/** project-permission-handlers.ts의 E2E_FORCE_CREATE_FALSE_KEY 와 동일 */
const E2E_FORCE_CREATE_FALSE_KEY = '__bts_e2e_force_create_false'

/** group-handlers.ts 기본 시드 — 개발팀 그룹 ID */
const GROUP_ID_DEV = '11111111-0000-4000-8000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// CSRF 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * MSW field-permission-handlers가 X-XSRF-TOKEN 헤더 존재를 검증한다.
 * API 클라이언트는 readXsrfToken()으로 XSRF-TOKEN 쿠키를 읽어 헤더에 보낸다.
 * E2E에서 Spring이 쿠키를 발급하지 않으므로 page.context().addCookies()로 수동 심는다.
 *
 * @param page Playwright Page 객체
 */
async function seedXsrfCookie(page: import('@playwright/test').Page): Promise<void> {
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
// 헬퍼 — 규칙 생성 (다른 시나리오의 사전조건 세팅용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * FieldPermissionFormDialog를 통해 CORE 필드 VIEW 규칙을 생성하고
 * 다이얼로그가 닫힐 때까지 대기한다.
 * 생성 후 목록에 해당 fieldKey가 보여야 반환 (stateful refetch 포함).
 *
 * @param page Playwright Page 객체
 * @param fieldKey CORE 필드 키 (예: 'environment', 'labels')
 * @param groupName 선택할 그룹명 (기본: '개발팀')
 * @param accessLevel 접근 수준 (기본: 'VIEW')
 */
async function createCoreFieldPermission(
  page: import('@playwright/test').Page,
  fieldKey: string,
  groupName: string = '개발팀',
  accessLevel: 'VIEW' | 'EDIT' = 'VIEW',
): Promise<void> {
  await page.getByRole('button', { name: '규칙 추가' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog').getByText('필드 권한 규칙 추가')).toBeVisible()

  // 필드 종류 기본값 CORE 그대로 사용
  // 필드 키 선택
  await page.getByLabel('필드 키').selectOption(fieldKey)

  // 그룹 드롭다운 로딩 대기 — useGroups() 응답 후 옵션 노출
  const groupSelect = page.getByLabel('그룹')
  await expect(groupSelect).not.toBeDisabled()
  await groupSelect.selectOption({ label: groupName })

  // 접근 수준 선택
  if (accessLevel === 'EDIT') {
    await page.getByLabel('접근 수준').selectOption('EDIT')
  }

  // 저장 클릭
  await page.getByRole('button', { name: '저장' }).click()

  // 다이얼로그 닫힘
  await expect(page.getByRole('dialog')).toHaveCount(0)

  // msw-mutation-stateful-refetch 교훈 — invalidateQueries 후 refetch된 목록에 fieldKey가 보여야 함
  await expect(page.getByText(fieldKey, { exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 규칙 생성 → 목록 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 필드 권한 규칙 생성 (FR-PM-07)', () => {
  // Given  alice 로그인 + 필드 권한 규칙 관리 페이지 진입
  // When   CORE/environment 필드 + 개발팀 그룹 + VIEW 접근 수준으로 규칙 생성
  // Then   목록에 규칙 행이 표시됨 (fieldKey, 그룹명, 접근 수준 모두 노출)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await seedXsrfCookie(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: '필드 권한 규칙 관리', level: 2 })).toBeVisible()
  })

  test('S1-A CORE/environment + 개발팀 + VIEW 규칙 생성 → 목록 표시', async ({ page }) => {
    // When. 규칙 추가 버튼 클릭
    await page.getByRole('button', { name: '규칙 추가' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('dialog').getByText('필드 권한 규칙 추가')).toBeVisible()

    // 필드 종류: CORE (기본값)
    // 필드 키: environment 선택
    await page.getByLabel('필드 키').selectOption('environment')

    // 그룹 드롭다운 로딩 대기 — useGroups() 응답 후 옵션 노출
    const groupSelect = page.getByLabel('그룹')
    await expect(groupSelect).not.toBeDisabled()
    await groupSelect.selectOption({ label: '개발팀' })

    // 접근 수준: VIEW (기본값)

    // 저장
    await page.getByRole('button', { name: '저장' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 행이 표시됨
    await expect(page.getByText('environment', { exact: true })).toBeVisible()
    await expect(page.getByText('개발팀')).toBeVisible()
    // 접근 수준 뱃지 '보기' 표시
    await expect(page.getByText('보기')).toBeVisible()
    // fieldKind 칩 '기본 필드' 표시
    await expect(page.getByText('기본 필드')).toBeVisible()
  })

  test('S1-B CORE/labels + 기획팀 + EDIT 규칙 생성 → 목록 표시', async ({ page }) => {
    await page.getByRole('button', { name: '규칙 추가' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    await page.getByLabel('필드 키').selectOption('labels')

    const groupSelect = page.getByLabel('그룹')
    await expect(groupSelect).not.toBeDisabled()
    await groupSelect.selectOption({ label: '기획팀' })

    await page.getByLabel('접근 수준').selectOption('EDIT')

    await page.getByRole('button', { name: '저장' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)

    // Then. 목록에 행 표시
    await expect(page.getByText('labels', { exact: true })).toBeVisible()
    await expect(page.getByText('기획팀')).toBeVisible()
    // 접근 수준 뱃지 '편집' 표시
    await expect(page.getByText('편집')).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 규칙 삭제 → 목록에서 사라짐
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 필드 권한 규칙 삭제 (FR-PM-07)', () => {
  // Given  "environment" CORE/VIEW 규칙이 목록에 있음
  // When   행의 삭제 버튼 → 인라인 확인 UI 표시 → 삭제 확인 클릭
  // Then   목록에서 규칙 행이 사라짐 (refetch 반영)

  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await seedXsrfCookie(page)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: '필드 권한 규칙 관리', level: 2 })).toBeVisible()
  })

  test('S2 삭제 — 인라인 확인 후 삭제 → 목록에서 사라짐', async ({ page }) => {
    // 사전조건: environment 규칙 생성
    await createCoreFieldPermission(page, 'environment')

    // 삭제 버튼 클릭 — aria-label="environment 규칙 삭제" (strict mode 회피)
    await page.getByRole('button', { name: 'environment 규칙 삭제' }).click()

    // 인라인 확인 UI 표시
    await expect(page.getByText('삭제하시겠습니까?')).toBeVisible()

    // "삭제 확인" 버튼 클릭
    await page.getByRole('button', { name: '삭제 확인' }).click()

    // Then. 목록에서 제거됨
    await expect(page.getByText('environment', { exact: true })).toHaveCount(0)
  })

  test('S2-취소 — 취소 버튼 클릭 시 규칙 유지', async ({ page }) => {
    // 사전조건: priority 규칙 생성
    await createCoreFieldPermission(page, 'priority')

    // 삭제 버튼 클릭
    await page.getByRole('button', { name: 'priority 규칙 삭제' }).click()
    await expect(page.getByText('삭제하시겠습니까?')).toBeVisible()

    // 취소 클릭
    await page.getByRole('button', { name: '취소' }).click()

    // Then. 규칙이 목록에 유지됨
    await expect(page.getByText('priority', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 이슈 화면 필드 제한 (restrictedFields 숨김 + noneditableFields disabled)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 이슈 화면 필드 권한 적용 (FR-PM-07)', () => {
  // Given  alice 로그인
  //        X-MSW-Seed-FieldPermissions 헤더로 field-permission store에 시드
  //          - environment: VIEW (restrictedFields → 섹션 숨김)
  //          - labels: EDIT (noneditableFields → 저장 버튼 disabled)
  //        addInitScript로 LS_KEY_FIELD_PERMISSION_SCENARIO='true' 설정
  //        → issue-handlers.ts가 store 파생으로 restrictedFields/noneditableFields 채움
  // When   ATLAS-1 이슈 상세 페이지 진입
  // Then   environment-section: 렌더되지 않음 (숨김)
  //        labels-section: 렌더됨, 저장 버튼(labels-save) disabled

  test('S3-A restrictedFields — environment 섹션 숨김', async ({ page }) => {
    // Given. localStorage 플래그 등록 (goto 전 적용)
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_FIELD_PERMISSION_SCENARIO)

    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. field-permission store 시드 — environment: VIEW → restrictedFields 파생
    const seedRules = [
      {
        id: '00000000-0000-4000-8000-000000000fp1',
        fieldKind: 'CORE',
        fieldKey: 'environment',
        groupId: GROUP_ID_DEV,
        groupName: '개발팀',
        accessLevel: 'VIEW',
      },
    ]
    const seedHeader = encodeURIComponent(JSON.stringify(seedRules))
    await page.evaluate(
      async ({
        projectKey,
        header,
      }: {
        projectKey: string
        header: string
      }) => {
        await fetch(`/api/v1/projects/${projectKey}/field-permissions`, {
          headers: { 'X-MSW-Seed-FieldPermissions': header },
        })
      },
      { projectKey: PROJECT_KEY, header: seedHeader },
    )

    // When. 이슈 상세 페이지 SPA 내부 navigate
    // full page goto는 MSW 핸들러 모듈 재로드 → fieldPermissionStore 초기화되므로
    // pushState + popstate 이벤트로 SPA 내부 navigate (store 유지)
    // msw-derived-behavior-shared-store-e2e 교훈 참조
    await page.evaluate((url: string) => {
      window.history.pushState({}, '', url)
      window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
    }, ISSUE_URL)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    // Then. environment-section이 렌더되지 않음 (isFieldHidden → 섹션 자체 미노출)
    await expect(page.getByTestId('environment-section')).toHaveCount(0)
  })

  test('S3-B noneditableFields — labels 저장 버튼 disabled', async ({ page }) => {
    // Given. localStorage 플래그 등록
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_FIELD_PERMISSION_SCENARIO)

    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. field-permission store 시드 — labels: EDIT → noneditableFields 파생
    const seedRules = [
      {
        id: '00000000-0000-4000-8000-000000000fp2',
        fieldKind: 'CORE',
        fieldKey: 'labels',
        groupId: GROUP_ID_DEV,
        groupName: '개발팀',
        accessLevel: 'EDIT',
      },
    ]
    const seedHeader = encodeURIComponent(JSON.stringify(seedRules))
    await page.evaluate(
      async ({
        projectKey,
        header,
      }: {
        projectKey: string
        header: string
      }) => {
        await fetch(`/api/v1/projects/${projectKey}/field-permissions`, {
          headers: { 'X-MSW-Seed-FieldPermissions': header },
        })
      },
      { projectKey: PROJECT_KEY, header: seedHeader },
    )

    // When. 이슈 상세 페이지 SPA 내부 navigate (store 유지)
    await page.evaluate((url: string) => {
      window.history.pushState({}, '', url)
      window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
    }, ISSUE_URL)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    // Then. labels-section이 렌더됨 (EDIT → noneditableFields, 섹션은 숨기지 않음)
    await expect(page.getByTestId('labels-section')).toBeVisible()

    // Then. labels-save 버튼 disabled (isFieldDisabled: noneditableFields.includes('labels'))
    const labelsSave = page.getByTestId('labels-save')
    await expect(labelsSave).toBeVisible()
    await expect(labelsSave).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 권한 없는 사용자 게이팅
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 필드 권한 관리 권한 게이팅 (FR-PM-07)', () => {
  // S4-A: alice 로그인 + E2E_FORCE_CREATE_FALSE_KEY='true'
  //       → project-permission-handlers가 nonMemberProjectPermissions(MANAGE_FIELD_PERMISSIONS:false) 반환
  //       → "규칙 추가" 버튼 disabled (fail-closed 게이팅)
  //
  // S4-B: bob 로그인 (memberProjectPermissions — MANAGE_FIELD_PERMISSIONS:false)
  //       → "규칙 추가" 버튼 disabled

  test('S4-A 권한 강제 비활성(force_create_false) — 규칙 추가 버튼 disabled', async ({ page }) => {
    // Given. goto 전 addInitScript 등록
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, E2E_FORCE_CREATE_FALSE_KEY)

    // Given. alice 로그인 (addInitScript는 goto 전 등록 → /login goto에도 적용)
    await loginAsAlice(page)

    // When. 필드 권한 규칙 관리 페이지 진입 (이 goto부터 nonMemberProjectPermissions 반환)
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: '필드 권한 규칙 관리', level: 2 })).toBeVisible()

    // Then. "규칙 추가" 버튼 disabled (fail-closed)
    const addButton = page.getByRole('button', { name: '규칙 추가' })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeDisabled()
  })

  test('S4-B bob(MEMBER — MANAGE_FIELD_PERMISSIONS:false) — 규칙 추가 버튼 disabled', async ({ page }) => {
    // Given. bob 로그인 (memberProjectPermissions)
    await loginAsBob(page)

    // When. 필드 권한 규칙 관리 페이지 진입
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: '필드 권한 규칙 관리', level: 2 })).toBeVisible()

    // Then. "규칙 추가" 버튼 disabled
    const addButton = page.getByRole('button', { name: '규칙 추가' })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeDisabled()

    // Then. 기존 규칙이 있어도 삭제 버튼이 모두 disabled
    const rows = page.getByTestId('field-permission-row')
    const rowCount = await rows.count()
    for (let i = 0; i < rowCount; i++) {
      const deleteButton = rows.nth(i).getByRole('button', { name: /규칙 삭제/ })
      if ((await deleteButton.count()) > 0) {
        await expect(deleteButton).toBeDisabled()
      }
    }
  })

  test('S4-C 403 토글 — 규칙 생성 시 Dialog 유지 + 에러 메시지 표시', async ({ page }) => {
    // Given. 403 강제 플래그 설정
    await page.addInitScript((lsKey: string) => {
      window.localStorage.setItem(lsKey, 'true')
    }, LS_KEY_FIELD_PERMISSION_403)

    // Given. alice 로그인 (addInitScript → /login goto에도 적용)
    await loginAsAlice(page)
    await seedXsrfCookie(page)

    // When. 필드 권한 규칙 관리 페이지 진입
    // 403 플래그는 POST/DELETE에만 영향 — 버튼이 canManage=false로 disabled가 되므로
    // 추가 권한 강제 없이 직접 Dialog를 여는 방법이 필요.
    // alice의 GET 권한은 여전히 adminProjectPermissions이므로 canManage=true.
    // msw-field-permission-403 플래그는 write 요청에만 403을 적용한다.
    await page.goto(SETTINGS_URL)
    await expect(page.getByRole('heading', { name: '필드 권한 규칙 관리', level: 2 })).toBeVisible()

    // 규칙 추가 버튼 클릭 (alice는 canManage=true)
    await page.getByRole('button', { name: '규칙 추가' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()

    // 그룹 선택 + 저장 — 로딩 대기 후 선택
    const groupSelect = page.getByLabel('그룹')
    await expect(groupSelect).not.toBeDisabled()
    await groupSelect.selectOption({ label: '개발팀' })
    await page.getByRole('button', { name: '저장' }).click()

    // Then. Dialog 유지 + 에러 메시지 표시 (submitError 경로)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('권한이 없습니다')
    await expect(page.getByRole('dialog')).toBeVisible()
  })
})
