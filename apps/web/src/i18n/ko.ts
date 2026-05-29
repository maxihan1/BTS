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
  typeChangeConflictError: '다른 사용자가 이미 이 이슈를 수정했습니다. 새로고침 후 다시 시도해 주세요.',
  /** 타입 변경 실패 기본 에러 메시지 */
  typeChangeError: '이슈 유형 변경 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
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
