// Slack 사용자 본인 연결 MSW 핸들러 — me-scope GET/POST/DELETE stateful + 오류 시나리오 토글 (FR-SL-02 D6 Task 9)
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 오류 시나리오 토글용 localStorage 키 (e2e-msw-scenario-toggle-localstorage-flag 선례)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — `POST /api/v1/slack/me/connection`(연결)의
 * 실패 시나리오를 지정한다. 값은 {@link SlackConnectScenario} 중 하나, 미설정/그 외 값이면
 * 정상 연결(happy path)로 처리한다.
 *
 * Playwright addInitScript로 goto 전에 값을 심으면 첫 요청부터 반영된다
 * (핸들러 임시 교체 대신 localStorage 플래그 — e2e-msw-scenario-toggle-localstorage-flag 교훈).
 */
export const SLACK_CONNECT_SCENARIO_KEY = 'msw:slack-connect-scenario'

/** 연결 실패 시나리오 종류 — 스펙 EC3/EC4/EC5(워크스페이스 미설치/스코프 부족/이메일 미발견) */
export type SlackConnectScenario = 'not-installed' | 'scope-missing' | 'not-found'

/** localStorage에서 현재 시나리오 플래그를 읽는다 — 미설정/알 수 없는 값이면 null(happy path) */
function readScenario(): SlackConnectScenario | null {
  try {
    const raw = localStorage.getItem(SLACK_CONNECT_SCENARIO_KEY)
    if (raw === 'not-installed' || raw === 'scope-missing' || raw === 'not-found') return raw
    return null
  } catch {
    // 테스트 환경(node)에서 localStorage 없을 수 있음 — happy path로 취급
    return null
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장소 — 연결 상태(단일 "본인" 사용자 시나리오, whoami처럼 사용자별 맵 불필요)
// 백엔드 응답 계약(스펙): connected/workspaceName/linkedAt만. slack_user_id/이메일 미노출.
// ─────────────────────────────────────────────────────────────────────────────

interface SlackUserConnectionState {
  connected: boolean
  workspaceName: string | null
  linkedAt: string | null
}

function initialConnectionState(): SlackUserConnectionState {
  return { connected: false, workspaceName: null, linkedAt: null }
}

let connectionStore: SlackUserConnectionState = initialConnectionState()

/**
 * 연결 저장소를 미연결 초기 상태로 리셋한다 — 각 테스트 beforeEach에서 호출.
 * 테스트 간 state leak을 방지한다(status-handlers.resetStatusStore 선례).
 */
export function resetSlackUserConnectionStore(): void {
  connectionStore = initialConnectionState()
}

/** 연결 성공 시 채워지는 고정 workspaceName fixture — E2E 배너 검증에서 재사용 */
export const MOCK_SLACK_CONNECTION_WORKSPACE_NAME = 'BTS 워크스페이스'
/** 연결 성공 시 채워지는 고정 linkedAt fixture — E2E 배너 검증에서 재사용 */
export const MOCK_SLACK_CONNECTION_LINKED_AT = '2026-07-10T00:00:00Z'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — Authorization Bearer 인증 (status-handlers.ts/preferences-handlers.ts와 동일 규약)
// ─────────────────────────────────────────────────────────────────────────────

/** mock access token prefix — auth-fixtures.mockAccessToken과 동일 형식 */
const MOCK_TOKEN_PREFIX = 'mock-access-token-'

/** Authorization Bearer 헤더가 유효한(known) 사용자를 가리키는지 판정한다. */
function isAuthenticated(request: Request): boolean {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return false

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(MOCK_TOKEN_PREFIX)) return false

  const username = token.slice(MOCK_TOKEN_PREFIX.length)
  return AUTH_USERS[username] !== undefined
}

/** 백엔드 `{code, message}` 에러 봉투(status-handlers.ts/preferences-handlers.ts 선례와 동일 형식) */
function errorBody(code: string, message: string): { code: string; message: string } {
  return { code, message }
}

/**
 * 시나리오 플래그에 따른 POST 연결 실패 응답을 계산한다.
 * 스펙 에러코드(EC3/EC4/EC5) 그대로 매핑. happy path(null)면 실패 없음.
 */
function connectScenarioError(): { status: number; body: { code: string; message: string } } | null {
  const scenario = readScenario()
  switch (scenario) {
    case 'not-installed':
      return {
        status: 409,
        body: errorBody('WORKSPACE_NOT_INSTALLED', 'Slack 워크스페이스가 설치되어 있지 않습니다.'),
      }
    case 'scope-missing':
      return {
        status: 409,
        body: errorBody('SLACK_SCOPE_MISSING', 'Slack 앱에 필요한 권한이 없습니다. 재연결이 필요합니다.'),
      }
    case 'not-found':
      return {
        status: 404,
        body: errorBody('SLACK_USER_NOT_FOUND', 'Slack에서 이메일과 일치하는 사용자를 찾을 수 없습니다.'),
      }
    case null:
      return null
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/slack/me/connection
// ─────────────────────────────────────────────────────────────────────────────

const getMyConnectionHandler = http.get('/api/v1/slack/me/connection', ({ request }) => {
  if (!isAuthenticated(request)) return new HttpResponse(null, { status: 401 })

  return HttpResponse.json(connectionStore)
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/slack/me/connection — 이메일 자동해석 연결(스펙 결정 C)
// ─────────────────────────────────────────────────────────────────────────────

const connectHandler = http.post('/api/v1/slack/me/connection', ({ request }) => {
  if (!isAuthenticated(request)) return new HttpResponse(null, { status: 401 })

  const scenarioError = connectScenarioError()
  if (scenarioError !== null) {
    return HttpResponse.json(scenarioError.body, { status: scenarioError.status })
  }

  connectionStore = {
    connected: true,
    workspaceName: MOCK_SLACK_CONNECTION_WORKSPACE_NAME,
    linkedAt: MOCK_SLACK_CONNECTION_LINKED_AT,
  }
  return HttpResponse.json(connectionStore)
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/slack/me/connection — 해제(미연결이어도 멱등 200, 스펙 EC8)
// ─────────────────────────────────────────────────────────────────────────────

const disconnectHandler = http.delete('/api/v1/slack/me/connection', ({ request }) => {
  if (!isAuthenticated(request)) return new HttpResponse(null, { status: 401 })

  connectionStore = initialConnectionState()
  return HttpResponse.json(connectionStore)
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** Slack 사용자 본인 연결 BC MSW 핸들러 배열 */
export const slackUserConnectionHandlers = [getMyConnectionHandler, connectHandler, disconnectHandler]
