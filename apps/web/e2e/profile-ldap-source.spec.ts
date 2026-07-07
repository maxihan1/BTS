// 표시 이름 필드 LDAP 출처 배지/재설정 E2E — /settings/profile (FR-PR-04)
import { test, expect } from '@playwright/test'
import { loginAsAlice, loginAsBob } from './fixtures/issue-fixtures'
import { profileLabels } from '../src/i18n/profile-labels'

/**
 * 교훈 반영.
 *   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지 — MSW 브라우저 워커 사용
 *     (이 파일은 playwright.config.ts 전역 설정을 그대로 따르며 별도 override 없음)
 *   - playwright-getbyrole-exact-strict-mode: exact:true + `main` 컨테이너로 strict mode violation 회피
 *   - e2e-fixture-whoami-userid-alignment: profile-fixtures.ts의 ALICE_PROFILE_FIXTURE.userId는
 *     auth-fixtures.aliceUser.userId를 그대로 참조(Task 4에서 보장) — loginAsAlice/loginAsBob 재사용
 *   - msw-mutation-stateful-refetch: 재설정(resync) mutation은 profileStore(모듈 레벨 상태)에
 *     실제로 영속돼야 배지가 재조회 후에도 유지된다. page.reload()는 profileStore를 리셋시키므로
 *     절대 사용하지 않는다 — SPA 내부 Link 이동(하드 리로드 없음)으로 재조회를 검증한다.
 *   - 라벨/문구는 profileLabels(정본) 참조 — hardcoded 금지
 *
 * profileStore는 Playwright 브라우저 컨텍스트 단위로 격리된다 — 각 test는 자기 데이터를
 * 스스로 만들고 검증한다(데이터 격리).
 */

test.describe('S1 LDAP 출처 배지 (FR-PR-04)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given LDAP 연결 + 동기화 상태(alice) When /settings/profile 진입 Then 표시 이름 필드에 동기화 배지 노출', async ({
    page,
  }) => {
    // Given. ALICE_PROFILE_FIXTURE.displayNameSource === 'LDAP' && ldapLinked === true (정본 fixture)

    // Then. displayName 필드 인접에 "LDAP에서 동기화됨" 배지가 노출된다
    await expect(
      page.locator('main').getByText(profileLabels.ldapSource.syncedBadge, { exact: true }),
    ).toBeVisible()
  })
})

test.describe('S4 override 후 재설정 (FR-PR-04)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given displayName 직접 편집·저장 When "LDAP 값으로 재설정" 클릭 Then 배지가 동기화 상태로 복귀(재조회 후에도 유지)', async ({
    page,
  }) => {
    // Given. displayName을 직접 편집하고 저장 — PATCH가 displayNameSource를 USER로 전환한다
    await page.getByLabel(profileLabels.form.displayNameLabel).fill('앨리스 (직접 수정)')
    await page.getByRole('button', { name: profileLabels.form.saveButton, exact: true }).click()
    await expect(page.getByRole('status')).toHaveText(profileLabels.status.saveSuccess)

    // Then. 배지가 "직접 편집됨"으로 전환 + "LDAP 값으로 재설정" 버튼 노출
    const overriddenBadge = page
      .locator('main')
      .getByText(profileLabels.ldapSource.overriddenBadge, { exact: true })
    await expect(overriddenBadge).toBeVisible()
    const resyncButton = page.getByRole('button', {
      name: profileLabels.ldapSource.resyncButton,
      exact: true,
    })
    await expect(resyncButton).toBeVisible()

    // When. 재설정 버튼 클릭 — resync mutation 성공 후 invalidateQueries로 profile을 재조회한다
    await resyncButton.click()

    // Then. 배지가 다시 "LDAP에서 동기화됨"으로 복귀 + 재설정 버튼 사라짐
    await expect(
      page.locator('main').getByText(profileLabels.ldapSource.syncedBadge, { exact: true }),
    ).toBeVisible()
    await expect(resyncButton).toHaveCount(0)

    // Then. SPA 내부 재진입(Link 클릭, 하드 리로드 없음)으로도 동기화 상태가 유지됨 — resync
    // mutation이 profileStore에 실제로 영속됐는지 검증한다(msw-mutation-stateful-refetch).
    // page.reload()/page.goto()는 profileStore 모듈 상태를 리셋시키므로 절대 사용하지 않는다.
    await page.getByRole('link', { name: '대시보드', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboards$/)
    await page.getByRole('button', { name: /계정 메뉴$/ }).click()
    await page.getByRole('menuitem', { name: '프로필', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/profile$/)

    await expect(
      page.locator('main').getByText(profileLabels.ldapSource.syncedBadge, { exact: true }),
    ).toBeVisible()
  })
})

test.describe('S5 로컬 사용자 미노출 (FR-PR-04)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsBob(page)
    await page.goto('/settings/profile')
    await expect(
      page.getByRole('heading', { name: profileLabels.page.heading, exact: true }),
    ).toBeVisible()
  })

  test('Given LDAP 미연결 로컬 사용자(bob) When /settings/profile 진입 Then 출처 배지·재설정 버튼 모두 미노출', async ({
    page,
  }) => {
    // Given. BOB_PROFILE_FIXTURE.ldapLinked === false (정본 fixture) — 출처 UI 블록 자체가 렌더되지 않는다

    // Then. 동기화/직접편집 배지 모두 노출되지 않는다
    await expect(
      page.locator('main').getByText(profileLabels.ldapSource.syncedBadge, { exact: true }),
    ).not.toBeVisible()
    await expect(
      page.locator('main').getByText(profileLabels.ldapSource.overriddenBadge, { exact: true }),
    ).not.toBeVisible()

    // Then. "LDAP 값으로 재설정" 버튼도 노출되지 않는다
    await expect(
      page.getByRole('button', { name: profileLabels.ldapSource.resyncButton, exact: true }),
    ).not.toBeVisible()
  })
})
