// 알림 정책 관리자 UI의 한국어 라벨 + enum → 한국어 단일 출처 (FR-NT-01)

/**
 * 미지 enum 값에 대해 원문 키를 그대로 반환하는 전방호환 헬퍼.
 *
 * 백엔드 enum이 확장될 때 UI가 깨지지 않고 원문을 표시하도록 한다.
 * enum 추가 시 아래 Record + api/notification-policies.ts 미러도 동반 갱신할 것.
 *
 * @param map  키 → 한국어 라벨 Record
 * @param key  조회할 enum 값 (wireValue 또는 NAME)
 * @returns    매핑된 라벨, 없으면 key 원문
 */
export function labelFor(map: Record<string, string>, key: string): string {
  return map[key] ?? key
}

// ─────────────────────────────────────────────────────────────────────────────
// eventTypeLabels — wireValue 9종
// 백엔드 NotificationEventType enum 추가 시 이 객체 + NOTIFICATION_EVENT_TYPES 미러도 갱신.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 이벤트 유형 wireValue(e.g. `"issue.created"`) → 한국어 라벨.
 *
 * - 백엔드 api 계약의 wireValue 9종 1:1 정합.
 * - 미지 값은 `labelFor(eventTypeLabels, key)` 경유 시 원문 fallback.
 */
export const eventTypeLabels: Record<string, string> = {
  'issue.created': '이슈 생성',
  'issue.assigned': '이슈 담당자 지정',
  'issue.transitioned': '이슈 상태 전이',
  'issue.commented': '이슈 댓글 작성',
  'issue.mentioned': '이슈 멘션',
  'issue.due_soon': '이슈 기한 임박',
  'issue.overdue': '이슈 기한 초과',
  'sprint.started': '스프린트 시작',
  'sprint.ended': '스프린트 종료',
  'automation.failed': '자동화 규칙 실패',
}

// ─────────────────────────────────────────────────────────────────────────────
// recipientRoleLabels — enum NAME 9종
// 백엔드 RecipientRole enum 추가 시 이 객체 + RECIPIENT_ROLES 미러도 갱신.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 수신자 역할 enum NAME(e.g. `"REPORTER"`) → 한국어 라벨.
 *
 * - 백엔드 api 계약의 NAME 9종 1:1 정합.
 * - 미지 값은 `labelFor(recipientRoleLabels, key)` 경유 시 원문 fallback.
 */
export const recipientRoleLabels: Record<string, string> = {
  REPORTER: '보고자',
  ASSIGNEE: '담당자',
  PREVIOUS_ASSIGNEE: '이전 담당자',
  WATCHER: '구독자',
  COMPONENT_LEAD: '컴포넌트 담당자',
  MENTIONED: '멘션된 사용자',
  PROJECT_MEMBER: '프로젝트 멤버',
  RULE_OWNER: '규칙 소유자',
  PROJECT_ADMIN: '프로젝트 관리자',
}

// ─────────────────────────────────────────────────────────────────────────────
// recipientRoleDescriptions — enum NAME 9종 한국어 설명
// 백엔드 RecipientRole enum/지원범위 변경 시 이 객체 + recipientRoleLabels + (api)UNSUPPORTED_RECIPIENT_ROLES 동반 갱신.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 수신자 역할 enum NAME(e.g. `"REPORTER"`) → 드롭다운 인라인 설명.
 *
 * - 각 역할이 "누구에게 알림이 가는지"를 한 줄로 설명하는 단일출처.
 * - `recipientRoleLabels`와 **같은 용어** 위에서 부연 — 별도 용어 도입 금지.
 * - RULE_OWNER는 현재 미지원(automation BC 부재) → "미지원" 문구 포함 필수.
 * - 미지 값은 UI에서 `labelFor(recipientRoleDescriptions, key)` 경유 시 원문 fallback.
 */
