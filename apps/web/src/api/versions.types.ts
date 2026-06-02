// 버전 BC Zod 스키마 + 추론 타입 정의 — backend VersionResponse DTO 1:1 대응 (FR-VR-01)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend VersionResponse DTO 직렬화 형태와 1:1 대응.
// createdAt/updatedAt 추가 금지 — VersionResponse.kt 는 6필드뿐.
// 날짜는 @JsonFormat("yyyy-MM-dd") 문자열로 직렬화됨 (z.date 아닌 z.string).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 단건 응답 Zod 스키마.
 * backend VersionResponse(id, projectId, name, description?, startDate?, releaseDate?) 1:1 대응.
 */
export const versionResponseSchema = z.object({
  id: z.string().uuid(),
  projectId: z.string().uuid(),
  name: z.string().min(1),
  description: z.string().nullable(),
  startDate: z.string().nullable(),
  releaseDate: z.string().nullable(),
})

/** backend `{ data: T }` 응답 래퍼 Zod 스키마 헬퍼 */
export const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 단건 응답 타입 */
export type Version = z.infer<typeof versionResponseSchema>

/** 버전 생성 입력 타입 */
export interface CreateVersionInput {
  name: string
  description?: string
  startDate?: string
  releaseDate?: string
}

/** 버전 수정 입력 타입 — name / description 만 변경 가능 */
export interface UpdateVersionInput {
  name?: string
  description?: string
}

/** 버전 날짜 변경 입력 타입 — null 은 날짜 해제 */
export interface ChangeDatesInput {
  startDate: string | null
  releaseDate: string | null
}
