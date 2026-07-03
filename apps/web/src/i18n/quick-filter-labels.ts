// 보드 퀵필터 UI 한국어 라벨 단일 출처 — FR-UX-01 Task 9

/**
 * 보드 퀵필터 UI(칩 목록·저장/편집 다이얼로그)가 노출하는 한국어 라벨/텍스트.
 *
 * boardFilterLabels.ts / savedFilterLabels.ts 패턴 동일 적용.
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 *
 * 그룹 — list / dialog / form / errors
 */
export const quickFilterLabels = {
  /** 칩 목록 영역 */
  list: {
    /** 칩 목록 role="list" aria-label */
    ariaLabel: '퀵필터 목록',
    /** "현재 필터 저장" 트리거 버튼 텍스트 */
    saveCurrentButton: '필터 저장',
    /**
     * 칩 수정 버튼 aria-label 생성 함수.
     * @param name 퀵필터 이름
     * @returns "{name} 수정"
     */
    editAriaLabel: (name: string): string => `${name} 수정`,
    /**
     * 칩 삭제 버튼 aria-label 생성 함수.
     * @param name 퀵필터 이름
     * @returns "{name} 삭제"
     */
    deleteAriaLabel: (name: string): string => `${name} 삭제`,
  },

  /** 저장/편집 다이얼로그 제목 */
  dialog: {
    /** 생성 모드 제목 */
    titleCreate: '퀵필터 저장',
    /** 편집 모드 제목 */
    titleEdit: '퀵필터 수정',
  },

  /** 다이얼로그 폼 필드 */
  form: {
    /** 이름 입력 필드 라벨 */
    nameLabel: '이름',
    /** 이름 입력 placeholder */
    namePlaceholder: '퀵필터 이름 입력',
    /** 이름 필수 입력 유효성 메시지 */
    nameRequired: '이름을 입력하세요',
    /** 이름 50자 이하 유효성 메시지 (백엔드 MAX_NAME_LENGTH와 동일) */
    nameTooLong: '이름은 50자 이하입니다',
    /** 저장 버튼 텍스트 */
    saveButton: '저장',
    /** 취소 버튼 텍스트 */
    cancelButton: '취소',
    /** 현재 적용된 필터가 없어 저장할 수 없을 때 안내 문구 (EC1) */
    emptyQueryHint: '적용된 필터가 없어 저장할 수 없습니다',
  },

  /** 오류 메시지 — HTTP status 기반 매핑(errorCode 계약 미확정이라 status만 사용) */
  errors: {
    /** 409 — 같은 보드 내 이름 중복(EC2) */
    nameConflict: '같은 이름의 퀵필터가 이미 있습니다',
    /** 400 — 잘못된 필터 조건(EC4) */
    invalidQuery: '필터 조건을 확인해 주세요',
    /** 그 외 생성/수정 실패 */
    saveFailed: '퀵필터 저장에 실패했습니다',
    /** 삭제 실패 */
    deleteFailed: '퀵필터 삭제에 실패했습니다',
  },
} as const

/** quickFilterLabels const 추론 타입 */
export type QuickFilterLabels = typeof quickFilterLabels
