// Import(CSV/JSON) 작업 MSW 핸들러 — jobId 기준 stateful 진행 시뮬레이션 store (FR-IM-01 D6/D7 Task-5)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: POST가 만든 잡을 GET이 조회할 수 있도록 공유 store를
//     두고, 진행(PENDING→RUNNING→COMPLETED)을 GET 호출 횟수에 따른 파생 동작으로 구현한다.
//   - search.ts exportJobsStatusHandler(FR-EX-02) 선례를 미러 — pollCount 0/1/2+ 3단계 진행.
//   - frontend-zod-backend-dto-contract-gap: 응답은 importJobStatusSchema.parse가 통과해야 하며,
//     totalRows/errorCode는 백엔드 @JsonInclude(NON_NULL)을 재현해 값이 있을 때만 키를 포함한다.
//
import { http, HttpResponse } from 'msw'
import type { ImportJobStatus } from '@/api/imports'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * FAILED 시나리오 강제 플래그. 'true' | null.
 *
 * GET 상태 폴링 핸들러는 기본적으로 PENDING→RUNNING→COMPLETED로만 진행해 실패 경로를
 * Playwright(브라우저 MSW 워커)에서 재현할 수단이 없었다. 이 플래그를 'true'로 설정하면
 * 다음 GET 호출에서 해당 job을 FAILED로 강제 전환한다 (E2E S3 "Import FAILED → 에러
 * 메시지 + [다시 시도]" 시나리오 재현용).
 *
 * 회귀 학습 e2e-msw-scenario-toggle-localstorage-flag 근거 — localStorage 플래그는
 * addInitScript 등으로 goto 전에 심어야 하며, 매 테스트 후 반드시 제거해 leak을 막는다
 * (bulk-operation-handlers.ts LS_KEY_BULK_REJECT, board-handlers.ts LS_KEY_BOARD_CONFLICT
 * 명명·정리 관례를 그대로 미러).
 */
export const LS_KEY_IMPORT_FAIL = '__bts_e2e_import_fail'

// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드용 정적 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/** COMPLETED 상태 샘플 픽스처 — importJobStatusSchema 드리프트 가드 단위 assert 전용(store 미영향) */
export const SAMPLE_IMPORT_JOB_COMPLETED: ImportJobStatus = {
  jobId: '00000000-0000-4000-a000-000000000099',
  status: 'COMPLETED',
  progress: 100,
  totalRows: 10,
  succeededRows: 9,
  failedRows: 1,
  errorLogReady: true,
  dryRun: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 저장 레코드 타입 — NON_NULL 재현: totalRows/errorCode는 선택 필드(값이 있을 때만 보유)
// ─────────────────────────────────────────────────────────────────────────────

interface ImportJobRecord {
  jobId: string
  status: ImportJobStatus['status']
  progress: number
  totalRows?: number
  succeededRows: number
  failedRows: number
  errorCode?: string
  errorLogReady: boolean
  dryRun: boolean
  pollCount: number
}

/** 기본 진행 시뮬레이션 총 행 수 (POST가 만든 잡의 RUNNING/COMPLETED 단계에서 사용) */
const DEFAULT_TOTAL_ROWS = 10

/** LS_KEY_IMPORT_FAIL 토글 시 표시할 중단 지점 progress (RUNNING 도달 전 파싱 실패) */
const FAILED_INTERRUPT_PROGRESS = 30

const TERMINAL_STATUSES = new Set<ImportJobStatus['status']>(['COMPLETED', 'FAILED'])

/** jobId → ImportJobRecord 공유 stateful store */
let importJobStore: Map<string, ImportJobRecord> = new Map()

/**
 * Import 작업 store를 초기 상태로 리셋한다.
 *
 * 각 테스트의 `beforeEach`에서 호출해 테스트 간 jobId/진행 상태 leak을 방지한다
 * (cfd-handlers.ts resetCfdStore / bulk-operation-handlers.ts resetBulkOperationState 선례).
 */
export function resetImportStore(): void {
  importJobStore = new Map()
}

/**
 * ImportJobStatus를 store에 직접 시드한다. 동일 jobId가 이미 있으면 덮어쓴다.
 *
 * POST를 거치지 않고 임의 상태(특히 FAILED + errorCode)를 store에 주입할 때 사용한다
 * (예: E2E S3 "seedImportJob(FAILED, IMPORT_PARSE_FAILED)" 시나리오, 단위 테스트의 종단 상태 검증).
 * status가 COMPLETED/FAILED로 시드되면 GET 폴링 핸들러는 더 이상 진행시키지 않고 그대로 반환한다
 * (getImportStatusHandler의 TERMINAL_STATUSES 분기 참고).
 *
 * totalRows/errorCode는 값이 있을 때만 내부 레코드에 포함한다 — 없으면 키 자체를 생략해
 * NON_NULL 직렬화(@JsonInclude(NON_NULL))를 재현한다.
 *
 * @param job 시드할 Import 작업 상태 (importJobStatusSchema로 파싱 가능한 완전한 형태)
 */
export function seedImportJob(job: ImportJobStatus): void {
  importJobStore.set(job.jobId, {
    jobId: job.jobId,
    status: job.status,
    progress: job.progress,
    ...(job.totalRows != null ? { totalRows: job.totalRows } : {}),
    succeededRows: job.succeededRows,
    failedRows: job.failedRows,
    ...(job.errorCode != null ? { errorCode: job.errorCode } : {}),
    errorLogReady: job.errorLogReady,
    dryRun: job.dryRun,
    pollCount: 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 레코드 → 응답 변환
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 내부 store 레코드를 importJobStatusSchema 형태의 응답 객체로 변환한다.
 *
 * totalRows/errorCode는 레코드에 값이 있을 때만 스프레드로 포함한다 — undefined 필드를
 * 그대로 실어 보내면 JSON.stringify가 키를 생략하므로 결과적으로 NON_NULL 직렬화와 동일해진다.
 */
function toResponse(record: ImportJobRecord): ImportJobStatus {
  return {
    jobId: record.jobId,
    status: record.status,
    progress: record.progress,
    ...(record.totalRows !== undefined ? { totalRows: record.totalRows } : {}),
    succeededRows: record.succeededRows,
    failedRows: record.failedRows,
    ...(record.errorCode !== undefined ? { errorCode: record.errorCode } : {}),
    errorLogReady: record.errorLogReady,
    dryRun: record.dryRun,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (attachment-handlers.ts/bulk-operation-handlers.ts 패턴 동일)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 * 3번째 그룹 첫 글자 '4', 4번째 그룹 첫 글자 '8'|'9'|'a'|'b' 보증 —
 * importJobStatusSchema의 z.string().uuid() 형식 검증을 통과해야 한다.
 */
function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/imports
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/imports — CSV/JSON Import 작업을 접수한다.
 *
 * FormData에서 dryRun만 읽어 응답에 반영한다(file/attachmentsZip/projectKey/format은 진행
 * 시뮬레이션에 영향을 주지 않음 — 첨부 전송 여부는 api/imports.test.ts에서 별도 검증).
 * 새 jobId를 발급해 PENDING 상태로 store에 등록하고 202를 반환한다.
 */
const submitImportHandler = http.post('/api/v1/imports', async ({ request }) => {
  let dryRun = false
  try {
    const formData = await request.formData()
    dryRun = formData.get('dryRun') === 'true'
  } catch {
    dryRun = false
  }

  const jobId = generateUuidV4()
  const record: ImportJobRecord = {
    jobId,
    status: 'PENDING',
    progress: 0,
    succeededRows: 0,
    failedRows: 0,
    errorLogReady: false,
    dryRun,
    pollCount: 0,
  }
  importJobStore.set(jobId, record)

  return HttpResponse.json(toResponse(record), { status: 202 })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/imports/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/imports/{id} — 폴링 조회 핸들러 (stateful 진행 시뮬레이션).
 *
 * 종단 상태(COMPLETED/FAILED — seedImportJob으로 직접 시드된 경우 포함)면 추가 진행 없이
 * 그대로 반환한다. LS_KEY_IMPORT_FAIL='true'이면(E2E S3 시나리오) 진행 상태를 무시하고 이
 * 호출에서 즉시 FAILED로 전환해 반환하며, 이후 호출은 TERMINAL_STATUSES 분기로 계속 FAILED를
 * 유지한다(폴링 중단). 플래그가 없으면 이 jobId에 대한 GET 호출 횟수(pollCount)에 따라 진행시킨다.
 * - pollCount=0(첫 GET): PENDING 유지, progress 0
 * - pollCount=1: RUNNING, progress 50, totalRows 확정
 * - pollCount>=2: COMPLETED, progress 100, succeededRows=totalRows, failedRows 0
 *
 * store에 없는 jobId는 404(작업 없음 또는 타인 소유 재현).
 */
const getImportStatusHandler = http.get('/api/v1/imports/:id', ({ params }) => {
  const id = params['id'] as string
  const record = importJobStore.get(id)
  if (!record) {
    return HttpResponse.json(
      { errorCode: 'IMPORT_NOT_FOUND', detail: 'Import 작업을 찾을 수 없습니다.' },
      { status: 404 },
    )
  }

  if (TERMINAL_STATUSES.has(record.status)) {
    return HttpResponse.json(toResponse(record))
  }

  // E2E 시나리오 토글 — FAILED 강제 (LS_KEY_IMPORT_FAIL)
  if (globalThis.localStorage?.getItem(LS_KEY_IMPORT_FAIL) === 'true') {
    record.status = 'FAILED'
    record.progress = FAILED_INTERRUPT_PROGRESS
    record.succeededRows = 0
    record.failedRows = 0
    record.errorCode = 'IMPORT_PARSE_FAILED'
    record.errorLogReady = false
    return HttpResponse.json(toResponse(record))
  }

  const count = record.pollCount
  record.pollCount = count + 1

  if (count === 0) {
    return HttpResponse.json(toResponse(record))
  }
  if (count === 1) {
    record.status = 'RUNNING'
    record.progress = 50
    record.totalRows = DEFAULT_TOTAL_ROWS
    record.succeededRows = Math.floor(DEFAULT_TOTAL_ROWS / 2)
    return HttpResponse.json(toResponse(record))
  }

  record.status = 'COMPLETED'
  record.progress = 100
  record.totalRows = DEFAULT_TOTAL_ROWS
  record.succeededRows = DEFAULT_TOTAL_ROWS
  record.failedRows = 0
  record.errorLogReady = false
  return HttpResponse.json(toResponse(record))
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/imports/:id/errors
// ─────────────────────────────────────────────────────────────────────────────

/** 실패행 에러 로그 최소 CSV 바이트 — UTF-8 BOM 포함 헤더 + 샘플 1행 (search.ts MINIMAL_CSV_BYTES 패턴 미러) */
const MINIMAL_IMPORT_ERROR_CSV_BYTES = new TextEncoder().encode(
  '﻿Row,ErrorCode,Detail\r\n2,IMPORT_VALIDATION_FAILED,summary is required\r\n',
)

/**
 * GET /api/v1/imports/{id}/errors — 완료된 Import 작업의 실패행 에러 로그 CSV를 반환한다.
 *
 * store에 없는 jobId는 404. errorLogReady 여부와 무관하게 store에 jobId만 있으면 CSV를
 * 반환한다(진행 상태 무관 — downloadImportErrorLog는 errorLogReady=true일 때만 호출하도록
 * 계약돼 있으므로 mock에서는 단순화).
 */
const downloadImportErrorsHandler = http.get('/api/v1/imports/:id/errors', ({ params }) => {
  const id = params['id'] as string
  if (!importJobStore.has(id)) {
    return HttpResponse.json(
      { errorCode: 'IMPORT_NOT_FOUND', detail: 'Import 작업을 찾을 수 없습니다.' },
      { status: 404 },
    )
  }

  return new HttpResponse(MINIMAL_IMPORT_ERROR_CSV_BYTES, {
    headers: {
      'Content-Type': 'text/csv; charset=UTF-8',
      'Content-Disposition': `attachment; filename="import-errors-${id.slice(0, 8)}.csv"`,
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** Import(CSV/JSON) BC MSW 핸들러 배열 — handlers.ts에 스프레드로 등록한다 */
export const importHandlers = [
  submitImportHandler,
  getImportStatusHandler,
  downloadImportErrorsHandler,
]