export const recipientRoleDescriptions: Record<string, string> = {
  REPORTER: '이슈를 등록한 보고자',
  ASSIGNEE: '현재 담당자',
  PREVIOUS_ASSIGNEE: '직전 담당자 1명',
  WATCHER: '이슈를 구독(지켜보기)한 사용자',
  COMPONENT_LEAD: '이슈가 속한 컴포넌트의 담당자',
  MENTIONED: '본문·댓글에서 @로 멘션된 사용자',
  PROJECT_MEMBER: '프로젝트의 모든 멤버',
  PROJECT_ADMIN: '프로젝트 관리자',
  RULE_OWNER: '자동화 규칙 소유자 (현재 미지원)',
}

// ─────────────────────────────────────────────────────────────────────────────
// channelLabels — enum NAME 5종
// 백엔드 NotificationChannel enum 추가 시 이 객체 + CHANNELS 미러도 갱신.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 채널 enum NAME(e.g. `"EMAIL"`) → 한국어 라벨.
 *
 * - 백엔드 api 계약의 NAME 5종 1:1 정합.
 * - 미지 값은 `labelFor(channelLabels, key)` 경유 시 원문 fallback.
 */
export const channelLabels: Record<string, string> = {
  EMAIL: '이메일',
  IN_APP: '인앱 알림',
  SLACK: 'Slack',
  TEAMS: 'Teams',
  WEBHOOK: 'Webhook',
}

// ─────────────────────────────────────────────────────────────────────────────
// notificationPolicyLabels — 페이지/테이블/폼/액션/빈상태/에러 문자열
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 관리자 페이지가 노출하는 한국어 문자열.
 *
 * 그룹 — page / table / form / actions / empty / error
 */
export const notificationPolicyLabels = {
  /** 페이지 헤더 */
  page: {
    /** 페이지 제목 */
    heading: '알림 정책',
    /** 페이지 부제목 */
    description: '이벤트별 알림 정책을 관리합니다. SYSTEM_ADMIN 전용.',
  },

  /** 테이블 헤더 */
  table: {
    /** 이벤트 유형 열 */
    eventType: '이벤트 유형',
    /** 수신자 역할 열 */
    recipientRole: '수신자',
    /** 채널 열 */
    channel: '채널',
    /** 활성 여부 열 */
    enabled: '활성',
    /** 액션 열 */
    actions: '관리',
  },

  /** 폼 필드 라벨 / 추가 버튼 */
  form: {
    /** 이벤트 유형 select 라벨 */
    eventType: '이벤트 유형',
    /** 수신자 역할 select 라벨 */
    recipientRole: '수신자',
    /** 채널 select 라벨 */
    channel: '채널',
    /** 정책 추가 버튼 텍스트 */
    addButton: '정책 추가',
  },

  /** 인라인 액션 버튼 */
  actions: {
    /** 삭제 버튼 텍스트 */
    deleteButton: '삭제',
    /** 인라인 삭제 확인 버튼 텍스트 */
    confirmButton: '확인',
    /** 인라인 삭제 취소 버튼 텍스트 */
    cancelButton: '취소',
    /** 활성화 토글 버튼 텍스트 */
    toggleEnable: '활성화',
    /** 비활성화 토글 버튼 텍스트 */
    toggleDisable: '비활성화',
  },

  /** 빈 상태 */
  empty: {
    /** 정책 없음 안내 텍스트 */
    noResults: '등록된 알림 정책이 없습니다.',
  },

  /** 에러 메시지 */
  error: {
    /** 409 NOTIF_POLICY_DUPLICATE — 동일 조합 중복 생성 시 */
    duplicate: '동일한 이벤트·수신자·채널 조합의 정책이 이미 존재합니다.',
    /** 기타 서버 에러 폴백 */
    generic: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  },
} as const

/** notificationPolicyLabels const 추론 타입 */
export type NotificationPolicyLabels = typeof notificationPolicyLabels
