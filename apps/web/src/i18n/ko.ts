// 한국어 UI 문자열 상수 — 모든 사용자 노출 텍스트를 이 파일에서 관리

/** 로그인 폼 관련 문자열 */
export const loginStrings = {
  /** 폼 레이블 */
  usernameLabel: '사용자명',
  passwordLabel: '비밀번호',
  providerLabel: '로그인 방식',
  submitButton: '로그인',

  /** identifier-first 1단계 */
  emailLabel: '이메일',
  continueButton: '계속',
  emailStepDescription: '이메일 주소로 로그인 방식을 확인합니다.',

  /** provider 드롭다운 옵션 표시 이름 */
  providerLocal: 'Local',
  providerLdapCorp: 'LDAP-corp',

  /** Zod 검증 에러 메시지 */
  usernameRequired: '사용자명을 입력하세요.',
  passwordRequired: '비밀번호를 입력하세요.',

  /** 백엔드 에러 코드 → 사용자 메시지 */
  errorInvalidCredentials: '사용자명 또는 비밀번호가 올바르지 않습니다.',
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
  /** 본문 열람 권한 없을 때 placeholder (FR-PM-07 restrictedFields) */
  descriptionRestricted: '이 필드를 볼 권한이 없습니다.',
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

  // ── 컴포넌트(components) — FR-CM-02 ─────────────────────────────────
  /** 메타패널 — 컴포넌트 레이블 */
  componentsLabel: '컴포넌트',
  /** 422 COMPONENT_NOT_FOUND 에러 메시지 */
  componentNotFoundError: '선택한 컴포넌트를 찾을 수 없습니다. 다시 선택해 주세요.',
  /** 컴포넌트 변경 실패 에러 메시지 */
  componentChangeError: '컴포넌트 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 보안등급(securityLevel) — FR-PM-06 PR-B ──────────────────────────
  /** 메타패널 — 보안등급 레이블 */
  securityLevelLabel: '보안등급',
  /** 보안등급 셀렉터 aria-label */
  securityLevelSelectLabel: '보안등급 선택',
  /** 보안등급 미지정 옵션 표시 */
  securityLevelNone: '선택 안 함',
  /** 보안등급 변경 실패 — 409 버전 충돌 */
  securityLevelVersionConflictError: '다른 사용자가 이미 이 이슈를 수정했습니다. 새로고침 후 다시 시도해 주세요.',
  /** 보안등급 변경 실패 — 403 권한 없음 */
  securityLevelForbiddenError: '보안등급을 변경할 권한이 없습니다.',
  /** 보안등급 변경 실패 — 기본 에러 */
  securityLevelChangeError: '보안등급 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 이슈 생성 폼 — 보안등급 레이블 */
  securityLevelCreateLabel: '보안등급 (선택)',

  // ── 영향 버전 / 수정 버전(affectsVersions / fixVersions) — FR-VR-03 ────
  /** 메타패널 — 영향 버전 레이블 */
  affectsVersionsLabel: '영향 버전',
  /** 영향 버전 검색 input placeholder */
  affectsVersionsSearchPlaceholder: '영향 버전 검색',
  /** 메타패널 — 수정 버전 레이블 */
  fixVersionsLabel: '수정 버전',
  /** 수정 버전 검색 input placeholder */
  fixVersionsSearchPlaceholder: '수정 버전 검색',
  /** 422 ISSUE_LINKED_VERSION_NOT_FOUND 에러 메시지 */
  versionLinkedNotFoundError: '선택한 버전을 찾을 수 없습니다. 다시 선택해 주세요.',
  /** 영향 버전 변경 실패 기본 에러 메시지 */
  affectsVersionsChangeError: '영향 버전 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 수정 버전 변경 실패 기본 에러 메시지 */
  fixVersionsChangeError: '수정 버전 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 커스텀 필드(custom fields) — FR-IS-10 E-3 ──────────────────────────
  /** 커스텀 필드 섹션 레이블 */
  customFieldsSectionLabel: '커스텀 필드',
  /** 커스텀 필드 저장 버튼 */
  customFieldsSaveButton: '저장',
  /** 커스텀 필드 저장 버튼 aria-label */
  customFieldsSaveAriaLabel: '커스텀 필드 저장',
  /** required 필드 빈값 저장 시도 경고 메시지 (스펙 E-3) */
  customFieldRequiredEmpty: '필수 항목을 모두 입력해 주세요.',

  // ── 변경 이력(changelog) — FR-HS-02 ─────────────────────────────────────
  /** 변경 이력 섹션 — 필드명 표시 (field 키 → 한글) */
  changelogFieldLabels: {
    lifecycle: '생명주기',
    summary: '제목',
    description: '본문',
    priority: '우선순위',
    labels: '라벨',
    environment: '환경',
    impact: '영향도',
    type: '유형',
    assignee: '담당자',
    status: '상태',
    resolution: '해결 결과',
    components: '컴포넌트',
    affectsVersions: '영향 버전',
    fixVersions: '수정 버전',
    securityLevel: '보안등급',
    key: '프로젝트 이동',
    startDate: '시작일',
    dueDate: '마감일',
    targetDate: '목표일',
    epic: '에픽',
  } as Record<string, string>,
  /** 변경 이력 — 생명주기 "created" 표시 문자열 */
  changelogLifecycleCreated: '이슈를 생성했습니다',
  /** 변경 이력 — 생명주기 "deleted" 표시 문자열 */
  changelogLifecycleDeleted: '이슈를 삭제했습니다',
  /** 변경 이력 — 값 없음 표시 */
  changelogValueNone: '(없음)',
  /** 변경 이력 — 삭제된 엔티티 폴백 표시 */
  changelogDeletedEntity: '(삭제됨)',
  /**
   * 변경 이력 — 댓글 본문 수정 항목의 필드 표시명 (FR-CO-02).
   *
   * 백엔드가 보내는 field 는 `comment:{commentId}` 라 `changelogFieldLabels` 테이블에
   * 넣을 수 없다(키가 댓글마다 다르다). commentId 는 사용자에게 의미 없는 UUID 이므로
   * 표시명에 싣지 않는다.
   */
  changelogCommentFieldLabel: '댓글',
  /**
   * 변경 이력 — 삭제된 댓글이라 본문이 가려진 항목 표시 (FR-CO-02 S10).
   *
   * `changelogValueNone`("(없음)") 과 **반드시 다른 문구**다. 전자는 "값이 비어 있었다",
   * 이쪽은 "값은 있었지만 댓글이 삭제돼 가렸다" 로 의미가 다르다. 같은 문구를 쓰면
   * 사용자가 "빈 댓글로 고쳤나" 로 오해한다.
   */
  changelogCommentMasked: '(삭제된 댓글)',
  /** 변경 이력 섹션 제목 */
  changelogSectionTitle: '변경 이력',
  /** 변경 이력 없을 때 빈 상태 메시지 */
  changelogEmpty: '변경 이력이 없습니다.',
  /** 더 보기 버튼 텍스트 */
  changelogLoadMore: '더 보기',
  /** 로딩 중 aria-label */
  changelogLoading: '변경 이력 로딩 중',
  /** 에러 메시지 */
  changelogError: '변경 이력을 불러오지 못했습니다.',
  /** actorName=null일 때 표시 — 시스템 이벤트 */
  changelogSystemActor: '시스템',

  // ── 감시자(watchers) — FR-WT-01 D6 ─────────────────────────────────────
  /** 감시자 섹션 제목 레이블 */
  watchersLabel: '감시자',
  /** 지켜보기 시작 버튼 텍스트 */
  watchButton: '지켜보기',
  /** 지켜보는 중 버튼 텍스트 */
  unwatchButton: '지켜보는 중',
  /** 감시자가 없을 때 빈 상태 메시지 */
  watchersEmpty: '감시자가 없습니다.',
  /** 감시자 목록 로딩 중 표시 문구 */
  watchersLoading: '감시자 불러오는 중',
  /** 감시자 목록 로드 실패 에러 메시지 */
  watchersError: '감시자를 불러오지 못했습니다.',
  /** 감시자 카운트 표시 — N명 */
  watchersCount: (n: number) => `${n}명`,
  /** 본인 감시자 항목 접미사 */
  watcherSelfSuffix: '(나)',
  /** 목록 초과 시 더 보기 표시 — +N명 더 */
  watchersMore: (n: number) => `+${n}명 더`,

  // ── 일정 필드(schedule dates) — FR-PL-01 ────────────────────────────────
  /** 메타패널 — 일정 섹션 레이블 */
  scheduleLabel: '일정',
  /** 시작일 필드 레이블 */
  startDateLabel: '시작일',
  /** 마감일 필드 레이블 */
  dueDateLabel: '마감일',
  /** 목표일 필드 레이블 */
  targetDateLabel: '목표일',
  /** 일정 저장 버튼 */
  scheduleSaveButton: '저장',
  /** 일정 저장 버튼 aria-label */
  scheduleSaveAriaLabel: '일정 저장',
  /** 일정 저장 실패 에러 메시지 */
  scheduleSaveError: '일정 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 활동 탭(activity tabs) — FR-UX-06 PR19 Task 1 ───────────────────────
  /** 활동 영역 — 작업로그 탭 라벨 */
  activityWorklogTabLabel: '작업로그',
  /** 활동 영역 — 연결 탭 라벨 */
  activityLinksTabLabel: '연결',
  /** 활동 영역 — 이력 탭 라벨 (기본 활성 탭, D1 / FR-CO-01 D8 에서도 기본 유지) */
  activityHistoryTabLabel: '이력',
  /** 활동 영역 — 댓글 탭 라벨 (FR-CO-01). 탭 라벨은 E2E 계약이므로 하드코딩 금지 */
  activityCommentTabLabel: '댓글',
} as const

