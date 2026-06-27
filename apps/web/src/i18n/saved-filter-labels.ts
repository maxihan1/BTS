// 저장된 필터 UI 한국어 라벨 단일 출처 — FR-SR-03 Task-1

/**
 * 저장된 필터 화면에서 사용하는 한국어 라벨/텍스트.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const savedFilterLabels = {
  /** 저장된 필터 섹션 제목 */
  sectionTitle: '저장된 필터',

  /** 내 필터 탭 */
  myFiltersTab: '내 필터',

  /** 저장된 필터가 없을 때 안내 문구 */
  emptyState: '저장된 필터가 없습니다',

  /** 새 필터 만들기 버튼 */
  createButton: '새 필터 만들기',

  /** 필터 수정 버튼 */
  editButton: '수정',

  /** 필터 삭제 버튼 */
  deleteButton: '삭제',

  /** 삭제 확인 대화상자 본문 */
  deleteConfirm: '이 필터를 삭제하시겠습니까?',

  /** 삭제 확인 버튼 */
  deleteConfirmButton: '삭제하기',

  /** 필터 저장 버튼 */
  saveButton: '저장',

  /** 취소 버튼 */
  cancelButton: '취소',

  /** 필터 실행 버튼 */
  runButton: '필터 적용',

  /** 이름 입력 필드 라벨 */
  nameLabel: '이름',

  /** 이름 입력 placeholder */
  namePlaceholder: '필터 이름 입력',

  /** AQL 쿼리 입력 필드 라벨 */
  queryLabel: 'AQL 쿼리',

  /** 공유 설정 섹션 라벨 */
  shareLabel: '공유 설정',

  /** 공유 타입 — 프로젝트 */
  shareTypeProject: '프로젝트',

  /** 공유 타입 — 그룹 */
  shareTypeGroup: '그룹',

  /** 공유 타입 — 인증된 사용자 전체 */
  shareTypeAuthenticated: '인증된 사용자 전체',

  /** 목록 불러오기 오류 */
  loadError: '필터 목록을 불러오지 못했습니다',

  /** 저장 실패 오류 */
  saveError: '필터 저장에 실패했습니다',

  /** 삭제 실패 오류 */
  deleteError: '필터 삭제에 실패했습니다',

  /** 이름 중복 오류 (409 SEARCH_FILTER_NAME_CONFLICT) */
  nameConflictError: '같은 이름의 필터가 이미 있습니다',

  /** OCC 충돌 오류 (409 SEARCH_FILTER_CONFLICT) */
  conflictError: '다른 사용자가 수정했습니다. 새로 고침 후 다시 시도하세요',

  /** 로딩 중 안내 */
  loading: '불러오는 중...',

  // ──────────────────────────────────────────────────────────────────────
  // Task-5 SavedFilterMenu 드롭다운 전용 라벨
  // ──────────────────────────────────────────────────────────────────────

  /** 저장된 필터 드롭다운 트리거 버튼 aria-label */
  menuTriggerAriaLabel: '저장된 필터 목록',

  /** 공유받은 필터 섹션 헤더 */
  sharedFiltersSection: '공유받은 필터',

  /** 드롭다운 빈 상태 메시지 */
  menuEmptyMessage: '저장된 필터 없음',

  /** 필터 행 공유 설정 버튼 (드롭다운 행용 — shareLabel은 다이얼로그 제목) */
  shareButton: '공유',

  /** 저장 필터 다이얼로그 생성 모드 제목 */
  dialogTitleCreate: '필터 저장',

  /** 저장 필터 다이얼로그 편집 모드 제목 */
  dialogTitleEdit: '필터 수정',

  /** 이름 필수 입력 유효성 메시지 */
  nameRequired: '이름을 입력하세요',

  /** 이름 100자 이하 유효성 메시지 */
  nameTooLong: '이름은 100자 이하입니다',

  /** 공유 토글 — 모든 로그인 사용자 */
  shareWithAuthenticated: '모든 로그인 사용자에게',

  /**
   * 공유 토글 — 이 프로젝트 멤버 (함수형 — projectKey를 인자로 받아 문자열 반환).
   * ko.test 콜론 가드는 typeof value === 'string' 필터라 함수 타입은 검사 대상 아님.
   */
  shareWithProjectMembers: (projectKey: string): string => `이 프로젝트(${projectKey}) 멤버에게`,
} as const

/** savedFilterLabels const 추론 타입 */
export type SavedFilterLabels = typeof savedFilterLabels
