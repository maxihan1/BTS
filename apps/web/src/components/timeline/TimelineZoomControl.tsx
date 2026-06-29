// 타임라인 줌 컨트롤 — 세그먼트 버튼(주/월/분기) + 확대/축소 버튼 (FR-TL-03 Task 4)
import { Button } from '@/components/ui/button'
import { timelineLabels } from '@/i18n/timeline-labels'
import { ZOOM_LEVELS, nextZoomIn, nextZoomOut, type ZoomLevel } from '@/lib/timeline-zoom'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 세그먼트 버튼 단축키 힌트 매핑 (D3 — aria-keyshortcuts).
 * 실제 키 핸들링은 Task 5 훅이 담당하며, 여기서는 discoverability 힌트만 제공한다.
 */
const ZOOM_KEYSHORTCUTS: Readonly<Record<ZoomLevel, string>> = {
  week: '1',
  month: '2',
  quarter: '3',
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** TimelineZoomControl 컴포넌트 props */
export interface TimelineZoomControlProps {
  /** 현재 줌 레벨 */
  zoomLevel: ZoomLevel
  /** 줌 레벨 변경 콜백 */
  onZoomChange: (level: ZoomLevel) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 줌 컨트롤.
 *
 * - 세그먼트 그룹: ZOOM_LEVELS(week/month/quarter) 순서 버튼. 현재 레벨은 `aria-pressed=true`.
 * - 확대(+)/축소(−) 버튼: 끝단(week/quarter)에서 각각 `disabled`.
 * - D2: 두 그룹은 별도 컨테이너 + `gap-3` 간격으로 시각적으로 구분된다.
 * - D3: 세그먼트 버튼에 `aria-keyshortcuts`(1/2/3) 힌트 포함. 실제 키 핸들링은 Task 5.
 */
export const TimelineZoomControl = ({ zoomLevel, onZoomChange }: TimelineZoomControlProps) => {
  const zoomedIn = nextZoomIn(zoomLevel)
  const zoomedOut = nextZoomOut(zoomLevel)
  const isAtMaxZoom = zoomedIn === zoomLevel   // week — 확대 끝단
  const isAtMinZoom = zoomedOut === zoomLevel  // quarter — 축소 끝단

  return (
    <div
      role="group"
      aria-label={timelineLabels.zoom.groupAriaLabel}
      className="flex items-center gap-3"
    >
      {/* 세그먼트 그룹 — 줌 레벨 직접 선택 (D2: 별도 컨테이너) */}
      <div className="flex">
        {ZOOM_LEVELS.map((level) => (
          <Button
            key={level}
            type="button"
            variant={zoomLevel === level ? 'default' : 'outline'}
            size="sm"
            aria-pressed={zoomLevel === level}
            aria-keyshortcuts={ZOOM_KEYSHORTCUTS[level]}
            onClick={() => onZoomChange(level)}
          >
            {timelineLabels.zoom[level]}
          </Button>
        ))}
      </div>

      {/* 확대/축소 버튼 그룹 (D2: 별도 컨테이너) */}
      <div className="flex gap-1">
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          aria-label={timelineLabels.zoom.zoomOutAriaLabel}
          disabled={isAtMinZoom}
          onClick={() => onZoomChange(zoomedOut)}
        >
          {'−'}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-sm"
          aria-label={timelineLabels.zoom.zoomInAriaLabel}
          disabled={isAtMaxZoom}
          onClick={() => onZoomChange(zoomedIn)}
        >
          {'+'}
        </Button>
      </div>
    </div>
  )
}
