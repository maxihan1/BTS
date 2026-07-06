// 사용자 프로필 설정 페이지 — /settings/profile, TanStack Router code-based 패턴
import type { JSX } from 'react'
import { ProfileForm } from '@/components/settings/ProfileForm'
import { profileLabels } from '@/i18n/profile-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 프로필 설정 페이지.
 *
 * @remarks
 * - `GET /api/v1/users/me/profile` 조회 결과로 표시 이름·시간대·부서를 편집하고
 *   아바타(업로드/삭제)를 관리한다(FR-PR-01).
 * - requireAuth 가드 적용 — 미인증 접근 시 /login 리다이렉트(spec S8).
 * - code-based 패턴 (settings.password.tsx 선례):
 *   ```ts
 *   import { ProfileSettingsRouteAdapter } from './routes/settings.profile'
 *   const settingsProfileRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/profile',
 *     component: ProfileSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuth,
 *   })
 *   ```
 *
 * @see ProfileForm 실제 폼 렌더링 컴포넌트
 * @see requireAuth 라우트 가드 함수 (auth/routeGuard)
 */
export function ProfileSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">{profileLabels.page.heading}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {profileLabels.page.description}
        </p>
      </div>
      <ProfileForm />
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
export function ProfileSettingsRouteAdapter(): JSX.Element {
  return <ProfileSettingsPage />
}
