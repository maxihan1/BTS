// FR-PM-02 프로젝트 권한 조회 MSW 핸들러 — Authorization Bearer 토큰(username) 기반 권한 결정
import { http, HttpResponse } from 'msw'
import {
  adminProjectPermissions,
  memberProjectPermissions,
  type ProjectPermissions,
} from './project-permission-fixtures'
import { AUTH_USERS } from './auth-fixtures'

export {
  adminProjectPermissions,
  memberProjectPermissions,
  nonMemberProjectPermissions,
} from './project-permission-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// username → 권한 매핑
//
// whoami 핸들러와 동일한 AUTH_USERS 맵을 사용해 일관성을 유지한다.
//   alice (userId ...001) — SYSTEM_ADMIN/PROJECT_ADMIN → adminProjectPermissions
//   bob   (userId ...002) — MEMBER                    → memberProjectPermissions
//
// AUTH_USERS에 없는 username은 resolveUsername에서 null 반환 → 401.
// 비멤버 검증은 server.use 오버라이드로 nonMemberProjectPermissions 주입.
// ─────────────────────────────────────────────────────────────────────────────

const PERMISSION_BY_USERNAME: Readonly<Record<string, ProjectPermissions>> = {
  alice: adminProjectPermissions,
  bob: memberProjectPermissions,
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
// GET /api/v1/users/me/project-permissions?projectKey={projectKey}
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 사용자의 프로젝트 권한 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. Authorization 헤더 없거나 토큰 미인식 → 401
 * 2. projectKey 쿼리 파라미터 없음 → 400
 * 성공 → 200 + { projectKey, permissions }
 *
 * username → 권한 매핑.
 *   alice → adminProjectPermissions  (CREATE:true)
 *   bob   → memberProjectPermissions (CREATE:true)
 *   그 외 알려진 username 없음 → 401 (미인증으로 처리)
 *
 * 비멤버 시나리오(CREATE:false) 검증은 server.use 오버라이드로
 * nonMemberProjectPermissions를 주입해 사용한다.
 */
const getProjectPermissionsHandler = http.get(
  '/api/v1/users/me/project-permissions',
  ({ request }) => {
    const username = resolveUsername(request)
    if (username === null) {
      return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
    }

    const url = new URL(request.url)
    const projectKey = url.searchParams.get('projectKey')

    if (projectKey === null || projectKey.trim() === '') {
      return HttpResponse.json(
        { error: 'projectKey_required' },
        { status: 400 },
      )
    }

    const permissions = PERMISSION_BY_USERNAME[username] ?? adminProjectPermissions

    return HttpResponse.json({ projectKey, permissions })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 권한 조회 MSW 핸들러 배열 */
export const projectPermissionHandlers = [getProjectPermissionsHandler]
