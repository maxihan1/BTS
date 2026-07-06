// 사용자 프로필 설정 페이지 E2E — /settings/profile 조회·저장·아바타 업로드/삭제·검증 실패 + Header 계정 트리거 연동 (FR-PR-01 D7)
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { profileLabels } from '../src/i18n/profile-labels'
import { ALICE_PROFILE_FIXTURE } from '../src/mocks/profile-fixtures'

/**
 * 교훈 반영.
 *   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
 *   - playwright-getbyrole-exact-strict-mode: exact:true로 strict mode violation 회피
 *   - e2e-fixture-whoami-userid-alignment: ALICE_PROFILE_FIXTURE.userId가 auth-fixtures.aliceUser와 정합
 *     (profile-fixtures.ts가 aliceUser.userId를 그대로 참조 — Task 4에서 이미 보장)
 *   - msw-mutation-stateful-refetch: page.reload()는 profileStore(모듈 레벨 상태)를 리셋하므로 금지.
 *     대신 SPA 내부 Link 클릭(하드 리로드 없음)으로 재진입해 재조회 값을 검증한다.
 *   - i18n 정본(profile-labels.ts)·fixture 정본(profile-fixtures.ts) import — 값 hardcoded 금지(login-ldap 선례)
 *
 * profileStore는 Playwright 브라우저 컨텍스트 단위로 격리된다 — 새 컨텍스트(새 test)마다 초기화되므로
 * 각 test는 자기 아바타 상태를 스스로 만들고 검증한다(데이터 격리).
 *
 * ★ 아바타 <img src>는 반드시 blob: URL이어야 한다(F5-1) — STATELESS JWT라 avatarUrl을 직접 <img src>에
 * 넣으면 실제 백엔드에서 401로 깨지는데, MSW 위에서는 서비스워커가 인증 없이도 응답해 가짜 그린이 될 수
 * 있다. 그래서 S4에서 src가 `/^blob:/`인지 명시적으로 확인한다.
 */

test.describe('S1 프로필 조회 (FR-PR-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given 로그인 상태 When /settings/profile 진입 Then username/email 읽기전용 + displayName/timezone/department 프리필', async ({
    page,
  }) => {
    // Then. username/email은 읽기 전용(편집 불가) + 시드값 표시
    const usernameInput = page.getByLabel(profileLabels.form.usernameLabel)
    await expect(usernameInput).toHaveValue(ALICE_PROFILE_FIXTURE.username)
    await expect(usernameInput).not.toBeEditable()

    const emailInput = page.getByLabel(profileLabels.form.emailLabel)
    await expect(emailInput).toHaveValue(ALICE_PROFILE_FIXTURE.email ?? '')
    await expect(emailInput).not.toBeEditable()

    // Then. displayName/timezone/department는 편집 가능 + 시드값 프리필
    await expect(page.getByLabel(profileLabels.form.displayNameLabel)).toHaveValue(
      ALICE_PROFILE_FIXTURE.displayName,
    )
    await expect(page.getByLabel(profileLabels.form.timezoneLabel)).toHaveValue(
      ALICE_PROFILE_FIXTURE.timezone,
    )
    await expect(page.getByLabel(profileLabels.form.departmentLabel)).toHaveValue(
      ALICE_PROFILE_FIXTURE.department ?? '',
    )

    // Then. 아바타 미설정 — 이니셜 폴백(role=img, aria-label=displayName, 첫 글자 텍스트)
    const avatarFallback = page
      .locator('main')
      .getByRole('img', { name: ALICE_PROFILE_FIXTURE.displayName })
    await expect(avatarFallback).toBeVisible()
    await expect(avatarFallback).toHaveText(ALICE_PROFILE_FIXTURE.displayName.charAt(0))
  })
})

