// FR-SL-02 D6/D7 Task 10 E2E — 본인 Slack 계정 연결/해제/오류 (/settings/slack)
//
// 파일명 참고. 계획서상 "slack-connect.spec.ts"였으나 그 경로는 이미 FR-SL-01(관리자 /admin/slack
// OAuth 연결) E2E가 점유 중이라(별개 라우트·별개 기능) 덮어쓰지 않고 slack-user-connection.spec.ts로
// 명명했다 — MSW 핸들러 파일명(slack-user-connection-handlers.ts)과 대응.
//
// 교훈 반영.
//   - e2e-msw-serviceworker-block: serviceWorkers:'block' 절대 금지(전역 playwright.config 그대로).
//   - playwright-getbyrole-exact-strict-mode: 버튼/제목 모두 exact:true. "Slack 연결" 문자열이
//     페이지 h1과 CardTitle(div, role 없음) 양쪽에 등장하지만 CardTitle은 heading role이 아니라서
//     getByRole('heading', ...) 사용 시 strict mode 충돌이 없다. 버튼 텍스트도 role=button으로
//     한정해 h1/CardTitle과 겹치지 않는다.
//   - e2e-msw-scenario-toggle-localstorage-flag: 오류 시나리오는 localStorage
//     `msw:slack-connect-scenario` 플래그를 addInitScript로 심어 토글(핸들러 임시 교체 금지).
//   - msw-mutation-stateful-refetch: slack-user-connection-handlers.ts의 connectionStore는 POST/DELETE
//     이후 상태를 모듈 변수에 영속하고, invalidateQueries 재조회가 그 값을 그대로 반영한다.
//   - 데이터 격리: connectionStore는 순수 모듈 상태라 Playwright가 test마다 새로 여는 브라우저
//     컨텍스트(하드 네비게이션 = 모듈 재평가)에서 항상 미연결 초기값으로 시작한다 — 별도 리셋 불필요
//     (preferences.spec.ts AUTH_USERS 선례와 동형).

import { test, expect } from '@playwright/test'
import { loginAsAlice } from './fixtures/issue-fixtures'
import { SLACK_CONNECT_SCENARIO_KEY, MOCK_SLACK_CONNECTION_WORKSPACE_NAME } from '../src/mocks/slack-user-connection-handlers'

const PAGE_URL = '/settings/slack'
const PAGE_HEADING = 'Slack 연결'
const CONNECT_BUTTON = 'Slack 연결'
const DISCONNECT_BUTTON = '연결 해제'

async function gotoSlackSettings(page: import('@playwright/test').Page): Promise<void> {
  await page.goto(PAGE_URL)
  await expect(page.getByRole('heading', { name: PAGE_HEADING, exact: true })).toBeVisible()
}

