// 사용자 환경설정(테마/언어/날짜포맷) 페이지 — /settings/preferences, TanStack Router code-based 패턴 (FR-PF-01 Task 7)
import type { JSX } from 'react'
import { PreferencesForm } from '@/components/settings/PreferencesForm'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 환경설정 페이지.
 *
 * @remarks
 * - 테마(light/dark/system)·날짜 표시 형식(iso/kr/us/eu)·언어(ko/en) 셀렉터를 렌더한다.
 *   변경 즉시 `PATCH /api/v1/users/me/preferences`로 저장한다(FR-PF-01).
 * - requireAuth 가드 적용 — 미인증 접근 시 /login 리다이렉트(settings.profile 선례와 동일
 *   단독 가드, requirePasswordChanged/requireMfaEnrolled 미적용).
 * - code-based 패턴 (settings.profile.tsx 선례):
 *   ```ts
 *   import { PreferencesSettingsRouteAdapter } from './routes/settings.preferences'
 *   const settingsPreferencesRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/preferences',
 *     component: PreferencesSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuth,
 *   })
 *   ```
 *
 * @see PreferencesForm 실제 폼 렌더링 컴포넌트
 * @see requireAuth 라우트 가드 함수 (auth/routeGuard)
 */
export function PreferencesSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">환경 설정</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          테마·언어·날짜 표시 형식을 설정하세요.
        </p>
      </div>
      <PreferencesForm />
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
export function PreferencesSettingsRouteAdapter(): JSX.Element {
  return <PreferencesSettingsPage />
}
