// FR-PM-03 D7 — 버전/컴포넌트 관리 권한 게이팅 E2E
//
// MANAGE_COMPONENTS / MANAGE_VERSIONS 권한 게이팅을 검증한다.
// MSW 핸들러의 E2E_FORCE_CREATE_FALSE_KEY 플래그를 재사용한다.
// 이 플래그가 'true'이면 핸들러가 nonMemberProjectPermissions(MANAGE_*:false)를 반환한다.
//
// S1 — PROJECT_ADMIN(기본 — 플래그 없음, adminProjectPermissions).
//   Given  alice 로그인 + project-permissions MANAGE_*:true (기본 MSW 핸들러)
//   When   설정 페이지 진입 + 항목 생성(사전조건)
//   Then   "추가" 버튼 활성, 행 "수정"/"삭제" 버튼 활성
//
// S2 — 권한 없는 사용자 (addInitScript 패턴).
//   Given  alice 로그인 + addInitScript 로 localStorage 플래그 설정
//   When   설정 페이지 진입 (첫 fetch 시점부터 MSW가 MANAGE_*:false 반환)
//   Then   "추가" 버튼 disabled
//   Then   페이지 정상 렌더 (에러 없음, 빈 상태 또는 목록 영역 표시)
//
// 아키텍처 제약: MSW 핸들러(componentStore/versionStore)는 메인 스레드 모듈 스코프에 위치.
//   SPA가 리로드/재탐색되면 모듈이 재평가되어 스토어가 초기화된다.
//   따라서 S2에서 "기존 행의 버튼 disabled" 검증은 SPA 리로드 없이는 불가능하다.
//   행 버튼 disabled는 ComponentRow/VersionRow 단위 테스트(canManage=false)에서 담당한다.
//   E2E에서는 "추가 버튼 disabled" + "페이지 정상 렌더"로 fail-closed 게이팅을 검증한다.
//
// 교훈 반영.
//   - playwright-getbyrole-exact-strict-mode: aria-label="<이름> 수정"으로 행 한정
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript는 등록 이후 탐색에 적용
//   - e2e-fixture-whoami-userid-alignment: alice(00000000-...-001) adminProjectPermissions 정합
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/session-fixtures'

// -----------------------------------------------------------------------------
// 상수 — 프로젝트 키 + 라우트
// -----------------------------------------------------------------------------

const PROJECT_KEY = 'ATLAS'
const COMPONENTS_URL = `/projects/${PROJECT_KEY}/settings/components`
const VERSIONS_URL = `/projects/${PROJECT_KEY}/settings/versions`

// =============================================================================
// Suite: 컴포넌트 관리 권한 게이팅 (FR-PM-03 Task-6)
// =============================================================================

test.describe('컴포넌트 관리 권한 게이팅 (FR-PM-03 D7)', () => {
  // ---------------------------------------------------------------------------
  // S1 — PROJECT_ADMIN: 관리 버튼 활성, 행 버튼 활성
  //
  // Given  alice 로그인 + 기본 MSW 핸들러(MANAGE_COMPONENTS:true, 플래그 없음)
  // When   컴포넌트 설정 페이지 진입 + 컴포넌트 생성(사전조건)
  // Then   "컴포넌트 추가" 버튼 enabled
  // Then   행의 "수정"/"삭제" 버튼 enabled
  // ---------------------------------------------------------------------------
  test('S1 PROJECT_ADMIN — 컴포넌트 추가/수정/삭제 버튼 활성', async ({ page }) => {
    // Given. alice 로그인 (플래그 없음 → MANAGE_COMPONENTS:true 기본 응답)
    await loginAsAlice(page)

    // When. 컴포넌트 설정 페이지 진입
    await page.goto(COMPONENTS_URL)
    await expect(page.getByRole('heading', { name: '컴포넌트 설정', level: 2 })).toBeVisible()

    // Then. "컴포넌트 추가" 버튼 활성(enabled)
    const addButton = page.getByRole('button', { name: '컴포넌트 추가' })
    await expect(addButton).toBeVisible()
    await expect(addButton).not.toBeDisabled()

    // 사전조건: 행이 있어야 수정/삭제 버튼을 검증할 수 있음
    const componentName = 'S1-게이팅-컴포넌트'
    await addButton.click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByLabel('이름').fill(componentName)
    await page.getByRole('button', { name: '저장' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByText(componentName, { exact: true })).toBeVisible()

    // Then. 행의 "수정" 버튼 활성
    // aria-label="<이름> 수정" 패턴으로 행 한정 (strict mode violation 회피)
    const editButton = page.getByRole('button', { name: `${componentName} 수정` })
    await expect(editButton).toBeVisible()
    await expect(editButton).not.toBeDisabled()

    // Then. 행의 "삭제" 버튼 활성
    const deleteButton = page.getByRole('button', { name: `${componentName} 삭제` })
    await expect(deleteButton).toBeVisible()
    await expect(deleteButton).not.toBeDisabled()
  })

  // ---------------------------------------------------------------------------
  // S2 — 권한 없는 사용자: "추가" 버튼 disabled, 페이지 정상 렌더
  //
  // Given  alice 로그인 + addInitScript(E2E_FORCE_CREATE_FALSE_KEY='true')
  //        → 첫 fetch 시점부터 MSW가 MANAGE_COMPONENTS:false 반환
  // When   컴포넌트 설정 페이지 진입
  // Then   "컴포넌트 추가" 버튼 disabled (fail-closed 게이팅)
  // Then   페이지 정상 렌더 — 에러 없이 빈 상태 메시지 또는 목록 영역 표시
  //
  // 선례 동형: issue-create-gate.spec.ts:59-61 addInitScript 패턴
  // ---------------------------------------------------------------------------
  test('S2 권한 없는 사용자 — 컴포넌트 추가 버튼 disabled, 페이지 정상 렌더', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 다음 goto부터 플래그를 심는다.
    // MSW 핸들러(E2E_FORCE_CREATE_FALSE_KEY)가 이 플래그를 읽어 MANAGE_COMPONENTS:false 반환.
    // 선례 issue-create-gate.spec.ts:59-61의 addInitScript 정본 패턴과 동일.
    await page.addInitScript(() => {
      window.localStorage.setItem('__bts_e2e_force_create_false', 'true')
    })

    // When. 컴포넌트 설정 페이지 진입 (이 goto부터 플래그 적용)
    await page.goto(COMPONENTS_URL)
    await expect(page.getByRole('heading', { name: '컴포넌트 설정', level: 2 })).toBeVisible()

    // Then. "컴포넌트 추가" 버튼 disabled (fail-closed 게이팅)
    const addButton = page.getByRole('button', { name: '컴포넌트 추가' })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeDisabled()

    // Then. 페이지 정상 렌더 — 에러 없이 빈 상태 또는 목록이 보임
    // (권한 없음은 컴포넌트 GET 조회와 무관 — 목록 조회 핸들러는 별도)
    const emptyMsg = page.getByText('아직 컴포넌트가 없습니다.')
    const componentsList = page.locator('ul.space-y-2')
    const isRenderedNormally = (await emptyMsg.isVisible()) || (await componentsList.count()) > 0
    expect(isRenderedNormally).toBe(true)
  })
})

