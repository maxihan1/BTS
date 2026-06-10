// 계정 연결 UI의 한국어 라벨 + errorCode → 사용자 메시지 단일 출처

/**
 * 계정 연결 UI(/settings/account-links)가 노출하는 한국어 라벨/텍스트.
 *
 * - component-labels.ts / version-labels.ts 패턴 동일 적용
 * - errorCode → 사용자 메시지 매핑은 accountLinkErrorMessage 함수로 분리
 * - 백엔드 ProblemDetail detail 필드를 직접 노출하지 않음 (단일 출처)
 * - 계정 열거 방지: 오류 메시지는 공격자가 계정 존재 여부를 추론할 수 없는 수준으로 일반화
 *
 * 그룹 — page / card / add / unlink / reauth / toast / callback
 */
export const accountLinkLabels = {
  /** 페이지 전체 영역 */
  page: {
    /** 페이지 h1 heading */
    heading: '계정 연결',
    /** 페이지 설명 문구 */
    description: '외부 계정을 연결하면 여러 방법으로 로그인할 수 있습니다.',
    /** 연결된 계정 없음 안내 텍스트 */
    emptyMessage: '연결된 외부 계정이 없습니다.',
    /** 빈 상태에서 연결 추가 CTA 버튼 텍스트 */
    addCta: '외부 계정 연결하기',
    /** 로딩 상태 aria-label */
    loadingStatus: '연결된 계정 목록 로딩 중',
  },

  /** 연결된 계정 카드 영역 */
  card: {
    /** 연결 일시 라벨 */
    linkedAtLabel: '연결 일시',
    /** 마지막 로그인 라벨 */
    lastLoginLabel: '마지막 로그인',
    /** 마지막 로그인 기록 없음 텍스트 */
    noLoginHistory: '로그인 기록 없음',
    /** 비활성 공급자 배지 텍스트 */
    inactiveBadge: '비활성',
    /** provider 식별 불가 시 fallback 텍스트 */
    unknownProvider: '알 수 없는 공급자',
    /** provider 타입 표시명 배지 */
    typeBadge: {
      LDAP: 'LDAP',
      SAML: 'SAML',
      OIDC: 'OIDC',
    },
  },

  /** 계정 추가 영역 */
  add: {
    /** 계정 추가 버튼 visible 텍스트 */
    addButton: '계정 추가',
    /** provider 선택 안내 문구 */
    providerSelectGuide: '연결할 인증 방식을 선택하세요.',
    /** 연결 가능한 공급자 없음 안내 텍스트 */
    noLinkableProviders: '현재 연결 가능한 인증 방식이 없습니다.',
    /** LDAP 연결 폼 필드 */
    ldapForm: {
      /** 사용자명 입력 필드 label */
      usernameLabel: '사용자명',
      /** 비밀번호 입력 필드 label */
      passwordLabel: '비밀번호',
      /** LDAP 연결 제출 버튼 텍스트 */
      submitButton: '연결',
    },
    /** SSO 연결 안내 문구 */
    ssoGuide: '외부 로그인으로 이동합니다.',
  },

  /** 계정 연결 해제 영역 */
  unlink: {
    /** 해제 버튼 visible 텍스트 */
    unlinkButton: '해제',
    /** 해제 확인 dialog 제목 */
    dialogTitle: '계정 연결 해제',
    /** 해제 확인 dialog 본문 */
    dialogBody: '이 계정 연결을 해제하시겠습니까?',
    /** 해제 확인 버튼 텍스트 */
    confirmButton: '해제',
    /** 해제 취소 버튼 텍스트 */
    cancelButton: '취소',
  },

  /** 재인증 모달 영역 */
  reauth: {
    /** 재인증 모달 제목 */
    modalTitle: '재인증 필요',
    /** 재인증 안내 문구 */
    modalGuide: '이 작업을 계속하려면 먼저 본인 확인이 필요합니다.',
    /** LOCAL 계정 비밀번호 입력 label */
    localPasswordLabel: '비밀번호',
    /** LDAP 계정 사용자명 입력 label */
    ldapUsernameLabel: '사용자명',
    /** LDAP 계정 비밀번호 입력 label */
    ldapPasswordLabel: '비밀번호',
    /** SSO 재인증 버튼 텍스트 */
    ssoButton: 'SSO로 재인증',
    /** 재인증 제출 버튼 텍스트 */
    submitButton: '확인',
  },

  /** 토스트 메시지 */
  toast: {
    /** 계정 연결 성공 토스트 */
    linkSuccess: '외부 계정이 연결되었습니다.',
    /** 이미 연결된 계정(멱등) 토스트 */
    linkAlreadyLinked: '이미 연결된 계정입니다.',
    /** 계정 연결 해제 성공 토스트 */
    unlinkSuccess: '계정 연결이 해제되었습니다.',
    /** 재인증 성공 토스트 */
    reauthSuccess: '재인증이 완료되었습니다.',
  },

  /** 콜백 페이지 status 메시지 */
  callback: {
    link: {
      /** 연결 성공 안내 */
      success: '외부 계정 연결이 완료되었습니다.',
      /** 이미 연결된 계정 안내 */
      alreadyLinked: '이미 연결된 계정입니다.',
      /** 다른 계정에 연결된 신원 충돌 안내 */
      conflict: '이 신원은 다른 계정에 이미 연결되어 있습니다.',
      /** 연결 오류 안내 */
      error: '계정 연결 중 오류가 발생했습니다.',
    },
    reauth: {
      /** 재인증 성공 안내 */
      success: '재인증이 완료되었습니다.',
      /** 재인증 실패 안내 */
      failed: '재인증에 실패했습니다.',
    },
  },
} as const

/** 라벨 const 추론 타입 */
export type AccountLinkLabels = typeof accountLinkLabels

/**
 * 백엔드 errorCode를 사용자 노출 메시지로 변환한다.
 * ProblemDetail detail 필드는 직접 쓰지 않으며 이 함수가 단일 출처다.
 * 계정 열거 방지를 위해 오류 메시지는 공격자가 계정 존재 여부를 추론할 수 없게 일반화한다.
 */
export function accountLinkErrorMessage(errorCode: string | null): string {
  switch (errorCode) {
    case 'account_already_linked':
      return '이미 다른 계정에 연결된 신원입니다.'
    case 'last_login_method':
      return '마지막 로그인 수단은 해제할 수 없습니다.'
    case 'provider_unavailable':
      return '인증 서버를 일시적으로 사용할 수 없습니다.'
    case 'link_authentication_failed':
      return '인증에 실패했습니다. 입력 정보를 확인해 주세요.'
    case 'reauth_failed':
      return '재인증에 실패했습니다.'
    case 'step_up_required':
      return '이 작업을 계속하려면 재인증이 필요합니다.'
    case 'reauth_fields_required':
      return '입력 정보를 모두 채워 주세요.'
    default:
      return '요청을 처리하지 못했습니다.'
  }
}