/** 2단계 인증(TOTP) 설정 및 로그인 2단계 UI 문자열 */
export const mfaStrings = {
  // ── 설정 화면(/settings/mfa) ──────────────────────────────────────────
  /** 페이지 h1 제목 */
  settingsTitle: '2단계 인증',
  /** 페이지 설명 문구 */
  settingsDescription: 'Authenticator 앱을 사용해 계정 보안을 강화하세요.',
  /** TOTP 활성화 상태 표시 배지 */
  statusEnabled: '활성화됨',
  /** TOTP 비활성화 상태 표시 배지 */
  statusDisabled: '비활성화됨',
  /** 활성화 시작 버튼 */
  enableButton: '2단계 인증 활성화',
  /** 비활성화 버튼 (step-up 코드 확인 후 실행) */
  disableButton: '2단계 인증 비활성화',
  /** QR 코드 스캔 안내 문구 */
  qrScanGuide: 'Authenticator 앱으로 아래 QR 코드를 스캔하세요.',
  /** secret 수동 입력 안내 문구 (QR 스캔 불가 환경용) */
  secretManualGuide: 'QR 스캔이 어려우면 아래 코드를 Authenticator 앱에 직접 입력하세요.',
  /** 설정 코드 입력 필드 레이블 */
  codeLabel: '인증 코드 (6자리)',
  /** 설정 코드 입력 필드 placeholder */
  codePlaceholder: '000000',
  /** 활성화 폼 제출 버튼 레이블 */
  enableConfirmButton: '활성화 확인',
  /** 비활성화 폼 — step-up 코드 입력 필드 레이블 */
  disableCodeLabel: '현재 인증 코드 (6자리)',
  /** 비활성화 폼 제출 버튼 레이블 */
  disableConfirmButton: '비활성화 확인',

  // ── 백업코드 설정 섹션 ─────────────────────────────────────────────────
  /** 백업코드 섹션 제목 */
  backupSectionTitle: '백업 코드',
  /** 백업코드 섹션 설명 문구 */
  backupSectionDescription: '2단계 인증 기기를 분실했을 때 백업 코드로 로그인할 수 있습니다. 각 코드는 1회만 사용 가능합니다.',
  /** 백업코드 최초 생성 버튼 */
  backupGenerateButton: '백업 코드 생성',
  /** 백업코드 재생성 버튼 */
  backupRegenerateButton: '백업 코드 재생성',
  /** 남은 백업코드 개수 앞 안내 텍스트 */
  backupRemainingPrefix: '남은 백업 코드',
  /** 백업코드 소진 임박 경고 메시지 */
  backupLowWarning: '백업 코드가 얼마 남지 않았습니다. 재생성을 권장합니다.',
  /** 백업코드 모두 소진 경고 메시지 */
  backupNoneWarning: '남은 백업 코드가 없습니다. 새로 생성하세요.',
  /** 백업코드 저장 안내 — 화면 닫으면 재확인 불가 */
  backupSaveWarning: '지금 저장하세요. 이 화면을 닫으면 다시 볼 수 없습니다.',
  /** 코드 복사 버튼 레이블 */
  backupCopyButton: '복사',
  /** 복사 완료 상태 레이블 */
  backupCopiedLabel: '복사됨',
  /** 코드 다운로드 버튼 레이블 */
  backupDownloadButton: '다운로드',
  /** 저장 완료 후 Dialog 닫기 버튼 레이블 */
  backupCloseButton: '저장 완료',
  /** 클립보드 복사 실패 안내 메시지 */
  backupCopyFailed: '복사에 실패했습니다. 코드를 직접 선택해 복사하세요.',
  /** 다운로드 파일명 */
  backupDownloadFileName: 'bts-backup-codes.txt',
  /** 다운로드 파일 헤더 */
  backupDownloadHeader: 'BTS 백업 코드 (각 코드는 1회만 사용 가능)',
  /** 재생성 확인 Dialog 제목 */
  backupRegenerateConfirmTitle: '백업 코드 재생성',
  /** 재생성 확인 Dialog 본문 */
  backupRegenerateConfirmBody: '기존 백업 코드가 모두 무효화됩니다. 계속하시겠습니까?',
  /** 재생성 확인 버튼 레이블 */
  backupRegenerateConfirmButton: '재생성',
  /** 재생성 취소 버튼 레이블 */
  backupRegenerateCancelButton: '취소',

  // ── 로그인 2단계 ──────────────────────────────────────────────────────
  /** TOTP 코드 입력 화면 안내 문구 */
  loginStepGuide: 'Authenticator 앱에 표시된 6자리 코드를 입력하세요.',
  /** 로그인 2단계 코드 입력 필드 레이블 */
  loginCodeLabel: '인증 코드',
  /** 로그인 2단계 검증 버튼 */
  loginVerifyButton: '확인',
  /** 로그인 1단계로 돌아가는 링크/버튼 문구 */
  loginBackToLogin: '다시 로그인',
  /** 백업코드 로그인으로 전환하는 링크/버튼 문구 */
  loginUseBackupCode: '백업 코드로 로그인',
  /** TOTP 코드 로그인으로 돌아가는 링크/버튼 문구 */
  loginUseTotp: 'Authenticator 코드로 돌아가기',
  /** 백업코드 입력 필드 레이블 */
  loginBackupCodeLabel: '백업 코드',
  /** 백업코드 입력 화면 안내 문구 */
  loginBackupStepGuide: '백업 코드 중 하나를 입력하세요.',
  /** 백업코드 입력 검증 실패(빈 값) 메시지 */
  loginBackupCodeRequired: '백업 코드를 입력하세요.',
  /** 평문 백업코드 목록 aria-label */
  backupCodesListLabel: '백업 코드 목록',
  /** 남은 백업코드 개수 단위 접미사 */
  backupRemainingUnit: '개',

  // ── MFA 강제 정책(FR-MF-04) ──────────────────────────────────────────────
  /** MFA 등록 강제 게이트 안내 배너 문구 (mfaEnrollmentRequired=true 시 노출) */
  enforcementBanner: '보안 정책에 따라 2단계 인증 등록이 필요합니다. 등록을 완료해야 계속할 수 있습니다.',

  // ── 보안 키(WebAuthn/FIDO2) 설정 섹션 (FR-MF-03) ─────────────────────────
  /** 보안 키 섹션 제목 */
  webauthnSectionTitle: '보안 키',
  /** 보안 키 섹션 설명 문구 */
  webauthnSectionDescription: '하드웨어 보안 키나 기기 내장 인증(Face ID, 지문 등)으로 2단계 인증을 수행합니다.',
  /** 보안 키 추가 버튼 */
  webauthnAddButton: '보안 키 추가',
  /** 보안 키 별칭 입력 필드 레이블 */
  webauthnNameLabel: '별칭',
  /** 보안 키 별칭 입력 필드 placeholder */
  webauthnNamePlaceholder: '예: 회사 노트북',
  /** 등록된 보안 키가 없을 때 빈 상태 메시지 */
  webauthnEmptyState: '등록된 보안 키가 없습니다.',
  /** 보안 키 삭제 버튼 레이블 */
  webauthnDeleteButton: '삭제',
  /** 보안 키 삭제 확인 Dialog 제목 */
  webauthnDeleteConfirmTitle: '보안 키 삭제',
  /** 보안 키 삭제 확인 Dialog 본문 */
  webauthnDeleteConfirmBody: '이 보안 키를 삭제하시겠습니까? 삭제 후에는 해당 키로 인증할 수 없습니다.',
  /** 보안 키 삭제 확인 버튼 레이블 */
  webauthnDeleteConfirmButton: '삭제',
  /** 보안 키 삭제 취소 버튼 레이블 */
  webauthnDeleteCancelButton: '취소',
  /** 로그인 2단계 — 보안 키로 인증 버튼 */
  webauthnVerifyButton: '보안 키로 인증',
  /** 로그인 2단계 — 보안 키 인증 실패(코드 입력이 아닌 키 흐름이므로 전용 문구) */
  webauthnVerifyFailed: '보안 키 인증에 실패했습니다. 다시 시도하거나 다시 로그인해 주세요.',
  /** WebAuthn API 미지원 브라우저 안내 문구 */
  webauthnUnsupportedBrowser: '이 브라우저는 보안 키를 지원하지 않습니다. 최신 브라우저를 사용하세요.',
  /** lastUsedAt이 null인 경우 표시 문구 */
  webauthnLastUsedNever: '사용 안 함',
  /** lastUsedAt 앞 레이블 */
  webauthnLastUsedLabel: '마지막 사용',
  /** 등록 진행 중 안내 문구 */
  webauthnRegisteringGuide: '브라우저 안내에 따라 보안 키를 터치하거나 인증을 완료하세요.',

  // ── 신뢰 디바이스(FR-MF-05) ──────────────────────────────────────────────
  /** 신뢰 디바이스 섹션 제목 */
  trustedDevicesSectionTitle: '신뢰한 기기',
  /** 신뢰 디바이스 섹션 설명 문구 */
  trustedDevicesSectionDescription: '30일간 MFA 인증을 건너뜀으로 설정된 기기 목록입니다.',
  /** 신뢰 디바이스가 없을 때 빈 상태 메시지 */
  trustedDevicesEmptyState: '신뢰한 기기가 없습니다.',
  /** 단건 신뢰 해제 버튼 레이블 */
  trustedDevicesRevokeButton: '신뢰 해제',
  /** 전체 신뢰 해제 버튼 레이블 */
  trustedDevicesRevokeAllButton: '모든 기기 신뢰 해제',
  /** 신뢰 해제 확인 문구 */
  trustedDevicesRevokeConfirm: '이 기기의 신뢰를 해제하시겠습니까.',
  /** 기기명이 없을 때 대체 표시 문구 */
  trustedDevicesLabelFallback: '알 수 없는 기기',
  /** 마지막 사용 기록이 없을 때 표시 문구 */
  trustedDevicesLastUsedNever: '사용 기록 없음',
  /** 로그인 시 기기 신뢰 체크박스 레이블 */
  trustedDevicesLoginCheckboxLabel: '이 기기를 30일간 신뢰',
  /** 신뢰 해제 인라인 확인 박스 — 확인 버튼 */
  trustedDevicesConfirmButton: '확인',
  /** 신뢰 해제 인라인 확인 박스 — 취소 버튼 */
  trustedDevicesCancelButton: '취소',
  /** 기기 행 — 등록일 레이블 (콜론은 JSX에서 부착) */
  trustedDevicesRegisteredLabel: '등록',
  /** 기기 행 — 마지막 사용 레이블 (콜론은 JSX에서 부착) */
  trustedDevicesLastUsedLabel: '마지막 사용',
  /** 기기 행 — 만료일 레이블 (콜론은 JSX에서 부착) */
  trustedDevicesExpiresLabel: '만료',
  /** 목록 로드 실패 에러 메시지 */
  trustedDevicesLoadError: '기기 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
} as const

