// 이슈 다중 선택 상태를 관리하는 훅 — 페이지 교차 누적 선택 지원
import { useState, useCallback, useMemo } from 'react'

/** useIssueSelection 훅의 반환 타입 */
interface IssueSelectionState {
  /** 현재 선택된 이슈 키 배열 */
  selectedKeys: string[]
  /** 선택된 이슈 수 */
  count: number
  /**
   * 특정 키가 선택됐는지 확인한다.
   * @param key 확인할 이슈 키
   */
  isSelected: (key: string) => boolean
  /**
   * 키를 토글한다. 이미 선택된 경우 제거, 아니면 추가.
   * @param key 토글할 이슈 키
   */
  toggle: (key: string) => void
  /**
   * 현재 페이지의 키를 전체 선택에 추가한다. 기존 선택은 유지된다(페이지 교차 누적).
   * @param keys 현재 페이지의 이슈 키 배열
   */
  selectAllOnPage: (keys: string[]) => void
  /**
   * 현재 페이지의 키만 선택에서 제거한다. 다른 페이지 선택은 유지된다.
   * @param keys 현재 페이지의 이슈 키 배열
   */
  clearPageSelection: (keys: string[]) => void
  /** 전체 선택을 해제한다. */
  clearAll: () => void
}

/**
 * 이슈 다중 선택 상태를 관리하는 훅.
 * 페이지를 이동해도 선택이 유지된다(페이지 교차 누적).
 */
export const useIssueSelection = (): IssueSelectionState => {
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const toggle = useCallback((key: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }, [])

  const selectAllOnPage = useCallback((keys: string[]) => {
    setSelected((prev) => {
      const next = new Set(prev)
      for (const key of keys) {
        next.add(key)
      }
      return next
    })
  }, [])

  const clearPageSelection = useCallback((keys: string[]) => {
    setSelected((prev) => {
      const next = new Set(prev)
      for (const key of keys) {
        next.delete(key)
      }
      return next
    })
  }, [])

  const clearAll = useCallback(() => {
    setSelected(new Set())
  }, [])

  const isSelected = useCallback(
    (key: string) => selected.has(key),
    [selected],
  )

  const selectedKeys = useMemo(() => Array.from(selected), [selected])
  const count = selected.size

  return {
    selectedKeys,
    count,
    isSelected,
    toggle,
    selectAllOnPage,
    clearPageSelection,
    clearAll,
  }
}
