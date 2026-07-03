// Import(CSV/JSON) 작업 접수/폴링/에러로그 API 클라이언트 — search-export-import BC (FR-IM-01 D6)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 ImportJobResponse DTO(@JsonInclude NON_NULL)와 1:1 대응
// backend 정본: search-export-import ImportJobResponse.kt
// totalRows/errorCode: .nullish() — NON_NULL 설정으로 null 시 키 자체가 없으므로
//   .nullable()만 사용하면 undefined 입력에서 ZodError 발생 (search.ts exportJobStatusSchema 동형)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Import 작업 접수/폴링 응답 Zod 스키마.
 * backend ImportJobResponse DTO @JsonInclude(NON_NULL) 직렬화 형태와 1:1 대응.
 *
 * - totalRows: RUNNING에서 파싱 후 확정. 그 전에는 키 자체가 없음 → nullish.
 * - errorCode: FAILED에서만 존재. 그 외 응답에서는 키 자체가 없음 → nullish.
 */
export const importJobStatusSchema = z.object({
  jobId: z.string().uuid(),
  status: z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']),
  progress: z.number().int().min(0).max(100),
  totalRows: z.number().int().nonnegative().nullish(),
  succeededRows: z.number().int().nonnegative(),
  failedRows: z.number().int().nonnegative(),
  errorCode: z.string().nullish(),
  errorLogReady: z.boolean(),
  dryRun: z.boolean(),
})

/** Import 작업 상태 타입 — Zod 스키마에서 추론 (interface 중복 정의 금지) */
export type ImportJobStatus = z.infer<typeof importJobStatusSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 폴링 간격 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Import 작업 폴링 간격(ms).
 * TanStack Query refetchInterval에 이 값을 사용해야 한다 (search.ts EXPORT_POLL_INTERVAL_MS 동형).
 * 종단 상태(COMPLETED/FAILED) 도달 시 refetchInterval 콜백에서 false를 반환해 폴링을 중단한다.
 */
export const IMPORT_POLL_INTERVAL_MS = 1500

// ─────────────────────────────────────────────────────────────────────────────
// 요청 파라미터 / 결과 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** submitImportJob 호출 파라미터 */
export interface SubmitImportParams {
  /** Import 대상 프로젝트 키 */
  projectKey: string
  /** 파일 형식 */
  format: 'CSV' | 'JSON'
  /** 업로드할 CSV/JSON 파일 */
  file: File
  /** 첨부 zip 파일 (선택 — CSV import는 무시됨) */
  attachmentsZip?: File | null
  /** 검증 전용 실행 여부 */
  dryRun: boolean
}

