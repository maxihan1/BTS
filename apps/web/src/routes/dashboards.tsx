// 대시보드 목록 + 생성 라우트 — DashboardsRouteAdapter + DashboardsListPage (FR-DB-01 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { useNavigate, Link } from '@tanstack/react-router'
import type { Dashboard } from '@/api/dashboards'
import { useDashboards, useCreateDashboard } from '@/hooks/use-dashboards'
import type { DashboardFormPayload } from '@/components/dashboard/DashboardForm'
import { DashboardForm } from '@/components/dashboard/DashboardForm'
import { dashboardLabels } from '@/i18n/dashboard-labels'
import { useAuthUser } from '@/auth/authStore'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// 인라인 Skeleton 헬퍼 — shadcn Skeleton 미설치이므로 board 라우트 패턴 차용
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// visibility 배지 — shadcn Badge 미설치이므로 DESIGN 토큰 인라인 사용
// ─────────────────────────────────────────────────────────────────────────────

/** visibility 값에 따른 라벨 매핑 */
const VISIBILITY_LABEL: Record<string, string> = {
  PRIVATE: dashboardLabels.card.visibility.PRIVATE,
  TEAM: dashboardLabels.card.visibility.TEAM,
  ORG: dashboardLabels.card.visibility.ORG,
}

/** visibility 배지 인라인 컴포넌트 props */
interface VisibilityBadgeProps {
  /** 공개 범위 코드 ("PRIVATE"|"TEAM"|"ORG") */
  visibility: string
}

/**
 * visibility 코드를 한국어 배지로 렌더한다.
 * shadcn Badge 미설치이므로 muted 배경 + 텍스트 라벨 인라인 구현.
 * 새 색 토큰은 도입하지 않는다 (DESIGN.md §신규 토큰 도입 금지).
 */
