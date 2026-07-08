// slack-integration BC MSW mock handlers — 연결 상태 조회 + authorize URL 발급 (FR-SL-01 D6/D7 Task 8)
import { http, HttpResponse } from 'msw'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면
 * `GET /api/v1/slack/installation` 응답을 connected:true(Acme Corp)로 오버라이드한다.
 *
 * Playwright addInitScript로 goto 전에 플래그를 설정하면 첫 fetch 시점부터 적용된다
 * (e2e-msw-scenario-toggle-localstorage-flag 교훈 — 핸들러 임시 교체 대신 localStorage 플래그).
 */
export const E2E_SLACK_CONNECTED_KEY = '__bts_e2e_slack_connected'

/** connected:true 시나리오에서 반환할 고정 team 값 — E2E 배너/카드 검증에서 재사용 */
export const MOCK_SLACK_TEAM_ID = 'T123'
export const MOCK_SLACK_TEAM_NAME = 'Acme Corp'
export const MOCK_SLACK_INSTALLED_AT = '2026-07-08T00:00:00Z'
/** connected:true 시나리오의 봇 사용자 ID — 카드 "봇 사용자 ID" 필드 검증에서 재사용 (라운드 2) */
export const MOCK_SLACK_BOT_USER_ID = 'U0BOT'
/** connected:true 시나리오의 최근 갱신 시각 — 카드 "최근 갱신" 필드 검증에서 재사용 (라운드 2) */
export const MOCK_SLACK_UPDATED_AT = '2026-07-08T03:00:00Z'
/** connected:true 시나리오의 설치자 이름 — 카드 "설치자" 필드 검증에서 재사용 (라운드 2) */
export const MOCK_SLACK_INSTALLER_NAME = '홍길동'

/**
 * GET /api/v1/slack/installation — 현재 워크스페이스의 Slack 연결 상태 조회.
 *
 * 응답 schema: `SlackInstallationSchema`
 * (connected/teamId/teamName/botUserId/installedAt/updatedAt/installerName).
 * localStorage 플래그 미설정(기본) 시 미연결, 설정 시 연결됨 고정 fixture를 반환한다.
 */
const installationHandler = http.get('/api/v1/slack/installation', () => {
  const connected = globalThis.localStorage?.getItem(E2E_SLACK_CONNECTED_KEY) === 'true'

  if (connected) {
    return HttpResponse.json({
      connected: true,
      teamId: MOCK_SLACK_TEAM_ID,
      teamName: MOCK_SLACK_TEAM_NAME,
      botUserId: MOCK_SLACK_BOT_USER_ID,
      installedAt: MOCK_SLACK_INSTALLED_AT,
      updatedAt: MOCK_SLACK_UPDATED_AT,
      installerName: MOCK_SLACK_INSTALLER_NAME,
    })
  }

  return HttpResponse.json({
    connected: false,
    teamId: null,
    teamName: null,
    botUserId: null,
    installedAt: null,
    updatedAt: null,
    installerName: null,
  })
})

/**
 * GET /api/v1/slack/install-url — Slack OAuth authorize URL 발급.
 *
 * 응답 schema: `SlackInstallUrlSchema` (url). 실제 slack.com 이동은 E2E에서 따라가지 않고
 * 요청 발생/버튼 노출까지만 검증한다(핸들러는 고정 stub URL만 반환).
 */
const installUrlHandler = http.get('/api/v1/slack/install-url', () => {
  return HttpResponse.json({
    url: 'https://slack.com/oauth/v2/authorize?client_id=mock-client-id&state=mock-state',
  })
})

export const slackHandlers = [installationHandler, installUrlHandler]
