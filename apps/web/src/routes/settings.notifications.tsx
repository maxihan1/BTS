// 사용자 알림 구독 설정 페이지 — /settings/notifications, code-based 패턴 (FR-NT-04)
import type { JSX } from 'react'
import { NotificationSubscriptionMatrix } from '@/components/settings/NotificationSubscriptionMatrix'
import { notificationSubscriptionStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 구독 설정 페이지.
 *
 * @remarks
 * - 로그인한 사용자가 이벤트별 채널 수신 여부를 직접 제어한다.
 * - 매트릭스는 GET 응답 기반 data-driven으로 렌더(채널·이벤트 하드코딩 없음, C1).
 * - requireAuth 가드 적용 — 미인증 접근 시 /login 리다이렉트.
 * - settings.password.tsx 레이아웃 패턴 (`mx-auto max-w-2xl px-4 py-8`) 동형.
 *
 * @see NotificationSubscriptionMatrix 실제 매트릭스 렌더링 컴포넌트
 */
export function NotificationSettingsPage(): JSX.Element {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">
          {notificationSubscriptionStrings.pageTitle}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {notificationSubscriptionStrings.pageDescription}
        </p>
      </div>
      <NotificationSubscriptionMatrix />
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
export function NotificationSettingsRouteAdapter(): JSX.Element {
  return <NotificationSettingsPage />
}
