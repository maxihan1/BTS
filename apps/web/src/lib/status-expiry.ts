// 상태 메시지 만료 프리셋을 실제 만료 시각(ISO Instant)으로 변환하는 순수 함수
export type ExpiryPreset = 'none' | '30m' | '1h' | '4h' | 'today' | 'week'

const MINUTE_MS = 60 * 1000
const HOUR_MS = 60 * MINUTE_MS

/**
 * 기준 시각(base)에서 daysToAdd만큼 날짜를 이동한 날의 "로컬 자정 직전"(23:59:59.999)을 반환한다.
 *
 * @param base 기준 시각
 * @param daysToAdd 기준 날짜에 더할 일수 (0이면 당일)
 * @returns 로컬 기준 해당 날짜의 23:59:59.999
 */
function endOfLocalDay(base: Date, daysToAdd: number): Date {
  return new Date(
    base.getFullYear(),
    base.getMonth(),
    base.getDate() + daysToAdd,
    23,
    59,
    59,
    999,
  )
}

/**
 * 상태 메시지 만료 프리셋을 실제 만료 시각(ISO 8601 Instant 문자열)으로 변환한다.
 *
 * - 'none': 만료 없음(null)
 * - '30m' / '1h' / '4h': now 기준 상대 오프셋
 * - 'today': 사용자 로컬 기준 오늘의 끝(23:59:59.999 local)
 * - 'week': 사용자 로컬 기준 이번 주의 끝 — 월요일 시작 주의 일요일 23:59:59.999 local
 *
 * @param preset 만료 프리셋
 * @param now 기준 시각 (테스트 결정성 확보를 위해 주입 — 내부에서 new Date() 직접 호출 금지)
 * @returns ISO 8601 Instant 문자열, 만료 없으면 null
 */
export function resolveExpiry(preset: ExpiryPreset, now: Date): string | null {
  switch (preset) {
    case 'none':
      return null
    case '30m':
      return new Date(now.getTime() + 30 * MINUTE_MS).toISOString()
    case '1h':
      return new Date(now.getTime() + HOUR_MS).toISOString()
    case '4h':
      return new Date(now.getTime() + 4 * HOUR_MS).toISOString()
    case 'today':
      return endOfLocalDay(now, 0).toISOString()
    case 'week': {
      // 월요일 시작 주 기준 — 일요일까지 남은 일수 (일요일 당일이면 0)
      const daysUntilSunday = (7 - now.getDay()) % 7
      return endOfLocalDay(now, daysUntilSunday).toISOString()
    }
  }
}
