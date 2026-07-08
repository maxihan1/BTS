// Slack 연결 관리자 페이지 — /admin/slack, SlackConnectionCard + SlackResultBanner 조립 (FR-SL-01 D6/D7 Task 7)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { SlackConnectionCard } from '@/components/settings/SlackConnectionCard'
import { SlackResultBanner } from '@/components/settings/SlackResultBanner'

/** /admin/slack 쿼리 파라미터 — OAuth 콜백 결과(성공 시 installed, 실패 시 error) */
interface SlackConnectSearch {
  readonly installed?: string
  readonly error?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Slack 연결 관리자 페이지.
 *
 * @remarks
 * - SYSTEM_ADMIN 전용 — requireSystemAdmin 가드는 router.ts 등록에서 적용된다.
 * - `?installed=<teamName>` / `?error=<code>` 콜백 쿼리를 최초 렌더 시 캡처해
 *   {@link SlackResultBanner}에 전달한다. `useState` 초기화 함수로 마운트 시 1회만
 *   캡처하는 이유는, 아래 useEffect가 쿼리 정리(navigate)를 실행하면 `useSearch()`가
 *   다음 렌더부터 빈 값을 반환하기 때문이다 — 캡처 없이 매 렌더 `useSearch()`를 직접
 *   읽으면 배너가 뜨자마자 사라지는 깜빡임이 생긴다(EC7, 정리는 새로고침 재표시 방지가
 *   목적이지 현재 화면에서 배너를 숨기는 것이 아니다).
 * - 배너가 표시된 이후 `useEffect`에서 `navigate({ search: {} })`로 쿼리를 제거해
 *   새로고침 시 배너가 재표시되지 않게 한다(settings.account-links 선례).
 *
 * @see SlackConnectionCard 연결 상태 카드
 * @see SlackResultBanner 콜백 결과 배너(순수 컴포넌트)
 */
export function SlackConnectionSettingsPage(): JSX.Element {
  const navigate = useNavigate()
  const search = useSearch({ strict: false }) as SlackConnectSearch

  // 최초 렌더 시점의 콜백 쿼리 값을 캡처 — 위 remarks 참고
  const [bannerParams] = useState<SlackConnectSearch>(() => ({
    installed: search.installed,
    error: search.error,
  }))

  useEffect(() => {
    if (bannerParams.installed === undefined && bannerParams.error === undefined) return
    void navigate({ to: '/admin/slack', search: {}, replace: true })
    // navigate는 stable ref, bannerParams는 마운트 시 캡처된 값이라 최초 1회만 실행하면 된다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">Slack 연결</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Slack 워크스페이스와 연결해 이슈 알림을 채널로 받아보세요.
        </p>
      </div>
      <div className="mb-6">
        <SlackResultBanner installed={bannerParams.installed} error={bannerParams.error} />
      </div>
      <SlackConnectionCard />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter (router.ts 등록용)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터 컴포넌트.
 * createRoute의 component 옵션에 직접 전달한다 (admin.webhooks 패턴 — 페이지가 라우터
 * 훅을 직접 사용하므로 어댑터는 별도 로직 없이 페이지를 렌더하기만 한다).
 */
export function SlackConnectionSettingsRouteAdapter(): JSX.Element {
  return <SlackConnectionSettingsPage />
}