// =============================================================================
// Suite: 버전 관리 권한 게이팅 (FR-PM-03 Task-6)
// =============================================================================

test.describe('버전 관리 권한 게이팅 (FR-PM-03 D7)', () => {
  // ---------------------------------------------------------------------------
  // S1 — PROJECT_ADMIN: 관리 버튼 활성, 행 버튼 활성
  //
  // Given  alice 로그인 + 기본 MSW 핸들러(MANAGE_VERSIONS:true, 플래그 없음)
  // When   버전 설정 페이지 진입 + 버전 생성(사전조건)
  // Then   "버전 추가" 버튼 enabled
  // Then   행의 "수정"/"삭제" 버튼 enabled
  // ---------------------------------------------------------------------------
  test('S1 PROJECT_ADMIN — 버전 추가/수정/삭제 버튼 활성', async ({ page }) => {
    // Given. alice 로그인 (플래그 없음 → MANAGE_VERSIONS:true 기본 응답)
    await loginAsAlice(page)

    // When. 버전 설정 페이지 진입
    await page.goto(VERSIONS_URL)
    await expect(page.getByRole('heading', { name: '버전 관리', level: 2 })).toBeVisible()

    // Then. "버전 추가" 버튼 활성(enabled)
    const addButton = page.getByRole('button', { name: '버전 추가' })
    await expect(addButton).toBeVisible()
    await expect(addButton).not.toBeDisabled()

    // 사전조건: 행이 있어야 수정/삭제 버튼을 검증할 수 있음
    const versionName = 'S1-게이팅-버전'
    await addButton.click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await page.getByLabel('이름').fill(versionName)
    await page.getByRole('button', { name: '저장' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByText(versionName, { exact: true })).toBeVisible()

    // Then. 행의 "수정" 버튼 활성
    const editButton = page.getByRole('button', { name: `${versionName} 수정` })
    await expect(editButton).toBeVisible()
    await expect(editButton).not.toBeDisabled()

    // Then. 행의 "삭제" 버튼 활성
    const deleteButton = page.getByRole('button', { name: `${versionName} 삭제` })
    await expect(deleteButton).toBeVisible()
    await expect(deleteButton).not.toBeDisabled()
  })

  // ---------------------------------------------------------------------------
  // S2 — 권한 없는 사용자: "추가" 버튼 disabled, 페이지 정상 렌더
  //
  // Given  alice 로그인 + addInitScript(E2E_FORCE_CREATE_FALSE_KEY='true')
  //        → 첫 fetch 시점부터 MSW가 MANAGE_VERSIONS:false 반환
  // When   버전 설정 페이지 진입
  // Then   "버전 추가" 버튼 disabled (fail-closed 게이팅)
  // Then   페이지 정상 렌더 — 에러 없이 빈 상태 메시지 또는 목록 영역 표시
  // ---------------------------------------------------------------------------
  test('S2 권한 없는 사용자 — 버전 추가 버튼 disabled, 페이지 정상 렌더', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // Given. 다음 goto부터 플래그를 심는다.
    await page.addInitScript(() => {
      window.localStorage.setItem('__bts_e2e_force_create_false', 'true')
    })

    // When. 버전 설정 페이지 진입 (이 goto부터 플래그 적용)
    await page.goto(VERSIONS_URL)
    await expect(page.getByRole('heading', { name: '버전 관리', level: 2 })).toBeVisible()

    // Then. "버전 추가" 버튼 disabled (fail-closed 게이팅)
    const addButton = page.getByRole('button', { name: '버전 추가' })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeDisabled()

    // Then. 페이지 정상 렌더 — 에러 없이 빈 상태 또는 목록이 보임
    const emptyMsg = page.getByText('아직 버전이 없습니다.')
    const versionList = page.locator('ul.space-y-2')
    const isRenderedNormally = (await emptyMsg.isVisible()) || (await versionList.count()) > 0
    expect(isRenderedNormally).toBe(true)
  })
})
