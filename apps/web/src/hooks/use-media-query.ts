// CSS 미디어쿼리 매칭 여부를 구독하는 훅 — matchMedia 기반, split view 폭 분기에 사용
import { useState, useEffect } from 'react'

function supportsMatchMedia(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
}

function getMatches(query: string): boolean {
  return supportsMatchMedia() ? window.matchMedia(query).matches : false
}

/**
 * CSS 미디어쿼리(query)의 매칭 여부를 구독해 반환하는 훅.
 * `window.matchMedia`의 `change` 이벤트를 구독해, 뷰포트 크기가 바뀌어 매칭 결과가
 * 달라지면 반환값이 갱신되고 컴포넌트가 리렌더된다.
 *
 * `window.matchMedia`가 없는 환경(SSR 등)에서는 throw하지 않고 항상 false를 반환한다.
 *
 * @param query CSS 미디어쿼리 문자열 (예: '(min-width: 1024px)')
 * @returns 현재 미디어쿼리 매칭 여부
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState<boolean>(() => getMatches(query))

  useEffect(() => {
    if (!supportsMatchMedia()) {
      setMatches(false)
      return
    }

    const mql = window.matchMedia(query)
    setMatches(mql.matches)

    const handleChange = (event: MediaQueryListEvent): void => {
      setMatches(event.matches)
    }

    mql.addEventListener('change', handleChange)
    return () => {
      mql.removeEventListener('change', handleChange)
    }
  }, [query])

  return matches
}
