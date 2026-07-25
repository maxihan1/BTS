// 스프린트 번다운/번업 차트 라우트 — RouteAdapter($projectKey/$sprintId, useSearch) + 상태별 화면 (FR-RP-01 D6/D7 Task-4)
import type { JSX } from 'react'
import { useParams, useSearch, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { fetchSprintBurndown } from '@/api/burndown'
import type { BurndownView } from '@/api/burndown'
import { ApiError } from '@/api/client'
import { BurndownChart } from '@/components/burndown/BurndownChart'
import { burndownLabels } from '@/i18n/burndown-labels'
import { Skeleton } from '@/components/ui/skeleton'

// ─────────────────────────────────────────────────────────────────────────────
// 상태별 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 로딩 중 표시하는 차트 높이 고정 스켈레톤.
 * BurndownChart 컨테이너 고정 높이(360px, h-[360px])와 동일한 값으로 레이아웃 시프트를 방지한다.
 */
function BurndownSkeleton(): JSX.Element {
  return (
    <Skeleton
      role="status"
      aria-label={burndownLabels.status.loading}
      className="h-[360px] w-full border"
    />
  )
}

interface BurndownStatusMessageProps {
  /** 표시할 안내 문구 */
  message: string
}

/** 403/404/422/빈 데이터/일반 에러 공용 안내 화면 — warm 톤(경고색 미사용, 중립 텍스트) */
function BurndownStatusMessage({ message }: BurndownStatusMessageProps): JSX.Element {
  return (
    <div className="rounded-md border p-8 text-center text-sm text-muted-foreground">{message}</div>
  )
}

/**
 * ApiError.status로부터 표시할 안내 문구를 결정한다.
 * 403(권한없음)/404(스프린트없음)/422(날짜미설정)는 지정 라벨, 그 외(5xx 등)는 status.loadFailed로 폴백한다.
 *
 * @param status ApiError.status(없으면 undefined)
 */
function resolveErrorMessage(status: number | undefined): string {
  switch (status) {
    case 403:
      return burndownLabels.status.forbidden
    case 404:
      return burndownLabels.status.sprintNotFound
    case 422:
      return burndownLabels.status.datesRequired
    default:
      return burndownLabels.status.loadFailed
  }
}

interface BurndownViewToggleProps {
  /** 현재 선택된 뷰 */
  view: BurndownView
  /** 뷰 변경 콜백 — URL search param(?view=) 갱신 */
  onChange: (next: BurndownView) => void
}

/** 번다운/번업 뷰 전환 segmented control (role="tablist", 키보드/aria/포커스 지원) */
function BurndownViewToggle({ view, onChange }: BurndownViewToggleProps): JSX.Element {
  const options: Array<{ value: BurndownView; label: string }> = [
    { value: 'burndown', label: burndownLabels.toggle.burndown },
    { value: 'burnup', label: burndownLabels.toggle.burnup },
  ]

  return (
    <div role="tablist" aria-label={burndownLabels.page.title} className="inline-flex rounded-md border p-0.5">
      {options.map((option) => {
        const isActive = view === option.value
        return (
          // PR22 OUT — P4 role="tab": 탭 시맨틱을 직접 지정하므로 Button 프리미티브의 role 처리와 충돌할 위험이 있다
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(option.value)}
            className={`rounded px-3 py-1.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              isActive ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Page — 라우터 비의존 (props 기반, 단위 테스트 가능)
// ─────────────────────────────────────────────────────────────────────────────

/** SprintBurndownPage props */
export interface SprintBurndownPageProps {
  /** URL params에서 추출한 스프린트 ID */
  sprintId: string
  /** URL search에서 추출한 현재 뷰(번다운/번업) */
  view: BurndownView
  /** 뷰 토글 변경 콜백 */
  onViewChange: (next: BurndownView) => void
}

/**
 * 스프린트 번다운/번업 차트 페이지.
 *
 * 상태 분기 순서: 403 → 404 → 422(날짜 미설정) → 일반 에러 → 로딩 → 빈 데이터 → 차트.
 *
 * @param sprintId 조회할 스프린트 ID
 * @param view 현재 뷰(번다운/번업)
 * @param onViewChange 뷰 토글 변경 콜백
 */
export function SprintBurndownPage({ sprintId, view, onViewChange }: SprintBurndownPageProps): JSX.Element {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['sprint-burndown', sprintId],
    queryFn: () => fetchSprintBurndown(sprintId),
    enabled: sprintId !== '',
  })

  const status = error instanceof ApiError ? error.status : undefined

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-8">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold">{burndownLabels.page.title}</h1>
        <BurndownViewToggle view={view} onChange={onViewChange} />
      </header>

      {isError && <BurndownStatusMessage message={resolveErrorMessage(status)} />}
      {!isError && isPending && <BurndownSkeleton />}
      {!isError && !isPending && data !== undefined && data.points.length === 0 && (
        <BurndownStatusMessage message={burndownLabels.status.empty} />
      )}
      {!isError && !isPending && data !== undefined && data.points.length > 0 && (
        <BurndownChart response={data} view={view} />
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter — router.ts 등록용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터.
 * useParams로 $projectKey/$sprintId, useSearch로 view를 추출한다.
 * 토글 변경 시 navigate로 URL search(?view=)를 갱신한다 (공유 URL 정합).
 */
export function SprintBurndownRouteAdapter(): JSX.Element {
  const { projectKey, sprintId } = useParams({ strict: false })
  const search = useSearch({ strict: false }) as { view?: BurndownView }
  const navigate = useNavigate()

  const view: BurndownView = search.view === 'burnup' ? 'burnup' : 'burndown'

  function handleViewChange(next: BurndownView): void {
    void navigate({
      to: '/projects/$projectKey/sprints/$sprintId/burndown',
      params: { projectKey: projectKey ?? '', sprintId: sprintId ?? '' },
      search: (prev) => ({ ...prev, view: next }),
    })
  }

  return <SprintBurndownPage sprintId={sprintId ?? ''} view={view} onViewChange={handleViewChange} />
}
