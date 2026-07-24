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
    const isValid =
      Array.isArray(parsed) &&
      parsed.every((item) => typeof item === 'string' && allColumnKeys.includes(item))
    return isValid ? (parsed as string[]) : defaultVisible
  } catch {
    // JSON 파싱 실패 또는 스토리지 접근 불가 — 기본값으로 안전 복구(EC2)
    return defaultVisible
  }
}

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
    return new Set([...stored, ...requiredKeys])
  })

  const toggle = useCallback(
    (key: string) => {
      if (requiredKeys.includes(key)) return

      setVisibleSet((prev) => {
        const next = new Set(prev)
        if (next.has(key)) {
          next.delete(key)
        } else {
          next.add(key)
        }
        for (const requiredKey of requiredKeys) {
          next.add(requiredKey)
        }
        writeStoredVisible(storageKey, Array.from(next))
        return next
      })
    },
    [storageKey, requiredKeys],
  )

  const isVisible = useCallback((key: string) => visibleSet.has(key), [visibleSet])

  const visible = useMemo(() => Array.from(visibleSet), [visibleSet])

  return { visible, isVisible, toggle }
}
