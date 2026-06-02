// FR-PM-02 이슈 권한 조회 MSW 핸들러 — Authorization Bearer 토큰(username) 기반 권한 결정
import { http, HttpResponse } from 'msw'
import {
  adminPermissionsFixture,
  memberPermissionsFixture,
  type IssuePermissions,
} from './issue-permission-fixtures'
import { AUTH_USERS } from './auth-fixtures'

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
