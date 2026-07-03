// 초 단위 소요 시간(백엔드 Long seconds)을 한국어 문자열로 변환하는 순수 포맷터

const SECONDS_PER_MINUTE = 60
const SECONDS_PER_HOUR = 3600
const SECONDS_PER_DAY = 86400

/**
 * 상위 두 단위(예. 시간+분)만 조합해 문자열을 만든다.
 * 하위 단위 값이 0이면 생략한다 — "2시간 0분" 대신 "2시간"으로 표시해
 * 불필요한 "0" 노출을 줄인다.
 *
 * @param majorValue 상위 단위 값 (예. 시간, 일)
 * @param majorLabel 상위 단위 라벨 (예. "시간", "일")
 * @param minorValue 하위 단위 값 (예. 분, 시간)
 * @param minorLabel 하위 단위 라벨 (예. "분", "시간")
 * @returns 조합된 한국어 문자열
 */
function formatUnitPair(
  majorValue: number,
  majorLabel: string,
  minorValue: number,
  minorLabel: string,
): string {
  if (minorValue > 0) return `${majorValue}${majorLabel} ${minorValue}${minorLabel}`
  return `${majorValue}${majorLabel}`
}

/**
 * 초 단위 소요 시간을 사람이 읽기 쉬운 한국어 문자열로 변환한다.
 *
 * 표시 단위는 크기에 따라 초 → 분 → 시간(+분) → 일(+시간) 4단계로 전환되며,
 * 각 단계는 상위 두 단위까지만 노출하고 그보다 작은 잔여값은 버림(truncate) 처리한다.
 * 예. 3599초는 "59분 59초"가 아니라 "59분"으로 표시한다 — Cycle/Lead Time처럼
 * 대략적인 소요 시간 파악이 목적인 화면에서 초 단위 정밀도까지 노출하면
 * 정보 과부하가 되고, 반올림 시 경계값(예. 3600초 전후)에서 상위 단위로
 * 잘못 올림되는 혼란을 막기 위함이다.
 *
 * 음수 입력은 발생하지 않는다고 가정하나(백엔드가 음수 cycle을 제외), 방어적으로
 * 0으로 취급한다.
 *
 * @param seconds 초 단위 소요 시간
 * @returns 한국어로 포맷된 소요 시간 문자열
 *
 * @example
 * formatDuration(59)    // "59초"
 * formatDuration(60)    // "1분"
 * formatDuration(3660)  // "1시간 1분"
 * formatDuration(90000) // "1일 1시간"
 */
export function formatDuration(seconds: number): string {
  const total = Math.floor(Math.max(0, seconds))

  if (total < SECONDS_PER_MINUTE) return `${total}초`

  if (total < SECONDS_PER_HOUR) {
    const minutes = Math.floor(total / SECONDS_PER_MINUTE)
    return `${minutes}분`
  }

  if (total < SECONDS_PER_DAY) {
    const hours = Math.floor(total / SECONDS_PER_HOUR)
    const minutes = Math.floor((total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
    return formatUnitPair(hours, '시간', minutes, '분')
  }

  const days = Math.floor(total / SECONDS_PER_DAY)
  const hours = Math.floor((total % SECONDS_PER_DAY) / SECONDS_PER_HOUR)
  return formatUnitPair(days, '일', hours, '시간')
}

/**
 * 두 소요 시간(초)을 "<시작> ~ <끝>" 형태의 한국어 구간 문자열로 변환한다.
 * 히스토그램 구간 라벨(예. Cycle Time 분포의 버킷 경계 표시)에서 사용한다.
 *
 * @param startSeconds 구간 시작 값 (초)
 * @param endSeconds 구간 끝 값 (초)
 * @returns "<formatDuration(start)> ~ <formatDuration(end)>" 형태 문자열
 *
 * @example
 * formatDurationRange(0, 3600) // "0초 ~ 1시간"
 */
export function formatDurationRange(startSeconds: number, endSeconds: number): string {
  return `${formatDuration(startSeconds)} ~ ${formatDuration(endSeconds)}`
}
