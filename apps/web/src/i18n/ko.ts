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