// ─────────────────────────────────────────────────────────────────────────────
// S1 — 연결 happy path: 미연결 → "Slack 연결" 클릭 → 연결됨 표시
//
// Given   alice 로그인, Slack 미연결(기본 fixture)
// When    /settings/slack 진입 → "Slack 연결" 버튼 클릭
// Then    워크스페이스 이름 + "연결 해제" 버튼 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S1 본인 Slack 연결 happy path (FR-SL-02)', () => {
  test('Given 미연결 When "Slack 연결" 클릭 Then 워크스페이스 이름 + "연결 해제" 버튼 표시', async ({ page }) => {
    // Given. alice 로그인 → /settings/slack 진입 (미연결 초기 상태)
    await loginAsAlice(page)
    await gotoSlackSettings(page)
    await expect(page.getByText('Slack에 연결되어 있지 않습니다.', { exact: true })).toBeVisible()

    // When. "Slack 연결" 버튼 클릭
    await page.getByRole('button', { name: CONNECT_BUTTON, exact: true }).click()

    // Then. 워크스페이스 이름 표시
    await expect(page.getByText(MOCK_SLACK_CONNECTION_WORKSPACE_NAME, { exact: true })).toBeVisible()

    // Then. "연결 해제" 버튼 표시
    await expect(page.getByRole('button', { name: DISCONNECT_BUTTON, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2 — 연결 해제: 연결 상태에서 "연결 해제" 클릭 → 미연결 재표시
//
// Given   alice 로그인, "Slack 연결" 클릭으로 연결 상태를 스스로 만든다(데이터 격리)
// When    "연결 해제" 버튼 클릭
// Then    "Slack 연결" 버튼 재표시(미연결)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S2 본인 Slack 연결 해제 (FR-SL-02)', () => {
  test('Given 연결됨 When "연결 해제" 클릭 Then "Slack 연결" 버튼 재표시', async ({ page }) => {
    // Given. alice 로그인 → 연결 상태로 전환(이 test 안에서 스스로 데이터 생성)
    await loginAsAlice(page)
    await gotoSlackSettings(page)
    await page.getByRole('button', { name: CONNECT_BUTTON, exact: true }).click()
    await expect(page.getByRole('button', { name: DISCONNECT_BUTTON, exact: true })).toBeVisible()

    // When. "연결 해제" 버튼 클릭
    await page.getByRole('button', { name: DISCONNECT_BUTTON, exact: true }).click()

    // Then. 미연결 문구 + "Slack 연결" 버튼 재표시
    await expect(page.getByText('Slack에 연결되어 있지 않습니다.', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: CONNECT_BUTTON, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3 — 오류: 이메일 미발견(SLACK_USER_NOT_FOUND, EC5) → role=alert 배너
//
// Given   alice 로그인, localStorage 시나리오 플래그 'not-found'(addInitScript)
// When    /settings/slack 진입 → "Slack 연결" 클릭
// Then    role=alert 배너에 "Slack에서 회원님 이메일로 계정을 찾을 수 없습니다" 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S3 본인 Slack 연결 오류 — 이메일 미발견 (FR-SL-02 EC5)', () => {
  test('Given not-found 시나리오 When "Slack 연결" 클릭 Then 이메일 미발견 오류 배너 표시', async ({ page }) => {
    // Given. localStorage 시나리오 플래그를 addInitScript로 심어 첫 요청부터 반영
    await page.addInitScript(
      ({ key, value }) => {
        window.localStorage.setItem(key, value)
      },
      { key: SLACK_CONNECT_SCENARIO_KEY, value: 'not-found' },
    )
    await loginAsAlice(page)
    await gotoSlackSettings(page)

    // When. "Slack 연결" 버튼 클릭 (POST가 404 SLACK_USER_NOT_FOUND 반환)
    await page.getByRole('button', { name: CONNECT_BUTTON, exact: true }).click()

    // Then. role=alert 배너에 매핑된 한국어 오류 메시지 표시
    await expect(
      page.getByRole('alert').filter({ hasText: 'Slack에서 회원님 이메일로 계정을 찾을 수 없습니다' }),
    ).toBeVisible()

    // Then. 여전히 미연결 상태(연결 실패 — "Slack 연결" 버튼 유지)
    await expect(page.getByRole('button', { name: CONNECT_BUTTON, exact: true })).toBeVisible()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4 — 오류: 워크스페이스 미설치(WORKSPACE_NOT_INSTALLED, EC3) → role=alert 배너
//
// Given   alice 로그인, localStorage 시나리오 플래그 'not-installed'(addInitScript)
// When    /settings/slack 진입 → "Slack 연결" 클릭
// Then    role=alert 배너에 "먼저 관리자가 워크스페이스에 Slack을 연결해야 합니다" 표시
// ─────────────────────────────────────────────────────────────────────────────

test.describe('S4 본인 Slack 연결 오류 — 워크스페이스 미설치 (FR-SL-02 EC3)', () => {
  test('Given not-installed 시나리오 When "Slack 연결" 클릭 Then 워크스페이스 미설치 오류 배너 표시', async ({
    page,
  }) => {
    // Given. localStorage 시나리오 플래그를 addInitScript로 심어 첫 요청부터 반영
    await page.addInitScript(
      ({ key, value }) => {
        window.localStorage.setItem(key, value)
      },
      { key: SLACK_CONNECT_SCENARIO_KEY, value: 'not-installed' },
    )
    await loginAsAlice(page)
    await gotoSlackSettings(page)

    // When. "Slack 연결" 버튼 클릭 (POST가 409 WORKSPACE_NOT_INSTALLED 반환)
    await page.getByRole('button', { name: CONNECT_BUTTON, exact: true }).click()

    // Then. role=alert 배너에 매핑된 한국어 오류 메시지 표시
    await expect(
      page.getByRole('alert').filter({ hasText: '먼저 관리자가 워크스페이스에 Slack을 연결해야 합니다' }),
    ).toBeVisible()
  })
})
