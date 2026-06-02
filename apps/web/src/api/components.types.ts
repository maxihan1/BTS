// 컴포넌트 BC Zod 스키마 + 추론 타입 정의 — backend ComponentResponse DTO 1:1 대응 (FR-CM-01)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend ComponentResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컴포넌트 단건 응답 Zod 스키마.
 * backend ComponentResponse(id, projectId, name, description?, leadUserId?) 1:1 대응.
 */
export const componentResponseSchema = z.object({
  id: z.string().uuid(),
  projectId: z.string().uuid(),
  name: z.string().min(1),
  description: z.string().nullable(),
  leadUserId: z.string().uuid().nullable(),
})

/** backend `{ data: T }` 응답 래퍼 Zod 스키마 헬퍼 */
export const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 컴포넌트 단건 응답 타입 */
export type Component = z.infer<typeof componentResponseSchema>

/** 컴포넌트 생성 입력 타입 */
export interface CreateComponentInput {
  name: string
  description?: string
  leadUserId?: string
}

/** 컴포넌트 수정 입력 타입 — name / description 만 변경 가능 */
export interface UpdateComponentInput {
  name?: string
  description?: string
}

/** 컴포넌트 리드 변경 입력 타입 — null 은 리드 해제 */
export interface ChangeLeadInput {
  leadUserId: string | null
}
