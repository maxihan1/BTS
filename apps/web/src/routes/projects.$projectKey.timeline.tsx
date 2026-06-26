// 타임라인 라우트 — TimelineRouteAdapter + TimelinePage (FR-TL-01 Task 6)
import type { JSX } from 'react'
import { useMemo } from 'react'
import { useParams, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'
import { ApiError } from '@/api/client'
import { fetchUsers } from '@/api/users'
import type { TimelineItem } from '@/api/timeline'
import { useTimeline } from '@/hooks/use-timeline'
import { GanttChart } from '@/components/timeline/GanttChart'
import { timelineLabels } from '@/i18n/timeline-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수
// ─────────────────────────────────────────────────────────────────────────────

const AGILE_ACCESS_DENIED = 'AGILE_ACCESS_DENIED'

/** ProblemDetail body에서 errorCode를 추출하는 헬퍼 */
function extractErrorCode(body: unknown): string | undefined {
  if (body !== null && typeof body === 'object' && 'errorCode' in body) {
    const code = (body as Record<string, unknown>)['errorCode']
    return typeof code === 'string' ? code : undefined
  }
  return undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 사용자 배열 스키마 — userId → displayName|username 맵 구성용
// ─────────────────────────────────────────────────────────────────────────────

/** userId → displayName|username 맵을 구성하는 최소 Zod 배열 스키마 (board 패턴 미러) */
const usersArraySchema = z.array(
  z.object({
    id: z.string(),
    username: z.string(),
    displayName: z.string().nullable(),
  }),
)

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼 — 컴포넌트 외부 추출 (테스트 가능, 재렌더 없이 재계산)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * usersRaw를 userId → displayName|username Map으로 변환한다.
 * 파싱 실패 또는 undefined이면 빈 Map을 반환한다.
 * board 패턴 미러 (C2).
 *
 * @param usersRaw useQuery 원본 응답 (unknown — QueryClient 캐시 타입 소거 대응)
 * @returns userId → 표시이름 Map
 */
function buildUserMap(usersRaw: unknown): Map<string, string> {
  const parsed = usersRaw !== undefined ? usersArraySchema.safeParse(usersRaw) : null
  if (parsed === null || !parsed.success) return new Map()
  const map = new Map<string, string>()
  for (const u of parsed.data) {
    map.set(u.id, u.displayName ?? u.username)
  }
  return map
}

/**
 * TimelineItem 목록과 userId→name Map으로 issueKey→displayName Map을 구성한다.
 * GanttChart의 resolveAssigneeName이 fallback(미배정/알 수 없음)을 처리하므로
 * name이 없는 경우(assigneeId=null 또는 미해석 userId)는 맵에서 제외한다.
 *
 * @param items 타임라인 아이템 목록
 * @param userMap userId → 표시이름 Map
 * @returns issueKey → 표시이름 Map (GanttChart assigneeNames prop용)
 */
function buildAssigneeNames(items: TimelineItem[], userMap: Map<string, string>): Map<string, string> {
  const map = new Map<string, string>()
  for (const item of items) {
    if (item.assigneeId !== null) {
      const name = userMap.get(item.assigneeId)
      if (name !== undefined) {
        map.set(item.key, name)
      }
    }
  }
  return map
}

// ─────────────────────────────────────────────────────────────────────────────
// 스켈레톤 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 로딩 중 플레이스홀더 — shadcn Skeleton 미설치이므로 인라인 구현 (board 패턴 동일) */
function Skeleton({ className }: { className?: string }): JSX.Element {
  return (
    <div
      className={`animate-pulse rounded-md bg-muted ${className ?? ''}`}
      aria-hidden="true"
    />
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// TimelineRouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * router.ts에 등록되는 라우트 어댑터.
 * useParams로 $projectKey를 추출해 TimelinePage에 전달한다.
 * projectKey 변경 시 TimelinePage를 key prop으로 재마운트 — GanttChart collapsedGroups 초기화.
 */
export function TimelineRouteAdapter(): JSX.Element {
  const { projectKey } = useParams({ strict: false })
  const key = projectKey ?? ''
  return <TimelinePage key={key} projectKey={key} />
}

// ─────────────────────────────────────────────────────────────────────────────
// TimelinePage Props
// ─────────────────────────────────────────────────────────────────────────────

/** TimelinePage Props */
export interface TimelinePageProps {
  /** URL params에서 추출한 프로젝트 식별 키 */
  projectKey: string
}

// ─────────────────────────────────────────────────────────────────────────────
// TimelinePage 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 타임라인(Gantt) 페이지.
 *
 * - useTimeline(projectKey)로 타임라인 아이템 목록 조회.
 * - useQuery(['users'], fetchUsers)로 사용자 목록 조회 → issueKey→displayName 매핑 (C2).
 * - 상태 분기: 로딩(Skeleton), 403(접근거부 S6), 빈 상태(S4 안내), 정상(GanttChart + truncated 배너 S5).
 * - onSelectIssue → navigate /issues/$key.
 *
 * @param projectKey 프로젝트 식별 키
 */
export function TimelinePage({ projectKey }: TimelinePageProps): JSX.Element {
  const navigate = useNavigate()

  const { data, isLoading, error, isError } = useTimeline(projectKey)

  // 전체 사용자 목록 조회 — userId → displayName|username Map 구성용 (C2, board 패턴 미러)
  const { data: usersRaw } = useQuery({
    queryKey: ['users'],
    queryFn: () => fetchUsers(),
    staleTime: 60_000,
    enabled: projectKey !== '',
  })

  // userId → displayName|username Map (best-effort, N+1 방지)
  const userMap: Map<string, string> = useMemo(() => buildUserMap(usersRaw), [usersRaw])

  // issueKey → displayName Map (GanttChart assigneeNames prop용, C2)
  const assigneeNames: Map<string, string> = useMemo(
    () => buildAssigneeNames(data?.items ?? [], userMap),
    [data, userMap],
  )

  /** 이슈 행 클릭 → /issues/$key 이동 */
  function handleSelectIssue(key: string): void {
    void navigate({ to: '/issues/$key', params: { key } })
  }

  // 403 접근 거부 판정 (S6) — status 또는 errorCode 기준 (board 패턴 미러)
  const isAccessDenied =
    isError &&
    error instanceof ApiError &&
    (error.status === 403 || extractErrorCode(error.body) === AGILE_ACCESS_DENIED)

  // ── 로딩 ──────────────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  // ── 403 접근 거부 (S6) ────────────────────────────────────────────────────

  if (isAccessDenied) {
    return (
      <div className="p-8 flex flex-col items-center justify-center min-h-48 gap-4 text-center">
        <p className="text-lg font-medium">접근 권한이 없습니다</p>
        <p className="text-sm text-muted-foreground">
          해당 프로젝트의 타임라인에 접근할 권한이 없습니다.
        </p>
      </div>
    )
  }

  // ── 빈 상태 (S4) ─────────────────────────────────────────────────────────

  if (data !== undefined && data.items.length === 0) {
    return (
      <div className="p-8 flex flex-col items-center justify-center min-h-48 text-center">
        <p className="text-sm text-muted-foreground">{timelineLabels.empty.noItems}</p>
      </div>
    )
  }

  // ── 정상 — GanttChart ────────────────────────────────────────────────────

  return (
    <div className="p-4 space-y-3">
      {/* 경고 배너 — truncated (S5) */}
      {data?.truncated === true && (
        <div
          role="alert"
          className="rounded-md bg-amber-50 border border-amber-200 px-4 py-2 text-sm text-amber-800"
        >
          표시되지 않은 이슈가 있습니다. 이슈 목록에서 전체를 확인하세요.
        </div>
      )}

      {/* GanttChart — data가 있는 경우에만 렌더 */}
      {data !== undefined && (
        <GanttChart
          items={data.items}
          assigneeNames={assigneeNames}
          onSelectIssue={handleSelectIssue}
        />
      )}
    </div>
  )
}
