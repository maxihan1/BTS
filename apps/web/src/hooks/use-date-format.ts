// 로그인 사용자의 dateFormat 환경설정을 바인딩한 날짜 포맷 함수를 반환하는 훅 — FR-PF-01
import { useAuthUser } from '@/auth/authStore'
import { formatDateByPreset, formatDateTimeByPreset, isDatePreset } from '@/lib/date-preferences'
import type { DatePreset } from '@/lib/date-preferences'

/** 미인증/필드 없음/알 수 없는 프리셋일 때 사용할 기본값(백엔드 기본값과 동일) */
const FALLBACK_PRESET: DatePreset = 'iso'

/** {@link useDateFormat} 반환 형태 */
export interface DateFormatter {
  /** 현재 적용 중인 dateFormat 프리셋 — 미인증/알 수 없는 값이면 {@link FALLBACK_PRESET} */
  preset: DatePreset
  /** 날짜(연-월-일)만 현재 프리셋으로 포맷 — null 입력 시 "—" */
  formatDate: (iso: string | null) => string
  /** 날짜+시각(HH:mm, 24시간제)을 현재 프리셋으로 포맷 — null 입력 시 "—" */
  formatDateTime: (iso: string | null) => string
}

/**
 * 로그인 사용자의 dateFormat 환경설정(`useAuthUser().dateFormat`)을 읽어
 * 날짜 포맷 함수를 바인딩해 반환하는 훅.
 *
 * 미인증 상태(`useAuthUser()`가 null), 필드 부재(`undefined` — whoami 스키마가
 * `.optional()`이라 기존 인라인 mock에는 없을 수 있음), 또는 지원 프리셋이 아닌 값이면
 * {@link FALLBACK_PRESET}('iso')로 폴백한다 — {@link isDatePreset} 타입 가드로 안전하게 좁힌다.
 *
 * @returns 현재 프리셋 + 바인딩된 formatDate/formatDateTime 함수
 */
export function useDateFormat(): DateFormatter {
  const user = useAuthUser()
  const dateFormat = user?.dateFormat
  const preset = dateFormat !== undefined && isDatePreset(dateFormat) ? dateFormat : FALLBACK_PRESET

  return {
    preset,
    formatDate: (iso) => formatDateByPreset(iso, preset),
    formatDateTime: (iso) => formatDateTimeByPreset(iso, preset),
  }
}
