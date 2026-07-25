// 에픽 진행률을 3색 가로 막대로 표시하는 컴포넌트 — FR-EP-02 Task-5
import type { JSX } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchEpicProgress } from '@/api/epic-children'
import type { EpicProgress } from '@/api/epic-children'
import { epicProgressStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 퍼센트 계산 기준 (0~100) */
const PERCENT_MAX = 100

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 — 비율 계산
// ─────────────────────────────────────────────────────────────────────────────

interface SegmentWidths {
  /** done 구간 너비 (0~100 퍼센트) */
  done: number
  /** inProgress 구간 너비 (0~100 퍼센트) */
  inProgress: number
  /** todo 구간 너비 (0~100 퍼센트) */
  todo: number
}

/**
 * 카테고리별 이슈 수를 받아 각 구간의 너비 퍼센트를 계산한다.
 * total=0이면 모든 구간 0%를 반환한다.
 *
 * @param byCategory 카테고리별 집계 ({todo, inProgress, done})
 * @param total 전체 자식 이슈 수
 * @returns 각 구간 너비 (0~100 정수)
 */
// eslint-disable-next-line react-refresh/only-export-components
export function computeSegmentWidths(
  byCategory: EpicProgress['byCategory'],
  total: number,
): SegmentWidths {
  if (total === 0) {
    return { done: 0, inProgress: 0, todo: 0 }
  }
  const done = Math.round((byCategory.done / total) * PERCENT_MAX)
  const inProgress = Math.round((byCategory.inProgress / total) * PERCENT_MAX)
  // todo는 나머지를 채워 합계 100이 되도록 한다 (반올림 오차 흡수)
  const todo = PERCENT_MAX - done - inProgress
  return { done, inProgress, todo: Math.max(0, todo) }
}

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 진행률 쿼리 키.
 * EpicChildrenSection의 자식 목록 invalidate 시 함께 invalidate되도록
 * 호환 가능한 구조로 정의한다.
 * export는 EpicChildrenSection의 cross-mutation invalidate에서 사용된다.
 *
 * @param epicKey 에픽 이슈 키
 */
// eslint-disable-next-line react-refresh/only-export-components
export const epicProgressKey = (epicKey: string): [string, string, string] =>
  ['epic-progress', epicKey, 'summary']

// ─────────────────────────────────────────────────────────────────────────────
// 막대 UI 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface ProgressBarProps {
  progress: EpicProgress
}

/**
 * 실제 3색 가로 막대를 렌더한다.
 * total=0이면 빈 바(회색 전체)와 "자식 이슈 없음" 텍스트를 표시한다.
 *
 * @remarks
 * visibility 주석: total 값은 호출자의 권한(accessibleLevels)에 따라 다를 수 있으므로
 * 사용자마다 다른 수치가 보이는 것은 버그가 아니다.
 */
function ProgressBarContent({ progress }: ProgressBarProps): JSX.Element {
  const { total, done, donePercentage, byCategory } = progress
  const isEmpty = total === 0
  const segments = computeSegmentWidths(byCategory, total)

  return (
    <div data-testid="epic-progress-bar" className="flex flex-col gap-1">
      {/* 상단 — 퍼센트 + 카운트 */}
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span className="font-medium text-foreground">{donePercentage}%</span>
        <span>
          {isEmpty
            ? epicProgressStrings.noChildrenState
            : epicProgressStrings.countLabel(done, total)}
        </span>
      </div>

      {/* 3색 가로 바 */}
      <div
        role="progressbar"
        aria-valuenow={donePercentage}
        aria-valuemin={0}
        aria-valuemax={PERCENT_MAX}
        aria-label={epicProgressStrings.progressAriaLabel(donePercentage)}
        className="flex h-2 w-full overflow-hidden rounded-full bg-muted"
      >
        {/* done — 완료 상태 토큰 (FR-UX-06 PR22: bg-emerald-500 → bg-success) */}
        <div
          data-testid="progress-segment-done"
          aria-label={epicProgressStrings.doneLabel}
          style={{ width: `${segments.done}%` }}
          className="bg-success transition-all duration-300"
        />
        {/* inProgress — 진행중 상태 토큰 (FR-UX-06 PR22: bg-blue-400 → bg-info) */}
        <div
          data-testid="progress-segment-inprogress"
          aria-label={epicProgressStrings.inProgressLabel}
          style={{ width: `${segments.inProgress}%` }}
          className="bg-info transition-all duration-300"
        />
        {/* todo — 회색 (bg-muted에 흡수되지 않도록 별도 렌더) */}
        <div
          data-testid="progress-segment-todo"
          aria-label={epicProgressStrings.todoLabel}
          style={{ width: `${segments.todo}%` }}
          className="bg-muted-foreground/20 transition-all duration-300"
        />
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props 유니온 — progress 직접 주입 vs epicKey 패칭
// ─────────────────────────────────────────────────────────────────────────────

interface EpicProgressBarWithProgressProps {
  /** 이미 패칭된 진행률 데이터를 직접 전달 */
  progress: EpicProgress
  epicKey?: never
}

interface EpicProgressBarWithKeyProps {
  /** 에픽 이슈 키 — 컴포넌트 내부에서 자체 패칭 */
  epicKey: string
  progress?: never
}

type EpicProgressBarProps = EpicProgressBarWithProgressProps | EpicProgressBarWithKeyProps

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트 — EpicProgressBar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 진행률 3색 가로 막대 컴포넌트.
 *
 * 두 가지 방식으로 사용할 수 있다.
 * 1. `progress` prop으로 직접 데이터 주입 — 부모가 이미 패칭한 경우 (단위 테스트 친화)
 * 2. `epicKey` prop으로 자체 패칭 — `fetchEpicProgress`로 내부 useQuery 사용
 *
 * @remarks
 * visibility 주석: `total` 값은 조회 권한(accessibleLevels)에 따라 사용자마다 다를 수 있다.
 * 서로 다른 수치가 보이는 것은 의도된 동작이며 버그가 아니다.
 *
 * @param progress 이미 패칭된 진행률 데이터 (progress 모드)
 * @param epicKey 에픽 이슈 키 (epicKey 모드)
 */
export const EpicProgressBar = (props: EpicProgressBarProps): JSX.Element => {
  if (props.progress !== undefined) {
    return <ProgressBarContent progress={props.progress} />
  }

  return <EpicProgressBarFetcher epicKey={props.epicKey} />
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 패칭 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface EpicProgressBarFetcherProps {
  epicKey: string
}

/**
 * epicKey를 받아 자체 패칭하는 내부 컴포넌트.
 * EpicChildrenSection과 동일한 useQuery 패턴 사용.
 */
function EpicProgressBarFetcher({ epicKey }: EpicProgressBarFetcherProps): JSX.Element {
  const { data, isLoading } = useQuery({
    queryKey: epicProgressKey(epicKey),
    queryFn: () => fetchEpicProgress(epicKey),
    staleTime: 30_000,
  })

  if (isLoading) {
    return (
      <div data-testid="epic-progress-loading" className="flex flex-col gap-1">
        <div className="h-2 w-full animate-pulse rounded-full bg-muted" />
      </div>
    )
  }

  if (data === undefined) {
    return (
      <div data-testid="epic-progress-bar" className="flex flex-col gap-1">
        <div
          role="progressbar"
          aria-valuenow={0}
          aria-valuemin={0}
          aria-valuemax={PERCENT_MAX}
          aria-label={epicProgressStrings.progressAriaLabel(0)}
          className="h-2 w-full rounded-full bg-muted"
        />
      </div>
    )
  }

  return <ProgressBarContent progress={data} />
}
