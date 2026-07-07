// 사용자 환경설정(dateFormat) 기반 날짜/일시 포맷터 — 네이티브 Intl만 사용(FR-PF-01, 신규 의존성 0)

/** 지원하는 날짜 표시 프리셋 4종 — 스펙 §date_format 프리셋 매핑(iso/kr/us/eu) */
export const DATE_PRESETS = ['iso', 'kr', 'us', 'eu'] as const

/** {@link DATE_PRESETS} 중 하나 — 사용자 환경설정 date_format 값 */
export type DatePreset = (typeof DATE_PRESETS)[number]

/** null 날짜 입력 시 반환하는 플레이스홀더 */
const NULL_PLACEHOLDER = '—'

/**
 * 절대 날짜 표시에 고정 사용하는 기본 타임존.
 * FR-PF-01 범위에는 타임존 설정이 포함되지 않는다(FR-PR-01 프로필 타임존과는 별개 개념).
 */
const DEFAULT_TIMEZONE = 'Asia/Seoul'

/**
 * 값이 지원하는 {@link DatePreset}인지 판별하는 타입 가드.
 * whoami 등 외부에서 온 느슨한 string 값(백엔드 view-layer 필드는 plain String)을
 * 안전하게 좁힐 때 사용한다 — `useDateFormat` 훅이 폴백 판단에 사용.
 *
 * @param value 검사할 문자열
 * @returns DatePreset 여부
 */
export function isDatePreset(value: string): value is DatePreset {
  return (DATE_PRESETS as readonly string[]).includes(value)
}

/**
 * 날짜(연-월-일)만 프리셋 형식으로 포맷한다.
 *
 * @param iso ISO 8601 날짜 문자열 또는 null
 * @param preset 날짜 표시 프리셋(iso/kr/us/eu)
 * @param tz IANA 타임존 — 기본 `Asia/Seoul`
 * @returns 프리셋 형식 날짜 문자열, iso가 null이면 "—"
 */
export function formatDateByPreset(
  iso: string | null,
  preset: DatePreset,
  tz: string = DEFAULT_TIMEZONE,
): string {
  if (iso === null) return NULL_PLACEHOLDER
  return formatDatePart(new Date(iso), preset, tz)
}

/**
 * 날짜+시각을 "프리셋 날짜 HH:mm"(24시간제) 형식으로 포맷한다.
 * 시각 부분은 프리셋과 무관하게 항상 HH:mm(24시간제)로 고정된다.
 *
 * @param iso ISO 8601 날짜 문자열 또는 null
 * @param preset 날짜 표시 프리셋(iso/kr/us/eu)
 * @param tz IANA 타임존 — 기본 `Asia/Seoul`
 * @returns "프리셋 날짜 HH:mm" 문자열, iso가 null이면 "—"
 */
export function formatDateTimeByPreset(
  iso: string | null,
  preset: DatePreset,
  tz: string = DEFAULT_TIMEZONE,
): string {
  if (iso === null) return NULL_PLACEHOLDER
  const date = new Date(iso)
  return `${formatDatePart(date, preset, tz)} ${formatTimePart(date, tz)}`
}

/**
 * 날짜(연-월-일) 부분을 프리셋에 맞는 로케일로 포맷하는 내부 헬퍼.
 * 각 로케일의 기본 숫자 날짜 포맷이 스펙 §date_format 프리셋 표기와 정확히 일치해
 * 문자열 재조립 없이 그대로 사용한다(iso→en-CA, kr→ko-KR, us→en-US, eu→en-GB).
 */
function formatDatePart(date: Date, preset: DatePreset, tz: string): string {
  const options: Intl.DateTimeFormatOptions = {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }
  switch (preset) {
    case 'iso':
      return new Intl.DateTimeFormat('en-CA', options).format(date)
    case 'kr':
      return new Intl.DateTimeFormat('ko-KR', options).format(date)
    case 'us':
      return new Intl.DateTimeFormat('en-US', options).format(date)
    case 'eu':
      return new Intl.DateTimeFormat('en-GB', options).format(date)
  }
}

/**
 * 시각(HH:mm, 24시간제) 부분을 포맷하는 내부 헬퍼 — 프리셋과 무관하게 고정.
 * `hourCycle: 'h23'`로 자정을 "24:00"이 아닌 "00:00"으로 강제한다.
 */
function formatTimePart(date: Date, tz: string): string {
  return new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(date)
}
