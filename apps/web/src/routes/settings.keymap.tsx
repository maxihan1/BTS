// 단축키 커스터마이즈 설정 페이지 — /settings/keymap, TanStack Router code-based 패턴 (FR-PF-03 Task 9)
import type { JSX } from 'react'
import { KeymapForm } from '@/components/settings/KeymapForm'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 단축키 커스터마이즈 설정 페이지.
 *
 * @remarks
 * - action 5종(도움말/새 이슈 생성/검색/내 이슈/대시보드)의 key_combo를 캡처 input으로
 *   재배치하고, 저장 시 `PATCH /api/v1/users/me/keymap`으로 replace-all 저장한다(FR-PF-03).
 * - requireAuthAndPasswordChanged 가드 적용 — settings.sessions/notifications/pats와 동일하게
 *   requireAuth + requirePasswordChanged + requireMfaEnrolled 체인을 적용한다.
 * - code-based 패턴 (settings.preferences.tsx 선례):
 *   ```ts
 *   import { KeymapSettingsRouteAdapter } from './routes/settings.keymap'
 *   const settingsKeymapRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/keymap',
 *     component: KeymapSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuthAndPasswordChanged,
 *   })
 *   ```
 *
 * @see KeymapForm 실제 폼 렌더링 컴포넌트
 */
export function KeymapSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">단축키 설정</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          자주 쓰는 동작에 원하는 키를 배정하세요.
        </p>
      </div>
      <KeymapForm />
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
export function KeymapSettingsRouteAdapter(): JSX.Element {
  return <KeymapSettingsPage />
}
