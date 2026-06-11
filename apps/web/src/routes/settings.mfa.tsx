// 2단계 인증(TOTP) 설정 페이지 — /settings/mfa, TanStack Router code-based 패턴
import type { JSX } from 'react'
import { MfaSettings } from '@/components/auth/MfaSettings'
import { mfaStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 2단계 인증(TOTP) 설정 페이지.
 *
 * @remarks
 * - Authenticator 앱 기반 TOTP 2FA를 활성화·비활성화한다.
 * - requireAuth 가드 적용 — 미인증 접근 시 /login 리다이렉트.
 * - code-based 패턴 (settings.password.tsx 선례):
 *   ```ts
 *   import { MfaSettingsRouteAdapter } from './routes/settings.mfa'
 *   const settingsMfaRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/mfa',
 *     component: MfaSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuth,
 *   })
 *   ```
 *
 * @see MfaSettings 실제 설정 화면 렌더링 컴포넌트
 * @see requireAuth 라우트 가드 함수 (auth/routeGuard)
 */
export function MfaSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">{mfaStrings.settingsTitle}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {mfaStrings.settingsDescription}
        </p>
      </div>
      <MfaSettings />
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
export function MfaSettingsRouteAdapter(): JSX.Element {
  return <MfaSettingsPage />
}
