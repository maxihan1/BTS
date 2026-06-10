// ISO 날짜 문자열을 한국어 로컬 형식으로 포맷하는 공유 유틸

/**
 * ISO 8601 날짜 문자열을 한국어 로컬 형식으로 포맷한다.
 * SessionList의 formatDateTime과 동일 동작. 연·월·일·시·분 포함.
 *
 * @example
 * formatDateTime('2026-05-29T10:00:00Z') // "2026. 5. 29. 오후 7:00"
 *
 * @param iso ISO 8601 날짜 문자열
 * @returns ko-KR 로컬 날짜+시각 문자열
 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  return date.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
