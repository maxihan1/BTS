// 알림 보관함 종 아이콘 뱃지 관련 순수 유틸리티 함수 (FR-UX-03 D6/D7)

/** 뱃지에 표시하는 최대 숫자 — 초과하면 "MAX_BADGE_COUNT+" 로 표시 */
export const MAX_BADGE_COUNT = 99

/**
 * 미읽음 카운트를 뱃지 표시 문자열로 변환한다.
 * - 0이면 null (뱃지 숨김)
 * - 1~99이면 숫자 문자열
 * - 100 이상이면 "99+"
 *
 * @param count 미읽음 카운트 (0 이상 정수)
 * @returns 뱃지 텍스트 또는 null (숨김)
 */
export function formatUnreadBadge(count: number): string | null {
  if (count <= 0) return null
  if (count > MAX_BADGE_COUNT) return `${MAX_BADGE_COUNT}+`
  return String(count)
}
