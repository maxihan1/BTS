// Import 매핑 마법사(analyze/validate/collect/confirm) API 클라이언트 — search-export-import BC (FR-IM-02 D6)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'
import { importJobStatusSchema } from './imports'
import type { ImportJobStatus } from './imports'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 ImportMappingController 응답 DTO(@JsonInclude NON_NULL 부분)와 1:1 대응
// backend 정본: search-export-import ImportMappingController.kt + web/dto/*.kt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `POST /api/v1/imports/analyze` 응답 Zod 스키마.
 * backend `ImportAnalysisResponse` DTO와 1:1 대응 — 이 DTO는 NON_NULL 어노테이션이 없다(모든 필드 non-null).
 *
 * - sampleRows: JSON 업로드는 항상 빈 배열이므로 빈 배열 입력을 허용해야 한다.
 * - targetFields: analyze 요청과 무관하게 고정된 카탈로그 11종 + IGNORE 센티널을 매번 포함한다.
 */
export const importAnalysisResponseSchema = z.object({
  jobId: z.string().uuid(),
  status: z.literal('AWAITING_MAPPING'),
  format: z.string(),
  sourceFields: z.array(z.object({ name: z.string() })),
  sampleRows: z.array(z.array(z.string())),
  targetFields: z.array(
    z.object({
      key: z.string(),
      label: z.string(),
      required: z.boolean(),
      multi: z.boolean(),
    }),
  ),
})

/** `POST /api/v1/imports/analyze` 응답 타입 — Zod 스키마에서 추론 */
export type ImportAnalysisResponse = z.infer<typeof importAnalysisResponseSchema>

/**
 * 매핑 검증/수집 이슈 하나의 Zod 스키마.
 * backend `MappingValidationResponse.MappingIssueItem` DTO(@JsonInclude NON_NULL)와 1:1 대응.
 *
 * - field: 전역 이슈(예: SUMMARY_NOT_MAPPED)는 backend에서 null → 직렬화 시 키 자체가 없음 → nullish.
 */
const mappingIssueSchema = z.object({
  code: z.string(),
  message: z.string(),
  field: z.string().nullish(),
})

/**
 * `POST /api/v1/imports/{jobId}/mapping/validate` 응답 Zod 스키마.
 * backend `MappingValidationResponse` DTO와 1:1 대응.
 */
export const mappingValidationResponseSchema = z.object({
  valid: z.boolean(),
  errors: z.array(mappingIssueSchema),
  warnings: z.array(mappingIssueSchema),
})

/** `POST /api/v1/imports/{jobId}/mapping/validate` 응답 타입 — Zod 스키마에서 추론 */
export type MappingValidationResponse = z.infer<typeof mappingValidationResponseSchema>

/** 매핑 검증/수집 이슈 하나(에러 또는 경고) 타입 — Zod 스키마에서 추론 */
export type MappingIssue = z.infer<typeof mappingIssueSchema>

/**
 * `POST /api/v1/imports/{jobId}/mapping/users` 응답 Zod 스키마.
 * backend `UserCollectionResponse` DTO(@JsonInclude NON_NULL on UserEntryItem)와 1:1 대응.
 *
 * - suggestedUserId/suggestedDisplayName: 추천 대상을 찾지 못하면 backend에서 null → 키 자체가 없음 → nullish.
 */
export const userCollectionResponseSchema = z.object({
  users: z.array(
    z.object({
      sourceIdentifier: z.string(),
      suggestedUserId: z.string().uuid().nullish(),
      suggestedDisplayName: z.string().nullish(),
    }),
  ),
})

/** `POST /api/v1/imports/{jobId}/mapping/users` 응답 타입 — Zod 스키마에서 추론 */
export type UserCollectionResponse = z.infer<typeof userCollectionResponseSchema>

/**
 * 값 매핑 대상 필드 카탈로그 — backend `ValueTargetField` enum 상수명과 1:1 대응.
 * union 리터럴이라 `type`으로 선언한다(DEVELOPMENT.md §2.2 — interface는 union 금지).
 */
export const valueTargetFieldSchema = z.enum(['STATUS', 'TYPE', 'PRIORITY'])

/** 값 매핑 대상 필드 — Zod enum 스키마에서 추론 */
export type ValueTargetField = z.infer<typeof valueTargetFieldSchema>

/**
 * `POST /api/v1/imports/{jobId}/mapping/values` 응답 Zod 스키마.
 * backend `ValueCollectionResponse` DTO(@JsonInclude NON_NULL on ValueEntryItem)와 1:1 대응.
 *
 * - suggestedTargetValue: 실재하는 후보를 찾지 못하면 backend에서 null → 키 자체가 없음 → nullish.
 */
export const valueCollectionResponseSchema = z.object({
  fields: z.array(
    z.object({
      targetField: valueTargetFieldSchema,
      values: z.array(
        z.object({
          sourceValue: z.string(),
          suggestedTargetValue: z.string().nullish(),
        }),
      ),
    }),
  ),
})

