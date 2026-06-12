// 이슈 템플릿 BC Zod 스키마 + 추론 타입 정의 — backend IssueTemplateResponse DTO 1:1 대응 (FR-TM-01)
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// backend IssueTemplateResponse DTO 직렬화 형태와 1:1 대응.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 템플릿 단건 응답 Zod 스키마.
 * backend IssueTemplateResponse DTO 1:1 대응.
 *
 * createdAt/updatedAt — backend가 항상 non-null로 직렬화하므로 z.string() (nullable 아님).
 * issues.ts:32-33의 nullable 선례와 달리 템플릿은 non-null 계약임에 유의.
 */
export const issueTemplateResponseSchema = z.object({
  id: z.string().uuid(),
  projectId: z.string().uuid(),
  issueTypeId: z.number().int().positive(),
  name: z.string(),
  content: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

/** backend `{ data: T }` 응답 래퍼 Zod 스키마 헬퍼 */
export const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 템플릿 단건 응답 타입 */
export type IssueTemplate = z.infer<typeof issueTemplateResponseSchema>

/**
 * 이슈 템플릿 생성 입력 타입 — issueTypeId·name·content 필수.
 * backend CreateIssueTemplateRequest(issueTypeId:Long, name:String, content:String) 1:1 대응.
 */
export interface CreateIssueTemplateInput {
  issueTypeId: number
  name: string
  content: string
}

/**
 * 이슈 템플릿 수정 입력 타입 — 전부 optional(무변경).
 * backend UpdateIssueTemplateRequest(name?/content?) 1:1 대응.
 * issueTypeId는 불변이므로 타입에서 제외.
 */
export interface UpdateIssueTemplateInput {
  name?: string
  content?: string
}