/**
 * MFA 관련 백엔드 에러 코드를 사용자 노출 메시지로 변환한다.
 * ProblemDetail detail 필드는 직접 노출하지 않으며 이 함수가 단일 출처다.
 * 계정 열거 방지를 위해 invalid_code / too_many_attempts는 원인 과노출 없는 일반 톤을 유지한다.
 *
 * @param errorCode - 백엔드 ProblemDetail 의 error 필드 값
 * @returns 사용자에게 노출할 한국어 메시지
 */
export function mfaErrorMessage(errorCode: string): string {
  switch (errorCode) {
    case 'invalid_code':
      return '코드가 올바르지 않습니다.'
    case 'too_many_attempts':
      return '시도가 너무 많습니다. 잠시 후 다시 시도하세요.'
    case 'no_pending_setup':
      return '진행 중인 설정이 없습니다. 다시 시도해 주세요.'
    case 'already_enabled':
      return '이미 2단계 인증이 활성화되어 있습니다.'
    case 'not_enabled':
      return '2단계 인증이 활성화되어 있지 않습니다.'
    case 'totp_not_active':
      return '먼저 Authenticator 앱(2단계 인증)을 활성화하세요.'
    case 'invalid_registration':
      return '보안 키 등록에 실패했습니다. 다시 시도해 주세요.'
    case 'already_registered':
      return '이미 등록된 보안 키입니다.'
    case 'not_found':
      return '보안 키를 찾을 수 없습니다.'
    case 'mfa_challenge_expired':
      return '인증 요청이 만료되었습니다. 다시 시도해 주세요.'
    case 'invalid_method':
    default:
      return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  }
}

