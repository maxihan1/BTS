// 비밀번호 변경 설정 페이지 — /settings/password, TanStack Router code-based 패턴
import type { JSX } from 'react'
import { ChangePasswordForm } from '@/components/auth/ChangePasswordForm'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비밀번호 변경 설정 페이지.
 *
 * - 현재 비밀번호 확인 후 새 비밀번호로 변경한다.
 * - 변경 성공 시 현재 세션 외 다른 세션은 자동 무효화된다(백엔드 처리).
 * - requireAuth 가드 적용 — 미인증 접근 시 /login 리다이렉트.
 * - code-based 패턴 (PR #26 컨벤션):
 *   ```ts
 *   import { PasswordSettingsRouteAdapter } from './routes/settings.password'
 *   const settingsPasswordRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/password',
 *     component: PasswordSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuth,
 *   })
 *   ```
 */
export function PasswordSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">비밀번호 변경</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          현재 비밀번호를 확인한 후 새 비밀번호로 변경하세요.
        </p>
      </div>
      <ChangePasswordForm />
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
export function PasswordSettingsRouteAdapter(): JSX.Element {
  return <PasswordSettingsPage />
}
