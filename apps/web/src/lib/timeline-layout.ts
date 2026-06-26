// 타임라인 Gantt 레이아웃 순수 함수 — 날짜 범위·막대 좌표·Epic 그룹 조립 (FR-TL-01 D6)
import type { TimelineItem } from '@/api/timeline'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 일 단위 열 폭(px). 외부에서 dayWidth 인수로 재정의 가능 */
export const DAY_WIDTH_PX = 20

/** 최소 막대 폭(일 수). startDate만·dueDate만·start>due 클램프 시 사용 (EC1/EC2) */
export const MIN_BAR_DAYS = 1

/** 하루를 ms 로 표현한 값 */
const MS_PER_DAY = 86_400_000

/** 미분류 그룹 레이블 */
export const LABEL_UNCLASSIFIED = '미분류'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전체 타임라인 날짜 범위.
 * startMs·endMs 는 UTC epoch milliseconds.
 */
export interface DateRange {
  startMs: number
  endMs: number
}

/**
 * 단일 아이템의 Gantt 막대 좌표 정보.
 *
 * - `barX`: 막대 좌측 x 좌표 (px)
 * - `barWidth`: 막대 폭 (px)
 * - `milestoneX`: targetDate 마일스톤 ◆ x 좌표 (px). targetDate 없으면 null.
 * - `openStart`: dueDate만 있는 개방 막대(좌측 미정)
 * - `openEnd`: startDate만 있는 개방 막대(우측 미정)
 */
export interface BarGeometry {
  barX: number
  barWidth: number
  milestoneX: number | null
  openStart: boolean
  openEnd: boolean
}

/**
 * Epic 기준 그룹.
 *
 * - `epicItem`: Epic 아이템 자신. null이면 "미분류" 그룹.
 * - `items`: 그룹에 속한 아이템 목록. Epic 자신이 첫 번째, 이후 자식 순.
 */
export interface TimelineGroup {
  epicItem: TimelineItem | null
  items: TimelineItem[]
}

// ─────────────────────────────────────────────────────────────────────────────
// UTC 날짜 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ISO date 문자열(`"YYYY-MM-DD"`)을 UTC epoch milliseconds 로 파싱한다.
 *
 * `new Date("YYYY-MM-DD")` 는 UTC 자정 기준이지만
 * 로컬 타임존에 따라 하루 어긋날 수 있다(NFR4).
 * `Date.UTC(y, m, d)` 로 명시적 UTC 변환을 보장한다.
 *
 * @param iso ISO date 문자열 (예: `"2026-07-20"`)
 * @returns UTC epoch milliseconds
 */
export function parseIsoDateUtc(iso: string): number {
  const parts = iso.split('-')
  const year = parseInt(parts[0] ?? '0', 10)
  const month = parseInt(parts[1] ?? '1', 10) - 1  // Date.UTC는 0-indexed month
  const day = parseInt(parts[2] ?? '1', 10)
  return Date.UTC(year, month, day)
}

/**
 * 두 UTC epoch ms 값의 일수 차이를 반환한다.
 *
 * @param aMs UTC epoch milliseconds (시작)
 * @param bMs UTC epoch milliseconds (끝)
 * @returns `bMs`에서 `aMs`를 뺀 일수 (소수점 버림)
 */
export function daysBetweenUtc(aMs: number, bMs: number): number {
  return Math.floor((bMs - aMs) / MS_PER_DAY)
}

// ─────────────────────────────────────────────────────────────────────────────
// computeDateRange
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 아이템 목록에서 전체 날짜 범위를 계산한다.
 *
 * - startDate·dueDate·targetDate 를 모두 고려해 min/max 를 구한다 (EC3).
 * - 날짜가 하나도 없으면 null 을 반환한다.
 * - rangeStart === rangeEnd 일 때 endMs 를 하루 뒤로 보정해 폭 0 방지(EC8).
 *
 * @param items 타임라인 아이템 목록
 * @returns DateRange 또는 null (날짜 없는 경우)
 */
export function computeDateRange(items: TimelineItem[]): DateRange | null {
  let minMs = Infinity
  let maxMs = -Infinity

  for (const item of items) {
    if (item.startDate !== null) {
      const ms = parseIsoDateUtc(item.startDate)
      if (ms < minMs) minMs = ms
    }
    if (item.dueDate !== null) {
      const ms = parseIsoDateUtc(item.dueDate)
      if (ms > maxMs) maxMs = ms
    }
    if (item.targetDate !== null) {
      const ms = parseIsoDateUtc(item.targetDate)
      if (ms < minMs) minMs = ms
      if (ms > maxMs) maxMs = ms
    }
  }

  if (minMs === Infinity && maxMs === -Infinity) return null

  // 한쪽만 날짜가 있을 경우 — min 또는 max 를 같은 날로 설정
  if (minMs === Infinity) minMs = maxMs
  if (maxMs === -Infinity) maxMs = minMs

  // EC8: 단일 날짜(폭 0) 방지 — endMs 를 1일 뒤로
  if (minMs === maxMs) {
    maxMs = minMs + MS_PER_DAY
  }

  return { startMs: minMs, endMs: maxMs }
}

