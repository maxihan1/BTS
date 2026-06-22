// 칸반 보드 컬럼 헤더 WIP 카드 수 배지 — 제한 없음/미초과/초과 세 가지 상태 표현
import { cn } from '@/lib/utils'
import { boardLabels } from '@/i18n/board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 디자인 토큰 상수 — 매직 클래스 금지
// ─────────────────────────────────────────────────────────────────────────────

/** WIP 미초과(또는 제한 없음) 배지 — 기본 primary 톤 */
const BADGE_NEUTRAL =
  'rounded-full bg-primary px-1.5 py-0.5 text-xs font-medium text-primary-foreground'

/** WIP 초과 배지 — amber 주의 톤 (경고는 정보, 차단 아님) */
const BADGE_EXCEEDED =
  'rounded-full bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800 border border-amber-300'

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
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 컬럼 헤더의 카드 수 / WIP 제한 배지.
 *
 * - wipLimit=null → 단순 카드 수만 표시 (기존 동작 보존).
 * - wipLimit 있음·미초과 → "{count}/{limit}" 중립 배지.
 * - wipLimit 있음·초과 → "{count}/{limit}" amber 경고 배지 + aria-label.
 */
export function WipCountBadge({ count, wipLimit, wipExceeded }: WipCountBadgeProps) {
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

  if (wipExceeded) {
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

  return (
    <span
      className={BADGE_NEUTRAL}
      aria-label={boardLabels.column.cardCountAriaLabel(count)}
    >
      {label}
    </span>
  )
}
