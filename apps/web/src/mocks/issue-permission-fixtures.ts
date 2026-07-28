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

// ─────────────────────────────────────────────────────────────────────────────
// VIEWER 권한 fixture
// ─────────────────────────────────────────────────────────────────────────────

/**
 * VIEWER 권한 fixture — 읽기만 가능. 쓰기 계열 전부 불허.
 *
 * ★왜 필요한가. 이 fixture 가 없던 동안 **`UPDATE=false` 인 사용자가 모크에 존재하지 않아**,
 * 이슈 수준 쓰기 게이트의 유무를 어떤 프론트 테스트도 관측할 수 없었다. 백엔드
 * `CommentApplicationService.update:194` / `delete:279` 는 댓글을 조회하기 **전에**
 * 이슈 `UPDATE` 게이트를 통과시키는데, 모크에는 그 게이트가 아예 없어 같은 상황에서
 * 403 대신 404 를 냈다 — 모크와 백엔드의 판정 순서가 갈린 것이다.
 *
 * 관측 가능성이 없으면 정렬해도 그것이 유지되는지 알 수 없다. 이 fixture 가 판별자다.
 */
export const viewerPermissionsFixture: IssuePermissions = {
  UPDATE: false,
  SOFT_DELETE: false,
  TRANSITION: false,
}
