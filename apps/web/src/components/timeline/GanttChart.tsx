// Gantt 차트 루트 컴포넌트 — Epic 그룹 조립·접기/펼치기·레이블 열·스크롤 영역 (FR-TL-01 D6)
import type { JSX } from 'react'
import { useState } from 'react'
import type { TimelineItem } from '@/api/timeline'
import {
  assembleEpicGroups,
  computeDateRange,
  DAY_WIDTH_PX,
  LABEL_UNCLASSIFIED,
} from '@/lib/timeline-layout'
import { timelineLabels } from '@/i18n/timeline-labels'
import { TimelineAxis } from './TimelineAxis'
import { TimelineRow, ROW_HEIGHT_PX } from './TimelineRow'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 좌측 레이블 열 폭(px) — sticky left-0 고정 */
const LABEL_COL_WIDTH_PX = 240

/** 축 헤더 전체 높이(px) — 월 눈금 행 + 주 눈금 행 */
const AXIS_HEIGHT_PX = ROW_HEIGHT_PX * 1.5

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 키 기준으로 담당자 표시명을 반환한다.
 *
 * - `assigneeNames`에 issueKey가 있으면 해당 값 반환
 * - `assigneeId === null` → "미배정" (EC11)
 * - `assigneeId !== null` & map에 없음 → "알 수 없음" (EC11)
 *
 * @param item 타임라인 아이템
 * @param assigneeNames issueKey → displayName 맵
 * @returns 담당자 표시 문자열
 */