/** 이슈 링크 패널(IssueLinksPanel) 관련 문자열 — FR-LK-01 D6 */
export const issueLinkStrings = {
  // ── 섹션 제목 ─────────────────────────────────────────────────────────
  /** 링크 섹션 제목 */
  linksSectionTitle: '링크',
  /** 부모 섹션 제목 */
  parentSectionTitle: '부모 이슈',

  // ── 링크 유형 표시명 ─────────────────────────────────────────────────
  /** 링크 유형 — blocks */
  linkTypeBlocks: '막음',
  /** 링크 유형 — relates */
  linkTypeRelates: '관련',
  /** 링크 유형 — duplicates */
  linkTypeDuplicates: '중복',
  /** 링크 유형 — clones */
  linkTypeClones: '복제',

  // ── 추가 폼 ──────────────────────────────────────────────────────────
  /** 링크 유형 select aria-label */
  linkTypeSelectLabel: '링크 유형',
  /** 대상 이슈 키 input placeholder */
  targetKeyPlaceholder: '이슈 키 (예: ATLAS-1)',
  /** 대상 이슈 키 input aria-label */
  targetKeyLabel: '대상 이슈 키',
  /** 링크 추가 버튼 텍스트 */
  addLinkButton: '링크 추가',

  // ── 부모 지정 폼 ─────────────────────────────────────────────────────
  /** 부모 이슈 키 input placeholder */
  parentKeyPlaceholder: '부모 이슈 키 (예: ATLAS-1)',
  /** 부모 이슈 키 input aria-label */
  parentKeyLabel: '부모 이슈 키',
  /** 부모 지정 버튼 텍스트 */
  setParentButton: '부모 지정',
  /** 부모 해제 버튼 텍스트 */
  clearParentButton: '해제',

  // ── 빈 상태 ─────────────────────────────────────────────────────────
  /** 링크가 없을 때 빈 상태 메시지 */
  emptyState: '링크가 없습니다.',

  // ── 행 액션 ─────────────────────────────────────────────────────────
  /** 링크 행 제거 버튼 aria-label */
  removeLinkButton: '링크 제거',

  // ── 로딩 ─────────────────────────────────────────────────────────────
  /** 링크 목록 로딩 중 표시 */
  loadingState: '로딩 중...',

  // ── 에러 메시지 (인라인, 토스트 아님) ───────────────────────────────
  /** LINK_SELF_REFERENCE — 자기 자신에게 링크할 수 없음 */
  errorLinkSelfReference: '자기 자신에게 링크할 수 없습니다.',
  /** DUPLICATE_LINK — 이미 동일한 링크가 존재함 */
  errorDuplicateLink: '이미 동일한 링크가 존재합니다.',
  /** ISSUE_NOT_FOUND — 대상 이슈를 찾을 수 없음 */
  errorIssueNotFound: '이슈를 찾을 수 없습니다.',
  /** LINK_CYCLE — 링크 순환 참조 */
  errorLinkCycle: '순환 링크는 허용되지 않습니다.',
  /** LINK_NOT_FOUND — 링크를 찾을 수 없음 */
  errorLinkNotFound: '링크를 찾을 수 없습니다.',
  /** PARENT_SELF_REFERENCE — 자기 자신을 부모로 설정할 수 없음 */
  errorParentSelfReference: '자기 자신을 부모로 설정할 수 없습니다.',
  /** PARENT_CYCLE — 부모 설정 순환 참조 */
  errorParentCycle: '순환 계층 구조는 허용되지 않습니다.',
  /** 기타 알 수 없는 에러 fallback */
  errorDefault: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 성공 토스트 ──────────────────────────────────────────────────────
  /** 링크 추가 성공 토스트 */
  addLinkSuccess: '링크가 추가되었습니다.',
  /** 링크 제거 성공 토스트 */
  removeLinkSuccess: '링크가 제거되었습니다.',
  /** 부모 지정 성공 토스트 */
  setParentSuccess: '부모 이슈가 지정되었습니다.',
  /** 부모 해제 성공 토스트 */
  clearParentSuccess: '부모 이슈가 해제되었습니다.',

  // ── 소속 에픽 섹션 (EpicSection) — FR-EP-01 D6 ──────────────────────
  /** 소속 에픽 섹션 제목 */
  epicSectionTitle: '소속 에픽',
  /** 에픽 키 input placeholder */
  epicKeyPlaceholder: '에픽 이슈 키 (예: ATLAS-1)',
  /** 에픽 키 input aria-label */
  epicKeyLabel: '에픽 이슈 키',
  /** 에픽 지정 버튼 텍스트 */
  setEpicButton: '에픽 지정',
  /** 에픽 해제 버튼 텍스트 */
  clearEpicButton: '해제',
  /** 에픽 지정 성공 토스트 */
  setEpicSuccess: '소속 에픽이 지정되었습니다.',
  /** 에픽 해제 성공 토스트 */
  clearEpicSuccess: '소속 에픽이 해제되었습니다.',
} as const