/** downloadImportErrorLog 반환값 */
export interface ImportErrorLogResult {
  /** 실패행 에러 로그 CSV Blob */
  blob: Blob
  /** Content-Disposition 헤더에서 파싱한 파일명 */
  filename: string
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/** 비-2xx 응답이면 ApiError를 throw하는 공통 가드 (3개 함수 중복 제거) */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * POST /api/v1/imports — CSV/JSON 파일을 multipart로 접수한다.
 *
 * apiFetch가 body instanceof FormData를 감지해 Content-Type을 자동 설정한다 (raw fetch 금지).
 *
 * @param params 접수 파라미터 (projectKey/format/file/attachmentsZip?/dryRun)
 * @returns ImportJobStatus — jobId/status("PENDING") 등
 * @throws ApiError 400(형식 미지원/검증 실패), 401(미인증), 403(권한없음), 413(파일 크기 초과)
 */
export async function submitImportJob(params: SubmitImportParams): Promise<ImportJobStatus> {
  const fd = new FormData()
  fd.append('file', params.file)
  if (params.attachmentsZip != null) {
    fd.append('attachmentsZip', params.attachmentsZip)
  }
  fd.append('projectKey', params.projectKey)
  fd.append('format', params.format)
  fd.append('dryRun', String(params.dryRun))

  const res = await apiFetch('/api/v1/imports', { method: 'POST', body: fd })
  await throwIfNotOk(res)
  return importJobStatusSchema.parse(await res.json())
}

/**
 * GET /api/v1/imports/{jobId} — Import 작업의 현재 상태를 조회한다.
 *
 * 폴링 루프에서 주기적으로 호출한다 (TanStack Query refetchInterval IMPORT_POLL_INTERVAL_MS).
 * status가 COMPLETED 또는 FAILED이면 종단 상태로 폴링을 중단해야 한다.
 *
 * @param jobId Import 작업 UUID
 * @returns ImportJobStatus — importJobStatusSchema 파싱 결과
 * @throws ApiError 404(작업 없음 또는 타인 소유)
 */
export async function fetchImportJobStatus(jobId: string): Promise<ImportJobStatus> {
  const res = await apiFetch(`/api/v1/imports/${jobId}`)
  await throwIfNotOk(res)
  return importJobStatusSchema.parse(await res.json())
}

/**
 * GET /api/v1/imports/{jobId}/errors — 완료된 Import 작업의 실패행 에러 로그를 내려받는다.
 *
 * fetchImportJobStatus 결과 errorLogReady=true일 때만 호출해야 한다.
 * Content-Disposition 파싱 패턴은 search.ts downloadExportJobResult와 동일.
 *
 * @param jobId Import 작업 UUID
 * @returns { blob, filename } — blob: CSV Blob, filename: Content-Disposition 파싱 파일명
 * @throws ApiError 404(작업 없음/타인 소유/에러 로그 미준비)
 */
export async function downloadImportErrorLog(jobId: string): Promise<ImportErrorLogResult> {
  const res = await apiFetch(`/api/v1/imports/${jobId}/errors`)
  await throwIfNotOk(res)

  const contentDisposition = res.headers.get('content-disposition') ?? ''
  const filenameMatch = /filename="([^"]+)"/.exec(contentDisposition)
  const filename = filenameMatch?.[1] ?? `${jobId}-errors.csv`

  const blob = await res.blob()
  return { blob, filename }
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 → i18n 메시지 매핑 — 백엔드 IMPORT_ prefix 에러코드 정본
// backend 정본: ImportExceptionHandler.kt(컨트롤러 예외) + ImportJobProcessor.kt(워커 실패 코드)
// ─────────────────────────────────────────────────────────────────────────────

/** Import 관련 백엔드 에러 코드 → 한글 메시지 매핑 */
export const IMPORT_ERROR_CODES: Record<string, string> = {
  IMPORT_ACCESS_DENIED: '이 프로젝트에 이슈를 생성할 권한이 없습니다.',
  IMPORT_FILE_TOO_LARGE: '파일 크기가 허용 한도를 초과합니다.',
  IMPORT_UNSUPPORTED_FORMAT: '지원하지 않는 파일 형식입니다.',
  IMPORT_VALIDATION_FAILED: '요청 형식이 올바르지 않습니다.',
  IMPORT_ROW_LIMIT_EXCEEDED: '행 수가 허용 한도를 초과합니다.',
  IMPORT_PARSE_FAILED: '파일을 파싱하지 못했습니다. 형식을 확인하세요.',
  IMPORT_INTERNAL_ERROR: '서버 오류가 발생했습니다.',
}

/**
 * Import 실패 errorCode를 사용자 노출용 한글 메시지로 변환한다.
 *
 * @param errorCode 백엔드 errorCode (없으면 null/undefined)
 * @returns 매핑된 한글 메시지 — 매핑 없거나 errorCode 없으면 공통 fallback 메시지
 */
export function importFailureMessage(errorCode: string | null | undefined): string {
  if (errorCode == null) {
    return '알 수 없는 오류가 발생했습니다.'
  }
  return IMPORT_ERROR_CODES[errorCode] ?? '알 수 없는 오류가 발생했습니다.'
}
