// 감사 로그 관리자 조회 UI E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점
import { AUTH_EVENT_TYPES } from '@/api/audit-logs'

/**
 * 감사 로그 관리자 조회 UI가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — page / filter / table / empty / pagination / fallback
 */
export const auditLogLabels = {
  /** 페이지 헤더 */
  page: {
    /** 페이지 제목 */
    heading: '인증 감사 로그',
    /** 페이지 부제목 */
    description: '시스템 인증 이벤트를 조회합니다. SYSTEM_ADMIN 전용.',
  },

  /** 필터 영역 */
  filter: {
    /** 이벤트 유형 필터 라벨 */
    eventType: '이벤트 유형',
    /** 사용자 필터 라벨 */
    userId: '사용자',
    /** 시작일 필터 라벨 */
    from: '시작일',
    /** 종료일 필터 라벨 */
    to: '종료일',
    /** 필터 초기화 버튼 텍스트 */
    reset: '초기화',
    /** 전체 이벤트 유형 Select 옵션 */
    allEvents: '전체 이벤트',
    /** 사용자 검색 input placeholder */
    searchPlaceholder: '이름 또는 아이디 검색...',
  },

  /** 테이블 헤더 */
  table: {
    /** 발생 시각 열 */
    createdAt: '시각',
    /** 이벤트 유형 열 */
    eventType: '이벤트',
    /** 행위 주체 열 */
    subject: '주체',
    /** 인증 제공자 열 */
    provider: '제공자',
    /** IP 주소 열 */
    ipAddress: 'IP',
    /** 메타데이터 열 */
    metadata: '상세',
  },

  /** 빈 상태 */
  empty: {
    /** 결과 없음 안내 텍스트 */
    noResults: '조건에 맞는 로그가 없습니다.',
  },

  /** 페이지네이션 */
  pagination: {
    /** 이전 페이지 버튼 텍스트 */
    previous: '이전',
    /** 다음 페이지 버튼 텍스트 */
    next: '다음',
    /**
     * "N개 중 X–Y" 표시 문자열 생성.
     * @param from 현재 페이지 첫 항목 번호 (1-based)
     * @param to 현재 페이지 마지막 항목 번호 (1-based)
     * @param total 전체 항목 수
     */
    rangeOf: (from: number, to: number, total: number): string =>
      `${total}개 중 ${from}–${to}`,
  },

  /** 폴백 표시 */
  fallback: {
    /** userId·username·displayName 모두 null일 때 표시할 주체 폴백 */
    unknownSubject: '(알 수 없음)',
    /** providerId가 없을 때 표시할 제공자 폴백 */
    unknownProvider: '(알 수 없음)',
  },
} as const

/** auditLogLabels const 추론 타입 */
export type AuditLogLabels = typeof auditLogLabels

// ─────────────────────────────────────────────────────────────────────────────
// 이벤트 유형 한국어 라벨 — AUTH_EVENT_TYPES 12종 1:1 정합
// 백엔드 AuthEventType enum이 추가되면 이 객체도 갱신할 것 (T4 테스트가 1차 가드)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AUTH_EVENT_TYPES 12종에 대한 한국어 라벨.
 *
 * - Record<(typeof AUTH_EVENT_TYPES)[number], string>으로 선언해 컴파일타임 키 누락을 방지한다.
 * - 백엔드 enum 추가 시 이 객체 + AUTH_EVENT_TYPES 배열도 함께 갱신해야 한다.
 */
export const authEventTypeLabels: Record<(typeof AUTH_EVENT_TYPES)[number], string> = {
  LOGIN_SUCCESS: '로그인 성공',
  LOGIN_FAILURE: '로그인 실패',
  LOGOUT: '로그아웃',
  LOGOUT_ALL_DEVICES: '전체 기기 로그아웃',
  TOKEN_REFRESHED: '토큰 갱신',
  SUSPICIOUS_REFRESH_REPLAY: '의심 토큰 재사용',
  USER_PROVISIONED: '사용자 프로비저닝',
  PAT_USED: 'PAT 사용',
  LDAP_UNAVAILABLE: 'LDAP 서버 장애',
  PROJECT_MEMBER_ADDED: '프로젝트 멤버 추가',
  PROJECT_ROLE_CHANGED: '프로젝트 역할 변경',
  PROJECT_MEMBER_REMOVED: '프로젝트 멤버 제거',
}
