// 관리자 인증 감사 로그 조회 API 클라이언트 + Zod 스키마 (GET, CSRF 불요)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드 AuthEventType enum 미러 — 백엔드 AuthEventType.kt와 동기화
// enum 추가 시 이 배열 + audit-log-labels.ts authEventTypeLabels도 함께 갱신할 것
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백엔드 AuthEventType enum의 프론트 미러.
 * 필터 드롭다운 + 라벨 키 출처로 사용한다.
 * eventType 응답 필드는 전방호환을 위해 z.string()으로 받는다 (아래 auditLogEntrySchema 참조).
 */
export const AUTH_EVENT_TYPES = [
  'LOGIN_SUCCESS',
  'LOGIN_FAILURE',
  'LOGOUT',
  'LOGOUT_ALL_DEVICES',
  'TOKEN_REFRESHED',
  'SUSPICIOUS_REFRESH_REPLAY',
  'USER_PROVISIONED',
  'PAT_USED',
  'LDAP_UNAVAILABLE',
  'PROJECT_MEMBER_ADDED',
  'PROJECT_ROLE_CHANGED',
  'PROJECT_MEMBER_REMOVED',
] as const

/** 알려진 이벤트 타입 유니온 */
export type AuthEventType = (typeof AUTH_EVENT_TYPES)[number]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 AuthAuditLogEntryResponse DTO 1:1 정합
// @JsonInclude(NON_NULL) 적용 — null 필드는 JSON에서 키 자체가 생략됨
// → userId/username/displayName/ipAddress/userAgent 반드시 .nullish() (optional + nullable)
//   .nullable()만 쓰면 키 부재 시 파싱 실패 (FR-AU-08 D6 겪은 함정)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 감사 로그 단건 응답 Zod 스키마.
 *
 * - eventType은 z.string() — 백엔드 enum 추가 시 파싱 무파손 (전방호환).
 *   라벨 매핑은 authEventTypeLabels에서 담당하며 미지 값은 원문 표시.
 * - userId/username/displayName/ipAddress/userAgent는 .nullish() 필수.
 *   @JsonInclude(NON_NULL)로 null이면 키 자체가 응답에 없으므로 optional도 포함해야 함.
 */
export const auditLogEntrySchema = z.object({
  /** 감사 로그 ID (Long → number) */
  id: z.number(),
  /** 행위 주체 UUID — 미상(LOGIN_FAILURE 등) 또는 삭제 사용자 시 null/키 없음 */
  userId: z.string().uuid().nullish(),
  /** 행위 주체 username — LEFT JOIN users, 미존재 시 null/키 없음 */
  username: z.string().nullish(),
  /** 행위 주체 표시 이름 — LEFT JOIN users, 미존재 시 null/키 없음 */
  displayName: z.string().nullish(),
  /**
   * 이벤트 유형 — z.string() 전방호환.
   * 백엔드 AuthEventType enum 12종이 기본값이나 미래 추가에 대비해 string으로 수신.
   */
  eventType: z.string(),
  /** 인증 제공자 식별자 (예: "local", "ldap") — 항상 존재 */
  providerId: z.string(),
  /** 접속 IP 주소 — 헤더 부재 시 null/키 없음 */
  ipAddress: z.string().nullish(),
  /** User-Agent 문자열 — 헤더 부재 시 null/키 없음 */
  userAgent: z.string().nullish(),
  /** 이벤트 부가 정보 — 항상 존재 (빈 맵 {} 포함) */
  metadata: z.record(z.string(), z.string()),
  /** 이벤트 발생 시각 (ISO 8601) — 항상 존재 */
  createdAt: z.string(),
})

/** 감사 로그 단건 타입 — z.infer 자동 추론 */
export type AuditLogEntry = z.infer<typeof auditLogEntrySchema>

/**
 * 감사 로그 페이지 응답 Zod 스키마.
 * 백엔드 AuthAuditLogPageResponse DTO 1:1 정합.
 */
export const auditLogPageSchema = z.object({
  items: z.array(auditLogEntrySchema),
  /** 현재 페이지 번호 (0-base) */
  page: z.number(),
  /** 페이지 크기 */
  size: z.number(),
  /** 전체 항목 수 */
  totalElements: z.number(),
  /** 전체 페이지 수 */
  totalPages: z.number(),
})

/** 감사 로그 페이지 응답 타입 */
export type AuditLogPage = z.infer<typeof auditLogPageSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 파라미터 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 감사 로그 조회 쿼리 파라미터.
 * 전부 선택(optional). undefined이면 URLSearchParams에서 생략된다 (fetchUsers 선례).
 */
export interface AuditLogQueryParams {
  /** 이벤트 유형 필터 — AuthEventType enum name */
  eventType?: string
  /** 행위 주체 UUID 필터 */
  userId?: string
  /**
   * 조회 시작 시각 (ISO Instant, 끝에 Z).
   * 경계 포함 (created_at >= from).
   * 프론트: 선택 날짜 00:00:00.000Z 전달.
   */
  from?: string
  /**
   * 조회 종료 시각 (ISO Instant, 끝에 Z).
   * 경계 포함 (created_at <= to).
   * 프론트: 선택 날짜 23:59:59.999Z 전달.
   */
  to?: string
  /** 페이지 번호 (0-base, 기본 0) */
  page?: number
  /** 페이지 크기 (1..100, 기본 50) */
  size?: number
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 관리자 인증 감사 로그를 조회한다. SYSTEM_ADMIN 전용.
 *
 * `GET /api/v1/admin/auth-audit-logs` — CSRF 불요 (GET, Spring Security GET 제외).
 *
 * @param params 필터 + 페이지네이션 파라미터 (전부 선택)
 * @returns 감사 로그 페이지 응답 (items + 페이지 메타)
 * @throws ApiError(403) 비관리자 또는 PAT 인증
 * @throws ApiError(401) 미인증
 * @throws ApiError(400) 잘못된 파라미터 (eventType 미정의·from/to 파싱 실패·page<0·size 범위 밖)
 */
export async function fetchAuditLogs(params: AuditLogQueryParams): Promise<AuditLogPage> {
  const searchParams = new URLSearchParams()

  // undefined는 생략 (fetchUsers 선례)
  if (params.eventType !== undefined) searchParams.set('eventType', params.eventType)
  if (params.userId !== undefined) searchParams.set('userId', params.userId)
  if (params.from !== undefined) searchParams.set('from', params.from)
  if (params.to !== undefined) searchParams.set('to', params.to)
  if (params.page !== undefined) searchParams.set('page', String(params.page))
  if (params.size !== undefined) searchParams.set('size', String(params.size))

  const queryString = searchParams.toString()
  const path = queryString !== ''
    ? `/api/v1/admin/auth-audit-logs?${queryString}`
    : '/api/v1/admin/auth-audit-logs'

  return apiGet(path, auditLogPageSchema)
}
