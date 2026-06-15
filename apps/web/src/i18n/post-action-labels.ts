// 워크플로우 전이 post-action 설정 UI 의 E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점

/**
 * post-action(CALL_WEBHOOK) 폼/다이얼로그 UI가 E2E 셀렉터로 노출하는 한국어 라벨/텍스트.
 *
 * - PR #22 §F4 학습 — E2E 가 i18n 정본 참조해 hardcoded string drift 차단
 * - 그룹 — dialog / form / section / list
 *   - dialog: 제목·버튼 계열 (E2E getByRole 기준)
 *   - form: 필드 라벨·placeholder·에러 메시지 (E2E getByLabelText·getByText 기준)
 *   - section: PostActionConfigSection 제목·전이선택·추가버튼 계열
 *   - list: 목록 테이블 헤더·빈 상태·수정/삭제 버튼 계열
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

  /** 섹션 제목·전이 선택·버튼 계열 (PostActionConfigSection 기준) */
  section: {
    /** 섹션 제목 */
    title: 'Post-Action 설정',
    /** 전이 선택 select label */
    transitionSelectLabel: '전이 선택',
    /** 전이 미선택 시 placeholder 옵션 텍스트 */
    transitionSelectPlaceholder: '전이를 선택하세요',
    /** Webhook 추가 버튼 텍스트 */
    addWebhookButton: 'Webhook 추가',
    /**
     * 일부 전이가 '__' 구분자 계약 충돌로 선택 불가임을 알리는 안내 문구.
     * 근본 원인: transitionKey()는 `${from}__${to}` 형식으로 합성하며,
     * 백엔드 WorkflowTransition.kt도 동일 구분자로 split한다.
     * state key에 '__'가 포함되면 합성키가 3+ 조각으로 쪼개져 PostActionNotFoundException 발생.
     * 구분자 자체 변경은 cross-BC 계약 변경이라 이 PR 밖 후속 작업.
     */
    ambiguousKeyHint: '일부 전이는 키 형식 제약으로 설정할 수 없습니다.',
  },

  /** post-action 목록 테이블 헤더·빈 상태 (PostActionConfigSection 기준) */
  list: {
    /** type 컬럼 헤더 */
    typeColumn: '유형',
    /** config 컬럼 헤더 (url/method 요약) */
    configColumn: '설정',
    /** displayOrder 컬럼 헤더 */
    orderColumn: '순서',
    /** 액션 컬럼 헤더 */
    actionColumn: '액션',
    /** 0건일 때 빈 상태 안내 문구 */
    emptyState: '등록된 post-action이 없습니다.',
    /** 행 수정 버튼 텍스트 */
    editButton: '수정',
    /** 행 삭제 버튼 텍스트 */
    deleteButton: '삭제',
  },

  /** 에러 메시지 — mutation 실패 toast, list query 에러 UI */
  error: {
    /** 목록 조회 실패 안내 문구 */
    loadFailed: 'post-action 목록을 불러오지 못했습니다. 다시 시도해 주세요.',
    /** 추가 실패 toast 메시지 */
    addFailed: 'post-action 추가에 실패했습니다.',
    /** 수정 실패 toast 메시지 */
    updateFailed: 'post-action 수정에 실패했습니다.',
    /** 삭제 실패 toast 메시지 */
    removeFailed: 'post-action 삭제에 실패했습니다.',
    /** 전이 미선택 시 추가 버튼 비활성 안내 */
    selectTransitionFirst: '전이를 먼저 선택하세요.',
  },
} as const

/** 라벨 const 의 추론 타입 — 호출자 타입 안전성 */
export type PostActionLabels = typeof postActionLabels
