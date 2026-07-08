// Slack 연결 관리자 페이지 — /admin/slack, SlackConnectionCard + SlackResultBanner 조립 (FR-SL-01 D6/D7 Task 7/R8)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { SlackConnectionCard } from '@/components/settings/SlackConnectionCard'
import { SlackResultBanner } from '@/components/settings/SlackResultBanner'
import { getSlackInstallUrl } from '@/api/slack'

/** /admin/slack 쿼리 파라미터 — OAuth 콜백 결과(성공 시 installed, 실패 시 error) */
interface SlackConnectSearch {
  readonly installed?: string
  readonly error?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수 / 문구 — 관리자 전용 고정 한국어 (admin.webhooks.tsx 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  heading: 'Slack 연결',
  description:
    '이 워크스페이스에 연결된 Slack 앱을 관리합니다. 연결하면 이슈 알림을 채널로 받아볼 수 있습니다.',
} as const

/** 오류 배너 "다시 시도"에서 authorize URL 조회(`getSlackInstallUrl`) 실패 시 표시할 메시지 */
const RETRY_ERROR_MESSAGE = 'Slack 연결을 다시 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'

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
  // "닫기" 클릭 시 배너를 화면에서 숨긴다(쿼리 정리와 별개 — 쿼리는 이미 위 useEffect가 정리)
  const [dismissed, setDismissed] = useState(false)
  // "다시 시도" 재조회 실패 시 표시할 인라인 오류(SlackConnectionCard.handleConnect의 connectError와 대칭)
  const [retryError, setRetryError] = useState<string | null>(null)

  /**
   * 오류 배너의 "다시 시도" — authorize URL을 새로 조회해 그대로 이동한다(SlackConnectionCard 연결
   * 흐름과 동일 패턴). 조회 자체가 실패하면(네트워크 오류 등) 페이지를 유지한 채 인라인 오류를
   * 표시한다 — 실패해도 catch 없이 방치하면 unhandled rejection + 피드백 없는 무반응이 된다.
   */
  async function handleRetry(): Promise<void> {
    setRetryError(null)
    try {
      const { url } = await getSlackInstallUrl()
      window.location.assign(url)
    } catch {
      setRetryError(RETRY_ERROR_MESSAGE)
    }
  }

  function handleDismiss(): void {
    setDismissed(true)
  }

  useEffect(() => {
    // 캡처한 콜백 값이 둘 다 없으면(=일반 진입) 정리할 쿼리가 없다 — navigate 생략
    if (bannerParams.installed === undefined && bannerParams.error === undefined) return
    // 배너는 이미 위 useState 캡처값으로 렌더되었으므로, 여기서 쿼리만 제거해도 화면에서
    // 배너가 사라지지 않는다. replace:true로 히스토리에 콜백 URL을 남기지 않는다.
    void navigate({ to: '/admin/slack', search: {}, replace: true })
    // navigate는 stable ref, bannerParams는 마운트 시 캡처된 값이라 최초 1회만 실행하면 된다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-semibold">{labels.heading}</h1>
        <p className="mt-1 text-sm text-muted-foreground">{labels.description}</p>
      </div>
      <div className="mb-6">
        {!dismissed && (
          <SlackResultBanner
            installed={bannerParams.installed}
            error={bannerParams.error}
            onRetry={handleRetry}
            onDismiss={handleDismiss}
          />
        )}
        {retryError !== null && (
          <p role="alert" aria-live="polite" className="mt-2 text-sm text-destructive">
            {retryError}
          </p>
        )}
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
