// 부재중(Out of Office) 설정 E2E — 계정 드롭다운에서 설정/해제 + 헤더 배지 whoami 연동 (FR-PR-03 D7)
import { test, expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { oooLabels } from '../src/i18n/ooo-labels'
import { aliceUser } from '../src/mocks/auth-fixtures'

/**
 * 교훈 반영.
 *   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — MSW 브라우저 워커 사용.
 *   - msw-mutation-stateful-refetch: oooStore(모듈 상태)는 page.reload()로 리셋되므로 하드 리로드 금지.
 *     저장/해제 후 refreshWhoami()가 whoami를 재조회해 authStore.user를 갱신 → 헤더 배지 즉시 반영을 검증한다.
 *   - i18n 정본(ooo-labels.ts) import — 값 hardcoded 금지(status.spec.ts/profile.spec.ts 선례).
 *   - playwright-getbyrole-exact-strict-mode: "부재중 설정"이 계정 드롭다운 메뉴 아이템 라벨과
 *     모달 제목에 동시에 쓰이므로(oooLabels.accountMenuItem === oooLabels.title), role로 구분
 *     (menuitem vs heading)하고 모달 내부 요소는 `dialog` role 컨테이너로 스코프한다.
 *   - ui-pr-defer-e2e-regression-latent: 이 spec은 whoami view-layer(+oooActive/oooUntil) 확장의
 *     헤더 연동을 커버한다. S4는 미설정 상태에서 optional 필드가 기존 계정 트리거 라벨을
 *     깨지 않는지(Zod mock fanout 방어)를 검증한다.
 *
 * oooStore는 Playwright 브라우저 컨텍스트 단위로 격리된다 — 각 test가 자기 상태를 스스로 설정/검증한다.
 */

const ACCOUNT_TRIGGER = /계정 메뉴$/

/**
 * `Date`를 `<input type="datetime-local">`이 요구하는 "YYYY-MM-DDTHH:mm"(로컬) 형태로 변환한다.
 * 구현(`ooo-datetime.ts`)과 독립적으로 E2E 자체에서 값을 구성해 블랙박스 검증을 유지한다.
 */
function toDatetimeLocalValue(date: Date): string {
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** `toDatetimeLocalValue`의 앞 10자(YYYY-MM-DD) — Header 복귀일 표시(`formatOooReturnDate`)와 동일 포맷. */
function toLocalDateOnly(date: Date): string {
  return toDatetimeLocalValue(date).slice(0, 10)
}

/** 계정 드롭다운을 열고 "부재중 설정"을 클릭해 OooModal을 연다. 모달은 `dialog` role로 스코프해 반환한다. */
async function openOooModal(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
  await page.getByRole('menuitem', { name: oooLabels.accountMenuItem, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: oooLabels.title, exact: true })).toBeVisible()
  return dialog
}

test.describe('FR-PR-03 부재중(Out of Office)', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await expect(page.getByRole('button', { name: ACCOUNT_TRIGGER })).toBeVisible()
  })

  test('S1 부재중 설정(기간+대리자+메시지) → 헤더 계정 메뉴에 "부재중" 배지 표시', async ({ page }) => {
    const dialog = await openOooModal(page)

    // Given. 지금 활성(과거 시작~미래 종료)인 기간 — 저장 즉시 active:true가 되도록 구성
    const start = new Date(Date.now() - 24 * 60 * 60 * 1000) // 어제
    const end = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000) // 7일 뒤
    const expectedReturnDate = toLocalDateOnly(end)

    // When. 기간 + 대리자(bob) 검색 선택 + 메시지 입력 후 저장
    await dialog.getByLabel(oooLabels.startsAtLabel).fill(toDatetimeLocalValue(start))
    await dialog.getByLabel(oooLabels.endsAtLabel).fill(toDatetimeLocalValue(end))
    await dialog.getByLabel(oooLabels.delegateLabel).fill('bob')
    await dialog.getByRole('button', { name: 'bob', exact: true }).click()
    await dialog.getByLabel(oooLabels.messageLabel).fill('휴가 중입니다. 급한 건 bob에게.')
    await dialog.getByRole('button', { name: oooLabels.saveButton, exact: true }).click()

    // Then. 저장 성공 → 모달 닫힘 + 헤더 계정 메뉴에 배지(+복귀일 tooltip) 표시(whoami 재조회 반영)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    const badge = page.getByText(oooLabels.headerBadge, { exact: true })
    await expect(badge).toBeVisible()
    await expect(badge).toHaveAttribute(
      'title',
      `${oooLabels.headerBadgeReturnPrefix}: ${expectedReturnDate}`,
    )
    await expect(
      page.getByRole('button', {
        name: `${aliceUser.displayName}, ${oooLabels.headerBadge}(${oooLabels.headerBadgeReturnPrefix} ${expectedReturnDate}) 계정 메뉴`,
        exact: true,
      }),
    ).toBeVisible()
  })

  test('S2 부재중 해제 → 헤더 배지가 사라진다', async ({ page }) => {
    // Given. 먼저 부재중을 설정(데이터 격리 — 이 test가 자체적으로 만든다, status.spec.ts S2 선례)
    const setupDialog = await openOooModal(page)
    const start = new Date(Date.now() - 24 * 60 * 60 * 1000)
    const end = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)
    await setupDialog.getByLabel(oooLabels.startsAtLabel).fill(toDatetimeLocalValue(start))
    await setupDialog.getByLabel(oooLabels.endsAtLabel).fill(toDatetimeLocalValue(end))
    await setupDialog.getByRole('button', { name: oooLabels.saveButton, exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByText(oooLabels.headerBadge, { exact: true })).toBeVisible()

    // When. 다시 열어 "부재중 해제"
    const clearDialog = await openOooModal(page)
    await clearDialog.getByRole('button', { name: oooLabels.clearButton, exact: true }).click()

    // Then. 모달 닫힘 + 배지 사라짐(whoami 재조회 → oooActive:false)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByText(oooLabels.headerBadge, { exact: true })).toHaveCount(0)
    await expect(
      page.getByRole('button', { name: `${aliceUser.displayName} 계정 메뉴`, exact: true }),
    ).toBeVisible()
  })

  test('S3 검증 에러(종료<=시작) → alert 표시 + 모달 유지 + 배지 미표시', async ({ page }) => {
    const dialog = await openOooModal(page)

    // Given/When. 종료 시각이 시작 시각보다 앞서도록 입력(EC2 위반)
    const start = new Date(Date.now() + 24 * 60 * 60 * 1000) // 내일
    const end = new Date(start.getTime() - 60 * 60 * 1000) // 시작보다 1시간 전
    await dialog.getByLabel(oooLabels.startsAtLabel).fill(toDatetimeLocalValue(start))
    await dialog.getByLabel(oooLabels.endsAtLabel).fill(toDatetimeLocalValue(end))

    let dialogMessage: string | null = null
    page.once('dialog', (d) => {
      dialogMessage = d.message()
      void d.dismiss()
    })
    await dialog.getByRole('button', { name: oooLabels.saveButton, exact: true }).click()

    // Then. window.alert() 발생 + 모달은 닫히지 않고 유지 + 헤더 배지 없음(저장 안 됨)
    await expect.poll(() => dialogMessage).toBe(oooLabels.errorMessage)
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByText(oooLabels.headerBadge, { exact: true })).toHaveCount(0)
  })

  test('S4 whoami mock fanout 회귀 — 부재중 미설정 시 배지 없음 + 계정 트리거 라벨 정상', async ({
    page,
  }) => {
    // Given/When. 로그인 직후(부재중 미설정 기본 상태) — beforeEach의 로그인이 이미 완료됨.
    // Then. oooActive/oooUntil이 optional로 추가돼도 기존 whoami 파싱·Header 렌더가 깨지지 않는다.
    await expect(
      page.getByRole('button', { name: `${aliceUser.displayName} 계정 메뉴`, exact: true }),
    ).toBeVisible()
    await expect(page.getByText(oooLabels.headerBadge, { exact: true })).toHaveCount(0)
  })
})
