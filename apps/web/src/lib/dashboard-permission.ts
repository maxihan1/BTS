// 대시보드 권한 판정 순수 함수

/**
 * 현재 사용자가 대시보드를 편집할 수 있는지 판정한다.
 *
 * 편집 권한은 대시보드 소유자(ownerId)와 현재 사용자(currentUserId)가 일치할 때만 부여된다.
 * currentUserId 가 null 또는 undefined 이면 무조건 false 를 반환한다.
 *
 * @param dashboard - ownerId 를 포함하는 대시보드 객체
 * @param currentUserId - 로그인 중인 사용자 ID (비로그인 시 null 또는 undefined)
 * @returns 편집 가능 여부
 */
export function canEditDashboard(
  dashboard: { ownerId: string },
  currentUserId: string | null | undefined,
): boolean {
  if (currentUserId == null || currentUserId === '') return false
  return dashboard.ownerId === currentUserId
}