test.describe('S2 표시이름/타임존/부서 저장 (FR-PR-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given 폼 입력 변경 When 저장 클릭 Then 성공 메시지 + 값 반영 + SPA 재진입 후에도 값 유지', async ({
    page,
  }) => {
    const nextDisplayName = '김맥시'
    const nextTimezone = 'America/New_York'
    const nextDepartment = '디자인팀'

    // When. 세 필드 변경
    await page.getByLabel(profileLabels.form.displayNameLabel).fill(nextDisplayName)
    await page.getByLabel(profileLabels.form.timezoneLabel).selectOption(nextTimezone)
    await page.getByLabel(profileLabels.form.departmentLabel).fill(nextDepartment)

    // When. 저장 클릭 — PATCH 1회
    await page.getByRole('button', { name: profileLabels.form.saveButton, exact: true }).click()

    // Then. 성공 메시지(role=status) + 폼에 갱신값 즉시 반영
    await expect(page.getByRole('status')).toHaveText(profileLabels.status.saveSuccess)
    await expect(page.getByLabel(profileLabels.form.displayNameLabel)).toHaveValue(nextDisplayName)
    await expect(page.getByLabel(profileLabels.form.timezoneLabel)).toHaveValue(nextTimezone)
    await expect(page.getByLabel(profileLabels.form.departmentLabel)).toHaveValue(nextDepartment)

    // Then. SPA 내부 재진입(Link 클릭, 하드 리로드 없음)으로도 값이 유지됨 — MSW mutation이
    // profileStore에 실제로 영속됐는지 검증한다(msw-mutation-stateful-refetch).
    // page.reload()/page.goto()는 profileStore 모듈 상태를 리셋시키므로 절대 사용하지 않는다.
    await page.getByRole('link', { name: '대시보드', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboards$/)
    await page.getByRole('button', { name: /계정 메뉴$/ }).click()
    await page.getByRole('menuitem', { name: '프로필', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/profile$/)

    await expect(page.getByLabel(profileLabels.form.displayNameLabel)).toHaveValue(nextDisplayName)
    await expect(page.getByLabel(profileLabels.form.timezoneLabel)).toHaveValue(nextTimezone)
    await expect(page.getByLabel(profileLabels.form.departmentLabel)).toHaveValue(nextDepartment)
  })
})

test.describe('S4 아바타 업로드 (FR-PR-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given 아바타 미설정 When 이미지 파일 선택 Then 페이지 아바타에 인증 이미지(blob)가 표시된다', async ({
    page,
  }) => {
    const pageAvatarImg = page.locator('main img')
    // Given. 업로드 전에는 실제 <img> 태그가 없다(이니셜 폴백 div만 존재)
    await expect(pageAvatarImg).toHaveCount(0)

    // When. 드롭존 없이 단순 파일 input에 이미지 주입 (setInputFiles)
    await page.getByLabel(profileLabels.avatar.fileInputLabel).setInputFiles({
      name: 'avatar.png',
      mimeType: 'image/png',
      buffer: Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    })

    // Then. 업로드 성공 → <img> 등장
    await expect(pageAvatarImg).toHaveCount(1)
    await expect(pageAvatarImg).toBeVisible()

    // Then. F5-1 회귀 방지 — src가 blob: URL(인증 fetch 경로)이어야 한다. avatarUrl을 <img src>에
    // 직접 넣으면(회귀) 실 백엔드에서 401로 깨지지만 MSW E2E에서는 가짜 그린이 될 수 있다.
    await expect(pageAvatarImg).toHaveAttribute('src', /^blob:/)
  })
})

