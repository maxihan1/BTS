// 작업 시간(초) ↔ 시간/분 변환·표시 순수 함수

const SECONDS_PER_HOUR = 3600
const SECONDS_PER_MINUTE = 60

/**
 * 시간·분 입력 형식. parseHm의 인수 타입.
 */
export interface HourMinute {
  hours: number
  minutes: number
}

/**
 * 시간/분 입력을 총 초(seconds)로 변환한다.
 * 음수 입력은 0으로 처리한다.
 *
 * @param param0 시간과 분으로 구성된 객체
 * @returns 총 초 (0 이상의 정수)
 *
 * @example
 * parseHm({ hours: 2, minutes: 30 }) // 9000
 * parseHm({ hours: 0, minutes: 0 })  // 0
 */
export function parseHm({ hours, minutes }: HourMinute): number {
  const h = Math.max(0, hours)
  const m = Math.max(0, minutes)
  return h * SECONDS_PER_HOUR + m * SECONDS_PER_MINUTE
}

/**
 * 총 초(seconds)를 "Xh Ym" 또는 "Ym" 형식의 문자열로 변환한다.
 * 시간이 0이면 분만 표시한다. 일·주 단위로는 변환하지 않는다.
 * 음수 입력은 0으로 처리한다.
 *
 * @param seconds 총 초 (음수는 0으로 처리)
 * @returns "Xh Ym" 또는 "Ym" 형식 문자열
 *
 * @example
 * formatSeconds(9000) // "2h 30m"
 * formatSeconds(2700) // "45m"
 * formatSeconds(0)    // "0m"
 * formatSeconds(3600) // "1h 0m"
 */
export function formatSeconds(seconds: number): string {
  const s = Math.max(0, seconds)
  const h = Math.floor(s / SECONDS_PER_HOUR)
  const m = Math.floor((s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
  return h > 0 ? `${h}h ${m}m` : `${m}m`
}
