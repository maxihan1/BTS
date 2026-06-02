// FR-PM-02 이슈 권한 MSW fixture 데이터 — ADMIN/MEMBER 두 역할 응답 초기값

/** 이슈 권한 응답 구조 */
export interface IssuePermissions {
  UPDATE: boolean
  SOFT_DELETE: boolean
  TRANSITION: boolean
}

/** 이슈 권한 전체 응답 구조 */
export interface IssuePermissionResponse {
  issueKey: string
  permissions: IssuePermissions
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 권한 응답 fixture 헬퍼 */
export const makeIssuePermissionResponse = (
  issueKey: string,
  permissions: IssuePermissions,
): IssuePermissionResponse => ({ issueKey, permissions })

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN 권한 fixture
// alice(00000000-0000-0000-0000-000000000001) 기준 — SYSTEM_ADMIN 또는 PROJECT_ADMIN
// ADMIN: UPDATE=true, SOFT_DELETE=true, TRANSITION=true
// ─────────────────────────────────────────────────────────────────────────────

/** ADMIN 권한 fixture — UPDATE/SOFT_DELETE/TRANSITION 모두 허용 */
export const adminPermissionsFixture: IssuePermissions = {
  UPDATE: true,
  SOFT_DELETE: true,
  TRANSITION: true,
}

// ─────────────────────────────────────────────────────────────────────────────
// MEMBER 권한 fixture
// bob(00000000-0000-0000-0000-000000000002) 기준 — MEMBER 역할
// MEMBER: UPDATE=true, SOFT_DELETE=false, TRANSITION=true
// ─────────────────────────────────────────────────────────────────────────────

/** MEMBER 권한 fixture — UPDATE/TRANSITION 허용, SOFT_DELETE 불허 */
export const memberPermissionsFixture: IssuePermissions = {
  UPDATE: true,
  SOFT_DELETE: false,
  TRANSITION: true,
}
