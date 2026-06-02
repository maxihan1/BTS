// FR-PM-02 프로젝트 권한 조회 MSW 핸들러 — Authorization Bearer 토큰(username) 기반 권한 결정
import { http, HttpResponse } from 'msw'
import { AUTH_USERS } from './auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 프로젝트 권한 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 권한 응답 구조 */
export interface ProjectPermissions {
  CREATE: boolean
}

/** 프로젝트 권한 전체 응답 구조 */
export interface ProjectPermissionResponse {
  projectKey: string
  permissions: ProjectPermissions
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture 상수
//
// alice (userId ...001) — SYSTEM_ADMIN/PROJECT_ADMIN → CREATE:true
// bob   (userId ...002) — MEMBER                    → CREATE:true
// 비멤버/비활성 역할 검증용                           → CREATE:false
//
// S2 E2E(Task 6)가 nonMemberProjectPermissions 오버라이드로 버튼 비활성을 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

/** ADMIN 프로젝트 권한 fixture — CREATE 허용 */
export const adminProjectPermissions: ProjectPermissions = {
  CREATE: true,
}

/** MEMBER 프로젝트 권한 fixture — CREATE 허용 (이슈 생성은 MEMBER도 가능) */
export const memberProjectPermissions: ProjectPermissions = {
  CREATE: true,
}

/**
 * 비멤버/비활성 역할 프로젝트 권한 fixture — CREATE 불허.
 *
 * 백엔드(Task 1)는 인증된 비멤버에게 401이 아닌 200+CREATE:false를 반환한다.
 * S2 E2E(Task 6)에서 server.use 오버라이드로 주입해 CREATE 버튼 비활성 상태를 검증한다.
 */
export const nonMemberProjectPermissions: ProjectPermissions = {
  CREATE: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// username → 권한 매핑
//
// whoami 핸들러와 동일한 AUTH_USERS 맵을 사용해 일관성을 유지한다.
// alice/bob 모두 프로젝트 멤버로 CREATE:true.
// AUTH_USERS에 없는 username은 resolveUsername에서 null 반환 → 401.
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
 * 비멤버 검증은 server.use 오버라이드로 nonMemberProjectPermissions 주입.
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
