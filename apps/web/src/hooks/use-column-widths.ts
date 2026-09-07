// 테이블 컬럼 폭(px)을 localStorage 에 영속하는 훅 — 드래그 리사이즈의 상태 정본
import { useState, useCallback } from 'react'

/**
 * 컬럼 폭 하한(px).
 *
 * ★0 으로 줄일 수 있으면 **되돌릴 손잡이가 사라진다** — 폭 0 인 컬럼은 리사이즈 핸들도
 * 같이 사라져서, 초기화 메뉴를 찾기 전까지 사용자가 자기 화면을 망가뜨린 채로 갇힌다.
 * 48px 은 아이콘 한 개(16px) + 셀 좌우 여백(16px) + 리사이즈 핸들 여유다.
 */
export const MIN_COLUMN_WIDTH = 48

/** 컬럼 폭 상한(px) — 한 컬럼이 표를 통째로 밀어내지 못하게 한다 */
export const MAX_COLUMN_WIDTH = 960

/** useColumnWidths 반환 타입 */
export interface ColumnWidthsResult {
  /** 컬럼 키 → 현재 폭(px). 기본 폭 위에 저장값을 덮은 결과 */
  widths: Readonly<Record<string, number>>
  /**
   * 컬럼 폭을 확정한다. 하한/상한으로 클램프하고 정수로 반올림한 뒤 영속한다.
   * `defaults` 에 없는 컬럼 키는 무시한다.
   */
  setWidth: (key: string, width: number) => void
  /** 전체를 기본 폭으로 되돌리고 저장값을 지운다 */
  reset: () => void
}

/** 폭 하나를 하한·상한 안의 정수로 다듬는다 */
function clampWidth(width: number): number {
  return Math.round(Math.min(MAX_COLUMN_WIDTH, Math.max(MIN_COLUMN_WIDTH, width)))
}

/**
 * localStorage 에서 읽은 값이 유효한 컬럼 폭 맵인지 검사한다.
 *
 * ★**전체 무효** 처리다(부분 필터링이 아니다) — `use-column-visibility` 의 EC2 와 같은
 * 처방이다. 모르는 키가 섞였거나 범위를 벗어난 값이 하나라도 있으면 스키마가 바뀐
 * 옛 저장값으로 보고 통째로 버린다. 반쯤 살려 두면 화면이 설명 불가능한 상태가 된다.
 *
 * @param parsed `JSON.parse` 결과(타입 미확정)
 * @param knownKeys 기본 폭이 정의된 컬럼 키 목록
 */
function isValidStoredWidths(
  parsed: unknown,
  knownKeys: readonly string[],
): parsed is Record<string, number> {
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return false

  return Object.entries(parsed).every(
    ([key, value]) =>
      knownKeys.includes(key) &&
      typeof value === 'number' &&
      Number.isFinite(value) &&
      value >= MIN_COLUMN_WIDTH &&
      value <= MAX_COLUMN_WIDTH,
  )
}

/**
 * localStorage 에서 컬럼 폭 저장값을 안전하게 읽는다.
 *
 * SSR(`window` 부재)·JSON 파싱 실패·손상된 값은 모두 빈 객체로 폴백한다(fail-safe).
 *
 * @param storageKey localStorage 키
 * @param knownKeys 기본 폭이 정의된 컬럼 키 목록
 * @returns 유효한 저장값. 없거나 손상됐으면 빈 객체
 */
function readStoredWidths(storageKey: string, knownKeys: readonly string[]): Record<string, number> {
  if (typeof window === 'undefined') return {}

  try {
    const raw = window.localStorage.getItem(storageKey)
    if (raw === null) return {}
    const parsed: unknown = JSON.parse(raw)
    return isValidStoredWidths(parsed, knownKeys) ? parsed : {}
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 기본 폭으로 안전 복구
    return {}
  }
}

/**
 * localStorage 에 컬럼 폭을 안전하게 쓴다.
 *
 * QuotaExceededError·SecurityError 등이 나도 무시한다 — 영속만 생략되고 메모리 상태는
 * 그대로 살아 있어 이번 세션의 조작은 정상 동작한다.
 *
 * @param storageKey localStorage 키
 * @param overrides 기본 폭과 다른 값만 담은 맵
 */
function writeStoredWidths(storageKey: string, overrides: Record<string, number>): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(storageKey, JSON.stringify(overrides))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

/**
 * 컬럼 폭을 localStorage 에 영속하는 훅.
 *
 * **저장하는 것은 기본값과 다른 컬럼뿐이다.** 전량을 저장하면 나중에 기본 폭을 바꿔도
 * 이미 방문한 사용자에게는 옛 폭이 영원히 남는다 — 「고쳤는데 내 화면만 안 바뀐다」의
 * 전형적인 양식이다.
 *
 * @param storageKey localStorage 키(호출부마다 고유해야 함)
 * @param defaults 컬럼 키 → 기본 폭(px). 이 맵의 키가 곧 유효한 컬럼 키 목록이다
 * @returns 현재 폭 맵 + 변경/초기화 함수
 */
export function useColumnWidths(
  storageKey: string,
  defaults: Readonly<Record<string, number>>,
): ColumnWidthsResult {
  const [overrides, setOverrides] = useState<Record<string, number>>(() =>
    readStoredWidths(storageKey, Object.keys(defaults)),
  )

  const setWidth = useCallback(
    (key: string, width: number) => {
      if (!(key in defaults)) return

      setOverrides((prev) => {
        const next = { ...prev, [key]: clampWidth(width) }
        writeStoredWidths(storageKey, next)
        return next
      })
    },
    [storageKey, defaults],
  )

  const reset = useCallback(() => {
    setOverrides({})
    if (typeof window === 'undefined') return
    try {
      window.localStorage.removeItem(storageKey)
    } catch {
      // 스토리지 접근 불가 — 메모리 상태만 초기화된다
    }
  }, [storageKey])

  return { widths: { ...defaults, ...overrides }, setWidth, reset }
}
