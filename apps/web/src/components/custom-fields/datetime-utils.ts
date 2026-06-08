// DATETIME 위젯용 ISO8601 offset ↔ datetime-local 변환 유틸 (FR-IS-10 D6 Task 5)

/**
 * ISO8601 offset 문자열을 datetime-local input 값("YYYY-MM-DDTHH:mm")으로 변환한다.
 * 오프셋이 명시된 경우 해당 오프셋 기준 로컬 시각을 그대로 보존한다.
 * 값이 없거나 파싱 불가능하면 빈 문자열을 반환한다.
 *
 * @param iso ISO8601 offset 문자열 (예: "2024-03-15T09:30:00+09:00")
 * @returns datetime-local 입력 형식 문자열 (예: "2024-03-15T09:30")
 */
export function toLocalInput(iso: string): string {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return ''
    // ISO 문자열에서 오프셋을 포함한 "YYYY-MM-DDTHH:mm" 접두사만 추출
    // new Date().toISOString()은 UTC로 변환되므로 원본 문자열에서 파싱
    const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/.exec(iso)
    if (match !== null && match[1] !== undefined) {
      return match[1]
    }
    return ''
  } catch {
    return ''
  }
}

/**
 * datetime-local input 값("YYYY-MM-DDTHH:mm")을 ISO8601 offset 문자열로 변환한다.
 * 로컬 시스템 타임존 오프셋을 적용한다. 빈 값이면 빈 문자열을 반환한다.
 *
 * @param local datetime-local 입력값 (예: "2024-06-01T14:00")
 * @returns ISO8601 offset 문자열 (예: "2024-06-01T14:00:00+09:00")
 */
export function toIsoOffset(local: string): string {
  if (!local) return ''
  try {
    const d = new Date(local)
    if (isNaN(d.getTime())) return ''
    // 시스템 타임존 오프셋(분 단위)
    const offsetMin = -d.getTimezoneOffset()
    const sign = offsetMin >= 0 ? '+' : '-'
    const absMin = Math.abs(offsetMin)
    const hh = String(Math.floor(absMin / 60)).padStart(2, '0')
    const mm = String(absMin % 60).padStart(2, '0')
    const pad = (n: number): string => String(n).padStart(2, '0')
    return (
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
      `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
      `${sign}${hh}:${mm}`
    )
  } catch {
    return ''
  }
}
