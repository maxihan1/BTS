// 대시보드 layout(JSONB) 직렬화/역직렬화 순수 함수 — FR-DB-01

// ─────────────────────────────────────────────────────────────────────────────
// 그리드 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 12컬럼 그리드 — react-grid-layout 기본 */
export const COLS = 12

/** 한 행(row)의 픽셀 높이 */
export const ROW_HEIGHT = 60

/** 타일 사이 마진 [x, y] (픽셀) */
export const MARGIN: [number, number] = [8, 8]

/** 그리드 컨테이너 패딩 [x, y] (픽셀) */
export const CONTAINER_PADDING: [number, number] = [8, 8]

/** 신규 타일 기본 너비 (컬럼 수) */
export const DEFAULT_W = 6

/** 신규 타일 기본 높이 (행 수) */
export const DEFAULT_H = 4

/** 신규 타일 기본 제목 */
const DEFAULT_TITLE = '새 위젯'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 타일 — react-grid-layout 의 Layout 항목과 호환 (i, x, y, w, h 동일 키).
 * title 필드는 BTS 전용 확장. gadgetType/config는 FR-DB-02 가젯 시스템 확장.
 */
export interface DashboardTile {
  /** 타일 고유 식별자 (react-grid-layout Layout.i 와 동일) */
  i: string
  /** 그리드 열(column) 위치 (0-based) */
  x: number
  /** 그리드 행(row) 위치 (0-based) */
  y: number
  /** 타일 너비 (컬럼 수) */
  w: number
  /** 타일 높이 (행 수) */
  h: number
  /**
   * 타일 표시 제목.
   * - legacy 타일: 사용자가 지정한 이름.
   * - 가젯 타일: 카탈로그 label이 헤더를 대체하므로 부재 가능.
   *
   * optional로 변경한 이유: 가젯 타일은 title 없이 gadgetType만 가질 수 있고,
   * isDashboardTile 검사도 title OR gadgetType 존재를 요구한다.
   */
  title?: string
  /**
   * 가젯 종류 식별자 — 카탈로그 GadgetType enum 값(소문자 snake_case).
   * 없으면 legacy 일반 타일로 취급한다.
   */
  gadgetType?: string
  /**
   * 가젯 설정 객체. 가젯 타일에만 존재하며 가젯별 configFields 스키마를 따른다.
   * 저장 전 클라측 검증은 validateGadgetConfig(Task 1)가 담당한다.
   */
  config?: Record<string, unknown>
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 타입 가드
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 파싱된 unknown 값이 DashboardTile 구조를 만족하는지 검사한다.
 *
 * 필수: i(string), x/y/w/h(number).
 * 선택: title(string), gadgetType(string), config(비-null 비-배열 객체).
 * 허용 조건: legacy 타일은 title 있어야 하고, 가젯 타일은 gadgetType 있으면 title 없어도 허용.
 */
function isDashboardTile(value: unknown): value is DashboardTile {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>

  // 필수 위치 필드 (react-grid-layout 호환)
  if (
    typeof v['i'] !== 'string' ||
    typeof v['x'] !== 'number' ||
    typeof v['y'] !== 'number' ||
    typeof v['w'] !== 'number' ||
    typeof v['h'] !== 'number'
  ) {
    return false
  }

  // gadgetType: 있으면 string이어야 함
  if (v['gadgetType'] !== undefined && typeof v['gadgetType'] !== 'string') return false

  // config: 있으면 비-null 비-배열 객체여야 함 (Record<string, unknown> 호환)
  if (
    v['config'] !== undefined &&
    (typeof v['config'] !== 'object' || v['config'] === null || Array.isArray(v['config']))
  ) {
    return false
  }

  // 유효한 타일 조건: legacy 타일은 title(string), 가젯 타일은 gadgetType(string)으로 판별
  return typeof v['title'] === 'string' || typeof v['gadgetType'] === 'string'
}

// ─────────────────────────────────────────────────────────────────────────────
// 공개 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * JSONB 컬럼에서 읽어온 문자열을 DashboardTile 배열로 파싱한다.
 *
 * - 빈 문자열, 손상된 JSON, 비-배열 값은 모두 `[]` 을 반환한다(throw 절대 금지).
 * - 배열 요소가 DashboardTile 구조를 만족하지 않으면 해당 요소를 제외한다.
 *
 * @param jsonString - DB 또는 API 에서 수신한 raw JSON 문자열
 * @returns 파싱된 DashboardTile 배열 (실패 시 빈 배열)
 */
export function parseLayout(jsonString: string): DashboardTile[] {
  if (jsonString.trim() === '') return []
  let parsed: unknown
  try {
    parsed = JSON.parse(jsonString)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []
  return parsed.filter(isDashboardTile)
}

/**
 * DashboardTile 배열을 JSONB 저장 또는 API 전송용 JSON 문자열로 직렬화한다.
 *
 * @param tiles - 직렬화할 타일 배열
 * @returns JSON 문자열
 */
export function serializeLayout(tiles: DashboardTile[]): string {
  return JSON.stringify(tiles)
}

/**
 * 기존 타일 배열 아래에 새 타일을 생성한다.
 *
 * - i 는 `crypto.randomUUID()` 로 생성해 고유성을 보장한다.
 * - y 는 기존 타일의 최대 y+h 값으로 맨 아래에 배치한다(react-grid-layout
 *   빈 칸 자동 탐색은 컴포넌트 레이어에서 담당하므로 여기서는 단순 append).
 * - x 는 0 으로 고정하고 w/h 는 기본값을 사용한다.
 *
 * @param existing - 현재 그리드에 있는 타일 목록
 * @returns 새로 생성된 DashboardTile
 */
export function createTile(existing: DashboardTile[]): DashboardTile {
  const maxBottom = existing.reduce((acc, tile) => Math.max(acc, tile.y + tile.h), 0)
  return {
    i: crypto.randomUUID(),
    x: 0,
    y: maxBottom,
    w: DEFAULT_W,
    h: DEFAULT_H,
    title: DEFAULT_TITLE,
  }
}
