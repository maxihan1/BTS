// FR-PM-02 이슈 권한 조회 MSW 핸들러 — Authorization Bearer 토큰(username) 기반 권한 결정
import { http, HttpResponse } from 'msw'
import {
  adminPermissionsFixture,
  memberPermissionsFixture,
  type IssuePermissions,
} from './issue-permission-fixtures'
import { AUTH_USERS } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 전용 localStorage 플래그
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 이 키가 'true'이면 UPDATE:false를 반환한다.
 *
 * 권한 없는 사용자(읽기 전용) 시나리오를 검증할 때 사용한다.
 * Playwright addInitScript 로 goto 전에 플래그를 설정하면 첫 권한 fetch 시점부터
 * UPDATE:false 가 반환된다. dev/test 빌드 전용(production 미포함).
 *
 * 사용 예:
 *   await page.addInitScript((key) => {
 *     window.localStorage.setItem(key, 'true')
 *   }, E2E_FORCE_READONLY_ISSUE_KEY)
 */
export const E2E_FORCE_READONLY_ISSUE_KEY = '__bts_e2e_force_readonly_issue'

// ─────────────────────────────────────────────────────────────────────────────
// username → 권한 매핑
//
// project-member-fixtures.ts 기준.
//   alice (userId ...001) — SYSTEM_ADMIN/PROJECT_ADMIN → adminPermissionsFixture
//   bob   (userId ...002) — MEMBER                    → memberPermissionsFixture
//
// whoami 핸들러와 동일한 AUTH_USERS 맵을 사용해 일관성을 유지한다.
// ─────────────────────────────────────────────────────────────────────────────

const PERMISSION_BY_USERNAME: Readonly<Record<string, IssuePermissions>> = {
  alice: adminPermissionsFixture,
  bob: memberPermissionsFixture,
}

// ─────────────────────────────────────────────────────────────────────────────
// Authorization Bearer 토큰에서 username 추출 헬퍼
//
// whoami 핸들러와 동일한 패턴.
//   "Bearer mock-access-token-{username}" → username
//   헤더 없음 / prefix 불일치 / 알려지지 않은 username → null
// ─────────────────────────────────────────────────────────────────────────────

const TOKEN_PREFIX = 'mock-access-token-'

function resolveUsername(request: Request): string | null {
  const authHeader = request.headers.get('Authorization')
  if (authHeader === null || !authHeader.startsWith('Bearer ')) return null

  const token = authHeader.slice('Bearer '.length)
  if (!token.startsWith(TOKEN_PREFIX)) return null

  const username = token.slice(TOKEN_PREFIX.length)
  return AUTH_USERS[username] !== undefined ? username : null
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/issue-permissions?issueKey={issueKey}
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 사용자의 이슈 권한 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. Authorization 헤더 없거나 토큰 미인식 → 401
 * 2. issueKey 쿼리 파라미터 없음 → 400
 * 성공 → 200 + { issueKey, permissions }
 *
 * username → 권한 매핑.
 *   alice → adminPermissionsFixture  (UPDATE/SOFT_DELETE/TRANSITION 모두 true)
 *   bob   → memberPermissionsFixture (UPDATE/TRANSITION true, SOFT_DELETE false)
 *   그 외 알려진 username 없음 → 401
 */
const getIssuePermissionsHandler = http.get(
  '/api/v1/users/me/issue-permissions',
  ({ request }) => {
    const username = resolveUsername(request)
    if (username === null) {
      return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
    }

    const url = new URL(request.url)
    const issueKey = url.searchParams.get('issueKey')

    if (issueKey === null || issueKey.trim() === '') {
      return HttpResponse.json(
        { error: 'issueKey_required' },
        { status: 400 },
      )
    }

    // E2E 전용: localStorage 플래그가 'true'이면 UPDATE:false 반환 (읽기 전용 게이팅 검증용)
    if (globalThis.localStorage?.getItem(E2E_FORCE_READONLY_ISSUE_KEY) === 'true') {
      return HttpResponse.json({
        issueKey,
        permissions: { UPDATE: false, SOFT_DELETE: false, TRANSITION: false },
      })
    }

    const permissions = PERMISSION_BY_USERNAME[username] ?? {
      UPDATE: false,
      SOFT_DELETE: false,
      TRANSITION: false,
    }

    return HttpResponse.json({ issueKey, permissions })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 권한 조회 MSW 핸들러 배열 */
export const issuePermissionHandlers = [getIssuePermissionsHandler]
