// 컴포넌트 관리 UI의 한국어 라벨 + errorCode → 사용자 메시지 단일 출처

/**
 * 컴포넌트 관리 UI가 노출하는 한국어 라벨/텍스트.
 *
 * - project-member-labels.ts / workflow-scheme-labels.ts 패턴 동일 적용
 * - errorCode → 사용자 메시지 매핑은 componentErrorMessage 함수로 분리
 * - 백엔드 ProblemDetail detail 필드를 직접 노출하지 않음 (단일 출처)
 *
 * 그룹 — page / actions / form
 */
export const componentLabels = {
  /** 페이지/목록 영역 */
  page: {
    /** 페이지 h1 heading */
    heading: '컴포넌트 설정',
    /** 페이지 설명 문구 */
    description: '이 프로젝트의 컴포넌트를 관리합니다.',
    /** 컴포넌트 없음 안내 텍스트 */
    emptyMessage: '아직 컴포넌트가 없습니다.',
    /** 로딩 상태 aria-label */
    loadingStatus: '컴포넌트 목록 로딩 중',
  },

  /** 액션 버튼/확인 문구 */
  actions: {
    /** 컴포넌트 추가 버튼 visible 텍스트 */
    addButton: '컴포넌트 추가',
    /** 수정 버튼 visible 텍스트 */
    editButton: '수정',
    /** 삭제 버튼 visible 텍스트 */
    deleteButton: '삭제',
    /** 저장 버튼 visible 텍스트 */
    saveButton: '저장',
    /** 취소 버튼 visible 텍스트 */
    cancelButton: '취소',
    /** 삭제 확인 dialog 본문 텍스트 */
    deleteConfirm: '정말 삭제하시겠습니까?',
  },

  /** 폼 필드 라벨 / placeholder */
  form: {
    /** 이름 필드 label */
    nameLabel: '이름',
    /** 설명 필드 label */
    descriptionLabel: '설명',
    /** 리드 필드 label */
    leadLabel: '리드',
    /** 이름 필드 placeholder */
    namePlaceholder: '컴포넌트 이름을 입력하세요',
    /** 설명 필드 placeholder */
    descriptionPlaceholder: '컴포넌트 설명을 입력하세요 (선택)',
    /** 리드 미지정 표시 텍스트 */
    leadUnassigned: '미지정',
  },
} as const

/** 라벨 const 추론 타입 */
export type ComponentLabels = typeof componentLabels

/**
 * 백엔드 errorCode를 사용자 노출 메시지로 변환한다.
 * ProblemDetail detail 필드는 직접 쓰지 않으며 이 함수가 단일 출처다.
 */
export function componentErrorMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'COMPONENT_NAME_DUPLICATE':
      return '이미 같은 이름의 컴포넌트가 있습니다.'
    case 'COMPONENT_LEAD_NOT_FOUND':
      return '선택한 리드 사용자를 찾을 수 없습니다.'
    case 'PROJECT_NOT_FOUND':
      return '프로젝트를 찾을 수 없습니다.'
    case 'VALIDATION_FAILED':
      return '입력값을 확인해 주세요.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}
