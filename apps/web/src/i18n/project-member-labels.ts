// 프로젝트 멤버 관리 UI E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점

/**
 * 프로젝트 멤버 관리 UI가 E2E 셀렉터로 노출하는 한국어 라벨/텍스트.
 *
 * - workflow-scheme-labels.ts 패턴 동일 적용 (PR #22 §F4 학습)
 * - 영역 한정: 라벨/aria-label/button/heading/badge 텍스트만 포함
 * - placeholder, Zod error message, FormDescription 등 보조 텍스트는 미포함
 *
 * 그룹 — list / row / roleSelect / addDialog
 */
export const projectMemberLabels = {
  /** MemberList 컴포넌트 */
  list: {
    /** 페이지/섹션 헤더 */
    heading: '프로젝트 멤버',
    /** 멤버 추가 버튼 visible 텍스트 */
    addMemberButton: '멤버 추가',
    /** 로딩 상태 aria-label */
    loadingStatus: '멤버 목록 로딩 중',
    /** 에러 메시지 텍스트 */
    errorMessage: '멤버 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
    /** 멤버 없음 안내 텍스트 */
    emptyMessage: '아직 멤버가 없습니다.',
  },

  /** MemberRow 컴포넌트 */
  row: {
    /** 제거 버튼 visible 텍스트 */
    removeButton: '제거',
    /** 제거 버튼 aria-label (이름 포함 패턴 — {name} 치환) */
    removeAriaLabel: (name: string) => `${name} 멤버 제거`,
    /** 관리자 역할 배지 텍스트 */
    adminBadge: '관리자',
    /** 일반 멤버 역할 배지 텍스트 */
    memberBadge: '멤버',
    /** orphan 멤버 폴백 표시 이름 */
    unknownUser: '(알 수 없는 사용자)',
  },

  /** RoleSelect 컴포넌트 */
  roleSelect: {
    /** Select trigger aria-label */
    triggerAriaLabel: '역할 변경',
    /** 관리자 옵션 텍스트 */
    adminOption: '관리자',
    /** 멤버 옵션 텍스트 */
    memberOption: '멤버',
  },

  /** AddMemberDialog 컴포넌트 */
  addDialog: {
    /** Dialog 제목 */
    title: '새 멤버 추가',
    /** Dialog 열기 버튼 visible 텍스트 (MemberList에서 재사용) */
    triggerButton: '멤버 추가',
    /** 사용자 검색 input placeholder */
    searchPlaceholder: '이름 또는 아이디 검색...',
    /** 검색 결과 없음 안내 텍스트 (EC-4) */
    noResults: '검색 결과 없음',
    /** 역할 Select trigger aria-label */
    roleSelectAriaLabel: '추가할 역할 선택',
    /** 역할 — 관리자 옵션 */
    roleAdminOption: '관리자',
    /** 역할 — 멤버 옵션 */
    roleMemberOption: '멤버',
    /** 추가 확인 버튼 visible 텍스트 */
    confirmButton: '추가',
    /** 취소 버튼 visible 텍스트 */
    cancelButton: '취소',
  },
} as const

/** 라벨 const 추론 타입 */
export type ProjectMemberLabels = typeof projectMemberLabels