function resolveAssigneeName(item: TimelineItem, assigneeNames: Map<string, string>): string {
  const name = assigneeNames.get(item.key)
  if (name !== undefined) return name
  if (item.assigneeId === null) return timelineLabels.row.unassigned
  return timelineLabels.row.unknownAssignee
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** GanttChart Props */
export interface GanttChartProps {
  /** 백엔드 정렬된 타임라인 아이템 목록 */
  items: TimelineItem[]
  /**
   * issueKey → 담당자 displayName 맵.
   * 부모가 userId → name 해석 후 issueKey 기준으로 주입한다.
   */
  assigneeNames: Map<string, string>
  /** 행 클릭 시 이슈 상세로 이동하는 콜백 */
  onSelectIssue: (key: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// GanttChart 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Gantt 차트 루트 컴포넌트.
 *
 * - `assembleEpicGroups`로 Epic 기준 그룹을 조립하고, `computeDateRange`로 전체 날짜 범위를 계산한다.
 * - 좌측 레이블 열(`sticky left-0`)과 우측 시간축/막대 영역을 같은 가로 스크롤 컨테이너에 배치한다 (G1).
 * - 각 Epic 그룹에 접기/펼치기 토글 버튼을 제공한다 (aria-expanded, NFR3).
 * - 토글 버튼 클릭은 `stopPropagation`으로 `onSelectIssue`를 차단한다 (G2).
 * - 행 레이블 클릭 → `onSelectIssue(key)` 호출.
 */
export function GanttChart({ items, assigneeNames, onSelectIssue }: GanttChartProps): JSX.Element {
  const groups = assembleEpicGroups(items)
  const range = computeDateRange(items)

  /** 접힌 에픽 그룹 키 집합 (기본값: 모두 펼쳐진 상태) */
  const [collapsedGroups, setCollapsedGroups] = useState<ReadonlySet<string>>(new Set())

  function toggleGroup(key: string): void {
    setCollapsedGroups((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  if (groups.length === 0 || range === null) {
    return (
      <p className="text-muted-foreground text-sm p-4">{timelineLabels.empty.noItems}</p>
    )
  }

  return (
    <div className="overflow-x-auto relative" data-testid="gantt-chart">
      <div className="flex min-w-max">

        {/* ── 좌측 레이블 열 (sticky left-0) ── */}
        <div
          className="sticky left-0 z-10 flex-shrink-0 bg-background border-r border-border"
          style={{ width: LABEL_COL_WIDTH_PX }}
        >
          {/* 축 헤더 높이만큼 빈 공간 */}
          <div className="border-b border-border" style={{ height: AXIS_HEIGHT_PX }} />

          {groups.map((group) => {
            const groupKey = group.epicItem?.key ?? 'unclassified'
            const isCollapsed = collapsedGroups.has(groupKey)

            if (group.epicItem !== null) {
              const epic = group.epicItem
              const childItems = group.items.slice(1)

              return (
                <div key={groupKey}>
                  {/* 에픽 행 레이블 — 클릭 시 onSelectIssue 호출 */}
                  <div
                    className="flex items-center gap-1 px-2 border-b border-border cursor-pointer hover:bg-accent"
                    style={{ height: ROW_HEIGHT_PX }}
                    onClick={() => onSelectIssue(epic.key)}
                  >
                    {/* 접기/펼치기 토글 버튼 — stopPropagation으로 onSelectIssue 차단 (G2) */}
                    <button
                      type="button"
                      aria-expanded={!isCollapsed}
                      aria-label={isCollapsed
                        ? timelineLabels.group.expandAriaLabel
                        : timelineLabels.group.collapseAriaLabel}
                      className="flex-shrink-0 text-xs text-muted-foreground hover:text-foreground w-4 h-4"
                      onClick={(e) => { e.stopPropagation(); toggleGroup(groupKey) }}
                    >
                      {isCollapsed ? '▶' : '▼'}
                    </button>
                    <span className="flex-1 truncate text-sm font-medium">{epic.key}</span>
                    <span className="text-xs text-muted-foreground truncate max-w-20">
                      {resolveAssigneeName(epic, assigneeNames)}
                    </span>
                  </div>

                  {/* 자식 행 레이블 (접힌 상태에서 DOM에서 제거) */}
                  {!isCollapsed && childItems.map((child) => (
                    <div
                      key={child.key}
                      className="flex items-center gap-1 px-2 pl-7 border-b border-border cursor-pointer hover:bg-accent"
                      style={{ height: ROW_HEIGHT_PX }}
                      onClick={() => onSelectIssue(child.key)}
                    >
                      <span className="flex-1 truncate text-sm">{child.key}</span>
                      <span className="text-xs text-muted-foreground truncate max-w-20">
                        {resolveAssigneeName(child, assigneeNames)}
                      </span>
                    </div>
                  ))}
                </div>
              )
            }

            // 미분류 그룹
            return (
              <div key="unclassified">
                {/* 미분류 그룹 헤더 */}
                <div
                  className="flex items-center px-2 border-b border-border bg-muted"
                  style={{ height: ROW_HEIGHT_PX }}
                >
                  <span className="text-sm text-muted-foreground font-medium">{LABEL_UNCLASSIFIED}</span>
                </div>

                {/* 미분류 아이템 레이블 행 */}
                {group.items.map((item) => (
                  <div
                    key={item.key}
                    className="flex items-center gap-1 px-2 pl-4 border-b border-border cursor-pointer hover:bg-accent"
                    style={{ height: ROW_HEIGHT_PX }}
                    onClick={() => onSelectIssue(item.key)}
                  >
                    <span className="flex-1 truncate text-sm">{item.key}</span>
                    <span className="text-xs text-muted-foreground truncate max-w-20">
                      {resolveAssigneeName(item, assigneeNames)}
                    </span>
                  </div>
                ))}
              </div>
            )
          })}
        </div>

        {/* ── 우측 시간축 + 막대 영역 ── */}
        <div className="flex-1">
          <TimelineAxis range={range} dayWidth={DAY_WIDTH_PX} />

          {groups.map((group) => {
            const groupKey = group.epicItem?.key ?? 'unclassified'
            const isCollapsed = collapsedGroups.has(groupKey)

            if (group.epicItem !== null) {
              const epic = group.epicItem
              const childItems = group.items.slice(1)

              return (
                <div key={groupKey}>
                  <TimelineRow item={epic} range={range} dayWidth={DAY_WIDTH_PX} onSelectIssue={onSelectIssue} />
                  {!isCollapsed && childItems.map((child) => (
                    <TimelineRow key={child.key} item={child} range={range} dayWidth={DAY_WIDTH_PX} onSelectIssue={onSelectIssue} />
                  ))}
                </div>
              )
            }

            return (
              <div key="unclassified">
                {/* 미분류 그룹 헤더 빈 행 */}
                <div className="border-b border-border bg-muted/20" style={{ height: ROW_HEIGHT_PX }} />
                {group.items.map((item) => (
                  <TimelineRow key={item.key} item={item} range={range} dayWidth={DAY_WIDTH_PX} onSelectIssue={onSelectIssue} />
                ))}
              </div>
            )
          })}
        </div>

      </div>
    </div>
  )
}
