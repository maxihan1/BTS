// 본인 Slack 계정 연결 설정 페이지 — /settings/slack, TanStack Router code-based 패턴 (FR-SL-02 D6 Task 8)
import type { JSX } from 'react'
import { SlackUserConnectionCard } from '@/components/settings/SlackUserConnectionCard'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 Slack 계정 연결 설정 페이지.
 *
 * @remarks
 * - 본인 BTS 계정과 Slack 계정을 연결/해제해 알림(FR-SL-02 D1~D5 발송 파이프라인)을 Slack DM으로도
 *   받을 수 있게 한다. 실제 조회/연결/해제 로직은 {@link SlackUserConnectionCard}가 담당한다.
 * - requireAuthAndPasswordChanged 가드 적용 — settings.calendar/keymap과 동일하게
 *   requireAuth + requirePasswordChanged + requireMfaEnrolled 체인을 적용한다.
 * - code-based 패턴 (settings.calendar.tsx 선례):
 *   ```ts
 *   import { SlackSettingsRouteAdapter } from './routes/settings.slack'
 *   const settingsSlackRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/slack',
 *     component: SlackSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuthAndPasswordChanged,
 *   })
 *   ```
 *
 * @see SlackUserConnectionCard 실제 연결/해제 카드 컴포넌트
 */
export function SlackSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">Slack 연결</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          본인 Slack 계정을 연결하면 담당 이슈 알림을 Slack DM으로도 받을 수 있습니다.
        </p>
      </div>
      <SlackUserConnectionCard />
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
export function SlackSettingsRouteAdapter(): JSX.Element {
  return <SlackSettingsPage />
}
