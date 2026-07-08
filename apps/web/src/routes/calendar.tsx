// 개인 캘린더 페이지 라우트 어댑터 (FR-CA-01 Task 7)
import type { JSX } from 'react'
import { CalendarView } from '@/features/calendar/CalendarView'

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 *
 * `/calendar` 경로에 URL 파라미터가 없으므로 props 없이 CalendarView를 직접 렌더한다
 * (inbox.tsx `InboxRouteAdapter` 선례와 동일 패턴).
 *
 * router.ts 등록 방법(code-based 패턴, 이 레포의 실제 라우팅 관례 — createFileRoute 아님).
 *
 *   import { CalendarRouteAdapter } from './routes/calendar'
 *
 *   const calendarRoute = createRoute({
 *     getParentRoute: () => rootRoute,
 *     path: '/calendar',
 *     component: CalendarRouteAdapter,
 *     staticData: { requireAuth: true },
 *     beforeLoad: requireAuthAndPasswordChanged,
 *   })
 */
export function CalendarRouteAdapter(): JSX.Element {
  return <CalendarView />
}
