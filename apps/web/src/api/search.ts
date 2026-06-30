// search-export-import BC AQL 검색 + CSV/XLSX 내보내기 API 클라이언트 — FR-SR-02/FR-EX-01 D6
import { z } from 'zod'
import { apiPost, apiGet, apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — 백엔드 SearchErrorCode 열거값 정본
// backend 정본: search-export-import SearchController.kt — 변경 시 동반 수정
// ─────────────────────────────────────────────────────────────────────────────

/** AQL 검색 관련 백엔드 에러 코드 */
export const SEARCH_ERROR_CODES = {
  SYNTAX_ERROR: 'SEARCH_SYNTAX_ERROR',
  UNKNOWN_FIELD: 'SEARCH_UNKNOWN_FIELD',
  FIELD_NOT_YET_SUPPORTED: 'SEARCH_FIELD_NOT_YET_SUPPORTED',
  VALIDATION_FAILED: 'SEARCH_VALIDATION_FAILED',
  UNAUTHENTICATED: 'SEARCH_UNAUTHENTICATED',
  ACCESS_DENIED: 'SEARCH_ACCESS_DENIED',
  INTERNAL_ERROR: 'SEARCH_INTERNAL_ERROR',
  /** 동기 Export 상한(1만건) 초과 — 자동 비동기 분기 트리거 (FR-EX-02) */
  EXPORT_LIMIT_EXCEEDED: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
  /** 비동기 잡이 아직 완료되지 않아 다운로드 불가 (FR-EX-02) */
  EXPORT_NOT_READY: 'SEARCH_EXPORT_NOT_READY',
  /** 비동기 잡 결과 오브젝트 스토리지 저장 실패 (FR-EX-02) */
  EXPORT_STORAGE_ERROR: 'SEARCH_EXPORT_STORAGE_ERROR',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 AqlSearchHit DTO와 1:1 대응
// backend 정본: search-export-import AqlSearchHit.kt
// labels 필드 없음 — backend DTO에 없으므로 invent 금지 (frontend-zod-backend-dto-contract-gap)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AQL 검색 결과 단건 Zod 스키마.
 * 백엔드 AqlSearchHit DTO 직렬화 형태와 1:1 대응.
 *
 * - assigneeId: UUID 또는 null (미할당 이슈)
 * - priority: int 1..5
 * - updatedAt: ISO 8601 Instant 문자열
 */
export const aqlSearchHitSchema = z.object({
  key: z.string().min(1),
  summary: z.string(),
  typeKey: z.string().min(1),
  currentStateKey: z.string().min(1),
  assigneeId: z.string().uuid().nullable(),
  priority: z.number().int().min(1).max(5),
  priorityName: z.string().min(1),
  projectKey: z.string().min(1),
  updatedAt: z.string().min(1),
})

/** AQL 검색 결과 단건 타입 */
export type AqlSearchHit = z.infer<typeof aqlSearchHitSchema>

/**
 * Spring Page<AqlSearchHit> 응답 Zod 스키마.
 * issues.ts pageSchema 패턴 미러 — 래퍼 없음 (DataResponse 감싸지 않음, C2).
 * backend Spring Page 직렬화 형태와 1:1 대응.
 */
export const aqlSearchPageSchema = z.object({
  content: z.array(aqlSearchHitSchema),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  number: z.number().int().nonnegative(),
  first: z.boolean(),
  last: z.boolean(),
  empty: z.boolean(),
})

/** AQL 검색 결과 페이지 타입 */
export type AqlSearchPage = z.infer<typeof aqlSearchPageSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 파라미터 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** searchAql 호출 파라미터 */
export interface SearchAqlParams {
  /** 검색 대상 프로젝트 키 */
  projectKey: string
  /** AQL 쿼리 문자열 (최대 2000자) */
  query: string
  /** 페이지 번호 (0-based, 기본값 0) */
  page?: number
  /** 페이지 크기 (1..100, 기본값 50) */
  size?: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 비동기 Export Job Zod 스키마 — 백엔드 ExportJobResponse DTO 1:1 대응 (FR-EX-02)
// backend 정본: search-export-import ExportJobResponse.kt (@JsonInclude NON_NULL)
// rowCount/errorCode: .nullish() — NON_NULL 설정으로 null 시 키 자체가 없으므로
//   .nullable()만 사용하면 undefined 입력에서 ZodError 발생 (B2)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 비동기 Export 잡 상태 응답 Zod 스키마.
 * backend ExportJobResponse DTO @JsonInclude(NON_NULL) 직렬화 형태와 1:1 대응.
 *
 * - status 종단 상태: COMPLETED / FAILED (폴링 중단 신호)
 * - rowCount: COMPLETED 후에만 존재. PENDING/RUNNING 응답에서는 키 자체가 없음.
 * - errorCode: FAILED 후에만 존재. COMPLETED/PENDING/RUNNING 응답에서는 키 자체가 없음.
 */
export const exportJobStatusSchema = z.object({
  jobId: z.string().uuid(),
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  progress: z.number().int().min(0).max(100),
  /** 완료 후 실제 내보낸 행 수. PENDING/RUNNING 응답에서 키가 없으므로 nullish. */
  rowCount: z.number().int().nonnegative().nullish(),
  format: z.string().min(1),
  /** FAILED 시 실패 사유 코드. 비실패 응답에서 키가 없으므로 nullish. */
  errorCode: z.string().nullish(),
  downloadReady: z.boolean(),
})

/** 비동기 Export 잡 상태 타입 */
export type ExportJobStatus = z.infer<typeof exportJobStatusSchema>

/**
 * POST /api/v1/search/export-jobs 202 접수 응답 Zod 스키마.
 * backend ExportJobAccepted DTO 직렬화 형태와 1:1 대응.
 */
const exportJobAcceptedSchema = z.object({
  jobId: z.string().uuid(),
  status: z.literal('PENDING'),
})

/** 비동기 Export 잡 접수 응답 타입 */
export type ExportJobAccepted = z.infer<typeof exportJobAcceptedSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 비동기 Export Job 파라미터 인터페이스 (FR-EX-02)
// ─────────────────────────────────────────────────────────────────────────────

/** submitExportJob 호출 파라미터 — 동기 exportIssues와 동일한 DTO 구조 */
export interface SubmitExportJobParams {
  /** 내보낼 이슈의 프로젝트 키 */
  projectKey: string
  /** AQL 쿼리 문자열 (최대 2000자) */
  query: string
  /** 파일 형식 — CSV(UTF-8 BOM) 또는 XLSX(Apache POI) */
  format: 'CSV' | 'XLSX'
  /** 내보낼 컬럼 토큰 목록 (미지정 시 전체 9컬럼) */
  columns?: readonly string[]
}

// ─────────────────────────────────────────────────────────────────────────────
// 내보내기 파라미터 / 결과 인터페이스 (FR-EX-01)
// ─────────────────────────────────────────────────────────────────────────────

/** exportIssues 호출 파라미터 */
export interface ExportIssuesParams {
  /** 내보낼 이슈의 프로젝트 키 */
  projectKey: string
  /** AQL 쿼리 문자열 (최대 2000자) */
  query: string
  /** 파일 형식 — CSV(UTF-8 BOM) 또는 XLSX(Apache POI) */
  format: 'CSV' | 'XLSX'
  /** 내보낼 컬럼 토큰 목록 (미지정 시 전체 9컬럼) */
  columns?: readonly string[]
}

/** exportIssues 반환값 */
export interface ExportIssuesResult {
  /** 내보내기 파일 Blob */
  blob: Blob
  /** Content-Disposition 헤더에서 파싱한 파일명 */
  filename: string
}

/**
 * POST /api/v1/search/export — AQL 검색 결과를 CSV 또는 XLSX로 내보낸다.
 *
 * `downloadAttachment` 패턴 복제: apiFetch → non-ok 시 ApiError throw → ok면 res.blob().
 * CSRF / credentials / 401-refresh는 apiFetch가 자동 처리 (raw fetch 금지 — plan R:B4/C10).
 * 파일명은 응답 Content-Disposition 헤더에서 파싱 (서버 생성 timestamp 포함 — plan R:devex-B2).
 *
 * @param params 내보내기 파라미터
 * @returns { blob, filename }
 * @throws ApiError 400(SEARCH_EXPORT_LIMIT_EXCEEDED / 문법오류 / 미지원컬럼),
 *                   401(미인증), 403(권한없음)
 */
export async function exportIssues(params: ExportIssuesParams): Promise<ExportIssuesResult> {
  const body: Record<string, unknown> = {
    projectKey: params.projectKey,
    query: params.query,
    format: params.format,
  }
  if (params.columns !== undefined) {
    // readonly string[] → string[] 변환 (백엔드 요청 직렬화용)
    body['columns'] = Array.from(params.columns)
  }

  const res = await apiFetch('/api/v1/search/export', { method: 'POST', body })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }

  // Content-Disposition: attachment; filename="ATLAS-issues-20260629T000000Z.csv"
  const contentDisposition = res.headers.get('content-disposition') ?? ''
  const filenameMatch = /filename="([^"]+)"/.exec(contentDisposition)
  const filename = filenameMatch?.[1] ?? `export.${params.format.toLowerCase()}`

  const blob = await res.blob()
  return { blob, filename }
}

