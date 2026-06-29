// 타임라인 Gantt 위 blocks 의존 라인 SVG 오버레이 — elbow 경로 + 클릭 강조
import type { JSX } from 'react'
import { useState } from 'react'
import type { DependencyLine } from '@/lib/timeline-layout'
import { timelineLabels } from '@/i18n/timeline-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** SVG <marker> ID — 화살촉 끝점 */
const MARKER_ID = 'dep-arrow-end'

/** 기본 라인 stroke 폭(px) */
const STROKE_WIDTH_DEFAULT = 1.5

/** 선택된 라인 stroke 폭(px) */
const STROKE_WIDTH_SELECTED = 2.5

/** 투명 hit-path stroke 폭(px) — 클릭 타겟 확대 */
const HIT_STROKE_WIDTH = 12

/** 비선택 라인 opacity — 흐림 처리 */
const OPACITY_DIMMED = 0.3

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** DependencyOverlay Props */
export interface DependencyOverlayProps {
  /** computeDependencyLines 결과 */
  lines: DependencyLine[]
  /**
   * 축 헤더 오프셋(px).
   *
   * 막대 영역 내 y 좌표(computeDependencyLines 결과)에 더해 SVG 내 절대 y로 변환.
   * GanttChart가 AXIS_HEIGHT_PX를 전달한다.
   */
  axisOffset: number
  /** SVG 전체 폭(px) — 우측 막대 영역 폭 */
  width: number
  /** SVG 전체 높이(px) — 행 높이 합 + axisOffset */
  height: number
}

// ─────────────────────────────────────────────────────────────────────────────
// DependencyOverlay 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 Gantt 우측 막대 영역 위 blocks 의존 라인 SVG 오버레이.
 *
 * **레이아웃**:
 * - `position: absolute`로 부모 막대 영역 위에 오버레이 (GanttChart가 위치 결정).
 * - SVG 전체 `pointer-events: none` — 기본 클릭이 하위 막대/레이블로 통과.
 * - 배경 rect (`pointer-events: all`, fill=transparent) — 빈 영역 클릭으로 선택 해제(S3).
 * - hit-path (`pointer-events: all`, stroke=transparent, strokeWidth=12) — 클릭 타겟 확대.
 *
 * **elbow path**: `M startX,startY+axisOffset H midX V endY+axisOffset H endX`
 *
 * **클릭 강조(S2/S3)**:
 * - `selectedKey = "${blockerKey}__${blockedKey}"`.
 * - hit-path 클릭 → 같은 key면 해제, 다른 key면 선택.
 * - 선택 시 해당 라인 stroke 굵게 + `stroke-primary`. 비선택 라인 `opacity=0.3` 흐림.
 * - 배경 rect 클릭 → selectedKey null (해제).
 *
 * **접근성(NFR3)**: 각 visible path에 `aria-label` — `deps.lineAriaLabel(blocker, blocked)`.
 *
 * @param props 좌표는 부모(GanttChart)가 계산해 주입 — jsdom 안전(getBBox 미사용)
 */
export function DependencyOverlay({
  lines,
  axisOffset,
  width,
  height,
}: DependencyOverlayProps): JSX.Element {
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  return (
    <svg
      width={width}
      height={height}
      style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none' }}
    >
      <defs>
        {/* 화살촉 마커 — blocker → blocked 방향 */}
        <marker
          id={MARKER_ID}
          markerWidth="8"
          markerHeight="8"
          refX="6"
          refY="0"
          orient="auto"
          markerUnits="strokeWidth"
        >
          <path d="M 0,-3 L 6,0 L 0,3 Z" className="fill-muted-foreground" />
        </marker>
      </defs>

      {/* 배경 클릭 → 선택 해제 (S3) */}
      <rect
        data-testid="dep-overlay-bg"
        x={0}
        y={0}
        width={width}
        height={height}
        fill="transparent"
        style={{ pointerEvents: 'all', cursor: 'default' }}
        onClick={() => setSelectedKey(null)}
      />

      {lines.map((line) => {
        const key = `${line.blockerKey}__${line.blockedKey}`
        const isSelected = selectedKey === key
        const isDimmed = selectedKey !== null && !isSelected

        // elbow d: M startX,startY+offset H midX V endY+offset H endX
        const d = `M ${line.startX},${line.startY + axisOffset} H ${line.midX} V ${line.endY + axisOffset} H ${line.endX}`

        return (
          <g key={key} style={{ pointerEvents: 'none' }}>
            {/* Visible elbow path — 색/굵기/opacity로 강조 표현 */}
            <path
              d={d}
              fill="none"
              className={isSelected ? 'stroke-primary' : 'stroke-muted-foreground'}
              strokeWidth={isSelected ? STROKE_WIDTH_SELECTED : STROKE_WIDTH_DEFAULT}
              opacity={isDimmed ? OPACITY_DIMMED : 1}
              markerEnd={`url(#${MARKER_ID})`}
              aria-label={timelineLabels.deps.lineAriaLabel(line.blockerKey, line.blockedKey)}
              data-selected={isSelected ? 'true' : undefined}
              data-dimmed={isDimmed ? 'true' : undefined}
            />
            {/* Hit-path: 투명 넓은 클릭 타겟 (얇은 라인 클릭 fiddly 해소) */}
            <path
              d={d}
              fill="none"
              stroke="transparent"
              strokeWidth={HIT_STROKE_WIDTH}
              style={{ cursor: 'pointer', pointerEvents: 'all' }}
              onClick={(e) => {
                e.stopPropagation()
                setSelectedKey((prev) => (prev === key ? null : key))
              }}
            />
          </g>
        )
      })}
    </svg>
  )
}
