// FR-API-03 PR4 D7 E2E — 아웃바운드 Webhook 구독 관리자 UI (SYSTEM_ADMIN 전용)
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로 SYSTEM_ADMIN 토글
//   - playwright-getbyrole-exact-strict-mode: 행마다 중복되는 버튼 텍스트는 name-scoped aria-label로 한정
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지(전역 playwright.config 그대로 사용)
//   - msw-derived-behavior-shared-store-e2e / EC-7: MSW 상태 변화 검증은 SPA 내부 라우팅으로만 —
//     webhook store는 시드/리셋 헤더가 없는 stateful Map(webhook-fixtures.ts)이라 page.reload()/goto
//     재진입은 store를 초기화할 수 있어(가짜 그린) 매 시나리오가 자신의 데이터를 UI로 직접 생성한다.
//   - e2e-fixture-whoami-userid-alignment: admin fixture는 isSystemAdmin=true로 whoami와 정합
//   - worktree-stale-base-rebase-and-e2e-msw-traps: Select/드롭다운 로딩 대기 후 옵션 클릭(로그인 2단계)

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY — audit-logs.spec.ts / notification-policies.spec.ts 동형 */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

/** Webhook 관리 목록 페이지 URL */
const PAGE_URL = '/admin/webhooks'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SYSTEM_ADMIN alice 로그인 (audit-logs.spec.ts:27-32 동형)
// ─────────────────────────────────────────────────────────────────────────────

