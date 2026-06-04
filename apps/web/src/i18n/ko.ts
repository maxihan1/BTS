// 한국어 UI 문자열 상수 — 모든 사용자 노출 텍스트를 이 파일에서 관리

/** 로그인 폼 관련 문자열 */
export const loginStrings = {
  /** 폼 레이블 */
  usernameLabel: '사용자명',
  passwordLabel: '비밀번호',
  providerLabel: '로그인 방식',
  submitButton: '로그인',

  /** provider 드롭다운 옵션 표시 이름 */
  providerLocal: 'Local',
  providerLdapCorp: 'LDAP-corp',

  /** Zod 검증 에러 메시지 */
  usernameRequired: '사용자명을 입력하세요.',
  passwordRequired: '비밀번호를 입력하세요.',

  /** 백엔드 에러 코드 → 사용자 메시지 */
  errorInvalidCredentials: '사용자명 또는 비밀번호가 올바르지 않습니다.',
  errorMfaRequired: '추가 인증이 필요합니다. 관리자에게 문의하세요.',
  errorUnknownProvider: '지원하지 않는 로그인 방식입니다. 다시 시도해 주세요.',
  errorDefault: '로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  /** SAML SSO 버튼 */
  samlLoginButtonLabel: (displayName: string) => `${displayName} 로 로그인`,
  samlDividerText: '또는',

  /** OIDC SSO 버튼 */
  oidcLoginButtonLabel: (displayName: string) => `${displayName} 로 로그인`,
  oidcDividerText: '또는',
} as const

/** /login 페이지 헤딩 */
export const loginPageStrings = {
  heading: 'BTS 로그인',
} as const

