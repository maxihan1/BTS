// 프로젝트 멤버 API Zod 스키마 및 추론 타입 정의 (FR-PM-01 Task F1)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend ProjectMemberResponse DTO와 1:1 대응 (camelCase)
// 필드: projectId / userId / role / createdAt / updatedAt / displayName / username
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 멤버 역할 enum.
 * backend ProjectRole: PROJECT_ADMIN | MEMBER
 */
export const roleEnum = z.enum(['PROJECT_ADMIN', 'MEMBER'])

/**
 * 프로젝트 멤버 단건 응답 Zod 스키마 — backend ProjectMemberResponse 7 필드.
 *
 * UUID 필드(projectId, userId)는 `z.string()`을 사용한다.
 * Zod v4의 `z.string().uuid()` 정규식은 RFC 4122 variant/version 비트를 엄격히 검사하므로
 * 테스트 픽스처(예: `aaaaaaaa-bbbb-cccc-...`)가 거부된다.
 * 프로덕션 값은 PostgreSQL `gen_random_uuid()` v4 형식이어서 항상 통과한다.
 */
export const memberResponseSchema = z.object({
  /** 프로젝트 식별자 UUID */
  projectId: z.string(),
  /** 사용자 식별자 UUID */
  userId: z.string(),
  /** 멤버 역할 — PROJECT_ADMIN 또는 MEMBER */
  role: roleEnum,
  /** 멤버십 최초 생성 시각 (ISO-8601 UTC) */
  createdAt: z.string(),
  /** 마지막 역할 변경 시각 (ISO-8601 UTC) */
  updatedAt: z.string(),
  /** 사용자 표시 이름 — orphan 멤버십 또는 미설정 시 null */
  displayName: z.string().nullable(),
  /** 사용자 이름 — orphan 멤버십 시 null */
  username: z.string().nullable(),
})

/** 멤버 목록 응답 래퍼 Zod 스키마 — `{ members: [...] }` */
export const membersListSchema = z.object({
  members: z.array(memberResponseSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 멤버 단건 응답 타입 */
export type ProjectMember = z.infer<typeof memberResponseSchema>

/** 프로젝트 역할 타입 */
export type ProjectRole = z.infer<typeof roleEnum>

/** 멤버 목록 응답 래퍼 타입 */
export type ProjectMembersList = z.infer<typeof membersListSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 파싱 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 에러 응답 Zod 스키마 — backend `{ error: snake_case }` 형태 */
export const memberErrorResponseSchema = z.object({
  error: z.string().default('unknown_error'),
})

// ─────────────────────────────────────────────────────────────────────────────
// Input 인터페이스 — 뮤테이션 요청 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 멤버 추가 입력 — POST body */
export interface AddMemberInput {
  userId: string
  role: ProjectRole
}

/** 역할 변경 입력 — PATCH body */
export interface ChangeRoleInput {
  role: ProjectRole
}