function VisibilityBadge({ visibility }: VisibilityBadgeProps): JSX.Element {
  const label = VISIBILITY_LABEL[visibility] ?? visibility
  return (
    <span className="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
      {label}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardCard 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** DashboardCard props */
interface DashboardCardProps {
  /** 렌더할 대시보드 데이터 */
  dashboard: Dashboard
  /** 현재 로그인 사용자 ID (소유 여부 판단용) */
  currentUserId: string | undefined
}

/**
 * 대시보드 목록 카드.
 *
 * - 이름: text-lg font-semibold
 * - 설명: text-sm text-muted-foreground, 1줄 truncate
 * - visibility 배지: 우상단 VisibilityBadge
 * - 소유 여부: ownerId === currentUserId이면 "내 대시보드" 배지
 *
 * @param dashboard 대시보드 데이터
 * @param currentUserId 현재 사용자 ID
 */
function DashboardCard({ dashboard, currentUserId }: DashboardCardProps): JSX.Element {
  const isOwner = currentUserId !== undefined && dashboard.ownerId === currentUserId

  return (
    <Link
      to="/dashboards/$dashboardId"
      params={{ dashboardId: dashboard.id }}
      className="relative block rounded-lg border bg-card p-4 shadow-sm hover:shadow-md transition-shadow focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      data-testid="dashboard-card-link"
    >
      {/* visibility 배지 — 우상단 */}
      <div className="absolute top-3 right-3 flex items-center gap-1.5">
        {isOwner && (
          <span className="inline-flex items-center rounded-md bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground">
            {dashboardLabels.card.ownerBadge}
          </span>
        )}
        <VisibilityBadge visibility={dashboard.visibility} />
      </div>

      {/* 이름 */}
      <h3 className="text-lg font-semibold pr-32 leading-snug">{dashboard.name}</h3>

      {/* 설명 — null/undefined이면 렌더하지 않음 */}
      {dashboard.description != null && dashboard.description !== '' && (
        <p className="mt-1 text-sm text-muted-foreground truncate">{dashboard.description}</p>
      )}
    </Link>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardsListPage
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 목록 + 생성 페이지.
 *
 * - useDashboards로 목록 조회 (Task 4).
 * - isLoading → Skeleton. isError → 에러 메시지 + 다시 시도.
 * - 빈 목록(EC1) → 빈 상태 + 생성 CTA.
 * - 목록 → DashboardCard 그리드.
 * - "대시보드 만들기" 버튼 → DashboardForm(Task 6) 토글.
 * - DashboardForm 제출 → useCreateDashboard → 성공 시 /dashboards/$id 이동.
 *
 * 라우터 의존성 없음 — useNavigate는 DashboardsRouteAdapter에서 주입해도 되나,
 * 단순성을 위해 직접 사용 (DashboardsRouteAdapter도 같은 provider 안에서 렌더됨).
 */
export function DashboardsListPage(): JSX.Element {
  const navigate = useNavigate()
  const currentUser = useAuthUser()
  const currentUserId = currentUser?.userId

  const { data, isLoading, isError, refetch } = useDashboards()
  const { mutate: createDashboard, isPending } = useCreateDashboard()

  const [showForm, setShowForm] = useState(false)

  /** DashboardForm 제출 핸들러 */
  function handleFormSubmit(payload: DashboardFormPayload): void {
    // DashboardFormPayload = Record<string,unknown> — unknown 경유 캐스팅 필요
    createDashboard(
      payload as unknown as Parameters<typeof createDashboard>[0],
      {
        onSuccess: (created: Dashboard) => {
          void navigate({ to: '/dashboards/$dashboardId', params: { dashboardId: created.id } })
        },
      },
    )
  }

  // ── 로딩 ──────────────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-8 w-48" aria-hidden="true" />
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <Skeleton className="h-28" aria-hidden="true" />
          <Skeleton className="h-28" aria-hidden="true" />
          <Skeleton className="h-28" aria-hidden="true" />
        </div>
      </div>
    )
  }

  // ── 에러 ──────────────────────────────────────────────────────────────────

  if (isError) {
    return (
      <div className="p-8 flex flex-col items-center justify-center min-h-48 gap-4 text-center">
        <p className="text-sm text-muted-foreground">{dashboardLabels.list.error}</p>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => { void refetch() }}
        >
          다시 시도
        </Button>
      </div>
    )
  }

  const items = data?.items ?? []

  // ── 빈 목록 (EC1) ─────────────────────────────────────────────────────────

  if (items.length === 0) {
    return (
      <div className="p-8 flex flex-col items-center justify-center min-h-64 gap-6 text-center">
        <div className="space-y-2">
          {/* 빈 상태 제목 — labels의 title보다 맥락 강조를 위해 "아직" 프리픽스 추가 */}
          <h2 className="text-lg font-semibold">아직 {dashboardLabels.list.empty.title}</h2>
          <p className="text-sm text-muted-foreground">{dashboardLabels.list.empty.description}</p>
        </div>

        {showForm ? (
          <DashboardForm mode="create" onSubmit={handleFormSubmit} isPending={isPending} />
        ) : (
          <Button type="button" onClick={() => setShowForm(true)}>
            {dashboardLabels.list.empty.cta}
          </Button>
        )}
      </div>
    )
  }

  // ── 목록 ──────────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-6">
      {/* 헤더 — 페이지 제목 + 생성 버튼 */}
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{dashboardLabels.list.title}</h1>
        <Button
          type="button"
          onClick={() => setShowForm((prev) => !prev)}
        >
          {dashboardLabels.list.empty.cta}
        </Button>
      </div>

      {/* 인라인 생성 폼 — 버튼 토글로 표시 */}
      {showForm && (
        <div className="rounded-lg border bg-card p-6">
          <DashboardForm mode="create" onSubmit={handleFormSubmit} isPending={isPending} />
        </div>
      )}

      {/* 대시보드 카드 그리드 */}
      <ul className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4" aria-label={dashboardLabels.list.title}>
        {items.map((dashboard) => (
          <li key={dashboard.id}>
            <DashboardCard dashboard={dashboard} currentUserId={currentUserId} />
          </li>
        ))}
      </ul>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// DashboardsRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터.
 * 라우터 의존성(useParams, useSearch 등)을 격리해 DashboardsListPage를 순수하게 유지한다.
 *
 * /dashboards 경로는 search/path params가 없으므로 현재는 단순 위임만 담당한다.
 * 향후 ?page= 등 필터가 추가되면 이 어댑터에서 추출해 props로 전달한다.
 */
export function DashboardsRouteAdapter(): JSX.Element {
  return <DashboardsListPage />
}
