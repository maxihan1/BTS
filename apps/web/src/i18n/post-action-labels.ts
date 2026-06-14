// 워크플로우 전이 post-action 설정 UI 의 E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점

/**
 * post-action(CALL_WEBHOOK) 폼/다이얼로그 UI가 E2E 셀렉터로 노출하는 한국어 라벨/텍스트.
 *
 * - PR #22 §F4 학습 — E2E 가 i18n 정본 참조해 hardcoded string drift 차단
 * - 그룹 — dialog / form
 *   - dialog: 제목·버튼 계열 (E2E getByRole 기준)
 *   - form: 필드 라벨·placeholder·에러 메시지 (E2E getByLabelText·getByText 기준)
 */
export const postActionLabels = {
  /** 다이얼로그 제목 및 액션 버튼 텍스트 */
  dialog: {
    /** create 모드 다이얼로그 제목 */
    createTitle: 'Post-Action 추가',
    /** edit 모드 다이얼로그 제목 */
    editTitle: 'Post-Action 수정',
    /** create 모드 제출 버튼 visible 텍스트 */
    createButton: '추가',
    /** edit 모드 제출 버튼 visible 텍스트 */
    saveButton: '저장',
    /** 취소 버튼 visible 텍스트 */
    cancelButton: '취소',
    /** mutation 진행 중 제출 버튼 visible 텍스트 */
    submittingButton: '처리 중...',
  },

  /** 폼 필드 라벨·placeholder·인라인 에러 메시지 */
  form: {
    /** Webhook URL 입력 필드 label */
    urlLabel: 'Webhook URL',
    /** Webhook URL 입력 필드 placeholder */
    urlPlaceholder: 'https://example.com/webhook',
    /** HTTP 메서드 select 필드 label */
    methodLabel: '메서드 (HTTP Method)',
    /** url 필드 — 빈값 에러 메시지 */
    errorUrlRequired: 'URL을 입력해 주세요.',
    /** url 필드 — http/https 아님 에러 메시지 */
    errorUrlInvalid: 'URL은 http:// 또는 https://로 시작해야 합니다.',
    /** method 필드 — 빈값 에러 메시지 */
    errorMethodRequired: '메서드를 선택해 주세요.',
  },
} as const

/** 라벨 const 의 추론 타입 — 호출자 타입 안전성 */
export type PostActionLabels = typeof postActionLabels
