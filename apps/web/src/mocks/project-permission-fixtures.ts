// FR-PM-02 프로젝트 권한 MSW fixture 데이터 — ADMIN/MEMBER/비멤버 세 역할 응답 초기값
import type { ProjectPermissions as ProjectPermissionsResponse } from '@/api/project-permissions'

/** 프로젝트 권한 맵 — api의 Zod 파생 타입에서 도출(단일 진실원, C-2). */
export type ProjectPermissions = ProjectPermissionsResponse['permissions']

/** 프로젝트 권한 전체 응답 구조 */
export interface ProjectPermissionResponse {
  projectKey: string
  permissions: ProjectPermissions
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 권한 응답 fixture 헬퍼 */
export const makeProjectPermissionResponse = (
  projectKey: string,
  permissions: ProjectPermissions,
): ProjectPermissionResponse => ({ projectKey, permissions })

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN 권한 fixture
// alice(00000000-0000-0000-0000-000000000001) 기준 — SYSTEM_ADMIN 또는 PROJECT_ADMIN
// ADMIN: CREATE=true
// ─────────────────────────────────────────────────────────────────────────────

/** ADMIN 프로젝트 권한 fixture — CREATE 허용 */
export const adminProjectPermissions: ProjectPermissions = {
  CREATE: true,
}

// ─────────────────────────────────────────────────────────────────────────────
// MEMBER 권한 fixture
// bob(00000000-0000-0000-0000-000000000002) 기준 — MEMBER 역할
// MEMBER: CREATE=true (이슈 생성은 MEMBER도 가능)
// ─────────────────────────────────────────────────────────────────────────────

/** MEMBER 프로젝트 권한 fixture — CREATE 허용 */
export const memberProjectPermissions: ProjectPermissions = {
  CREATE: true,
}

// ─────────────────────────────────────────────────────────────────────────────
// 비멤버/비활성 역할 fixture
// 인증은 됐지만 프로젝트 비멤버이거나 비활성 역할인 사용자.
// 백엔드(Task 1)는 이 경우 401이 아닌 200+CREATE:false를 반환한다.
// S2 E2E(Task 6)에서 server.use 오버라이드로 주입해 CREATE 버튼 비활성 상태를 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비멤버/비활성 역할 프로젝트 권한 fixture — CREATE 불허.
 *
 * S2 E2E(Task 6) 오버라이드 예시.
 * ```ts
 * server.use(
 *   http.get('/api/v1/users/me/project-permissions', () =>
 *     HttpResponse.json({ projectKey: 'ATLAS', permissions: nonMemberProjectPermissions })
 *   )
 * )
 * ```
 */
export const nonMemberProjectPermissions: ProjectPermissions = {
  CREATE: false,
}
