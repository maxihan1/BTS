// 사용자 상태 메시지 E2E — 계정 메뉴에서 상태 설정/해제 + 헤더 아바타 배지 whoami 연동 (FR-PR-02 D7)
import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { statusLabels } from '../src/i18n/status-labels'

/**
 * 교훈 반영.
 *   - e2e-msw-serviceworker-block: serviceWorkers:'block' 금지 — MSW 브라우저 워커 사용.
 *   - msw-mutation-stateful-refetch: statusStore(모듈 상태)는 page.reload()로 리셋되므로 하드 리로드 금지.
 *     저장 후 refreshWhoami()가 whoami를 재조회해 authStore.user를 갱신 → 헤더 배지 즉시 반영을 검증한다.
 *   - i18n 정본(status-labels.ts) import — 값 hardcoded 금지(profile.spec.ts 선례).
 *   - ui-pr-defer-e2e-regression-latent: 이 spec은 whoami view-layer(+statusEmoji/statusText) 확장의
 *     헤더 연동을 커버한다. 기존 profile.spec.ts(계정 트리거 aria-label exact) 회귀 방지는 alice 초기
 *     상태가 all-null(ALICE_STATUS_FIXTURE)이라 aria-label이 '김앨리스 계정 메뉴'로 유지됨으로 보장된다.
 *
 * statusStore는 Playwright 브라우저 컨텍스트 단위로 격리된다 — 각 test가 자기 상태를 스스로 설정/검증한다.
 */

const ACCOUNT_TRIGGER = /계정 메뉴$/
const EMOJI = '🌴'
const STATUS_TEXT = '휴가 중'

/** 계정 메뉴를 열고 "상태 설정"을 클릭해 StatusModal을 연다. */
async function openStatusModal(page: import('@playwright/test').Page): Promise<void> {
  await page.getByRole('button', { name: ACCOUNT_TRIGGER }).click()
  await page.getByRole('menuitem', { name: statusLabels.title, exact: true }).click()
  await expect(page.getByRole('heading', { name: statusLabels.title })).toBeVisible()
}

test.describe('FR-PR-02 상태 메시지', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAlice(page)
    await expect(page.getByRole('button', { name: ACCOUNT_TRIGGER })).toBeVisible()
  })

  test('S1 상태 설정 → 헤더 아바타에 상태 이모지 배지 + 트리거 라벨에 상태 텍스트', async ({ page }) => {
    await openStatusModal(page)

    await page.getByLabel(statusLabels.emojiLabel).fill(EMOJI)
    await page.getByLabel(statusLabels.textLabel).fill(STATUS_TEXT)
    await page.getByRole('button', { name: statusLabels.saveButton, exact: true }).click()

    // Then. 저장 성공 → 헤더 배지에 이모지 노출 + 계정 트리거 aria-label에 상태 텍스트 병기(whoami 재조회 반영)
    await expect(page.getByText(EMOJI)).toBeVisible()
    await expect(
      page.getByRole('button', { name: new RegExp(`${STATUS_TEXT}.*계정 메뉴$`) }),
    ).toBeVisible()
  })

  test('S2 상태 해제 → 헤더 배지가 사라진다', async ({ page }) => {
    // Given. 먼저 상태를 설정(데이터 격리 — 이 test가 자체적으로 만든다)
    await openStatusModal(page)
    await page.getByLabel(statusLabels.emojiLabel).fill(EMOJI)
    await page.getByLabel(statusLabels.textLabel).fill(STATUS_TEXT)
    await page.getByRole('button', { name: statusLabels.saveButton, exact: true }).click()
    await expect(page.getByText(EMOJI)).toBeVisible()

    // When. 다시 열어 "상태 지우기"
    await openStatusModal(page)
    await page.getByRole('button', { name: statusLabels.clearButton, exact: true }).click()

    // Then. 배지 사라짐(whoami 재조회 → statusEmoji null)
    await expect(page.getByText(EMOJI)).toHaveCount(0)
  })

  test('S3 만료 프리셋을 선택해 저장해도 활성(미래 만료) 상태로 배지 표시', async ({ page }) => {
    await openStatusModal(page)

    await page.getByLabel(statusLabels.emojiLabel).fill(EMOJI)
    await page.getByLabel(statusLabels.textLabel).fill(STATUS_TEXT)
    await page.getByLabel(statusLabels.expiryLabel).selectOption('1h')
    await page.getByRole('button', { name: statusLabels.saveButton, exact: true }).click()

    // Then. 1시간 뒤 만료(미래)라 활성 → 배지 표시
    await expect(page.getByText(EMOJI)).toBeVisible()
  })
})
