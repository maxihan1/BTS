// 사용자 생성 관리자 페이지 — /admin/users/new, composeGuards(requireAuth, requireSystemAdmin)
import type { JSX } from 'react'
import { CreateUserForm } from '@/components/admin/CreateUserForm'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 생성 관리자 페이지.
 *
 * @remarks
 * - SYSTEM_ADMIN 전용 — requireSystemAdmin 가드 적용.
 * - 생성 성공 시 임시 비밀번호를 1회 표시 후 폼 초기화 가능.
 * - code-based 라우트 패턴:
 *   ```ts
 *   import { AdminUsersNewRouteAdapter } from './routes/admin.users.new'
 *   const adminUsersNewRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/admin/users/new',
 *     component: AdminUsersNewRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: composeGuards(requireAuth, requireSystemAdmin),
 *   })
 *   ```
 *
 * @see CreateUserForm 실제 폼 렌더링 컴포넌트
 * @see requireSystemAdmin SYSTEM_ADMIN 가드 함수 (auth/routeGuard)
 */
export function AdminUsersNewPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">사용자 생성</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          새 사용자를 생성하세요. 생성 시 임시 비밀번호가 발급됩니다.
        </p>
      </div>
      <CreateUserForm />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다.
 */
export function AdminUsersNewRouteAdapter(): JSX.Element {
  return <AdminUsersNewPage />
}
