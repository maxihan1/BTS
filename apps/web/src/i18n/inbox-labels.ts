// 알림 보관함(Inbox) UI 한국어 라벨 단일 출처 — FR-UX-03 D6/D7

/**
 * 알림 보관함(Inbox) 페이지·컴포넌트에서 사용하는 한국어 라벨/텍스트.
 *
 * 그룹 — 페이지 / 탭 / 검색 / 빈 상태 / 항목 버튼 / 일괄 / 발신자 / Header 종 / 페이지네이션
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const inboxLabels = {
  /** 페이지 제목·설명 */
  page: {
    /** 페이지 제목 */
    title: '알림 보관함',
    /** 페이지 설명 */
    description: '받은 알림을 확인하고 관리합니다.',
  },

  /** 탭 3종 — 전체 / 안읽음 / 보관함 */
  tabs: {
    /** 전체 탭 (보관 안 된 모든 알림, 읽음 무관) */
    all: '전체',
    /** 안읽음 탭 */
    unread: '안읽음',
    /** 보관함 탭 */
    archived: '보관함',
  },

  /** 검색 영역 */
  search: {
    /** 검색어 입력 placeholder */
    placeholder: '알림 검색',
    /** 발신자 검색 레이블 */
    sender: '발신자',
    /** 기간 시작 레이블 */
    dateFrom: '시작일',
    /** 기간 끝 레이블 */
    dateTo: '종료일',
  },

  /** 탭별 빈 상태 메시지 */
  empty: {
    /** 전체 탭 빈 상태 */
    all: '받은 알림이 없습니다.',
    /** 안읽음 탭 빈 상태 */
    unread: '읽지 않은 알림이 없습니다.',
    /** 보관함 탭 빈 상태 */
    archived: '보관된 알림이 없습니다.',
  },

  /** 항목 개별 버튼 */
  item: {
    /** 읽음 표시 버튼 */
    markRead: '읽음으로 표시',
    /** 안읽음 표시 버튼 */
    markUnread: '안읽음으로 표시',
    /** 보관 버튼 */
    archive: '보관',
    /** 보관 해제 버튼 */
    unarchive: '보관 해제',
  },

  /** 일괄 작업 */
  bulk: {
    /** 전체 읽음 표시 버튼 */
    readAll: '전체 읽음',
  },

  /** 발신자 표시 */
  sender: {
    /** actorUserId가 null인 경우 — 시스템 발신 */
    system: '시스템',
    /** actorUserId가 있으나 사용자 정보를 조회할 수 없는 경우 */
    unknown: '알 수 없는 사용자',
  },

  /** Header 알림 종(🔔) 아이콘 */
  bell: {
    /** 종 아이콘 버튼 aria-label */
    ariaLabel: '알림 보관함 열기',
    /** 미읽음 뱃지 스크린리더 텍스트 (숫자와 함께 사용) */
    unreadBadgeScreenReader: '읽지 않은 알림',
  },

  /** 페이지네이션 */
  pagination: {
    /** 이전 페이지 버튼 */
    previous: '이전',
    /** 다음 페이지 버튼 */
    next: '다음',
  },
} as const

/** inboxLabels const 추론 타입 */
export type InboxLabels = typeof inboxLabels
