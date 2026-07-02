// 대시보드 익명 공유 뷰 라우트 — 비인증 읽기전용 렌더 + embed 크롬 최소화 (FR-DB-03 D6/D7 Task 9)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { useParams, useSearch } from '@tanstack/react-router'
import { getPublicDashboard } from '@/api/dashboards'
import type { PublicDashboard } from '@/api/dashboards'
import { parseLayout } from '@/lib/dashboard-layout'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { DashboardGrid } from '@/components/dashboard/DashboardGrid'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SharedDashboardPage Props — 라우터/인증 비의존 순수 컴포넌트 */
export interface SharedDashboardPageProps {
  /** 공유 토큰 (경로 세그먼트) */
  token: string
  /**
   * 임베드 모드 — true면 이름/설명/여백 크롬을 제거하고 그리드만 렌더한다(spec S3/EC-7).
   * 기본 false — 공유 링크 직접 열람 시 이름/설명이 보이는 완전한 뷰.
   */
  embed?: boolean
}

/** raw fetch 로딩 상태 — 성공/실패 원인(만료·취소·삭제 등)은 구분하지 않는다(NFR-3) */
type FetchStatus = 'loading' | 'success' | 'error'

// ─────────────────────────────────────────────────────────────────────────────
// DashboardGrid 필수 콜백 no-op — 익명 뷰는 강제 읽기전용이라 실제 호출되지 않는다
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DashboardGrid는 onLayoutChange/onDeleteTile/onEditTitle을 필수 prop으로 요구하지만,
 * publicMode=true에서는 드래그·리사이즈·삭제·제목편집 UI가 전부 비활성화되어 호출되지 않는다.
 * 매개변수가 적은 함수는 더 많은 매개변수를 받는 콜백 타입에 할당 가능한 TS 함수 타입 규칙을
 * 이용해, 매개변수 없는 no-op 하나로 세 prop을 모두 충족한다.
 */
function noop(): void {}

// ─────────────────────────────────────────────────────────────────────────────
// raw fetch 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 공유 토큰으로 익명 대시보드를 raw fetch(`getPublicDashboard`)로 조회한다.
 *
 * - apiFetch/인증 훅/인증 store는 절대 사용하지 않는다 — 401 자동 refresh나 로그인 리다이렉트를
 *   유발하면 익명 경로 UX가 깨진다(memory auth-pre-session-401-raw-fetch, spec EC-11).
 * - 에러 종류를 구분하지 않고 'error'로 수렴시킨다 — 백엔드가 무효/만료/삭제 토큰을 모두 404로
 *   응답하므로(NFR-3, 존재 열거 차단) 프론트도 동일하게 취급한다.
 * - `cancelled` 플래그로 언마운트·토큰 변경 시 이전 요청의 stale setState를 차단한다.
 */
function useSharedDashboardFetch(token: string): {
  status: FetchStatus
  dashboard: PublicDashboard | null
} {
  const [status, setStatus] = useState<FetchStatus>('loading')
  const [dashboard, setDashboard] = useState<PublicDashboard | null>(null)

  useEffect(() => {
    let cancelled = false
    setStatus('loading')
    setDashboard(null)

    getPublicDashboard(token)
      .then((data) => {
        if (cancelled) return
        setDashboard(data)
        setStatus('success')
      })
      .catch(() => {
        if (cancelled) return
        setStatus('error')
      })

    return () => {
      cancelled = true
    }
  }, [token])

  return { status, dashboard }
}

// ─────────────────────────────────────────────────────────────────────────────
// 로딩 스켈레톤
// ─────────────────────────────────────────────────────────────────────────────

