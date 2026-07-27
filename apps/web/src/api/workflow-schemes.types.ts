// 워크플로우 스킴 API Zod 스키마 및 추론 타입 정의 — 백엔드 응답 "형태"마다 스키마 1장
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
// Zod 스키마 — 백엔드 DTO 직렬화 형태와 1:1
//
// ★ 원칙. 스키마 1장 = endpoint 1개 **또는 형태가 완전히 동일한 endpoint 묶음**.
//   endpoint 수가 아니라 "형태 수"만큼 스키마를 나눈다. 이 파일은 원래 스키마 3장을 각각
//   서로 다른 백엔드 DTO 2개에 재사용하고 있었고(목록/생성/수정이 한 장, 배정조회/배정이 한 장),
//   그래서 8 endpoint 중 7개가 계약에서 어긋나 있었다. 정합성은
//   `docs/contracts/workflow-schemes.snapshot.json` 과 `__tests__/workflow-schemes.contract.test.ts`
//   가 강제한다 — 스키마를 고치면 그 테스트가 먼저 말해준다.
//
// ★ 선언 순서. `const` 는 호이스팅되지 않는다. 참조되는 스키마가 반드시 위에 있어야 한다(TDZ).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스킴 공통 필드 — 목록·상세·배정조회·배정후보가 공유하는 최소 집합.
 *
 * `isStandard` 는 시스템 표준 스킴(V201 시드 4종) 여부다. 백엔드 도메인·DB 는 `isDefault`/
 * `is_default` 이지만 뷰 레이어가 `isStandard` 로 노출한다 — 매핑의 `isDefault`(기본 매핑)와
 * 다른 개념이라 이름을 분리했다.
 */
const schemeCoreSchema = z.object({
  id: z.number().int().nullable(),
  key: z.string().min(1),
  name: z.string().min(1),
  description: z.string().nullable(),
  isStandard: z.boolean(),
})

/**
 * GET `/api/v1/projects/{k}/workflow-scheme` · GET `.../assignable-workflow-schemes`.
 * 둘 다 백엔드 `ProjectWorkflowSchemeController.SchemeResponse` — 형태가 같아 한 장을 공유한다.
 */
export const assignedSchemeSchema = schemeCoreSchema

/**
 * POST · PUT `/api/v1/workflow-schemes` — 백엔드 `WorkflowSchemeResponse`.
 * 카운트·매핑이 없다. 목록 응답과 형태가 달라 스키마를 나눈다.
 */
export const schemeMutationResultSchema = schemeCoreSchema.extend({
  /** 생성·수정 응답에서는 항상 존재한다(저장 후 PK). */
  id: z.number().int(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

/**
 * 매핑 상세 — 상세 응답 안의 원소. 백엔드 `MappingResponseDetail`.
 *
 * `isDefault` 는 기본 매핑(이슈타입 지정 없이 적용되는 대체 매핑) 여부다. 백엔드가
 * `issue_type_id IS NULL` 로 판정해 명시적으로 실어 보낸다 — `issueTypeKey === null` 로
 * 프론트가 유도하면 cross-BC 조회 실패와 구분되지 않는다(EC-4).
 */
export const mappingDetailSchema = z.object({
  id: z.number().int().positive(),
  issueTypeKey: z.string().min(1).nullable(),
  issueTypeName: z.string().nullable(),
  workflowKey: z.string().min(1),
  workflowName: z.string().min(1),
  isDefault: z.boolean(),
})

/**
 * GET `/api/v1/workflow-schemes` — 백엔드 `WorkflowSchemeDetailResponse`(목록 판본).
 * 카운트를 포함하고 `mappings` 는 빈 배열로 온다.
 *
 * `mappings` 를 `z.unknown()` 으로 두면 목록 endpoint 에서 봉인이 무력해지므로(리뷰 F4),
 * 빈 배열이어도 원소 형태를 요구한다 — 빈 배열은 그대로 통과한다.
 */
export const schemeListItemSchema = schemeMutationResultSchema.extend({
  usedByProjectsCount: z.number().int().nonnegative(),
  mappingsCount: z.number().int().nonnegative(),
  mappings: z.array(mappingDetailSchema),
})

/** GET `/api/v1/workflow-schemes/{key}` — 목록 판본과 같은 DTO 이나 `mappings` 가 실제로 채워진다. */
export const schemeDetailSchema = schemeListItemSchema.extend({
  mappings: z.array(mappingDetailSchema),
})

/**
 * POST `/{key}/mappings` — 백엔드 `MappingResponse`.
 * 상세용 `MappingResponseDetail` 과 **완전히 다른 형태**다(키가 아니라 내부 PK 를 싣는다).
 */
export const mappingCreatedSchema = z.object({
  id: z.number().int().positive(),
  schemeId: z.number().int().positive(),
  issueTypeId: z.number().int().nullable(),
  workflowId: z.string().min(1),
  createdAt: z.string(),
})

/**
 * PUT `/api/v1/projects/{k}/workflow-scheme` — 백엔드 `AssignmentResponse`(배정 이력).
 * 화면은 이 값을 쓰지 않지만, 파싱해 두어야 백엔드가 형태를 바꿨을 때 계약 테스트가 잡는다.
 */
export const assignmentRecordSchema = z.object({
  projectId: z.string().min(1),
  workflowSchemeId: z.number().int().positive(),
  assignedAt: z.string(),
  assignedBy: z.string().min(1),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 배정 조회·배정 후보 목록의 스킴 항목 */
export type AssignedScheme = z.infer<typeof assignedSchemeSchema>

/** 스킴 생성·수정 응답 */
export type SchemeMutationResult = z.infer<typeof schemeMutationResultSchema>

/** 스킴 목록 항목 (카운트 포함) */
export type SchemeListItem = z.infer<typeof schemeListItemSchema>

/** 스킴 상세 (매핑 포함) */
export type SchemeDetail = z.infer<typeof schemeDetailSchema>

/** 매핑 상세 항목 */
export type MappingDetail = z.infer<typeof mappingDetailSchema>

/** 매핑 추가 응답 (내부 PK 형태) */
export type MappingCreated = z.infer<typeof mappingCreatedSchema>

/** 프로젝트-스킴 배정 이력 */
export type AssignmentRecord = z.infer<typeof assignmentRecordSchema>

// ─────────────────────────────────────────────────────────────────────────────
// Input 인터페이스 — 뮤테이션 요청 타입 (백엔드 request DTO 와 1:1)
// ─────────────────────────────────────────────────────────────────────────────

/** 스킴 생성 입력 — 백엔드 `CreateWorkflowSchemeRequest{key,name,description}` 와 1:1. */
export interface CreateSchemeInput {
  key: string
  name: string
  description?: string
}

/** 스킴 수정 입력 — 백엔드 `UpdateWorkflowSchemeRequest.name` 은 non-null 이라 선택 필드가 아니다. */
export interface UpdateSchemeInput {
  name: string
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
