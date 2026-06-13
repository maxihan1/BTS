// 이슈 템플릿 관리 UI의 한국어 라벨 + errorCode → 사용자 메시지 단일 출처

/**
 * 이슈 템플릿 관리 UI가 노출하는 한국어 라벨/텍스트.
 *
 * - custom-field-labels.ts 패턴 동일 적용
 * - errorCode → 사용자 메시지 매핑은 issueTemplateErrorMessage 함수로 분리
 * - 백엔드 ProblemDetail detail 필드를 직접 노출하지 않음 (단일 출처)
 *
 * 그룹 — page / actions / form
 */
export const issueTemplateLabels = {
  /** 페이지/목록 영역 */
  page: {
    /** 페이지 h1 heading */
    heading: '이슈 템플릿 설정',
    /** 페이지 설명 문구 */
    description: '이 프로젝트의 이슈 타입별 본문 템플릿을 관리합니다.',
    /** 템플릿 없음 안내 텍스트 */
    emptyMessage: '아직 이슈 템플릿이 없습니다.',
    /** 로딩 상태 aria-label */
    loadingStatus: '이슈 템플릿 목록 로딩 중',
  },

  /** 액션 버튼/확인 문구 */
  actions: {
    /** 템플릿 추가 버튼 visible 텍스트 */
    addButton: '템플릿 추가',
    /** 수정 버튼 visible 텍스트 */
    editButton: '수정',
    /** 삭제 버튼 visible 텍스트 */
    deleteButton: '삭제',
    /** 삭제 확인 dialog 본문 텍스트 */
    deleteConfirm: '정말 삭제하시겠습니까?',
    /** 권한 없음 tooltip/title 텍스트 */
    noPermission: '프로젝트 관리자만 수정할 수 있습니다.',
  },

  /** 폼 필드 라벨 */
  form: {
    /** 생성 다이얼로그 타이틀 */
    createTitle: '이슈 템플릿 추가',
    /** 수정 다이얼로그 타이틀 */
    editTitle: '이슈 템플릿 수정',
    /** 이슈 타입 select label */
    issueTypeLabel: '이슈 타입',
    /** 이슈 타입 placeholder */
    issueTypePlaceholder: '이슈 타입 선택',
    /** 템플릿 이름 label */
    nameLabel: '이름',
    /** 템플릿 본문 label */
    contentLabel: '본문 (Markdown)',
    /** 저장 버튼 visible 텍스트 */
    submitButton: '저장',
    /** 취소 버튼 visible 텍스트 */
    cancelButton: '취소',
    /** 변수 도움말 앞 문구 — "사용 가능 변수:" 앞부분 */
    variableHelpPrefix: '사용 가능 변수',
    /** 변수 도움말 뒷 문구 — 치환 시점 안내 */
    variableHelpSuffix: '이슈 생성 시 실제 값으로 치환됩니다',
    /** 변수 삽입 버튼 aria-label 생성 함수 */
    variableInsertAria: (label: string) => `${label} 변수 삽입`,
  },
} as const

/** 라벨 const 추론 타입 */
export type IssueTemplateLabels = typeof issueTemplateLabels

/**
 * 백엔드 errorCode를 사용자 노출 메시지로 변환한다.
 * ProblemDetail detail 필드는 직접 쓰지 않으며 이 함수가 단일 출처다.
 *
 * @param errorCode - 백엔드 ProblemDetail의 errorCode 필드값 (null 허용)
 * @returns 사용자에게 노출할 한국어 오류 메시지
 */
export function issueTemplateErrorMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'VALIDATION_FAILED':
      return '입력값을 확인해주세요.'
    case 'ISSUE_TEMPLATE_PROJECT_NOT_FOUND':
      return '프로젝트를 찾을 수 없습니다.'
    case 'ISSUE_TEMPLATE_NOT_FOUND':
      return '템플릿을 찾을 수 없습니다.'
    case 'ISSUE_TEMPLATE_ISSUE_TYPE_NOT_FOUND':
      return '이슈 타입을 찾을 수 없습니다.'
    case 'ISSUE_TEMPLATE_DUPLICATE':
      return '이미 해당 이슈 타입에 템플릿이 있습니다.'
    case 'ISSUE_TEMPLATE_ACCESS_DENIED':
      return '권한이 없습니다.'
    case 'ISSUE_TEMPLATE_INVALID':
      return '템플릿 내용이 올바르지 않습니다.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}