/** 링크 그래프 패널(IssueLinkGraph) 관련 문자열 — FR-LK-02 D6 */
export const linkGraphStrings = {
  // ── 섹션 제목 ─────────────────────────────────────────────────────────
  /** 그래프 섹션 제목 */
  sectionTitle: '링크 그래프',

  // ── 패널 토글 ─────────────────────────────────────────────────────────
  /** 패널 펼치기 버튼 레이블 */
  expandLabel: '그래프 펼치기',
  /** 패널 접기 버튼 레이블 */
  collapseLabel: '그래프 접기',

  // ── 깊이 선택 ─────────────────────────────────────────────────────────
  /** 깊이 셀렉터 레이블 */
  depthLabel: '깊이',
  /** 깊이 1단계 옵션 */
  depthOption1: '1단계',
  /** 깊이 2단계 옵션 */
  depthOption2: '2단계',
  /** 깊이 3단계 옵션 */
  depthOption3: '3단계',

  // ── 상태 메시지 ───────────────────────────────────────────────────────
  /** 그래프 노드가 없을 때 빈 상태 메시지 */
  emptyState: '연결된 이슈가 없습니다.',
  /** 노드 수가 상한(100개)을 초과해 그래프가 잘렸을 때 안내 메시지 */
  truncatedNotice: '노드가 너무 많아 일부만 표시됩니다.',
  /** 그래프 로딩 중 표시 문구 */
  loadingState: '그래프를 불러오는 중입니다.',
  /** 그래프 렌더링 오류 메시지 */
  renderError: '그래프를 렌더링하지 못했습니다.',
  /** 데이터 로드 실패 메시지 */
  loadError: '그래프 데이터를 불러오지 못했습니다.',
  /** 기준 이슈를 찾을 수 없을 때 메시지 */
  notFound: '이슈를 찾을 수 없습니다.',

  // ── 엣지 유형 표시명 (백엔드 대문자 enum → 한국어) ──────────────────
  /** 엣지 유형 — BLOCKS (issueLinkStrings.linkTypeBlocks와 통일) */
  edgeBlocks: '막음',
  /** 엣지 유형 — RELATES (issueLinkStrings.linkTypeRelates와 통일) */
  edgeRelates: '관련',
  /** 엣지 유형 — DUPLICATES */
  edgeDuplicates: '중복',
  /** 엣지 유형 — CLONES */
  edgeClones: '복제',
  /** 엣지 유형 — PARENT (부모 이슈 컨셉과 통일) */
  edgeParent: '부모',

  // ── 접근성 ───────────────────────────────────────────────────────────
  /** 그래프 노드 aria-label (이슈 키를 포함하는 함수형) */
  nodeAriaLabel: (issueKey: string) => `이슈 ${issueKey} 노드`,
} as const

/** 이슈 생성 폼 관련 문자열 */
export const issueCreateStrings = {
  /** 폼 레이블 */
  projectKeyLabel: '프로젝트',
  summaryLabel: '제목',
  submitButton: '이슈 생성',
  /** 제출 진행 중 버튼 문구 — 모달·라우트 두 경로가 같은 피드백을 준다 (게이트 2 C-2) */
  submitButtonPending: '이슈 생성 중…',

  /** Zod 검증 에러 메시지 */
  projectKeyRequired: '프로젝트를 선택하세요.',
  summaryRequired: '제목을 입력하세요.',
  // 백엔드 CreateIssueRequest.summary 가 @Size(max = 200) 다. 프론트가 500 을 허용하던
  // 선재 결함(201~500자가 프론트 통과 후 400)을 FR-UX-09 F2 에서 200 으로 정렬했다.
  summaryTooLong: '제목은 200자 이하로 입력하세요.',

  /** 백엔드 에러 코드 → 사용자 메시지 */
  errorProjectNotFound: '존재하지 않는 프로젝트입니다.',
  errorDefault: '이슈 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── FR-UX-09 F2 — 생성 모달 신규 필드 ────────────────────────────────
  /** 프로젝트 셀렉터 미선택 placeholder */
  projectPlaceholder: '프로젝트 선택',
  /** 본문 필드 레이블 */
  descriptionLabel: '설명',
  /** 본문 입력 placeholder */
  descriptionPlaceholder: '이슈 내용을 입력하세요',
  /** 본문 비움 시 서버가 템플릿으로 채운다는 안내 (FR-TM-01) */
  descriptionTemplateHint: '비워두면 프로젝트 템플릿이 채워집니다.',
  /** 담당자 미지정 시 자동 배정된다는 안내 (ADR 2026-07-31 D-2 3-state) */
  assigneeAutoHint: '비워두면 자동으로 배정됩니다.',
  /** 필드 그룹 소제목 — 기본 */
  groupBasicLabel: '기본',
  /** 필드 그룹 소제목 — 배정 */
  groupAssignmentLabel: '배정',
  /** 필드 그룹 소제목 — 추가 */
  groupExtraLabel: '추가',
  /** 접근 가능한 프로젝트가 0개일 때 빈 상태 제목 */
  noProjectsTitle: '참여 중인 프로젝트가 없습니다',
  /** 빈 상태 설명 */
  noProjectsDescription: '이슈를 만들려면 먼저 프로젝트에 참여해야 합니다.',
  /** 빈 상태에서 프로젝트 생성 권한이 있을 때 노출하는 CTA */
  createProjectCta: '프로젝트 만들기',
  /** 담당자로 지정한 사용자가 존재하지 않을 때 (422 ASSIGNEE_NOT_FOUND) */
  errorAssigneeNotFound: '지정한 담당자를 찾을 수 없습니다.',
  /** 생성 모달 제목 — role="dialog" 의 고유 접근성 이름이 된다 (NFR-1) */
  dialogTitle: '새 이슈 만들기',
  /** 생성 모달 취소 버튼 */
  cancelButton: '취소',
  /** 생성 성공 토스트 — {key} 를 이슈 키로 치환한다 (FR-16) */
  createdToast: '이슈를 만들었습니다',
  /** 생성 성공 토스트의 보기 액션 */
  createdToastAction: '보기',
} as const

