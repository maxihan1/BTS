// 날짜 포맷 유틸 — ISO 문자열을 "YYYY-MM-DD HH:mm" 형식으로 변환

/**
 * ISO 날짜 문자열을 "YYYY-MM-DD HH:mm" 형식으로 포맷한다.
 * null이면 "—"를 반환한다.
 *
 * @param iso ISO 8601 날짜 문자열 또는 null
 * @returns 포맷된 날짜 문자열 또는 "—"
 */
export function formatDate(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)
  const date = d.toISOString().slice(0, 10)
  const time = d.toISOString().slice(11, 16)
  return `${date} ${time}`
}