// ─────────────────────────────────────────────────────────────────────────────
// 비동기 Export Job API 함수 (FR-EX-02)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/search/export-jobs — 비동기 Export 잡을 접수한다.
 *
 * 검색 결과가 동기 상한(1만건)을 초과할 때 호출한다 (SEARCH_EXPORT_LIMIT_EXCEEDED).
 * 동기 exportIssues와 동일한 DTO 구조로 요청한다.
 *
 * 폴링 계약.
 * - 반환된 jobId로 fetchExportJobStatus를 1500ms 간격으로 폴링한다.
 * - status가 COMPLETED 또는 FAILED가 되면 폴링을 중단한다 (종단 상태).
 * - COMPLETED + downloadReady=true이면 downloadExportJobResult로 파일을 받는다.
 *
 * @param params 내보내기 파라미터 (동기 exportIssues와 동일 구조)
 * @returns ExportJobAccepted — jobId/status("PENDING")
 * @throws ApiError 400(AQL 오류/검증 실패), 401(미인증), 403(권한없음)
 */
export async function submitExportJob(params: SubmitExportJobParams): Promise<ExportJobAccepted> {
  const body: Record<string, unknown> = {
    projectKey: params.projectKey,
    query: params.query,
    format: params.format,
  }
  if (params.columns !== undefined) {
    // readonly string[] → string[] 변환 (백엔드 요청 직렬화용)
    body['columns'] = Array.from(params.columns)
  }
  return apiPost('/api/v1/search/export-jobs', body, exportJobAcceptedSchema)
}