/** 이슈 이동 마법사 Dialog 문자열 — FR-MV-01 D6 */
export const issueMoveStrings = {
  // ── Dialog 공통 ──────────────────────────────────────────────────────
  /** Dialog 제목 */
  dialogTitle: '이슈 이동',
  /** 취소 버튼 */
  cancelButton: '취소',
  /** 뒤로 버튼 */
  backButton: '뒤로',
  /** 다음 버튼 (Step 1 → Step 2) */
  nextButton: '다음',
  /** 이동 실행 버튼 */
  moveButton: '이동',
  /** preview 로딩 중 메시지 */
  previewLoading: '대상 프로젝트 분석 중',

  // ── Step 1 — 대상 프로젝트 선택 ──────────────────────────────────────
  /** Step 1 섹션 제목 */
  step1Title: '대상 프로젝트 선택',
  /** 대상 프로젝트 키 입력 레이블 */
  targetProjectKeyLabel: '대상 프로젝트 키',
  /** 대상 프로젝트 키 입력 placeholder */
  targetProjectKeyPlaceholder: '예: INFRA',

  // ── Step 2 — 매핑 확인 ───────────────────────────────────────────────
  /** Step 2 섹션 제목 */
  step2Title: '이동 매핑 확인',
  /** 루트 이슈 섹션 헤더 */
  rootIssueSectionHeader: '루트 이슈',
  /** 서브태스크 섹션 헤더 */
  subtaskSectionHeader: '서브태스크',
  /** 상태 선택 레이블 */
  targetStateLabel: '대상 상태 선택',
  /** 워크플로우 상태 호환 안내 */
  workflowIncompatible: '현재 상태가 대상 프로젝트에 없습니다. 대상 상태를 선택하세요.',
  /** 미매핑 제거 옵션 레이블 */
  mappingRemoveOption: '(제거)',
  /** 컴포넌트 매핑 섹션 제목 */
  componentMappingTitle: '컴포넌트 매핑',
  /** affects 버전 매핑 섹션 제목 */
  affectsVersionMappingTitle: '영향 버전 매핑',
  /** fix 버전 매핑 섹션 제목 */
  fixVersionMappingTitle: '수정 버전 매핑',
  /** 제거될 커스텀 필드 안내 */
  removedFieldsLabel: '제거될 커스텀 필드',
  /** 필수 커스텀 필드 입력 안내 */
  requiredMissingLabel: '필수 항목 입력',

  // ── 성공/오류 메시지 ──────────────────────────────────────────────────
  /** 이동 성공 토스트 */
  moveSuccessToast: '이슈가 이동되었습니다.',
  /** 서브태스크 포함 이동 성공 토스트 */
  moveSuccessWithSubtasksToast: (count: number) => `${count}개 이슈가 이동되었습니다.`,
  /** 403 권한 없음 에러 */
  errorForbidden: '이슈를 이동할 권한이 없습니다.',
  /** 404 대상 프로젝트 없음 에러 */
  errorProjectNotFound: '대상 프로젝트를 찾을 수 없습니다.',
  /** 409 OCC 충돌 에러 */
  errorVersionConflict: '다른 변경이 발생했습니다. 새로고침 후 다시 시도해 주세요.',
  /** 422 비호환 상태 미선택 에러 */
  errorInvalidTargetState: '비호환 상태를 선택해 주세요.',
  /** 422 다단계 서브태스크 에러 */
  errorSubtaskHasOwnSubtasks: '다단계 서브태스크는 이동할 수 없습니다.',
  /** 422 같은 프로젝트 에러 */
  errorSameProject: '현재 프로젝트와 같은 프로젝트로는 이동할 수 없습니다.',
  /** 기본 에러 */
  errorDefault: '이슈 이동 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** preview 실패 에러 */
  errorPreview: '이슈 이동 정보를 불러오지 못했습니다. 대상 프로젝트 키를 확인해 주세요.',
} as const

/** 워크로그 및 시간 추정 패널 문자열 — FR-TT-01 D6 */
export const worklogStrings = {
  // ── 추정(Estimate) 카드 ────────────────────────────────────────────────
  /** 추정 카드 섹션 제목 */
  estimateSectionTitle: '시간 추적',
  /** 원 추정 필드 레이블 */
  originalEstimateLabel: '원 추정',
  /** 기록 시간 필드 레이블 (읽기 전용) */
  timeSpentLabel: '기록 시간',
  /** 잔여 추정 필드 레이블 */
  remainingEstimateLabel: '잔여 추정',
  /** 추정 미설정 시 안내 문구 (E1) */
  estimateNotSet: '추정이 설정되지 않았습니다.',
  /** 추정 저장 버튼 텍스트 */
  estimateSaveButton: '저장',
  /** 추정 저장 버튼 aria-label */
  estimateSaveAriaLabel: '시간 추정 저장',
  /** 추정 저장 실패 토스트 메시지 */
  estimateSaveError: '시간 추정 저장 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 시간 입력 필드 레이블 */
  hoursLabel: '시간',
  /** 분 입력 필드 레이블 */
  minutesLabel: '분',

  // ── 워크로그 섹션 ──────────────────────────────────────────────────────
  /** 워크로그 섹션 제목 */
  worklogSectionTitle: '작업 기록',
  /** 워크로그 항목이 없을 때 빈 상태 메시지 (E7) */
  worklogEmptyState: '기록된 작업이 없습니다.',
  /** 워크로그 추가 폼 — 작업 시간(시간) 레이블 */
  worklogTimeHoursLabel: '시간',
  /** 워크로그 추가 폼 — 작업 시간(분) 레이블 */
  worklogTimeMinutesLabel: '분',
  /** 워크로그 추가 폼 — 시작 시각 레이블 */
  worklogStartedAtLabel: '시작 시각',
  /** 워크로그 추가 폼 — 코멘트 레이블 */
  worklogCommentLabel: '코멘트',
  /** 워크로그 추가 폼 — "잔여 직접 지정" 체크박스 레이블 */
  worklogAdjustRemainingLabel: '잔여 추정 직접 지정',
  /** 워크로그 추가 폼 — 자동 조정 미리보기 텍스트 */
  worklogAutoAdjustPreview: '기록 후 잔여 추정이 자동 조정됩니다.',
  /** 워크로그 추가 버튼 텍스트 */
  worklogAddButton: '추가',
  /** 워크로그 추가 버튼 aria-label */
  worklogAddAriaLabel: '작업 기록 추가',
  /** 워크로그 수정 버튼 텍스트 */
  worklogEditButton: '수정',
  /** 워크로그 수정 버튼 aria-label */
  worklogEditAriaLabel: '작업 기록 수정',
  /** 워크로그 삭제 버튼 텍스트 */
  worklogDeleteButton: '삭제',
  /** 워크로그 삭제 버튼 aria-label */
  worklogDeleteAriaLabel: '작업 기록 삭제',
  /** 워크로그 저장 버튼 텍스트 */
  worklogSaveButton: '저장',
  /** 워크로그 항목 — 작성자 레이블 */
  worklogAuthorLabel: '작성자',
  /** 워크로그 목록 로딩 중 표시 */
  worklogLoading: '작업 기록 불러오는 중',
  /** 워크로그 목록 로드 실패 토스트 메시지 */
  worklogLoadError: '작업 기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  /** 워크로그 추가 성공 토스트 메시지 */
  worklogAddSuccess: '작업 기록이 추가되었습니다.',
  /** 워크로그 추가 실패 토스트 메시지 */
  worklogAddError: '작업 기록 추가 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 워크로그 수정 성공 토스트 메시지 */
  worklogEditSuccess: '작업 기록이 수정되었습니다.',
  /** 워크로그 수정 실패 토스트 메시지 */
  worklogEditError: '작업 기록 수정 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 워크로그 삭제 성공 토스트 메시지 */
  worklogDeleteSuccess: '작업 기록이 삭제되었습니다.',
  /** 워크로그 삭제 실패 토스트 메시지 */
  worklogDeleteError: '작업 기록 삭제 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
} as const