/** 이슈 상세 페이지 관련 문자열 */
export const issueDetailStrings = {
  /** 로딩 상태 텍스트 */
  loading: '로딩 중...',
  /** 이슈를 찾을 수 없을 때 에러 메시지 */
  notFound: '이슈를 찾을 수 없습니다',
  /** 메타패널 — 상태 레이블 */
  statLabel: '상태',
  /** 메타패널 — 보고자 레이블 */
  reporterLabel: '보고자',
  /** 메타패널 — 프로젝트 레이블 */
  projectLabel: '프로젝트',
  /** 메타패널 — 버전 레이블 */
  versionLabel: '버전',
  /** 메타패널 — 생성일 레이블 */
  createdAtLabel: '생성',
  /** 메타패널 — 수정일 레이블 */
  updatedAtLabel: '수정',
  /** 삭제 버튼 텍스트 */
  deleteButton: '이슈 삭제',
  /** 삭제 권한 없을 때 사유 메시지 (aria-label / title) */
  deleteButtonNoPermission: '삭제 권한이 없습니다',
  /** 삭제 확인 메시지 */
  deleteConfirmMessage: '이 이슈를 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.',
  /** 삭제 확인 버튼 */
  confirmButton: '확인',
  /** 제목 편집 버튼 텍스트 */
  editTitleButton: '✎ 제목 수정',
  /** 제목 편집 input aria-label */
  titleEditLabel: '제목 편집',
  /** 저장 버튼 */
  saveButton: '저장',
  /** 취소 버튼 */
  cancelButton: '취소',
  /** 설명 자리표시자 */
  descriptionPlaceholder: '(FR-IS-04 본문 단계에서 추가 예정)',
  /** 메타패널 — 유형 레이블 */
  typeLabel: '유형',
  /** 유형 셀렉터 aria-label */
  typeSelectLabel: '유형 선택',
  /** 409 버전 충돌 에러 메시지 */
  versionConflictError: '다른 사용자가 이미 이 이슈를 수정했습니다. 새로고침 후 다시 시도해 주세요.',
  /** 타입 변경 실패 기본 에러 메시지 */
  typeChangeError: '이슈 유형 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 전이 셀렉터 aria-label */
  transitionSelectLabel: '상태 전이 선택',
  /** 가용전이 0건일 때 안내 문구 (종료상태 S6) */
  noTransitionsAvailable: '더 진행할 전이 없음',
  /** 409 transition_not_allowed 에러 메시지 (S3) */
  transitionNotAllowedError: '현재 상태에서 허용되지 않는 전이입니다.',
  /** 409 version_conflict 에러 메시지 (S4) — 최신 데이터 재조회 유도 */
  transitionVersionConflictError: '다른 사용자가 이미 이 이슈를 수정했습니다. 페이지를 새로고침해 최신 상태를 확인해 주세요.',
  /** 422 workflow_not_configured 에러 메시지 (S5) */
  transitionWorkflowNotConfiguredError: '이 이슈에 워크플로우가 설정되지 않아 상태를 변경할 수 없습니다.',

  // ── 본문(description) 탭/버튼 ──────────────────────────────────────
  /** 본문 편집 탭 레이블 */
  descriptionWriteTab: '편집',
  /** 본문 미리보기 탭 레이블 */
  descriptionPreviewTab: '미리보기',
  /** 본문이 비어 있을 때 안내 문구 */
  descriptionEmpty: '본문이 없습니다.',
  /** 본문 편집 시작 버튼 */
  descriptionEditButton: '본문 편집',
  /** 본문 편집 권한 없을 때 사유 메시지 (title/aria) */
  descriptionEditButtonNoPermission: '수정 권한이 없습니다',
  /** 본문 저장 버튼 */
  descriptionSaveButton: '저장',
  /** 본문 편집 취소 버튼 */
  descriptionCancelButton: '취소',

  // ── 우선순위(priority) ──────────────────────────────────────────────
  /** 메타패널 — 우선순위 레이블 */
  priorityLabel: '우선순위',
  /** 우선순위 셀렉터 aria-label */
  prioritySelectLabel: '우선순위 선택',
  /** 우선순위 1~5 한글 이름 매핑 (1=가장 높음 … 5=가장 낮음) */
  priorityNames: {
    1: '가장 높음',
    2: '높음',
    3: '보통',
    4: '낮음',
    5: '가장 낮음',
  } as Record<1 | 2 | 3 | 4 | 5, string>,
  /** 우선순위 변경 실패 에러 메시지 */
  priorityChangeError: '우선순위 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 영향도(impact) ─────────────────────────────────────────────────
  /** 메타패널 — 영향도 레이블 */
  impactLabel: '영향도',
  /** 영향도 셀렉터 aria-label */
  impactSelectLabel: '영향도 선택',
  /** 영향도 1~3 한글 이름 매핑 (1=높음, 2=보통, 3=낮음) */
  impactNames: {
    1: '높음',
    2: '보통',
    3: '낮음',
  } as Record<1 | 2 | 3, string>,
  /** 영향도 미지정 상태 표시 */
  impactUnset: '미지정',
  /** 영향도 변경 실패 에러 메시지 */
  impactChangeError: '영향도 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 환경(environment) ──────────────────────────────────────────────
  /** 메타패널 — 환경 레이블 */
  environmentLabel: '환경',
  /** 환경 입력 필드 자리표시자 */
  environmentPlaceholder: '재현 환경을 입력하세요.',
  /** 환경 저장 버튼 */
  environmentSaveButton: '저장',
  /** 환경 저장 실패 에러 메시지 */
  environmentSaveError: '환경 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 라벨(labels) ───────────────────────────────────────────────────
  /** 메타패널 — 라벨 레이블 */
  labelsLabel: '라벨',
  /** 라벨 추가 입력 필드 자리표시자 */
  labelAddPlaceholder: '라벨 추가',
  /** 라벨 제거 버튼 aria-label */
  labelRemoveLabel: '라벨 제거',
  /** 라벨 저장 버튼 */
  labelsSaveButton: '저장',
  /** 라벨 저장 실패 에러 메시지 */
  labelsSaveError: '라벨 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 담당자(assignee) — FR-IS-03 ─────────────────────────────────
  /** 메타패널 — 담당자 레이블 */
  assigneeLabel: '담당자',
  /** 담당자 미할당 상태 표시 */
  assigneeUnassigned: '미지정',
  /** 담당자 검색 input 자리표시자 */
  assigneeSearchPlaceholder: '사용자 검색',
  /** 담당자 해제 버튼 텍스트 */
  assigneeUnassignButton: '담당자 해제',
  /** 422 ASSIGNEE_NOT_FOUND 에러 메시지 */
  assigneeNotFoundError: '선택한 사용자를 찾을 수 없습니다. 다시 검색 후 선택해 주세요.',
  /** 담당자 변경 실패 에러 메시지 */
  assigneeChangeError: '담당자 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 해결 결과(resolution) — FR-IS-07 ────────────────────────────────
  /** 메타패널 — 해결 결과 레이블 */
  resolutionLabel: '해결 결과',

  // ── PDF 다운로드 — FR-IS-08 ──────────────────────────────────────────
  /** PDF 다운로드 버튼 텍스트 */
  pdfDownloadButton: 'PDF',
  /** PDF 다운로드 버튼 aria-label */
  pdfDownloadAriaLabel: '이슈를 PDF로 다운로드',
  /** PDF 다운로드 실패 에러 메시지 */
  pdfDownloadError: 'PDF 다운로드에 실패했습니다.',

  // ── 이슈 클론 — FR-IS-06 ────────────────────────────────────────────
  /** 클론 버튼 텍스트 */
  cloneButton: '이슈 클론',
  /** 클론 Dialog 제목 */
  cloneDialogTitle: '이슈 클론',
  /** 클론 Dialog — 담당자 포함 체크박스 레이블 */
  cloneIncludeAssigneeLabel: '담당자 포함',
  /** 클론 Dialog — 제목 재정의 입력 레이블 */
  cloneSummaryOverrideLabel: '새 이슈 제목 (선택)',
  /** 클론 Dialog — 제목 재정의 입력 placeholder */
  cloneSummaryOverridePlaceholder: '비워두면 원본 제목을 사용합니다',
  /** 클론 Dialog — 실행 버튼 */
  cloneSubmitButton: '클론 생성',
  /** 클론 Dialog — 취소 버튼 */
  cloneCancelButton: '취소',
  /** 클론 성공 토스트 */
  cloneSuccessToast: '이슈가 복제되었습니다.',
  /** 클론 실패 — 이슈 없음 */
  cloneErrorNotFound: '이슈를 찾을 수 없습니다.',
  /** 클론 실패 — 권한 없음 */
  cloneErrorForbidden: '이슈를 클론할 권한이 없습니다.',
  /** 클론 실패 — 유효성 오류 */
  cloneErrorValidation: '입력 값이 올바르지 않습니다. 확인 후 다시 시도해 주세요.',
  /** 클론 실패 — 기본 에러 */
  cloneErrorDefault: '이슈 클론 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
} as const

/** 이슈 생성 폼 관련 문자열 */
export const issueCreateStrings = {
  /** 폼 레이블 */
  projectKeyLabel: '프로젝트 키',
  summaryLabel: '제목',
  submitButton: '이슈 생성',

  /** Zod 검증 에러 메시지 */
  projectKeyRequired: '프로젝트 키를 입력하세요.',
  summaryRequired: '제목을 입력하세요.',
  summaryTooLong: '제목은 500자 이하로 입력하세요.',

  /** 백엔드 에러 코드 → 사용자 메시지 */
  errorProjectNotFound: '존재하지 않는 프로젝트입니다.',
  errorDefault: '이슈 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
} as const
