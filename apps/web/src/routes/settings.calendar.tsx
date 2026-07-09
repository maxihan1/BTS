// 캘린더 iCal 구독 설정 페이지 — /settings/calendar, TanStack Router code-based 패턴 (FR-CA-02 Task 9)
import type { JSX } from 'react'
import { CalendarFeedCard } from '@/components/settings/CalendarFeedCard'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 캘린더 구독(iCal Export) 설정 페이지.
 *
 * @remarks
 * - 담당 이슈 일정 + Worklog를 외부 캘린더 앱(Google/Apple/Outlook)이 익명 구독할 수 있는
 *   URL을 발급/재발급/취소한다(FR-CA-02). 실제 로직은 {@link CalendarFeedCard}가 담당한다.
 * - requireAuthAndPasswordChanged 가드 적용 — settings.sessions/notifications/pats/keymap과
 *   동일하게 requireAuth + requirePasswordChanged + requireMfaEnrolled 체인을 적용한다.
 * - code-based 패턴 (settings.keymap.tsx 선례):
 *   ```ts
 *   import { CalendarFeedSettingsRouteAdapter } from './routes/settings.calendar'
 *   const settingsCalendarRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/settings/calendar',
 *     component: CalendarFeedSettingsRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuthAndPasswordChanged,
 *   })
 *   ```
 *
 * @see CalendarFeedCard 실제 발급/재발급/취소 카드 컴포넌트
 */
export function CalendarFeedSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">캘린더 연동</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          담당 이슈 일정과 Worklog를 외부 캘린더 앱에서 구독할 수 있는 URL을 관리합니다.
        </p>
      </div>
      <CalendarFeedCard />
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
export function CalendarFeedSettingsRouteAdapter(): JSX.Element {
  return <CalendarFeedSettingsPage />
}
