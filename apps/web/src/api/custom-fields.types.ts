// 커스텀 필드 BC Zod 스키마 + 추론 타입 정의 — backend CustomFieldResponse DTO 1:1 대응 (FR-IS-10)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend CustomFieldResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 커스텀 필드 유형 enum — backend FieldType 10종 1:1 대응.
 */
export const fieldTypeEnum = z.enum([
  'SHORT_TEXT',
  'LONG_TEXT',
  'NUMBER',
  'DATE',
  'DATETIME',
  'SINGLE_SELECT',
  'MULTI_SELECT',
  'CHECKBOX',
  'RADIO',
  'URL',
])

/**
 * 선택지(option) 단건 응답 Zod 스키마.
 * backend CustomFieldOptionResponse(value, label, displayOrder) 1:1 대응.
 */
export const customFieldOptionSchema = z.object({
  value: z.string(),
  label: z.string(),
  displayOrder: z.number().int(),
})

/**
 * 커스텀 필드 단건 응답 Zod 스키마.
 * backend CustomFieldResponse DTO 1:1 대응.
 */
export const customFieldResponseSchema = z.object({
  id: z.string().uuid(),
  projectId: z.string().uuid(),
  key: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  fieldType: fieldTypeEnum,
  required: z.boolean(),
  displayOrder: z.number().int(),
  options: z.array(customFieldOptionSchema),
})

/** backend `{ data: T }` 응답 래퍼 Zod 스키마 헬퍼 */
export const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 커스텀 필드 유형 타입 */
export type FieldType = z.infer<typeof fieldTypeEnum>

/** 커스텀 필드 선택지 타입 */
export type CustomFieldOption = z.infer<typeof customFieldOptionSchema>

/** 커스텀 필드 단건 응답 타입 */
export type CustomField = z.infer<typeof customFieldResponseSchema>

/** 커스텀 필드 생성 선택지 입력 타입 */
export interface CreateCustomFieldOptionInput {
  value: string
  label: string
  displayOrder?: number
}

/** 커스텀 필드 생성 입력 타입 — key·name·fieldType 필수 */
export interface CreateCustomFieldInput {
  key: string
  name: string
  description?: string
  fieldType: FieldType
  required?: boolean
  displayOrder?: number
  options?: CreateCustomFieldOptionInput[]
}

/** 커스텀 필드 수정 선택지 입력 타입 */
export interface UpdateCustomFieldOptionInput {
  value: string
  label: string
  displayOrder?: number
}

/**
 * 커스텀 필드 수정 입력 타입 — 전부 optional(무변경).
 * fieldType·key 는 불변이므로 타입에서 제외.
 */
export interface UpdateCustomFieldInput {
  name?: string
  description?: string
  required?: boolean
  displayOrder?: number
  options?: UpdateCustomFieldOptionInput[]
}