// ─────────────────────────────────────────────────────────────────────────────
// computeBarGeometry
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 단일 타임라인 아이템의 Gantt 막대 좌표를 계산한다.
 *
 * 날짜 조합별 동작.
 * - startDate + dueDate 모두 있음 — `barX = (start - rangeStart)일 * dayWidth`,
 *   `barWidth = (due - start + 1)일 * dayWidth` (EC 정상)
 * - startDate 만 — 최소폭(MIN_BAR_DAYS), `openEnd = true` (EC1)
 * - dueDate 만 — `barX = due 위치`, 최소폭, `openStart = true` (EC1)
 * - 날짜 없음 — barX = 0, 최소폭, open 없음
 * - startDate > dueDate — 음수폭 클램프 → 최소폭 (EC2)
 * - targetDate 있음 — `milestoneX = (targetDate - rangeStart)일 * dayWidth` (FR4)
 *
 * **날짜 계산은 UTC 기준** — `parseIsoDateUtc` 사용 (NFR4).
 *
 * @param item 타임라인 아이템
 * @param range 전체 날짜 범위
 * @param dayWidth 일 단위 열 폭(px)
 * @returns BarGeometry
 */
export function computeBarGeometry(
  item: TimelineItem,
  range: DateRange,
  dayWidth: number,
): BarGeometry {
  const minWidth = MIN_BAR_DAYS * dayWidth

  const startMs = item.startDate !== null ? parseIsoDateUtc(item.startDate) : null
  const dueMs = item.dueDate !== null ? parseIsoDateUtc(item.dueDate) : null
  const targetMs = item.targetDate !== null ? parseIsoDateUtc(item.targetDate) : null

  const milestoneX = targetMs !== null ? daysBetweenUtc(range.startMs, targetMs) * dayWidth : null

  if (startMs !== null && dueMs !== null) {
    const rawDays = daysBetweenUtc(startMs, dueMs) + 1  // 당일 포함(+1)
    const days = Math.max(rawDays, MIN_BAR_DAYS)
    const barX = daysBetweenUtc(range.startMs, startMs) * dayWidth
    return {
      barX: Math.max(barX, 0),
      barWidth: days * dayWidth,
      milestoneX,
      openStart: false,
      openEnd: false,
    }
  }

  if (startMs !== null) {
    // EC1 — startDate 만
    const barX = daysBetweenUtc(range.startMs, startMs) * dayWidth
    return {
      barX: Math.max(barX, 0),
      barWidth: minWidth,
      milestoneX,
      openStart: false,
      openEnd: true,
    }
  }

  if (dueMs !== null) {
    // EC1 — dueDate 만
    const barX = daysBetweenUtc(range.startMs, dueMs) * dayWidth
    return {
      barX: Math.max(barX, 0),
      barWidth: minWidth,
      milestoneX,
      openStart: true,
      openEnd: false,
    }
  }

  // 날짜 없음
  return {
    barX: 0,
    barWidth: minWidth,
    milestoneX,
    openStart: false,
    openEnd: false,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// assembleEpicGroups
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 아이템 목록을 Epic 기준 그룹으로 조립한다.
 *
 * - `issueType === 'epic'` 아이템이 그룹 헤더(epicItem). Epic 자신도 items 첫 번째에 포함.
 * - `epicKey === <Epic.key>` 아이템은 해당 Epic 그룹의 자식.
 * - epicKey 매칭 실패 또는 `epicKey === null && issueType !== 'epic'` → "미분류" 그룹(EC6/EC7).
 * - 그룹 내 순서는 입력 순서 유지(백엔드 startDate ASC... 정렬 보존).
 * - Epic 그룹 순서는 입력 순서 기준, "미분류" 그룹은 항상 맨 끝.
 *
 * @param items 백엔드 정렬된 타임라인 아이템 목록
 * @returns TimelineGroup[] — Epic 그룹 목록. 미분류 그룹은 맨 끝.
 */
export function assembleEpicGroups(items: TimelineItem[]): TimelineGroup[] {
  if (items.length === 0) return []

  // 1단계: Epic 키 집합 구성
  const epicKeys = new Set<string>()
  for (const item of items) {
    if (item.issueType === 'epic') {
      epicKeys.add(item.key)
    }
  }

  // 2단계: Epic 별 그룹 맵 (입력 순서 유지를 위해 Map 사용)
  const epicGroupMap = new Map<string, TimelineGroup>()
  const unclassified: TimelineItem[] = []

  for (const item of items) {
    if (item.issueType === 'epic') {
      // Epic 자신 — 그룹을 만들거나 기존 그룹의 items 맨 앞에 삽입
      const existing = epicGroupMap.get(item.key)
      if (existing !== undefined) {
        existing.epicItem = item
        existing.items.unshift(item)
      } else {
        epicGroupMap.set(item.key, { epicItem: item, items: [item] })
      }
      continue
    }

    if (item.epicKey !== null && epicKeys.has(item.epicKey)) {
      // 매칭되는 Epic 그룹에 자식 추가
      const group = epicGroupMap.get(item.epicKey)
      if (group !== undefined) {
        group.items.push(item)
      } else {
        // Epic이 자식보다 나중에 오는 비정상 순서 — 그룹 예비 생성.
        // Epic 아이템이 도착하면 epicItem·items 을 업데이트한다(아래 unshift 분기).
        epicGroupMap.set(item.epicKey, { epicItem: null, items: [item] })
      }
    } else {
      // EC6/EC7: 미분류
      unclassified.push(item)
    }
  }

  const result: TimelineGroup[] = [...epicGroupMap.values()]

  if (unclassified.length > 0) {
    result.push({ epicItem: null, items: unclassified })
  }

  return result
}
