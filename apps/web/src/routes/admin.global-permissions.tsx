// 전역 권한 관리자 페이지 — /admin/global-permissions, GlobalPermissionList 조립 (FR-PM-10 D6 Task 6)
import type { JSX } from 'react'
import { GlobalPermissionList } from '@/components/global-permissions/GlobalPermissionList'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 권한 관리 페이지.
 *
 * @remarks
 * - SYSTEM_ADMIN 전용 — requireSystemAdmin 가드는 router.ts 등록(adminAuditLogsRoute 동형)에서 적용된다.
 * - {@link GlobalPermissionList}가 h1 heading "전역 권한 관리"·목록/부여/회수를 모두 담당한다.
 *   페이지는 다른 admin 조회 페이지(admin.audit-logs 등)와 동일한 컨테이너 레이아웃만 부여한다.
 *
 * @returns 전역 권한 관리 페이지 컴포넌트
 */
export function GlobalPermissionsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <GlobalPermissionList />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다(admin.audit-logs 패턴 — useParams 불필요).
 */
export function AdminGlobalPermissionsRouteAdapter(): JSX.Element {
  return <GlobalPermissionsPage />
}
