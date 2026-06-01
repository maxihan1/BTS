// 값 변경을 지연시키는 debounce 훅 — 담당자 검색 과도 요청 방지
import { useState, useEffect } from 'react'

/**
 * 입력값을 delay ms 지연 후 반환하는 debounce 훅.
 * 1000명 규모 사용자 검색에서 매 키스트로크마다 API 요청이 발생하는 것을 방지한다.
 *
 * @param value 지연할 원본 값
 * @param delay 지연 시간 (ms). 기본값 없음 — 호출 측에서 명시 권장.
 * @returns delay 경과 후 업데이트된 값
 */
export function useDebounce<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState<T>(value)

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value)
    }, delay)

    return () => {
      clearTimeout(timer)
    }
  }, [value, delay])

  return debouncedValue
}