/**
 * 댓글 섹션 UI 문자열 (FR-CO-01).
 *
 * 빈 상태(`commentEmptyState`) · 로드 실패(`commentLoadError`) · 권한 없음(`commentNoPermission`)
 * 은 **서로 다른 문구여야 한다.** 셋 다 "댓글이 안 보인다" 는 같은 증상으로 나타나므로
 * 문구가 겹치면 사용자가 원인을 구분할 수 없다 (`ko.test.ts` 가 이 구분을 단정한다).
 */
export const commentStrings = {
  /** 댓글 섹션 제목 (aria-label 겸용) */
  commentSectionTitle: '댓글',
  /** 본문 입력 레이블 */
  commentBodyLabel: '댓글 입력',
  /** 본문 입력 placeholder */
  commentBodyPlaceholder: '댓글을 입력하세요. Markdown 을 쓸 수 있습니다.',
  /** 작성 버튼 텍스트 */
  commentAddButton: '댓글 작성',
  /** 작성 중(제출 진행) 버튼 텍스트 */
  commentAddPending: '작성 중...',
  /** 작성 성공 토스트 */
  commentAddSuccess: '댓글을 작성했습니다.',
  /** 작성 실패 토스트 */
  commentAddError: '댓글 작성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 댓글 0건 — 정상이지만 비어 있는 상태 */
  commentEmptyState: '아직 댓글이 없습니다.',
  /** 목록 조회 중 */
  commentLoading: '댓글을 불러오는 중...',
  /** 목록 조회 실패 (403 포함) — 빈 상태와 반드시 구분 */
  commentLoadError: '댓글을 불러올 수 없습니다. 이 이슈의 댓글을 볼 권한이 없을 수 있습니다.',
  /** 쓰기 권한 없음 — 폼 대신 표시 */
  commentNoPermission: '이 이슈에 댓글을 작성할 권한이 없습니다.',

  // ── FR-CO-02 수정·삭제 ────────────────────────────────────────────────
  /** 댓글 행의 수정 버튼 (작성자 본인에게만 노출) */
  commentEditButton: '수정',
  /** 댓글 행의 삭제 버튼 (작성자 본인 또는 SOFT_DELETE 보유자에게 노출) */
  commentDeleteButton: '삭제',
  /** 인라인 편집 textarea 레이블 */
  commentEditBodyLabel: '댓글 수정 입력',
  /** 인라인 편집 저장 버튼 */
  commentEditSaveButton: '저장',
  /** 인라인 편집 취소 버튼 */
  commentEditCancelButton: '취소',
  /** 저장 진행 중 버튼 텍스트 */
  commentEditPending: '저장 중...',
  /** 수정 성공 토스트 */
  commentEditSuccess: '댓글을 수정했습니다.',
  /** 수정 실패 토스트 */
  commentEditError: '댓글 수정 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  /** 수정 이력 표시 — updatedAt 이 createdAt 과 다를 때만 노출 */
  commentEditedBadge: '(수정됨)',
  /** 삭제 확인 다이얼로그 제목 */
  commentDeleteDialogTitle: '댓글을 삭제할까요?',
  /** 삭제 확인 다이얼로그 본문 — 되돌릴 수 없음을 알린다 */
  commentDeleteDialogBody: '삭제한 댓글은 목록에서 사라지며 되돌릴 수 없습니다.',
  /** 삭제 확인 다이얼로그의 확인 버튼 */
  commentDeleteDialogConfirm: '삭제',
  /** 삭제 확인 다이얼로그의 취소 버튼 */
  commentDeleteDialogCancel: '취소',
  /** 삭제 성공 토스트 */
  commentDeleteSuccess: '댓글을 삭제했습니다.',
  /** 삭제 실패 토스트 */
  commentDeleteError: '댓글 삭제 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
} as const


