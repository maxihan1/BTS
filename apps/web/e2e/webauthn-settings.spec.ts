// FR-MF-03 E2E — 보안 키 설정 화면 등록/삭제 시나리오 (/settings/mfa)
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지
//   - playwright-getbyrole-exact-strict-mode: exact:true 또는 컨테이너 한정
//   - msw-mutation-stateful-refetch: webauthnStore 가 stateful — mutation 후 refetch 검증
//   - msw-derived-behavior-shared-store-e2e: webauthnStore 는 MSW 브라우저 인메모리 공유 store
//   - e2e-msw-scenario-toggle-localstorage-flag: localStorage 플래그 + addInitScript 패턴
//
// navigator.credentials.create / get 은 webauthn-stub.ts 가 addInitScript 로 교체한다.
// MSW webauthnStore 는 새 브라우저 컨텍스트(새 test)마다 초기화된다.

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { injectWebauthnStub } from './fixtures/webauthn-stub'
import { mfaStrings } from '../src/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 보안 키 등록
//
// Given   alice 로그인 → /settings/mfa 진입
//         webauthnStore 초기값(빈 목록)
//         navigator.credentials.create 가 stub 으로 교체됨
// When    "보안 키 추가" 버튼 클릭
// Then    등록 폼(별칭 입력 필드 + 안내 문구) 표시
// When    별칭 "테스트 키" 입력 → 등록 버튼 클릭
//         → register/start → stub create → register/finish
// Then    목록에 "테스트 키" 항목 표시 (빈 상태 메시지 사라짐)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 보안 키 등록 플로우 (FR-MF-03)', () => {
  test.beforeEach(async ({ page }) => {
    // goto 전에 stub 주입 — 브라우저 컨텍스트가 생성된 직후 실행된다
    await injectWebauthnStub(page)
  })

  test('Given 빈 목록 When 설정 페이지 진입 Then 보안 키 섹션 + 빈 상태 메시지 표시', async ({ page }) => {
    // Given. alice 로그인
    await loginAsAlice(page)

    // When. /settings/mfa 이동
    await page.goto('/settings/mfa')

    // Then. 보안 키 섹션 제목 표시
    await expect(page.getByRole('heading', { name: mfaStrings.webauthnSectionTitle, exact: true })).toBeVisible()

    // Then. 빈 상태 메시지 표시
    await expect(page.getByText(mfaStrings.webauthnEmptyState, { exact: true })).toBeVisible()

    // Then. "보안 키 추가" 버튼 표시
    await expect(page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true })).toBeVisible()
  })

  test('Given 빈 목록 When 추가 버튼 클릭 Then 등록 폼 표시', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await expect(page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true })).toBeVisible()

    // When. "보안 키 추가" 버튼 클릭
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()

    // Then. 등록 폼 — 별칭 입력 필드 + 안내 문구 표시
    await expect(page.getByLabel(mfaStrings.webauthnNameLabel, { exact: true })).toBeVisible()
    await expect(page.getByText(mfaStrings.webauthnRegisteringGuide, { exact: true })).toBeVisible()
  })

  test('Given 등록 폼 When 별칭 입력 → 등록 Then 목록에 새 키 표시', async ({ page }) => {
    // Given. alice 로그인 → /settings/mfa → 등록 폼 진입
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await expect(page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true })).toBeVisible()
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()
    await expect(page.getByLabel(mfaStrings.webauthnNameLabel, { exact: true })).toBeVisible()

    // When. 별칭 입력
    await page.getByLabel(mfaStrings.webauthnNameLabel, { exact: true }).fill('테스트 키')

    // When. 등록 버튼 클릭 → register/start → stub create → register/finish
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()

    // Then. 빈 상태 메시지 사라짐
    await expect(page.getByText(mfaStrings.webauthnEmptyState, { exact: true })).not.toBeVisible()

    // Then. 목록에 등록한 키 이름 표시 (invalidateQueries → GET 목록 refetch)
    await expect(page.getByText('테스트 키', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 보안 키 삭제
//
// Given   S1 등록 완료 후 (webauthnStore 에 키 1개)
// When    목록 항목의 "삭제" 버튼 클릭
// Then    인라인 삭제 확인 박스 표시 (제목 + 본문 + 확인/취소 버튼)
// When    "삭제" 확인 버튼 클릭 → DELETE /api/v1/auth/mfa/webauthn/:id
// Then    목록에서 항목 제거 + 빈 상태 메시지 복귀
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 보안 키 삭제 플로우 (FR-MF-03)', () => {
  test.beforeEach(async ({ page }) => {
    await injectWebauthnStub(page)
  })

  test('Given 등록된 키 When 삭제 버튼 클릭 Then 인라인 확인 박스 표시', async ({ page }) => {
    // Given. alice 로그인 → 키 등록까지
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()
    await page.getByLabel(mfaStrings.webauthnNameLabel, { exact: true }).fill('삭제할 키')
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()
    await expect(page.getByText('삭제할 키', { exact: true })).toBeVisible()

    // When. 목록 항목 내 "삭제" 버튼 클릭
    // <li> 항목을 컨테이너로 한정해 strict mode violation 방지
    const keyItem = page.locator('li').filter({ hasText: '삭제할 키' })
    await keyItem.getByRole('button', { name: mfaStrings.webauthnDeleteButton, exact: true }).click()

    // Then. 인라인 확인 박스 제목 + 본문 표시
    await expect(page.getByText(mfaStrings.webauthnDeleteConfirmTitle, { exact: true })).toBeVisible()
    await expect(page.getByText(mfaStrings.webauthnDeleteConfirmBody, { exact: true })).toBeVisible()

    // Then. 확인/취소 버튼 표시
    await expect(page.getByRole('button', { name: mfaStrings.webauthnDeleteConfirmButton, exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: mfaStrings.webauthnDeleteCancelButton, exact: true })).toBeVisible()
  })

  test('Given 인라인 확인 박스 When 삭제 확인 Then 목록에서 제거 + 빈 상태 복귀', async ({ page }) => {
    // Given. alice 로그인 → 키 등록 → 삭제 확인 박스 진입
    await loginAsAlice(page)
    await page.goto('/settings/mfa')
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()
    await page.getByLabel(mfaStrings.webauthnNameLabel, { exact: true }).fill('삭제할 키')
    await page.getByRole('button', { name: mfaStrings.webauthnAddButton, exact: true }).click()
    await expect(page.getByText('삭제할 키', { exact: true })).toBeVisible()

    // 삭제 버튼 클릭 → 확인 박스 진입
    const keyItem = page.locator('li').filter({ hasText: '삭제할 키' })
    await keyItem.getByRole('button', { name: mfaStrings.webauthnDeleteButton, exact: true }).click()
    await expect(page.getByText(mfaStrings.webauthnDeleteConfirmTitle, { exact: true })).toBeVisible()

    // When. "삭제" 확인 버튼 클릭 → DELETE API 호출
    await page.getByRole('button', { name: mfaStrings.webauthnDeleteConfirmButton, exact: true }).click()

    // Then. 목록에서 제거 (invalidateQueries → GET 목록 refetch)
    await expect(page.getByText('삭제할 키', { exact: true })).not.toBeVisible()

    // Then. 빈 상태 메시지 복귀
    await expect(page.getByText(mfaStrings.webauthnEmptyState, { exact: true })).toBeVisible()
  })
})