/** 익명 공유 뷰 로딩 스켈레톤 — 기존 DashboardDetailSkeleton과 동일 muted 톤(리뷰 보강 6) */
function SharedDashboardSkeleton(): JSX.Element {
  return (
    <div className="p-6 space-y-4" role="status" aria-busy="true">
      <div className="h-7 w-48 rounded bg-muted animate-pulse" aria-hidden="true" />
      <div className="h-64 rounded-lg bg-muted animate-pulse" aria-hidden="true" />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 404
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 무효/만료/취소/삭제된 토큰 접근 시 렌더 — 로그인 리다이렉트 없이 그대로 표시한다(EC-11).
 * 기존 DashboardNotFound(중앙정렬 role="alert", muted)와 동일 톤(리뷰 보강 6).
 */
function SharedDashboardNotFound(): JSX.Element {
  return (
    <div className="p-6 text-center" role="alert">
      <p className="text-lg font-medium text-muted-foreground">{dashboardLabels.share.notFound}</p>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 성공 렌더
// ─────────────────────────────────────────────────────────────────────────────

/** SharedDashboardContent Props */
interface SharedDashboardContentProps {
  /** 조회된 익명 공개 대시보드 응답 */
  dashboard: PublicDashboard
  /** 임베드 모드 여부 */
  embed: boolean
}

/**
 * 성공 상태 렌더 — DashboardGrid를 publicMode로 렌더한다(읽기전용, 편집/헤더/소유자정보 없음).
 * embed=true면 이름/설명/여백을 제거하고 그리드만 남긴다(EC-7).
 */
function SharedDashboardContent({ dashboard, embed }: SharedDashboardContentProps): JSX.Element {
  const tiles = parseLayout(dashboard.layout)
  const hasDescription =
    dashboard.description !== null && dashboard.description !== undefined && dashboard.description !== ''

  return (
    <div className={embed ? '' : 'p-6 space-y-4'}>
      {!embed && (
        <div className="space-y-1">
          <h1 className="text-xl font-semibold">{dashboard.name}</h1>
          {hasDescription && <p className="text-sm text-muted-foreground">{dashboard.description}</p>}
        </div>
      )}
      <DashboardGrid
        tiles={tiles}
        canEdit={false}
        publicMode
        onLayoutChange={noop}
        onDeleteTile={noop}
        onEditTitle={noop}
      />
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// SharedDashboardPage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 익명 대시보드 공유 뷰 (라우터/인증 비의존 순수 컴포넌트).
 *
 * mount 시 `getPublicDashboard(token)`(raw fetch)를 호출해 로딩/성공/404 3상태를 관리한다.
 * auth 훅·인증 store는 의도적으로 사용하지 않는다(익명 경로).
 *
 * @param token 공유 토큰 (경로 세그먼트)
 * @param embed 임베드 모드 여부 — true면 크롬 최소화(EC-7)
 */
export function SharedDashboardPage({ token, embed = false }: SharedDashboardPageProps): JSX.Element {
  const { status, dashboard } = useSharedDashboardFetch(token)

  if (status === 'loading') return <SharedDashboardSkeleton />
  if (status === 'error' || dashboard === null) return <SharedDashboardNotFound />

  return <SharedDashboardContent dashboard={dashboard} embed={embed} />
}

// ─────────────────────────────────────────────────────────────────────────────
// SharedDashboardRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TanStack Router useParams + useSearch를 주입해 SharedDashboardPage에 연결하는 어댑터.
 *
 * - useParams()에서 token을 추출한다.
 * - useSearch()에서 embed 쿼리 파라미터(`?embed=1`)를 추출한다 — router.ts validateSearch가 이미
 *   숫자/문자열 표현을 boolean으로 정규화해 두므로 값이 있으면(true) 곧 임베드 모드다.
 * - auth 훅은 의도적으로 사용하지 않는다 — 이 라우트는 인증 가드 밖에 등록된 공개 라우트다.
 *
 * ★ router.ts 등록도 이 task(Task 9) 담당 — beforeLoad 가드 없이 등록한다.
 */
export function SharedDashboardRouteAdapter(): JSX.Element {
  const { token } = useParams({ strict: false }) as { token: string }
  const search = useSearch({ strict: false }) as { embed?: boolean }

  return <SharedDashboardPage token={token} embed={search.embed === true} />
}
