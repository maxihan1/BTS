// 날짜 포맷 유틸 — ISO 문자열을 KST(Asia/Seoul) 기준 "YYYY-MM-DD HH:mm" 형식으로 변환

/**
 * ISO 날짜 문자열을 KST(Asia/Seoul, UTC+9) 기준 "YYYY-MM-DD HH:mm" 형식으로 포맷한다.
 * null이면 "—"를 반환한다.
 *
 * UTC 기반 toISOString() 대신 Intl.DateTimeFormat을 사용해
 * 자정 근처 하루 밀림 버그(UTC ≠ KST)를 방지한다.
 *
 * @param iso ISO 8601 날짜 문자열 또는 null
 * @returns KST 기준 포맷된 날짜 문자열 또는 "—"
 */
export function formatDate(iso: string | null): string {
  if (iso === null) return '—'
  const d = new Date(iso)

  const datePart = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(d)

  const timePart = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d)

  // ko-KR 로케일은 "2026. 01. 02." 형태 — "YYYY-MM-DD"로 정규화
  const normalizedDate = datePart
    .replace(/\.\s*/g, '-')  // ". " → "-"
    .replace(/-$/, '')        // 끝 "-" 제거
    .replace(/\s/g, '')       // 공백 제거

  // 시간은 "08:00" 또는 "08시 00분" 형태 — "HH:mm"으로 정규화
  const normalizedTime = timePart
    .replace(/시\s*/, ':')
    .replace(/분/, '')
    .replace(/\s/g, '')

  return `${normalizedDate} ${normalizedTime}`
}
