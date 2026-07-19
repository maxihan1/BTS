// 칸반 보드 컬럼 헤더 WIP 카드 수 배지 — 제한 없음/미초과/초과 세 가지 상태 표현
import { cn } from '@/lib/utils'
import { boardLabels } from '@/i18n/board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 디자인 토큰 상수 — 매직 클래스 금지
// ─────────────────────────────────────────────────────────────────────────────

/** WIP 미초과(또는 제한 없음) 배지 — 기본 primary 톤 */
const BADGE_NEUTRAL =
  'rounded-full bg-primary px-1.5 py-0.5 text-xs font-medium text-primary-foreground'

/** WIP 초과 배지 — warning 주의 톤 (경고는 정보, 차단 아님) */
const BADGE_EXCEEDED =
  'rounded-full bg-warning/10 px-1.5 py-0.5 text-xs font-medium text-warning-text border border-warning'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** WipCountBadge 컴포넌트 Props */
export interface WipCountBadgeProps {
  /** 현재 컬럼 카드 수 */
  count: number
  /** WIP 제한. null이면 제한 없음 */
  wipLimit: number | null
  /** WIP 초과 여부 (백엔드가 판정) */
  wipExceeded: boolean
  /**
   * 보드 필터 활성 여부.
   * true이면 카드 수가 필터된 수이므로 WIP 초과 경고를 약화 + "(필터됨)" 표시.
   * 이동/판정 로직에는 영향 없음 — 표시만 변경.
   */
  isFilterActive?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 컬럼 헤더의 카드 수 / WIP 제한 배지.
 *
 * - wipLimit=null → 단순 카드 수만 표시 (기존 동작 보존).
 * - wipLimit 있음·미초과 → "{count}/{limit}" 중립 배지.
 * - wipLimit 있음·초과·isFilterActive=false → "{count}/{limit}" warning 경고 배지 + aria-label.
 * - wipLimit 있음·초과·isFilterActive=true → 경고색 제거 + "(필터됨)" 라벨 (필터로 전체 수 알 수 없음 고지).
 */
export function WipCountBadge({ count, wipLimit, wipExceeded, isFilterActive = false }: WipCountBadgeProps) {
  if (wipLimit === null) {
    return (
      <span
        className={BADGE_NEUTRAL}
        aria-label={boardLabels.column.cardCountAriaLabel(count)}
      >
        {count}
      </span>
    )
  }

  const label = boardLabels.wip.countLabel(count, wipLimit)

  if (wipExceeded && !isFilterActive) {
    return (
      <span
        className={cn(BADGE_EXCEEDED)}
        aria-label={boardLabels.wip.exceededAriaLabel}
        title={boardLabels.wip.exceededTooltip}
      >
        {label}
      </span>
    )
  }

  if (wipExceeded && isFilterActive) {
    return (
      <span
        className={BADGE_NEUTRAL}
        aria-label={boardLabels.wip.filteredAriaLabel}
      >
        {label}{' '}
        <span className="text-muted-foreground">{boardLabels.wip.filteredSuffix}</span>
      </span>
    )
  }

  return (
    <span
      className={BADGE_NEUTRAL}
      aria-label={boardLabels.column.cardCountAriaLabel(count)}
    >
      {label}
    </span>
  )
}
