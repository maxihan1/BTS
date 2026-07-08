// 사용자 환경설정(테마/날짜포맷) E2E — 테마 지속성·date_format 반영·설정 저장 왕복 (FR-PF-01 Task 9)
import { test, expect } from '@playwright/test'
import { loginAsAlice, i18nLabels } from './fixtures/issue-fixtures'

/**
 * 교훈 반영.
 *   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — MSW 브라우저 워커 사용
 *     (playwright.config.ts에 이미 미설정, 기본값 'allow' 유지).
 *   - msw-mutation-stateful-refetch: preferences-handlers.ts의 PATCH는 auth-fixtures의
 *     AUTH_USERS 객체를 직접 mutate하고, whoamiHandler가 그 값을 그대로 반영한다.
 *   - ★ 이 store는 순수 모듈 상태라 page.reload()/page.goto() 하드 네비게이션에는 리셋된다.
 *     하지만 앱은 whoami를 부팅 시 재조회하지 않고 authStore의 sessionStorage 영속(`bts.auth`,
 *     zustand persist)에서만 user를 복원한다 — 그래서 "새로고침 후에도 테마/날짜포맷이
 *     유지된다"는 이 mock의 상태가 아니라 (a) `bts.theme` localStorage FOUC 프리하이드레이션
 *     (index.html 인라인 스크립트) (b) authStore sessionStorage persist라는 실제 클라이언트
 *     영속 코드로 검증된다. 두 경로 모두 실 프로덕션 코드라 mock 우회 걱정 없이 하드
 *     네비게이션(page.reload/page.goto)을 그대로 사용해도 된다.
 *   - playwright-getbyrole-exact-strict-mode: combobox/option/menuitem 모두 exact:true로 한정.
 *   - i18n 정본(i18nLabels.issueDetail.createdAtLabel) 재사용 — 이슈 상세 생성일 렌더 검증.
 *   - 데이터 격리 — 각 test는 alice로 새로 로그인(새 브라우저 컨텍스트)해 자기 상태를 스스로
 *     설정한다. auth-fixtures의 AUTH_USERS는 페이지 로드마다(모듈 재평가) 초기화되므로
 *     테스트 간 leak이 없다.
 */

const PREFERENCES_HEADING = '환경 설정'
const ACCOUNT_TRIGGER = /계정 메뉴$/

async function gotoPreferences(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/settings/preferences')
  await expect(
    page.getByRole('heading', { name: PREFERENCES_HEADING, exact: true }),
  ).toBeVisible()
}

test.describe('S1 테마 지속 (FR-PF-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await gotoPreferences(page)
  })

  test('Given 다크 테마 선택 When 새로고침·다른 라우트 이동 Then <html>에 dark 클래스가 유지된다', async ({
    page,
  }) => {
    // When. 테마를 '다크'로 변경 (선택 즉시 PATCH 저장)
    await page.getByRole('combobox', { name: '테마', exact: true }).click()
    await page.getByRole('option', { name: '다크', exact: true }).click()

    // Then. PATCH 성공 후 <html>에 .dark 클래스가 적용된다
    await expect(page.locator('html')).toHaveClass('dark')

    // When. 하드 새로고침 — bts.theme localStorage 프리하이드레이션(FOUC 방지 인라인 스크립트) +
    // authStore sessionStorage persist(`bts.auth`)로 React 마운트 전후 모두 dark가 유지돼야 한다
    await page.reload()

    // Then. 새로고침 후에도 다크 유지
    await expect(page.locator('html')).toHaveClass('dark')

    // When. 다른 라우트로 SPA 내부 이동(Header 링크 클릭, 하드 리로드 아님)
    await page.getByRole('link', { name: '대시보드', exact: true }).click()
    await expect(page).toHaveURL(/\/dashboards$/)

    // Then. 라우트 이동 후에도 다크 유지
    await expect(page.locator('html')).toHaveClass('dark')
  })
})

