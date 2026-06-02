// FR-PM-02 이슈 권한 조회 MSW 핸들러 — stateful 역할 전환 지원 (GET /api/v1/users/me/issue-permissions)
import { http, HttpResponse } from 'msw'
import {
  adminPermissionsFixture,
  memberPermissionsFixture,
  type IssuePermissions,
} from './issue-permission-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// Stateful 저장소 — 현재 응답 모드 (admin | member)
//
// 테스트에서 역할을 전환할 때 X-MSW-Reset-Permissions 헤더를 사용한다.
//   X-MSW-Reset-Permissions: admin  → ADMIN fixture로 리셋
//   X-MSW-Reset-Permissions: member → MEMBER fixture로 리셋
// 헤더 없이 GET 호출하면 현재 저장된 모드의 응답을 반환한다.
// ─────────────────────────────────────────────────────────────────────────────

type PermissionMode = 'admin' | 'member'

let currentMode: PermissionMode = 'admin'

function currentPermissions(): IssuePermissions {
  return currentMode === 'admin' ? adminPermissionsFixture : memberPermissionsFixture
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/issue-permissions?issueKey={issueKey}
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 사용자의 이슈 권한 조회.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. issueKey 쿼리 파라미터 없음 → 400
 * 성공 → 200 + { issueKey, permissions }
 *
 * X-MSW-Reset-Permissions 헤더로 stateful 모드를 전환할 수 있다.
 * - "admin"  : ADMIN fixture (UPDATE/SOFT_DELETE/TRANSITION 모두 true)
 * - "member" : MEMBER fixture (UPDATE/TRANSITION true, SOFT_DELETE false)
 */
const getIssuePermissionsHandler = http.get(
  '/api/v1/users/me/issue-permissions',
  ({ request }) => {
    const resetHeader = request.headers.get('X-MSW-Reset-Permissions')
    if (resetHeader === 'admin' || resetHeader === 'member') {
      currentMode = resetHeader
    }

    const url = new URL(request.url)
    const issueKey = url.searchParams.get('issueKey')

    if (issueKey === null || issueKey.trim() === '') {
      return HttpResponse.json(
        { error: 'issueKey_required' },
        { status: 400 },
      )
    }

    return HttpResponse.json({
      issueKey,
      permissions: currentPermissions(),
    })
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 권한 조회 MSW 핸들러 배열 */
export const issuePermissionHandlers = [getIssuePermissionsHandler]