/** `POST /api/v1/imports/{jobId}/mapping/values` 응답 타입 — Zod 스키마에서 추론 */
export type ValueCollectionResponse = z.infer<typeof valueCollectionResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 파라미터 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** analyzeImport 호출 파라미터 */
export interface AnalyzeImportParams {
  /** Import 대상 프로젝트 키 */
  projectKey: string
  /** 파일 형식 */
  format: 'CSV' | 'JSON'
  /** 분석할 CSV/JSON 파일 */
  file: File
}

/** 소스 필드 → 대상 필드 매핑 항목 하나. validate/collectUsers/collectValues/confirmMapping이 공유 */
export interface FieldMappingEntry {
  /** 소스(CSV 헤더 또는 JSON canonical) 필드 이름 */
  sourceField: string
  /** 대상 카탈로그 key 또는 IGNORE 센티널 */
  targetField: string
}

/** 사용자 매핑 항목 하나 — 소스 작성자 식별자 → 대상 BTS 사용자 UUID(옵션) */
export interface UserMappingEntry {
  /** 정규화 대상 소스 작성자 식별자(이메일) */
  sourceIdentifier: string
  /** 매핑할 대상 사용자 UUID. 생략/null이면 미매핑(폴백) 의도 */
  targetUserId?: string | null
}

/** 값 매핑 항목 하나 — 대상 필드(상태/유형/우선순위) + 소스 값 → 대상 값 */
export interface ValueMappingEntry {
  /** 값 매핑 대상 필드 */
  targetField: ValueTargetField
  /** Import 원본에 등장한 소스 값 */
  sourceValue: string
  /** 매핑할 BTS 대상 값 */
  targetValue: string
}