/**
 * GET /api/v1/search/export-jobs/{id} — 비동기 Export 잡의 현재 상태를 조회한다.
 *
 * 폴링 루프에서 주기적으로 호출한다 (TanStack Query refetchInterval 1500ms).
 * status가 COMPLETED 또는 FAILED이면 종단 상태로 폴링을 중단해야 한다.
 *
 * 응답 NON_NULL 주의.
 * - rowCount: COMPLETED 전에는 키가 없음 → nullish (undefined로 반환됨)
 * - errorCode: FAILED 전에는 키가 없음 → nullish (undefined로 반환됨)
 *
 * @param jobId Export 잡 UUID
 * @returns ExportJobStatus — exportJobStatusSchema 파싱 결과
 * @throws ApiError 404(잡 없음 또는 타인 소유)
 */
export async function fetchExportJobStatus(jobId: string): Promise<ExportJobStatus> {
  return apiGet(`/api/v1/search/export-jobs/${jobId}`, exportJobStatusSchema)
}

/**
 * GET /api/v1/search/export-jobs/{id}/download — 완료된 Export 잡의 파일을 내려받는다.
 *
 * fetchExportJobStatus 결과 downloadReady=true일 때만 호출해야 한다.
 * Content-Disposition 파싱 패턴은 exportIssues(search.ts:138-144)와 동일.
 *
 * @param jobId Export 잡 UUID
 * @returns { blob, filename } — blob: 파일 Blob, filename: Content-Disposition 파싱 파일명
 * @throws ApiError 409(SEARCH_EXPORT_NOT_READY — 아직 미완료), 404(잡 없음/타인 소유)
 */
export async function downloadExportJobResult(jobId: string): Promise<ExportIssuesResult> {
  const res = await apiFetch(`/api/v1/search/export-jobs/${jobId}/download`)
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }

  // Content-Disposition: attachment; filename="ATLAS-export-20260630T000000Z.csv"
  const contentDisposition = res.headers.get('content-disposition') ?? ''
  const filenameMatch = /filename="([^"]+)"/.exec(contentDisposition)
  const filename = filenameMatch?.[1] ?? 'export.csv'

  const blob = await res.blob()
  return { blob, filename }
}

// ─────────────────────────────────────────────────────────────────────────────
// AQL 검색 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/search/aql — AQL 쿼리로 이슈를 검색한다.
 *
 * CSRF 불필요 — SPA는 Bearer 토큰 전송, Spring SecurityConfig가 Bearer 요청 CSRF skip.
 * 기존 boards/bulk POST와 동일하게 apiPost 재사용.
 *
 * @param params 검색 파라미터 (projectKey, query, page?, size?)
 * @returns Page<AqlSearchHit> — Spring Page 래퍼 형태 그대로
 * @throws ApiError 400(문법오류/미지원필드), 401(미인증), 403(권한없음), 500
 */
export async function searchAql(params: SearchAqlParams): Promise<AqlSearchPage> {
  const body: Record<string, unknown> = {
    projectKey: params.projectKey,
    query: params.query,
  }
  if (params.page !== undefined) {
    body['page'] = params.page
  }
  if (params.size !== undefined) {
    body['size'] = params.size
  }

  return apiPost('/api/v1/search/aql', body, aqlSearchPageSchema)
}