/** 에픽 자식 이슈 목록 섹션 문자열 — FR-EP-01 D6 */
export const epicChildrenStrings = {
  // ── 섹션 제목 ─────────────────────────────────────────────────────────
  /** 섹션 제목 */
  sectionTitle: '자식 이슈',

  // ── 빈 상태 ─────────────────────────────────────────────────────────
  /** 자식 이슈가 없을 때 빈 상태 메시지 */
  emptyState: '연결된 자식 이슈가 없습니다.',

  // ── 로딩 ─────────────────────────────────────────────────────────────
  /** 목록 로딩 중 표시 */
  loadingState: '자식 이슈를 불러오는 중입니다.',

  // ── 추가 폼 ──────────────────────────────────────────────────────────
  /** 자식 이슈 키 입력 필드 aria-label */
  childKeyLabel: '자식 이슈 키',
  /** 자식 이슈 키 입력 필드 placeholder */
  childKeyPlaceholder: '이슈 키 (예: ATLAS-2)',
  /** 자식 추가 버튼 텍스트 */
  addChildButton: '추가',
  /** 자식 추가 성공 토스트 */
  addChildSuccess: '자식 이슈가 연결되었습니다.',

  // ── 행 액션 ─────────────────────────────────────────────────────────
  /** 자식 이슈 해제 버튼 aria-label */
  disconnectButton: '연결 해제',
  /** 자식 이슈 해제 성공 토스트 */
  disconnectSuccess: '자식 이슈 연결이 해제되었습니다.',

  // ── 에러 메시지 (인라인, 토스트 아님) ────────────────────────────────
  /** ISSUE_EPIC_CHILD_ALREADY_LINKED — 이미 연결된 이슈 */
  errorAlreadyLinked: '이미 이 에픽에 연결된 이슈입니다.',
  /** ISSUE_EPIC_CHILD_INVALID_TYPE — 에픽 타입은 자식이 될 수 없음 */
  errorInvalidType: '에픽 타입 이슈는 자식이 될 수 없습니다.',
  /** ISSUE_EPIC_CHILD_CROSS_PROJECT — 다른 프로젝트 이슈는 연결 불가 */
  errorCrossProject: '같은 프로젝트의 이슈만 자식으로 연결할 수 있습니다.',
  /** ISSUE_EPIC_CHILD_SELF_REFERENCE — 자기 자신을 자식으로 연결할 수 없음 */
  errorSelfReference: '자기 자신을 자식으로 연결할 수 없습니다.',
  /** ISSUE_EPIC_OR_CHILD_NOT_FOUND — 이슈를 찾을 수 없음 */
  errorNotFound: '이슈를 찾을 수 없습니다.',
  /** ISSUE_EPIC_VALIDATION_FAILED / 400 */
  errorValidation: '입력 값이 올바르지 않습니다. 확인 후 다시 시도해 주세요.',
  /** 기타 알 수 없는 에러 fallback */
  errorDefault: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
} as const

/** 에픽 진행률 막대 컴포넌트 문자열 — FR-EP-02 Task-5 */
export const epicProgressStrings = {
  // ── 섹션 레이블 ───────────────────────────────────────────────────────
  /** 진행률 섹션 제목 */
  sectionTitle: '진행률',
  /** 자식 이슈가 없을 때 빈 상태 메시지 */
  noChildrenState: '자식 이슈 없음',
  /** 완료 비율 텍스트 접미사 (aria-label용) */
  progressAriaLabel: (pct: number) => `진행률 ${pct}%`,

  // ── 카운트 텍스트 ──────────────────────────────────────────────────────
  /** done/total 카운트 표시 (예: "4 / 10 완료") */
  countLabel: (done: number, total: number) => `${done} / ${total} 완료`,

  // ── 범례 레이블 ───────────────────────────────────────────────────────
  /** 완료 구간 레이블 */
  doneLabel: '완료',
  /** 진행 중 구간 레이블 */
  inProgressLabel: '진행 중',
  /** 미시작 구간 레이블 */
  todoLabel: '미시작',
} as const

/** 사용자 알림 구독 설정 페이지 문자열 — /settings/notifications (FR-NT-04) */
export const notificationSubscriptionStrings = {
  /** 페이지 제목 */
  pageTitle: '알림 구독 설정',
  /** 페이지 설명 */
  pageDescription: '이벤트별로 원하는 채널을 선택해 알림을 받으세요.',
  /** 로딩 중 안내 */
  loading: '설정을 불러오는 중입니다.',
  /** 에러 안내 */
  errorGeneric: '알림 구독 설정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  /** 이벤트 유형 열 헤더 */
  columnEvent: '이벤트',
  /** 이슈 멘션 이벤트 라벨 — notification-policy-labels.ts 미포함 신규 라벨 */
  eventIssueMentioned: '이슈 멘션',
  /** 토글 활성 상태 레이블 (시각적 보조) */
  toggleEnabled: '알림 켜짐',
  /** 토글 비활성 상태 레이블 (시각적 보조) */
  toggleDisabled: '알림 꺼짐',
} as const

/** 단축키 커스터마이즈 설정 페이지 문자열 — /settings/keymap (FR-PF-03 Task 9) */
export const keymapSettingsStrings = {
  /** 페이지 제목 */
  pageTitle: '단축키 설정',
  /** 페이지 설명 */
  pageDescription: '자주 쓰는 동작에 원하는 키를 배정하세요. 변경 후에는 저장을 눌러야 적용됩니다.',
  /** 로딩 중 안내 */
  loadingMessage: '단축키 설정을 불러오는 중입니다.',
  /** 조회 실패 안내 */
  loadErrorMessage: '단축키 설정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  /** action id → 한국어 표시명 — 백엔드 KeymapAction.displayName 미러 (값을 바꿀 때 함께 갱신) */
  actionLabels: {
    help: '단축키 도움말',
    'create-issue': '새 이슈 생성',
    search: '검색으로 이동',
    'goto-my-issues': '내 이슈로 이동',
    'goto-dashboard': '대시보드로 이동',
  },
  /** 키 캡처 input aria-label — action 표시명 포함(중복 텍스트 input E2E 견고성) */
  captureInputAriaLabel: (actionLabel: string) => `${actionLabel} 단축키 입력`,
  /** 키 캡처 input placeholder */
  captureInputPlaceholder: '키 입력',
  /** 기본값 복원 버튼 레이블 */
  resetButtonLabel: '기본값 복원',
  /** 기본값 복원 버튼 aria-label — action 표시명 포함(중복 텍스트 버튼 E2E 견고성) */
  resetButtonAriaLabel: (actionLabel: string) => `${actionLabel} 기본값 복원`,
  /** 저장 버튼 레이블 */
  saveButtonLabel: '저장',
  /** 저장 버튼 aria-label */
  saveButtonAriaLabel: '단축키 설정 저장',
  /** 저장 실패(충돌 아닌 일반 에러) 메시지 */
  saveErrorMessage: '단축키 설정을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  /** 서버 409 충돌 배너 제목 */
  conflictHeading: '겹치는 단축키가 있습니다.',
  /** 빈값 위반 메시지 */
  conflictBlank: (actionNames: string) => `${actionNames}의 단축키가 비어 있습니다.`,
  /** key_combo 형식 위반 메시지 */
  conflictFormat: (actionNames: string) => `${actionNames}의 단축키 형식이 올바르지 않습니다. (예: c 또는 g i)`,
  /** 완전중복 위반 메시지 */
  conflictDuplicate: (keyCombo: string, actionNames: string) =>
    `"${keyCombo}"가 ${actionNames}에 중복 배정되었습니다.`,
  /** leader 접두 충돌 메시지 — single "g"와 leader(g X)가 공존 */
  conflictLeaderPrefix: (actionNames: string) =>
    `단일 키 "g"와 리더 시퀀스가 함께 배정될 수 없습니다. (${actionNames})`,
  /** dead leader(연속키가 leader 키와 동일, 예: "g g") 충돌 메시지 */
  conflictDeadLeader: (actionNames: string) => `"g g"는 사용할 수 없습니다. (${actionNames})`,
} as const
