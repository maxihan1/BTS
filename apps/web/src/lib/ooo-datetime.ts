// 부재중(Out of Office) 날짜/시각 순수 함수 — datetime-local↔ISO Instant 변환, 복귀일 표시 (FR-PR-03)

/**
 * `<input type="datetime-local">`의 bare 값(예: "2026-07-10T09:00", 타임존 정보 없음)을
 * 브라우저 로컬 시간대 기준으로 해석해 ISO 8601 Instant 문자열로 변환한다.
 *
 * `new Date(bareLocal)`은 타임존 오프셋이 없는 문자열을 로컬 시간대로 해석하는 브라우저 동작을
 * 그대로 활용한다(bare 값을 그대로 PATCH로 보내면 백엔드가 400을 반환하는 회귀 방지,
 * memory: date-input-iso-instant-query-param).
 *
 * @param localDateTime datetime-local input의 value
 * @returns ISO 8601 Instant 문자열. 빈 값/파싱 실패 시 null
 */
export function toInstant(localDateTime: string): string | null {
  if (localDateTime === '') return null
  const parsed = new Date(localDateTime)
  if (Number.isNaN(parsed.getTime())) return null
  return parsed.toISOString()
}

/**
 * ISO 8601 Instant 문자열을 `<input type="datetime-local">`이 요구하는
 * "YYYY-MM-DDTHH:mm"(로컬, 초 단위 생략) 형태로 변환한다({@link toInstant}의 역변환).
 *
 * 모달이 기존 설정값으로 폼을 초기화할 때 사용한다.
 *
 * @param instant ISO 8601 Instant 문자열, 미설정이면 null
 * @returns datetime-local input value. instant가 null/파싱 실패 시 빈 문자열
 */
export function toLocalInputValue(instant: string | null): string {
  if (instant === null) return ''
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 부재중 종료 시각(복귀일)을 브라우저 로컬 기준 "YYYY-MM-DD"로 포맷한다.
 * Header 부재중 배지의 tooltip/aria 등 복귀일 표시에 사용한다.
 *
 * @param endsAt 부재 종료 Instant(ISO) — 미설정이면 null
 * @returns 로컬 기준 "YYYY-MM-DD" 문자열. null/파싱 실패 시 null
 */
export function formatOooReturnDate(endsAt: string | null): string | null {
  if (endsAt === null) return null
  const date = new Date(endsAt)
  if (Number.isNaN(date.getTime())) return null
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
