// 즐겨찾기 UI 한국어 라벨 단일 출처 — FR-UX-02 D6/D7

/**
 * 즐겨찾기 버튼·드롭다운·그룹명·에러 메시지에서 사용하는 한국어 라벨/텍스트.
 *
 * 그룹 — aria 라벨 / 드롭다운 / 타입별 그룹명 / 에러 메시지
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const favoriteLabels = {
  /** 즐겨찾기 추가 버튼 aria-label */
  addAriaLabel: '즐겨찾기에 추가',

  /** 즐겨찾기 해제 버튼 aria-label */
  removeAriaLabel: '즐겨찾기 해제',

  /** 즐겨찾기 드롭다운 트리거 버튼 aria-label */
  dropdownTriggerAriaLabel: '즐겨찾기 목록 열기',

  /** 즐겨찾기 드롭다운 제목 */
  dropdownTitle: '즐겨찾기',

  /** 즐겨찾기 항목이 없을 때 안내 문구 */
  emptyMessage: '즐겨찾기한 항목이 없습니다',

  /** 타입별 그룹명 — 이슈 */
  groupIssue: '이슈',

  /** 타입별 그룹명 — 대시보드 */
  groupDashboard: '대시보드',

  /** 타입별 그룹명 — 프로젝트 */
  groupProject: '프로젝트',

  /** 즐겨찾기 등록 실패 에러 메시지 */
  addError: '즐겨찾기 추가에 실패했습니다',

  /** 즐겨찾기 해제 실패 에러 메시지 */
  removeError: '즐겨찾기 해제에 실패했습니다',
} as const

/** favoriteLabels const 추론 타입 */
export type FavoriteLabels = typeof favoriteLabels
