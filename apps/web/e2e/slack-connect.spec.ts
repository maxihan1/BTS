// FR-SL-01 D6/D7 Task 8 E2E — /admin/slack Slack 연결 관리자 페이지 (SYSTEM_ADMIN 전용)
//
// 교훈 반영.
//   - e2e-msw-scenario-toggle-localstorage-flag: addInitScript + localStorage 플래그로
//     SYSTEM_ADMIN 여부 / Slack 연결 상태를 토글 (핸들러 임시 교체 대신)
//   - playwright-getbyrole-exact-strict-mode: CardTitle("Slack 연결")과 페이지 h1 제목이
//     같은 문자열을 공유하므로 getByRole('heading', ...)로 h1만 한정하거나, 버튼/문구처럼
//     고유한 텍스트로만 단정한다
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지(전역 playwright.config 그대로 사용)
//   - e2e-fixture-whoami-userid-alignment: admin fixture는 isSystemAdmin=true로 whoami와 정합
//   - 버튼 클릭 시 window.location.assign으로 외부 slack.com 이동은 실제로 따라가지 않는다
//     (시나리오 3/4는 콜백 결과 URL 직접 진입으로 검증, 시나리오 1/2는 버튼 노출까지만 검증)

import { test, expect, type Page } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { E2E_SLACK_CONNECTED_KEY, MOCK_SLACK_TEAM_NAME } from '../src/mocks/slack-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** auth-handlers.ts E2E_IS_SYSTEM_ADMIN_KEY — webhook.spec.ts / audit-logs.spec.ts 동형 */
const LS_IS_SYSTEM_ADMIN = '__bts_e2e_is_system_admin'

/** Slack 연결 관리자 페이지 URL */
const PAGE_URL = '/admin/slack'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — SYSTEM_ADMIN alice 로그인 (webhook.spec.ts 동형), 필요 시 Slack 연결 상태도 함께 토글
// ─────────────────────────────────────────────────────────────────────────────

