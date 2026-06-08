// 필드 권한 규칙 + 그룹 BC Zod 스키마 + 추론 타입 정의 — backend DTO 1:1 대응 (FR-PM-07)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend FieldPermissionResponse + GroupResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 필드 종류 enum — backend FieldKind(CORE|CUSTOM) 1:1 대응.
 */
export const fieldKindEnum = z.enum(['CORE', 'CUSTOM'])

/**
 * 접근 수준 enum — backend AccessLevel(VIEW|EDIT) 1:1 대응.
 */
export const accessLevelEnum = z.enum(['VIEW', 'EDIT'])

/**
 * 필드 권한 규칙 단건 응답 Zod 스키마.
 * backend FieldPermissionResponse DTO 1:1 대응.
 */
export const fieldPermissionResponseSchema = z.object({
  id: z.string().uuid(),
  fieldKind: fieldKindEnum,
  fieldKey: z.string(),
  groupId: z.string().uuid(),
  groupName: z.string(),
  accessLevel: accessLevelEnum,
})

/**
 * 그룹 단건 응답 Zod 스키마.
 * backend UserGroupController GroupResponse DTO 1:1 대응.
 * listGroups 응답은 배열을 직접 반환 (data 래퍼 없음).
 */
export const groupResponseSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  description: z.string().nullable(),
  memberCount: z.number().int(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 필드 종류 타입 */
export type FieldKind = z.infer<typeof fieldKindEnum>

/** 접근 수준 타입 */
export type AccessLevel = z.infer<typeof accessLevelEnum>

/** 필드 권한 규칙 단건 응답 타입 */
export type FieldPermissionResponse = z.infer<typeof fieldPermissionResponseSchema>

/** 그룹 단건 응답 타입 */
export type GroupResponse = z.infer<typeof groupResponseSchema>

/** 필드 권한 규칙 생성 입력 타입 — fieldKind·fieldKey·groupId·accessLevel 필수 */
export interface CreateFieldPermissionInput {
  fieldKind: FieldKind
  fieldKey: string
  groupId: string
  accessLevel: AccessLevel
}