async function loginAsSystemAdmin(page: Page): Promise<void> {
  await page.addInitScript((key: string) => {
    window.localStorage.setItem(key, 'true')
  }, LS_IS_SYSTEM_ADMIN)
  await loginAsAlice(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — "새 구독" 폼으로 webhook 1건을 생성한다(Given 전제 데이터 생성용).
//
// webhook-fixtures.ts의 store는 시드/리셋 메커니즘이 없는 빈 Map으로 시작하므로(EC-7),
// 편집/이력/삭제 시나리오는 먼저 이 헬퍼로 자신의 데이터를 만든다(데이터 격리 원칙).
// ─────────────────────────────────────────────────────────────────────────────

async function createWebhookViaUi(page: Page, name: string, url: string): Promise<void> {
  await page.getByRole('button', { name: '새 구독', exact: true }).click()

  const form = page.locator('form')
  await expect(form).toBeVisible()

  await form.getByLabel('이름', { exact: true }).fill(name)
  await form.getByLabel('URL', { exact: true }).fill(url)
  await form.getByRole('checkbox', { name: '이슈 생성', exact: true }).check()
  await form.getByRole('button', { name: '생성', exact: true }).click()

  // 성공 시 formState가 closed로 돌아가 폼이 사라진다.
  await expect(form).not.toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — Header 게이팅 + 목록 페이지 진입
//
// Given   SYSTEM_ADMIN alice 로 로그인
// When    Header "관리 메뉴" nav의 "Webhook" 링크 클릭
// Then    /admin/webhooks 진입, 페이지 제목 표시, 빈 목록 상태 문구 + "새 구독" 버튼 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 Header 게이팅 + 목록 페이지 진입 (FR-API-03)', () => {
  test('Given SYSTEM_ADMIN 로그인 When Webhook 링크 클릭 Then 목록 페이지 진입', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // Then. Header "관리 메뉴" nav 노출 확인
    const adminNav = page.getByRole('navigation', { name: '관리 메뉴' })
    await expect(adminNav).toBeVisible()

    // Then. nav 내부에 "Webhook" 링크 노출 — strict mode 회피 위해 nav 컨테이너로 한정
    const webhookLink = adminNav.getByRole('link', { name: 'Webhook', exact: true })
    await expect(webhookLink).toBeVisible()

    // When. "Webhook" 링크 클릭 → SPA 내부 이동
    await webhookLink.click()
    await page.waitForURL(`**${PAGE_URL}`)
    expect(new URL(page.url()).pathname).toBe(PAGE_URL)

    // Then. 페이지 제목 표시
    await expect(page.getByRole('heading', { name: '아웃바운드 Webhook', exact: true })).toBeVisible()

    // Then. "새 구독" 버튼 표시
    await expect(page.getByRole('button', { name: '새 구독', exact: true })).toBeVisible()

    // Then. 빈 store이므로 목록 영역이 빈 상태 문구를 표시
    await expect(page.getByText('등록된 Webhook이 없습니다', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 새 구독 생성 → 목록에 새 행 반영
//
// Given   SYSTEM_ADMIN alice → /admin/webhooks 진입 (빈 목록)
// When    "새 구독" → name/url 입력 + "이슈 생성" 이벤트 체크 + 저장
// Then    목록에 새 행 1건 반영 (이름/URL 표시)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 새 구독 생성 → 목록 행 반영 (FR-API-03)', () => {
  test('Given 빈 목록 When 폼 입력 후 생성 Then 목록에 새 행 반영', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 목록 페이지 진입
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    await expect(page.getByText('등록된 Webhook이 없습니다', { exact: true })).toBeVisible()

    // When. "새 구독" 폼으로 webhook 생성
    const name = 'Slack 알림 webhook E2E'
    const url = 'https://hooks.example.com/e2e-create'
    await createWebhookViaUi(page, name, url)

    // Then. 목록에 새 행 1건 반영 — row aria-label=webhook.name(WebhookRow) 기준
    const row = page.getByRole('row', { name, exact: true })
    await expect(row).toBeVisible()
    await expect(row.getByText(url)).toBeVisible()

    // Then. 빈 상태 문구는 더 이상 표시되지 않음
    await expect(page.getByText('등록된 Webhook이 없습니다', { exact: true })).not.toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 행 편집 → 필드 변경 저장 → 반영
//
// Given   SYSTEM_ADMIN alice → /admin/webhooks 진입 → webhook 1건 생성(Given 전제)
// When    행 "편집" 클릭 → 이름 변경 → 저장
// Then    목록에 변경된 이름으로 반영 (기존 이름 행은 사라짐)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 행 편집 → 필드 변경 저장 반영 (FR-API-03)', () => {
  test('Given 생성된 webhook 행 When 편집 후 이름 변경 저장 Then 변경된 이름으로 반영', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 목록 진입 → webhook 1건 생성
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    const originalName = '편집 대상 webhook'
    await createWebhookViaUi(page, originalName, 'https://hooks.example.com/e2e-edit')
    await expect(page.getByRole('row', { name: originalName, exact: true })).toBeVisible()

    // When. 행 "편집" 버튼 클릭 (aria-label = `${name} 편집`, WebhookTable.tsx)
    await page.getByRole('button', { name: `${originalName} 편집`, exact: true }).click()

    const form = page.locator('form')
    await expect(form).toBeVisible()

    // When. 이름 필드 프리필 확인 후 변경
    const nameInput = form.getByLabel('이름', { exact: true })
    await expect(nameInput).toHaveValue(originalName)
    const updatedName = '편집 완료된 webhook'
    await nameInput.fill(updatedName)

    // When. "저장" 버튼 클릭(수정 모드 submit 라벨)
    await form.getByRole('button', { name: '저장', exact: true }).click()
    await expect(form).not.toBeVisible()

    // Then. 목록에 변경된 이름으로 반영
    await expect(page.getByRole('row', { name: updatedName, exact: true })).toBeVisible()

    // Then. 기존 이름 행은 더 이상 없음
    await expect(page.getByRole('row', { name: originalName, exact: true })).not.toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 발송 이력 조회 → 목록으로 복귀
//
// Given   SYSTEM_ADMIN alice → /admin/webhooks 진입 → webhook 1건 생성(Given 전제)
// When    행 "발송 이력" 클릭
// Then    /admin/webhooks/{id}/deliveries 이동 + 이력 페이지 표시
// When    "← 목록으로" 클릭
// Then    /admin/webhooks 복귀 + 생성한 webhook 행 여전히 표시(SPA 내부이동, 상태 보존)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 발송 이력 조회 → 목록으로 복귀 (FR-API-03)', () => {
  test('Given 생성된 webhook 행 When 발송 이력 클릭 Then 이력 페이지 표시 후 목록으로 복귀', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 목록 진입 → webhook 1건 생성
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    const name = '이력 조회 대상 webhook'
    await createWebhookViaUi(page, name, 'https://hooks.example.com/e2e-deliveries')
    await expect(page.getByRole('row', { name, exact: true })).toBeVisible()

    // When. 행 "발송 이력" 버튼 클릭 (aria-label = `${name} 발송 이력`, WebhookTable.tsx) → SPA 내부 이동
    await page.getByRole('button', { name: `${name} 발송 이력`, exact: true }).click()
    await page.waitForURL(/\/admin\/webhooks\/[0-9a-f-]+\/deliveries$/)

    // Then. 이력 페이지 표시 — 제목 + 빈 이력 상태 문구(직접 생성한 신규 구독이라 발송 이력 없음)
    await expect(page.getByRole('heading', { name: 'Webhook 발송 이력', exact: true })).toBeVisible()
    await expect(page.getByText('발송 이력이 없습니다', { exact: true })).toBeVisible()

    // When. "← 목록으로" 링크 클릭 → SPA 내부 이동(EC-7, reload 아님)
    await page.getByRole('link', { name: '← 목록으로', exact: true }).click()
    await page.waitForURL(`**${PAGE_URL}`)
    expect(new URL(page.url()).pathname).toBe(PAGE_URL)

    // Then. 복귀 후에도 생성한 webhook 행이 여전히 표시(store 영속 — 같은 페이지 세션 내 SPA 이동)
    await expect(page.getByRole('row', { name, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 행 삭제(인라인 확인) → 목록에서 제거
//
// Given   SYSTEM_ADMIN alice → /admin/webhooks 진입 → webhook 1건 생성(Given 전제)
// When    행 "삭제" → 인라인 "확인" 클릭
// Then    목록에서 제거 (빈 상태 문구 재노출)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 행 삭제(인라인 확인) → 목록에서 제거 (FR-API-03)', () => {
  test('Given 생성된 webhook 행 When 삭제 확인 클릭 Then 목록에서 제거', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 → 목록 진입 → webhook 1건 생성
    await loginAsSystemAdmin(page)
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)
    const name = '삭제 대상 webhook'
    await createWebhookViaUi(page, name, 'https://hooks.example.com/e2e-delete')
    await expect(page.getByRole('row', { name, exact: true })).toBeVisible()

    // When. 행 "삭제" 버튼 클릭 → 인라인 확인 상태로 전환 (aria-label = `${name} 삭제`, WebhookTable.tsx)
    await page.getByRole('button', { name: `${name} 삭제`, exact: true }).click()

    // 인라인 확인 버튼 표시
    const confirmButton = page.getByRole('button', { name: `${name} 삭제 확인`, exact: true })
    await expect(confirmButton).toBeVisible()

    // When. "확인" 클릭 → onDelete 호출 → 목록 refetch
    await confirmButton.click()

    // Then. 목록에서 제거 — 행 사라짐 + 빈 상태 문구 재노출
    await expect(page.getByRole('row', { name, exact: true })).not.toBeVisible()
    await expect(page.getByText('등록된 Webhook이 없습니다', { exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6 — 비관리자: 관리 메뉴 미노출 + /admin/webhooks 직접 진입 차단
//
// Given   일반 사용자 alice 로 로그인 (isSystemAdmin:false, 기본 fixture)
// When 1  Header 확인
// Then 1  "관리 메뉴" nav 미노출 + "Webhook" 링크 미노출
// When 2  /admin/webhooks 직접 goto
// Then 2  requireSystemAdmin 가드 → /dashboard redirect
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S6 비관리자 미노출 + 차단 (FR-API-03)', () => {
  test('Given 일반 사용자 로그인 When 관리 메뉴 확인 Then nav 미노출 + 직접 접근 시 redirect', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false (기본 fixture, LS 플래그 없음)
    await loginAsAlice(page)

    // Then (When 1). Header "관리 메뉴" nav 미노출
    await expect(page.getByRole('navigation', { name: '관리 메뉴' })).not.toBeVisible()

    // Then (When 1). "Webhook" 링크 미노출 (nav 없으므로 DOM에도 없음)
    await expect(page.getByRole('link', { name: 'Webhook', exact: true })).not.toBeVisible()

    // When 2. /admin/webhooks 직접 goto
    await page.goto(PAGE_URL)

    // Then 2. requireSystemAdmin 가드 → /dashboard redirect
    await page.waitForURL('**/dashboard')
    expect(new URL(page.url()).pathname).toBe('/dashboard')
  })
})
