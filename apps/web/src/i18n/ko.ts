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
  } as Record<string, string>,
  /** 변경 이력 — 생명주기 "created" 표시 문자열 */
  changelogLifecycleCreated: '이슈를 생성했습니다',
  /** 변경 이력 — 생명주기 "deleted" 표시 문자열 */
  changelogLifecycleDeleted: '이슈를 삭제했습니다',
  /** 변경 이력 — 값 없음 표시 */
  changelogValueNone: '(없음)',
  /** 변경 이력 — 삭제된 엔티티 폴백 표시 */
  changelogDeletedEntity: '(삭제됨)',
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
