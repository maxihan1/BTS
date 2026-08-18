// 버전 BC Zod 스키마 + 추론 타입 정의 — backend VersionResponse/ReleaseNotesResponse DTO 1:1 대응 (FR-VR-01, FR-VR-02, FR-VR-04)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// VersionStatus enum (backend VersionStatus.kt 1:1 대응)
// ─────────────────────────────────────────────────────────────────────────────

/** 버전 상태 enum — backend VersionStatus 3종과 1:1 대응 */
export const versionStatusSchema = z.enum(['UNRELEASED', 'RELEASED', 'ARCHIVED'])

/** 버전 상태 타입 */
export type VersionStatus = z.infer<typeof versionStatusSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend VersionResponse DTO 직렬화 형태와 1:1 대응.
// FR-VR-02: status(항상 존재) + releasedAt(@JsonInclude(NON_NULL) → null이면 필드 자체 없음).
// 날짜는 @JsonFormat("yyyy-MM-dd") 문자열로 직렬화됨 (z.date 아닌 z.string).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 버전 단건 응답 Zod 스키마.
 * backend VersionResponse(id, projectId, name, description?, startDate?, releaseDate?,
 * status, releasedAt?) 1:1 대응.
 * releasedAt: @JsonInclude(NON_NULL) → null이면 JSON 키 자체 없음 → z.string().nullable().optional()
 */
export const versionResponseSchema = z.object({
  id: z.string().uuid(),
  projectId: z.string().uuid(),
  name: z.string().min(1),
  description: z.string().nullable(),
  startDate: z.string().nullable(),
  releaseDate: z.string().nullable(),
  status: versionStatusSchema,
  releasedAt: z.string().nullable().optional(),
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

/** 버전 상태 전환 입력 타입 — FR-VR-02 */
export interface ChangeVersionStatusInput {
  status: VersionStatus
}

// ─────────────────────────────────────────────────────────────────────────────
// 릴리즈 노트 응답 스키마 — backend ReleaseNotesResponse DTO 1:1 대응 (FR-VR-04)
// versionId: UUID, projectKey: String, versionName: String,
// versionStatus: VersionStatus, releaseDate: LocalDate? (null 허용),
// issueCount: Int, generatedAt: Instant (ISO-8601 문자열), markdown: String
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 릴리즈 노트 조회 응답 Zod 스키마.
 * backend ReleaseNotesResponse(versionId, projectKey, versionName, versionStatus,
 * releaseDate?, issueCount, generatedAt, markdown) 1:1 대응.
 * releaseDate: @JsonFormat("yyyy-MM-dd") 또는 null (@JsonInclude 아님 — null 필드 포함됨).
 * generatedAt: Instant → Jackson ISO-8601 문자열 직렬화.
 */
export const releaseNotesResponseSchema = z.object({
  versionId: z.string().uuid(),
  projectKey: z.string().min(1),
  versionName: z.string().min(1),
  versionStatus: versionStatusSchema,
  releaseDate: z.string().nullable(),
  issueCount: z.number().int().nonnegative(),
  generatedAt: z.string(),
  markdown: z.string(),
})

/** 릴리즈 노트 응답 타입 */
export type ReleaseNotes = z.infer<typeof releaseNotesResponseSchema>
