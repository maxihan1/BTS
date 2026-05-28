// 워크플로우 스킴 API Zod 스키마 및 추론 타입 정의
import { z } from 'zod'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 응답 래퍼 헬퍼 — workflow-schemes, issue-types 파일에서 재사용
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend `{ data: T }` 래퍼 파싱 헬퍼.
 * 모든 BTS backend API는 단건/목록 응답을 `{ data: T }` 형태로 감싼다.
 */
export const dataOf = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend DTO 직렬화 형태와 1:1 대응
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 스킴 단건 목록 응답 Zod 스키마 — usedByProjectsCount, mappingsCount 포함 */
export const schemeResponseSchema = z.object({
  schemeKey: z.string().min(1),
  name: z.string().min(1),
  description: z.string(),
  usedByProjectsCount: z.number().int().nonnegative(),
  mappingsCount: z.number().int().nonnegative(),
})

/** 워크플로우 스킴 매핑 응답 Zod 스키마 — default 매핑 시 issueTypeKey/Name이 null */
export const mappingResponseSchema = z.object({
  id: z.number().int().positive(),
  /** default 매핑(이슈 타입 지정 없음) 시 null, backend T4 cross-BC lookup 결과 */
  issueTypeKey: z.string().min(1).nullable(),
  /** default 매핑 시 null */
  issueTypeName: z.string().nullable(),
  workflowKey: z.string().min(1),
  workflowName: z.string().min(1),
  isDefault: z.boolean(),
})

/** 워크플로우 스킴 상세(매핑 동봉) 응답 Zod 스키마 */
export const schemeDetailResponseSchema = schemeResponseSchema.extend({
  mappings: z.array(mappingResponseSchema),
})

/** 프로젝트-스킴 할당 응답 Zod 스키마 */
export const assignmentResponseSchema = z.object({
  projectKey: z.string().min(1),
  schemeKey: z.string().min(1),
  schemeName: z.string().min(1),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 스킴 단건 응답 타입 */
export type SchemeResponse = z.infer<typeof schemeResponseSchema>

/** 워크플로우 스킴 매핑 응답 타입 */
export type MappingResponse = z.infer<typeof mappingResponseSchema>

/** 워크플로우 스킴 상세(매핑 포함) 응답 타입 */
export type SchemeDetailResponse = z.infer<typeof schemeDetailResponseSchema>

/** 프로젝트-스킴 할당 응답 타입 */
export type AssignmentResponse = z.infer<typeof assignmentResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Input 인터페이스 — 뮤테이션 요청 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 스킴 생성 입력 */
export interface CreateSchemeInput {
  name: string
  description?: string
}

/** 스킴 수정 입력 */
export interface UpdateSchemeInput {
  name?: string
  description?: string
}

/**
 * 매핑 추가 입력.
 * issueTypeKey가 null이면 default 매핑(이슈 타입 미지정 → 해당 워크플로우가 기본값).
 * form에서 sentinel `"__default__"` → `toNullableIssueTypeKey()` 변환 후 전달할 것.
 */
export interface AddMappingInput {
  issueTypeKey: string | null
  workflowKey: string
}

/** 프로젝트-스킴 할당 UPSERT 입력 */
export interface AssignSchemeInput {
  schemeKey: string
}