test.describe('S2 date_format 반영 (FR-PF-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
  })

  test('Given 날짜 표시 형식=미국식 선택 When 저장 Then 설정 프리뷰가 즉시 MM/DD/YYYY로 갱신된다', async ({
    page,
  }) => {
    await gotoPreferences(page)

    // Given. 기본 프리셋(iso, EC1)의 프리뷰가 초기 렌더된다
    await expect(page.getByText('미리보기: 2026-07-07', { exact: true })).toBeVisible()

    // When. 날짜 표시 형식을 '미국식'으로 변경
    await page.getByRole('combobox', { name: '날짜 표시 형식', exact: true }).click()
    await page.getByRole('option', { name: '미국식 (07/07/2026)', exact: true }).click()

    // Then. 프리뷰가 즉시 미국식(MM/DD/YYYY)으로 갱신된다
    await expect(page.getByText('미리보기: 07/07/2026', { exact: true })).toBeVisible()
  })

  test('Given date_format=us 저장 When 이슈 상세 페이지 재진입 Then 생성일이 미국식(MM/DD/YYYY)으로 반영된다', async ({
    page,
  }) => {
    // Given. 기본값(iso)일 때 ATLAS-1 생성일은 ISO(YYYY-MM-DD HH:mm) 형식으로 렌더된다.
    // 라벨 "생성"의 바로 다음 형제 <p>가 값이다(테스트 전용 selector — data-testid 없이도
    // 구조적으로 안정적, IssueMetaPanel.tsx의 라벨/값 <p> 형제 배치를 그대로 활용).
    await page.goto('/issues/ATLAS-1')
    const createdAtValue = page
      .locator('main')
      .getByText(i18nLabels.issueDetail.createdAtLabel, { exact: true })
      .locator('xpath=following-sibling::p[1]')
    await expect(createdAtValue).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)

    // When. 환경설정에서 날짜 표시 형식을 '미국식'으로 저장
    await gotoPreferences(page)
    await page.getByRole('combobox', { name: '날짜 표시 형식', exact: true }).click()
    await page.getByRole('option', { name: '미국식 (07/07/2026)', exact: true }).click()
    await expect(page.getByText('미리보기: 07/07/2026', { exact: true })).toBeVisible()

    // Then. 이슈 상세로 재진입(하드 네비게이션이어도 authStore의 sessionStorage 영속값을
    // 그대로 복원 — whoami 재조회 없이도 dateFormat='us'가 유지된다) 시 생성일이
    // 미국식(MM/DD/YYYY)으로 렌더된다
    await page.goto('/issues/ATLAS-1')
    await expect(createdAtValue).toHaveText(/^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}$/)
  })
})

test.describe('S3 설정 저장 왕복 (FR-PF-01)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await gotoPreferences(page)
  })

  test('Given 테마를 다크로 변경 When 저장 Then PATCH 요청이 발생하고 설정 페이지 재진입 시 값이 유지된다', async ({
    page,
  }) => {
    let patchBody: unknown
    page.on('request', (request) => {
      if (request.method() === 'PATCH' && request.url().includes('/api/v1/users/me/preferences')) {
        patchBody = request.postDataJSON()
      }
    })

    // When. 테마를 '다크'로 변경(선택 즉시 PATCH)
    const themeCombobox = page.getByRole('combobox', { name: '테마', exact: true })
    await themeCombobox.click()
    await page.getByRole('option', { name: '다크', exact: true }).click()

    // Then. mutation 진행 중 disabled였던 셀렉터가 다시 활성화될 때까지 대기(PATCH 완료 신호)한
    // 뒤, 정확히 {theme:'dark'} 바디로 PATCH가 호출됐는지 확인한다
    await expect(themeCombobox).toBeEnabled()
    expect(patchBody).toEqual({ theme: 'dark' })

    // Then. whoami 재조회가 authStore를 실제로 갱신했는지 검증 — SPA 내부 이동(프로필→환경설정)
    // 으로 재진입해도 '다크'가 유지된다(로컬 컴포넌트 상태가 아니라 저장값임을 증명,
    // msw-mutation-stateful-refetch)
    await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
    await page.getByRole('menuitem', { name: '프로필', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/profile$/)

    await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
    await page.getByRole('menuitem', { name: '환경 설정', exact: true }).click()
    await expect(page).toHaveURL(/\/settings\/preferences$/)

    await expect(page.getByRole('combobox', { name: '테마', exact: true })).toHaveText('다크')
  })
})
