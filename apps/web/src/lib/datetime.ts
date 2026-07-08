// ISO 날짜 문자열을 iso 프리셋(YYYY-MM-DD HH:mm, Asia/Seoul)으로 위임하는 공유 유틸 — 비-컴포넌트 호출처 전용 (FR-PF-01 Task 8)
import { formatDateTimeByPreset } from './date-preferences'

/**
 * ISO 8601 날짜 문자열을 iso 프리셋(YYYY-MM-DD HH:mm, Asia/Seoul 기준) 형식으로 포맷한다.
 *
 * 컴포넌트에서 절대 날짜를 렌더할 때는 로그인 사용자의 `date_format` 환경설정을 반영하는
 * `useDateFormat()` 훅({@link formatDateTimeByPreset} 바인딩)을 사용해야 한다. 이 함수는
 * 훅을 호출할 수 없는 순수 유틸(비-컴포넌트) 호출처를 위해 기본 프리셋(iso)으로 고정 위임한다.
 *
 * @example
 * formatDateTime('2026-05-29T10:00:00Z') // "2026-05-29 19:00"
 *
 * @param iso ISO 8601 날짜 문자열
 * @returns iso 프리셋 기준 "YYYY-MM-DD HH:mm" 문자열
 */
export function formatDateTime(iso: string): string {
  return formatDateTimeByPreset(iso, 'iso')
}
