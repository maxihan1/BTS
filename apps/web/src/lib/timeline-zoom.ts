// 타임라인 줌 레벨 정의 — 프리셋·탐색·파싱·축 설정 순수 함수 (FR-TL-03)

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 타임라인 줌 레벨. 확대→축소 방향으로 week > month > quarter. */
export type ZoomLevel = 'week' | 'month' | 'quarter'

/**
 * 줌 레벨별 상·하단 축 레이블 단위.
 *
 * - `top`: 상단 대단위 레이블 (month | quarter)
 * - `bottom`: 하단 세부 레이블 (day | week | month)
 */
export interface AxisConfig {
  top: 'month' | 'quarter'
  bottom: 'day' | 'week' | 'month'
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 줌 레벨 배열 — 확대(week) → 축소(quarter) 순서.
 * `nextZoomIn` 은 인덱스를 낮추고 `nextZoomOut` 은 인덱스를 높인다.
 */
export const ZOOM_LEVELS: readonly ZoomLevel[] = ['week', 'month', 'quarter'] as const

/** 기본(초기) 줌 레벨. timeline-layout.ts 의 DAY_WIDTH_PX=20 과 대응한다. */
export const DEFAULT_ZOOM: ZoomLevel = 'month'

/**
 * 줌 레벨별 일(day) 단위 열 폭(px).
 *
 * - `month.dayWidth` 는 timeline-layout.ts 의 `DAY_WIDTH_PX = 20` 과 동일하게
 *   유지해 무회귀를 보장한다.
 * - `quarter.dayWidth` 는 `MIN_BAR_DAYS = 1` 가시성 최저선(4px)을 초과한다.
 */
export const ZOOM_PRESETS: Readonly<Record<ZoomLevel, { dayWidth: number }>> = {
  week: { dayWidth: 28 },
  month: { dayWidth: 20 },
  quarter: { dayWidth: 6 },
}

/** 각 줌 레벨의 상·하단 축 설정 매핑 */
const AXIS_CONFIG_MAP: Readonly<Record<ZoomLevel, AxisConfig>> = {
  week: { top: 'month', bottom: 'day' },
  month: { top: 'month', bottom: 'week' },
  quarter: { top: 'quarter', bottom: 'month' },
}

// ─────────────────────────────────────────────────────────────────────────────
// 줌 탐색
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 줌 레벨에서 한 단계 확대한 레벨을 반환한다.
 *
 * 이미 최대 확대(week)이면 week 를 그대로 반환한다(끝단 클램프).
 *
 * @param level 현재 줌 레벨
 * @returns 확대된 줌 레벨
 */
export function nextZoomIn(level: ZoomLevel): ZoomLevel {
  const idx = ZOOM_LEVELS.indexOf(level)
  const nextIdx = Math.max(idx - 1, 0)
  // nextIdx 는 항상 [0, length-1] 범위이므로 undefined 는 발생하지 않는다.
  // ?? DEFAULT_ZOOM 는 noUncheckedIndexedAccess 준수를 위한 컴파일 시 폴백이다.
  return ZOOM_LEVELS[nextIdx] ?? DEFAULT_ZOOM
}

/**
 * 현재 줌 레벨에서 한 단계 축소한 레벨을 반환한다.
 *
 * 이미 최대 축소(quarter)이면 quarter 를 그대로 반환한다(끝단 클램프).
 *
 * @param level 현재 줌 레벨
 * @returns 축소된 줌 레벨
 */
export function nextZoomOut(level: ZoomLevel): ZoomLevel {
  const idx = ZOOM_LEVELS.indexOf(level)
  const nextIdx = Math.min(idx + 1, ZOOM_LEVELS.length - 1)
  // nextIdx 는 항상 [0, length-1] 범위이므로 undefined 는 발생하지 않는다.
  // ?? DEFAULT_ZOOM 는 noUncheckedIndexedAccess 준수를 위한 컴파일 시 폴백이다.
  return ZOOM_LEVELS[nextIdx] ?? DEFAULT_ZOOM
}

// ─────────────────────────────────────────────────────────────────────────────
// 파싱
// ─────────────────────────────────────────────────────────────────────────────

/**
 * localStorage 등 외부 문자열을 ZoomLevel 로 안전하게 변환한다.
 *
 * ZOOM_LEVELS 화이트리스트에 없거나 null 이면 DEFAULT_ZOOM('month') 를 반환한다.
 * 이를 통해 신뢰할 수 없는 외부 값이 들어와도 타임라인이 기본 상태로 동작한다.
 *
 * @param raw localStorage 등 외부에서 읽은 문자열(신뢰 불가), 또는 null
 * @returns 유효한 ZoomLevel
 */
export function parseZoomLevel(raw: string | null): ZoomLevel {
  if (raw !== null && (ZOOM_LEVELS as readonly string[]).includes(raw)) {
    return raw as ZoomLevel
  }
  return DEFAULT_ZOOM
}

// ─────────────────────────────────────────────────────────────────────────────
// 축 설정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 줌 레벨에 맞는 상·하단 축 레이블 단위를 반환한다.
 *
 * | level   | top     | bottom |
 * |---------|---------|--------|
 * | week    | month   | day    |
 * | month   | month   | week   |
 * | quarter | quarter | month  |
 *
 * @param level 현재 줌 레벨
 * @returns AxisConfig — top/bottom 레이블 단위
 */
export function getAxisConfig(level: ZoomLevel): AxisConfig {
  return AXIS_CONFIG_MAP[level]
}
