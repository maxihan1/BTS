// 테이블 컬럼 표시 상태를 localStorage에 영속하는 제네릭 훅 (FR-UX-06 Phase 5 PR18 Task 3)
import { useState, useCallback, useMemo } from 'react'

/**
 * useColumnVisibility 훅의 반환 타입.
 */
export interface ColumnVisibilityResult {
  /** 현재 표시 중인 컬럼 키 배열 */
  visible: string[]
  /**
   * 특정 컬럼이 현재 표시 중인지 확인한다.
   * @param key 확인할 컬럼 키
   */
  isVisible: (key: string) => boolean
  /**
   * 컬럼 표시 여부를 토글한다. `requiredKeys`에 포함된 컬럼은 무시된다(항상 표시).
   * @param key 토글할 컬럼 키
   */
  toggle: (key: string) => void
}

/**
 * localStorage에서 읽은 값이 유효한 컬럼 키 배열인지 검사한다.
 *
 * 배열이 아니거나, 원소 중 문자열이 아니거나 `allColumnKeys`에 없는
 * 미지의 컬럼 키가 하나라도 섞여 있으면 무효로 판정한다(EC2 — 부분 필터링이
 * 아닌 전체 무효 처리로, 스키마가 바뀐 오래된 저장값을 안전하게 걸러낸다).
 *
 * @param parsed `JSON.parse` 결과(타입 미확정)
 * @param allColumnKeys 유효한 컬럼 키 전체 목록
 */
function isValidStoredVisible(parsed: unknown, allColumnKeys: string[]): parsed is string[] {
  return (
    Array.isArray(parsed) &&
    parsed.every((item) => typeof item === 'string' && allColumnKeys.includes(item))
  )
}

/**
 * 표시 목록에 필수 컬럼 키를 항상 포함시킨다(중복 제거).
 *
 * 저장값이 필수 컬럼을 빠뜨린 채 있어도(예: 오래된 저장값·손상 복구 전 필터링)
 * 이 함수를 거치면 필수 컬럼은 항상 표시 목록에 존재하게 된다.
 *
 * @param keys 기준이 되는 표시 컬럼 키 배열
 * @param requiredKeys 항상 포함돼야 하는 필수 컬럼 키 배열
 */
function ensureRequiredVisible(keys: string[], requiredKeys: string[]): string[] {
  return Array.from(new Set([...keys, ...requiredKeys]))
}

/**
 * localStorage에서 컬럼 표시 목록을 안전하게 읽는다.
 *
 * SSR 환경(`window` 부재)·JSON 파싱 실패·미지의 컬럼 키 포함 등 손상된 저장값은
 * 모두 `defaultVisible`로 폴백한다(fail-safe, EC2).
 *
 * @param storageKey localStorage 키
 * @param allColumnKeys 유효성 검사에 사용할 전체 컬럼 키 목록
 * @param defaultVisible 미설정·손상 시 반환할 기본 표시 컬럼 목록
 */
function readStoredVisible(
  storageKey: string,
  allColumnKeys: string[],
  defaultVisible: string[],
): string[] {
  if (typeof window === 'undefined') return defaultVisible

  try {
    const raw = window.localStorage.getItem(storageKey)
    if (raw === null) return defaultVisible
    const parsed: unknown = JSON.parse(raw)
    return isValidStoredVisible(parsed, allColumnKeys) ? parsed : defaultVisible
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 기본값으로 안전 복구(EC2)
    return defaultVisible
  }
}

/**
 * localStorage에 컬럼 표시 목록을 안전하게 쓴다.
 *
 * SSR 환경·QuotaExceededError·SecurityError 등 예외가 발생해도 무시하며,
 * 메모리 상의 React 상태는 정상 유지된다.
 *
 * @param storageKey localStorage 키
 * @param visible 저장할 표시 컬럼 키 배열
 */
function writeStoredVisible(storageKey: string, visible: string[]): void {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(storageKey, JSON.stringify(visible))
  } catch {
    // 스토리지 차단 또는 할당량 초과 — 영속 생략, 메모리 상태는 유지됨
  }
}

/**
 * 테이블 컬럼 표시 상태를 localStorage에 영속하는 제네릭 훅.
 *
 * @param storageKey localStorage 키(호출부마다 고유해야 함)
 * @param allColumnKeys 전체 컬럼 키 목록(손상 복구 시 유효성 검사용)
 * @param requiredKeys 항상 표시되는 필수 컬럼 키 목록(토글 무시)
 * @param defaultVisible 초기값·손상 복구 시 기본 표시 컬럼 목록
 */
export function useColumnVisibility(
  storageKey: string,
  allColumnKeys: string[],
  requiredKeys: string[],
  defaultVisible: string[],
): ColumnVisibilityResult {
  const [visibleSet, setVisibleSet] = useState<Set<string>>(() => {
    const stored = readStoredVisible(storageKey, allColumnKeys, defaultVisible)
    return new Set(ensureRequiredVisible(stored, requiredKeys))
  })

  const toggle = useCallback(
    (key: string) => {
      if (requiredKeys.includes(key)) return

      setVisibleSet((prev) => {
        const toggled = new Set(prev)
        if (toggled.has(key)) {
          toggled.delete(key)
        } else {
          toggled.add(key)
        }
        const next = ensureRequiredVisible(Array.from(toggled), requiredKeys)
        writeStoredVisible(storageKey, next)
        return new Set(next)
      })
    },
    [storageKey, requiredKeys],
  )

  const isVisible = useCallback((key: string) => visibleSet.has(key), [visibleSet])

  const visible = useMemo(() => Array.from(visibleSet), [visibleSet])

  return { visible, isVisible, toggle }
}
