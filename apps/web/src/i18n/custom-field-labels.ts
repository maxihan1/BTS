// 커스텀 필드 관리 UI의 한국어 라벨 + errorCode → 사용자 메시지 단일 출처

/**
 * 커스텀 필드 관리 UI가 노출하는 한국어 라벨/텍스트.
 *
 * - component-labels.ts / workflow-scheme-labels.ts 패턴 동일 적용
 * - errorCode → 사용자 메시지 매핑은 customFieldErrorMessage 함수로 분리
 * - 백엔드 ProblemDetail detail 필드를 직접 노출하지 않음 (단일 출처)
 *
 * 그룹 — placeholder(최상위) / page / actions / form / fieldTypes
 */
export const customFieldLabels = {
  /** 선택지 행 — 값 input placeholder (`CustomFieldFormDialog.tsx` OptionRow) */
  optionValuePlaceholder: '값',
  /** 선택지 행 — 라벨 input placeholder */
  optionLabelPlaceholder: '라벨',
  /** 필드 키 input placeholder */
  keyPlaceholder: '예: priority',
  /** 이름 input placeholder */
  namePlaceholder: '예: 우선순위',
  /** 설명 input placeholder — 선택 항목임을 알린다 */
  descriptionPlaceholder: '선택 입력',
  /** 값 입력 위젯(`CustomFieldInput.tsx`) 선택형 Select 의 미선택 placeholder */
  inputSelectPlaceholder: '선택하세요',

  /** 페이지/목록 영역 */
  page: {
    /** 페이지 h1 heading */
    heading: '커스텀 필드 설정',
    /** 페이지 설명 문구 */
    description: '이 프로젝트의 커스텀 필드를 관리합니다.',
    /** 커스텀 필드 없음 안내 텍스트 */
    emptyMessage: '아직 커스텀 필드가 없습니다.',
    /** 로딩 상태 aria-label */
    loadingStatus: '커스텀 필드 목록 로딩 중',
  },

  /** 액션 버튼/확인 문구 */
  actions: {
    /** 커스텀 필드 추가 버튼 visible 텍스트 */
    addButton: '필드 추가',
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
    /** 필드 키 label */
    keyLabel: '키',
    /** 이름 필드 label */
    nameLabel: '이름',
    /** 설명 필드 label */
    descriptionLabel: '설명',
    /** 필드 타입 select label */
    fieldTypeLabel: '필드 타입',
    /** 필수 여부 checkbox label */
    requiredLabel: '필수',
    /** 표시 순서 number label */
    displayOrderLabel: '표시 순서',
    /** 선택지 목록 label (SINGLE_SELECT / MULTI_SELECT / RADIO 등) */
    optionsLabel: '선택지',
    /** 선택지 추가 버튼 visible 텍스트 */
    addOptionButton: '선택지 추가',
  },

  /** FieldType 10종 사람이 읽는 한국어 라벨 */
  fieldTypes: {
    /** 한 줄 텍스트 입력 */
    SHORT_TEXT: '짧은 텍스트',
    /** 여러 줄 텍스트 입력 */
    LONG_TEXT: '긴 텍스트',
    /** 숫자 입력 */
    NUMBER: '숫자',
    /** 날짜 (시간 없음) */
    DATE: '날짜',
    /** 날짜 + 시간 */
    DATETIME: '날짜/시간',
    /** 단일 선택 드롭다운 */
    SINGLE_SELECT: '단일 선택',
    /** 다중 선택 */
    MULTI_SELECT: '다중 선택',
    /** 체크박스 (true/false) */
    CHECKBOX: '체크박스',
    /** 라디오 버튼 단일 선택 */
    RADIO: '라디오',
    /** URL 입력 */
    URL: 'URL',
  },
} as const

/** 라벨 const 추론 타입 */
export type CustomFieldLabels = typeof customFieldLabels

/**
 * 백엔드 errorCode를 사용자 노출 메시지로 변환한다.
 * ProblemDetail detail 필드는 직접 쓰지 않으며 이 함수가 단일 출처다.
 *
 * @param errorCode - 백엔드 ProblemDetail의 errorCode 필드값 (null 허용)
 * @returns 사용자에게 노출할 한국어 오류 메시지
 */
export function customFieldErrorMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'VALIDATION_FAILED':
      return '입력값을 확인해주세요.'
    case 'CUSTOM_FIELD_NOT_FOUND':
      return '필드를 찾을 수 없습니다.'
    case 'CUSTOM_FIELD_PROJECT_NOT_FOUND':
      return '프로젝트를 찾을 수 없습니다.'
    case 'CUSTOM_FIELD_KEY_DUPLICATE':
      return '이미 같은 키의 필드가 있습니다.'
    case 'CUSTOM_FIELD_ACCESS_DENIED':
      return '권한이 없습니다.'
    case 'CUSTOM_FIELD_INVALID_DEFINITION':
      return '필드 정의가 올바르지 않습니다. (선택형은 옵션이 1개 이상 필요)'
    case 'CUSTOM_FIELD_IMMUTABLE_CHANGE':
      return '필드 타입과 키는 변경할 수 없습니다.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}