test.describe('S5 아바타 삭제 (FR-PR-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given 아바타 업로드 완료 When 삭제 클릭 Then 이니셜 폴백으로 복귀', async ({ page }) => {
    // Given. 이 test 안에서 자체적으로 아바타를 업로드해 둔다(데이터 격리 — 다른 test 의존 금지)
    await page.getByLabel(profileLabels.avatar.fileInputLabel).setInputFiles({
      name: 'avatar.png',
      mimeType: 'image/png',
      buffer: Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    })
    const pageAvatarImg = page.locator('main img')
    await expect(pageAvatarImg).toHaveCount(1)

    // When. "아바타 삭제" 클릭
    await page.getByRole('button', { name: profileLabels.avatar.deleteButton, exact: true }).click()

    // Then. <img> 사라지고 이니셜 폴백 복귀(멱등 — 아바타 없어도 204)
    await expect(pageAvatarImg).toHaveCount(0)
    await expect(
      page.locator('main').getByRole('img', { name: ALICE_PROFILE_FIXTURE.displayName }),
    ).toBeVisible()
  })
})

test.describe('S6 저장 검증 실패 — 표시 이름 빈 값 (FR-PR-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  /**
   * F6 클라이언트 가드 — 표시 이름을 비우면 저장 버튼이 즉시 비활성화되고(handleSubmit 내부에도
   * 동일 이중 가드 존재), 실제 브라우저 조작으로는 PATCH 요청 자체가 발생하지 않는다. 즉 실제 사용자
   * 흐름으로는 PROFILE_VALIDATION_FAILED 400 → role=alert 경로에 도달할 수 없다.
   *
   * MSW ServiceWorker가 처리하는 엔드포인트는 page.route()로 우회 오버라이드가 되지 않고(기존
   * import.spec.ts/issue-components.spec.ts/dashboard-share.spec.ts 등에서 이미 확인된 제약),
   * profile-handlers.ts에는 강제 400 시나리오 localStorage 토글이 없다(핸들러 수정은 구현 코드라
   * qa-engineer 범위 밖). 그 400→role=alert 매핑 자체는 ProfileForm.test.tsx(S6/F7)가 이미
   * 단위 레벨에서 검증했으므로, 여기서는 실제로 도달 가능한 클라이언트 가드(F6)를 검증한다.
   */
  test('Given 표시 이름을 비움 When 저장 시도 Then 저장 버튼 비활성화 + PATCH 요청 미발생', async ({
    page,
  }) => {
    let patchCalled = false
    page.on('request', (req) => {
      if (req.url().includes('/api/v1/users/me/profile') && req.method() === 'PATCH') {
        patchCalled = true
      }
    })

    // When. 표시 이름을 빈 값으로 변경
    await page.getByLabel(profileLabels.form.displayNameLabel).fill('')

    // Then. 저장 버튼 비활성화(F6)
    const saveButton = page.getByRole('button', { name: profileLabels.form.saveButton, exact: true })
    await expect(saveButton).toBeDisabled()

    // Then. PATCH 요청이 발생하지 않음 + 성공/에러 메시지 모두 없음(화면 유지)
    expect(patchCalled).toBe(false)
    await expect(page.getByRole('status')).toHaveCount(0)
    await expect(page.getByRole('alert')).toHaveCount(0)
  })
})

test.describe('Header 계정 트리거 (FR-PR-01 F10/S9)', () => {
  test('Given displayName 있는 사용자 When 대시보드 진입 Then 계정 트리거에 displayName 표시 + "프로필" 링크로 이동', async ({
    page,
  }) => {
    // Given. alice 로그인 (whoami 응답의 displayName='김앨리스' — auth-fixtures.aliceUser 정본)
    await loginAsAlice(page)

    // Then. 계정 트리거 aria-label이 username('alice') 대신 displayName('김앨리스')을 우선 표시
    const accountTrigger = page.getByRole('button', {
      name: `${ALICE_PROFILE_FIXTURE.displayName} 계정 메뉴`,
      exact: true,
    })
    await expect(accountTrigger).toBeVisible()

    // When. 드롭다운 열고 "프로필" 링크 클릭
    await accountTrigger.click()
    await page.getByRole('menuitem', { name: '프로필', exact: true }).click()

    // Then. /settings/profile로 이동 + 페이지 렌더
    await expect(page).toHaveURL(/\/settings\/profile$/)
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })
})