/** confirmMapping 호출 파라미터 */
export interface ConfirmMappingParams {
  /** 확정할 필드 매핑 목록 */
  fieldMappings: FieldMappingEntry[]
  /** 검증 전용 실행 여부. 생략 시 false */
  dryRun?: boolean
  /** 확정할 사용자 매핑 목록. 생략 시 빈 목록 */
  userMappings?: UserMappingEntry[]
  /** 확정할 값 매핑 목록. 생략 시 빈 목록 */
  valueMappings?: ValueMappingEntry[]
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비-2xx 응답이면 ApiError를 throw하는 공통 가드.
 * `api/imports.ts`의 동명 helper와 동형이다 — 컨트롤러 간 헬퍼 공유를 지양하는 BC 관례에 따라
 * 이 파일 안에서 자체 복제한다(교훈 frontend-api-convention-per-bc).
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * POST /api/v1/imports/analyze — CSV/JSON 파일을 분석해 매핑 UI 진입 정보를 받는다.
 *
 * apiFetch가 body instanceof FormData를 감지해 Content-Type을 자동 설정한다 (raw fetch 금지).
 * 비동기 워커를 거치지 않는 동기 완결 흐름이라 200 OK로 분석 결과를 즉시 반환한다.
 *
 * @param params 분석 파라미터 (projectKey/format/file)
 * @returns ImportAnalysisResponse — jobId/sourceFields/sampleRows/targetFields
 * @throws ApiError 400(형식 미지원/projectKey 패턴), 401(미인증), 403(권한없음), 413(파일 크기 초과)
 */
export async function analyzeImport(params: AnalyzeImportParams): Promise<ImportAnalysisResponse> {
  const fd = new FormData()
  fd.append('file', params.file)
  fd.append('projectKey', params.projectKey)
  fd.append('format', params.format)

  const res = await apiFetch('/api/v1/imports/analyze', { method: 'POST', body: fd })
  await throwIfNotOk(res)
  return importAnalysisResponseSchema.parse(await res.json())
}

/**
 * POST /api/v1/imports/{jobId}/mapping/validate — 제안된 필드 매핑을 저장 없이 검증한다.
 *
 * @param jobId 검증 대상 Import 작업 UUID
 * @param fieldMappings 검증할 필드 매핑 목록
 * @returns MappingValidationResponse — valid/errors/warnings
 * @throws ApiError 401(미인증), 404(작업 없음/타인 소유), 409 IMPORT_MAPPING_STATE_CONFLICT
 */
export async function validateFieldMapping(
  jobId: string,
  fieldMappings: FieldMappingEntry[],
): Promise<MappingValidationResponse> {
  const res = await apiFetch(`/api/v1/imports/${jobId}/mapping/validate`, {
    method: 'POST',
    body: { fieldMappings },
  })
  await throwIfNotOk(res)
  return mappingValidationResponseSchema.parse(await res.json())
}

/**
 * POST /api/v1/imports/{jobId}/mapping/users — 원본 작성자 식별자를 수집하고 BTS 사용자 추천을 계산한다.
 *
 * 저장 없이 조회만 수행한다. 요청 바디는 validateFieldMapping과 동일한 fieldMappings 계약을 공유한다.
 *
 * @param jobId 대상 Import 작업 UUID
 * @param fieldMappings CSV 전량 스캔 전 선검증에 사용할 필드 매핑 목록 (JSON은 빈 배열)
 * @returns UserCollectionResponse — 소스 식별자별 추천 사용자 목록
 * @throws ApiError 401, 404, 409 IMPORT_MAPPING_STATE_CONFLICT, 422 IMPORT_MAPPING_INVALID
 */
export async function collectUsers(
  jobId: string,
  fieldMappings: FieldMappingEntry[],
): Promise<UserCollectionResponse> {
  const res = await apiFetch(`/api/v1/imports/${jobId}/mapping/users`, {
    method: 'POST',
    body: { fieldMappings },
  })
  await throwIfNotOk(res)
  return userCollectionResponseSchema.parse(await res.json())
}

/**
 * POST /api/v1/imports/{jobId}/mapping/values — 원본 상태/유형/우선순위 값을 수집하고 대상 값 추천을 계산한다.
 *
 * 저장 없이 조회만 수행한다. 요청 바디는 validateFieldMapping과 동일한 fieldMappings 계약을 공유한다.
 *
 * @param jobId 대상 Import 작업 UUID
 * @param fieldMappings CSV 전량 스캔 전 선검증에 사용할 필드 매핑 목록 (JSON은 빈 배열)
 * @returns ValueCollectionResponse — 대상 필드별 소스값+자동추천 목록
 * @throws ApiError 401, 404, 409 IMPORT_MAPPING_STATE_CONFLICT, 422 IMPORT_MAPPING_INVALID
 */
export async function collectValues(
  jobId: string,
  fieldMappings: FieldMappingEntry[],
): Promise<ValueCollectionResponse> {
  const res = await apiFetch(`/api/v1/imports/${jobId}/mapping/values`, {
    method: 'POST',
    body: { fieldMappings },
  })
  await throwIfNotOk(res)
  return valueCollectionResponseSchema.parse(await res.json())
}

/**
 * POST /api/v1/imports/{jobId}/mapping — 필드/사용자/값 매핑을 확정하고 작업을 PENDING으로 전이한다.
 *
 * 응답은 기존 `api/imports.ts`의 `importJobStatusSchema`를 재사용해 파싱한다 — 이후 상태 폴링은
 * 기존 `GET /api/v1/imports/{jobId}` 경로(`fetchImportJobStatus`)를 그대로 사용한다.
 *
 * @param jobId 확정 대상 Import 작업 UUID
 * @param params 확정 파라미터 — fieldMappings/dryRun?/userMappings?/valueMappings?
 * @returns ImportJobStatus — jobId/status("PENDING") 등
 * @throws ApiError 400(값 매핑 targetField 미지 상수명), 401, 404, 409 IMPORT_MAPPING_STATE_CONFLICT,
 *   422 IMPORT_MAPPING_INVALID/IMPORT_USER_MAPPING_INVALID/IMPORT_VALUE_MAPPING_INVALID
 */
export async function confirmMapping(jobId: string, params: ConfirmMappingParams): Promise<ImportJobStatus> {
  const res = await apiFetch(`/api/v1/imports/${jobId}/mapping`, {
    method: 'POST',
    body: {
      fieldMappings: params.fieldMappings,
      dryRun: params.dryRun ?? false,
      userMappings: params.userMappings ?? [],
      valueMappings: params.valueMappings ?? [],
    },
  })
  await throwIfNotOk(res)
  return importJobStatusSchema.parse(await res.json())
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → i18n 메시지 매핑 — 매핑 마법사 전용 IMPORT_MAPPING_ prefix 에러코드
// backend 정본: ImportMappingService.kt / MappingValidator.kt(예외 계층)
// ─────────────────────────────────────────────────────────────────────────────

/** Import 매핑 마법사 전용 백엔드 에러 코드 → 한글 메시지 매핑 */
export const IMPORT_MAPPING_ERROR_CODES: Record<string, string> = {
  IMPORT_MAPPING_INVALID: '필드 매핑이 올바르지 않습니다. 매핑을 확인하세요.',
  IMPORT_USER_MAPPING_INVALID: '사용자 매핑이 올바르지 않습니다. 매핑을 확인하세요.',
  IMPORT_VALUE_MAPPING_INVALID: '값 매핑이 올바르지 않습니다. 매핑을 확인하세요.',
  IMPORT_MAPPING_STATE_CONFLICT: '이미 처리 중이거나 완료된 작업입니다. 새로고침 후 다시 시도하세요.',
}

/**
 * Import 매핑 실패 errorCode를 사용자 노출용 한글 메시지로 변환한다.
 *
 * `api/imports.ts`의 `importFailureMessage`와 동형이나, 매핑 마법사 전용 에러코드 테이블을 참조한다.
 *
 * @param errorCode 백엔드 errorCode (없으면 null/undefined)
 * @returns 매핑된 한글 메시지 — 매핑 없거나 errorCode 없으면 공통 fallback 메시지
 */
export function importMappingFailureMessage(errorCode: string | null | undefined): string {
  if (errorCode == null) {
    return '알 수 없는 오류가 발생했습니다.'
  }
  return IMPORT_MAPPING_ERROR_CODES[errorCode] ?? '알 수 없는 오류가 발생했습니다.'
}
