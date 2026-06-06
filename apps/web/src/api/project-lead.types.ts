// 프로젝트 리드 API Zod 스키마 + 추론 타입 정의 — backend ProjectLeadResponse DTO 1:1 대응 (FR-CM-04)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend ProjectLeadResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 리드 응답 Zod 스키마.
 * backend ProjectLeadResponse(projectId, leadUserId?) 1:1 대응.
 */
export const projectLeadResponseSchema = z.object({
  projectId: z.string().uuid(),
  leadUserId: z.string().uuid().nullable(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 프로젝트 리드 응답 타입 */
export type ProjectLead = z.infer<typeof projectLeadResponseSchema>
