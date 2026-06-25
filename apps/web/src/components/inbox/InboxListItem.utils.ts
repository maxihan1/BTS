// InboxListItem 시각 포맷 헬퍼 — formatDate 재사용 래퍼 (FR-UX-03 D6/D7)
import { formatDate } from '@/lib/date-format'

/**
 * Inbox 항목 생성 시각(createdAt)을 표시용 문자열로 변환한다.
 * `lib/date-format.formatDate`를 재사용해 KST "YYYY-MM-DD HH:mm" 형식을 반환한다.
 *
 * @param createdAt ISO 8601 날짜 문자열
 * @returns KST 기준 포맷된 날짜 문자열
 */
export function formatCreatedAt(createdAt: string): string {
  return formatDate(createdAt)
}
