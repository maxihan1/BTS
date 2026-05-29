// 내 활성 세션 관리 페이지 — /settings/sessions, TanStack Router code-based 패턴
import type { JSX } from 'react'
import { SessionList } from '@/components/auth/SessionList'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 내 활성 세션 관리 페이지.
 *
 * - 로그인 중인 모든 기기/브라우저 세션을 나열한다.
 * - 현재 세션 이외의 세션을 강제 종료할 수 있다.
 * - code-based 패턴 (PR #26 컨벤션):
 *   ```ts
 *   import { SessionsSettingsRouteAdapter } from './routes/settings.sessions'
 *   const settingsSessionsRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/sessions',
 *     component: SessionsSettingsRouteAdapter,
 *   })
 *   ```
 */
export function SessionsSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">활성 세션 관리</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          현재 로그인된 기기와 브라우저 세션을 확인하고 관리하세요.
        </p>
      </div>
      <SessionList />
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
export function SessionsSettingsRouteAdapter(): JSX.Element {
  return <SessionsSettingsPage />
}