async function loginAsSystemAdmin(page: Page, options?: { readonly slackConnected?: boolean }): Promise<void> {
  await page.addInitScript(
    ({ adminKey, connectedKey, connected }) => {
      window.localStorage.setItem(adminKey, 'true')
      if (connected) {
        window.localStorage.setItem(connectedKey, 'true')
      }
    },
    { adminKey: LS_IS_SYSTEM_ADMIN, connectedKey: E2E_SLACK_CONNECTED_KEY, connected: options?.slackConnected === true },
  )
  await loginAsAlice(page)
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 관리자 + 미연결 진입 → "Slack에 연결" 버튼 노출
//
// Given   SYSTEM_ADMIN alice 로그인, Slack 미연결(기본 fixture)
// When    /admin/slack 진입
// Then    "Slack에 연결" 버튼 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 관리자 + 미연결 진입 (FR-SL-01)', () => {
  test('Given SYSTEM_ADMIN + 미연결 When /admin/slack 진입 Then "Slack에 연결" 버튼 노출', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 (Slack 미연결 — 기본 fixture)
    await loginAsSystemAdmin(page)

    // When. /admin/slack 진입
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)

    // Then. 미연결 안내 문구 + "Slack에 연결" 버튼 노출
    await expect(page.getByText('Slack에 연결되어 있지 않습니다.', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Slack에 연결', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 관리자 + 연결됨 토글 → 워크스페이스 이름 + "다시 연결" 버튼 노출
//
// Given   SYSTEM_ADMIN alice 로그인, Slack 연결됨(localStorage 토글, Acme Corp)
// When    /admin/slack 진입
// Then    "Acme Corp" 워크스페이스 이름 + "다시 연결" 버튼 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 관리자 + 연결됨 진입 (FR-SL-01)', () => {
  test('Given SYSTEM_ADMIN + 연결됨 When /admin/slack 진입 Then 워크스페이스 이름 + "다시 연결" 버튼 노출', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인 (Slack 연결됨 — localStorage 토글)
    await loginAsSystemAdmin(page, { slackConnected: true })

    // When. /admin/slack 진입
    await page.goto(PAGE_URL)
    await page.waitForURL(`**${PAGE_URL}`)

    // Then. 연결된 워크스페이스 이름 노출
    await expect(page.getByText(MOCK_SLACK_TEAM_NAME, { exact: true })).toBeVisible()

    // Then. "다시 연결" 버튼 노출
    await expect(page.getByRole('button', { name: '다시 연결', exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — OAuth 콜백 성공 → 성공 배너 노출
//
// Given   SYSTEM_ADMIN alice 로그인
// When    /admin/slack?installed=Acme%20Corp 진입 (OAuth 콜백 redirect 재현)
// Then    "Acme Corp 워크스페이스에 연결되었습니다" 성공 배너 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 OAuth 콜백 성공 배너 (FR-SL-01)', () => {
  test('Given SYSTEM_ADMIN When ?installed=Acme%20Corp 진입 Then 성공 배너 노출', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // When. 콜백 성공 쿼리로 진입
    // (waitForURL로 쿼리 포함 URL을 대기하지 않는다 — 마운트 직후 useEffect가 replace:true로
    //  쿼리를 정리해 URL이 곧 /admin/slack으로 바뀌므로 레이스가 생긴다. 배너는 useState
    //  캡처값으로 렌더되어 쿼리 정리와 무관하게 유지된다 — admin.slack.tsx remarks 참고)
    await page.goto(`${PAGE_URL}?installed=Acme%20Corp`)

    // Then. 성공 배너 노출 (SlackResultBanner role=alert)
    const banner = page.getByRole('alert').filter({ hasText: 'Acme Corp 워크스페이스에 연결되었습니다' })
    await expect(banner).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — OAuth 콜백 실패 → 오류 배너 노출
//
// Given   SYSTEM_ADMIN alice 로그인
// When    /admin/slack?error=invalid_state 진입 (OAuth 콜백 redirect 재현)
// Then    매핑된 오류 메시지 배너 노출
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 OAuth 콜백 실패 배너 (FR-SL-01)', () => {
  test('Given SYSTEM_ADMIN When ?error=invalid_state 진입 Then 오류 배너 노출', async ({ page }) => {
    // Given. SYSTEM_ADMIN alice 로그인
    await loginAsSystemAdmin(page)

    // When. 콜백 실패 쿼리로 진입 (waitForURL 미사용 사유는 S3 주석 참고)
    await page.goto(`${PAGE_URL}?error=invalid_state`)

    // Then. 매핑된 오류 메시지 배너 노출 (slackErrorMessages.invalid_state)
    const banner = page
      .getByRole('alert')
      .filter({ hasText: '연결 요청이 만료되었거나 유효하지 않습니다. 다시 시도해 주세요.' })
    await expect(banner).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5 — 비관리자: /admin/slack 직접 진입 차단
//
// Given   일반 사용자 alice 로 로그인 (isSystemAdmin:false, 기본 fixture)
// When    /admin/slack 직접 goto
// Then    requireSystemAdmin 가드 → /dashboard redirect (페이지 미노출)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S5 비관리자 접근 차단 (FR-SL-01)', () => {
  test('Given 일반 사용자 로그인 When /admin/slack 직접 goto Then /dashboard redirect', async ({ page }) => {
    // Given. 일반 alice 로그인 — isSystemAdmin:false (기본 fixture, LS 플래그 없음)
    await loginAsAlice(page)

    // When. /admin/slack 직접 goto
    await page.goto(PAGE_URL)

    // Then. requireSystemAdmin 가드 → /dashboard redirect
    await page.waitForURL('**/dashboard')
    expect(new URL(page.url()).pathname).toBe('/dashboard')

    // Then. Slack 연결 카드(페이지 본문)는 노출되지 않음
    await expect(page.getByRole('button', { name: 'Slack에 연결', exact: true })).not.toBeVisible()
  })
})
