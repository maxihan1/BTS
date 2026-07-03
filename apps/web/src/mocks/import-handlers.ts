// Import(CSV/JSON) 작업 MSW 핸들러 — jobId 기준 stateful 진행 시뮬레이션 store (FR-IM-01 D6/D7 Task-5)
import { http, HttpResponse } from 'msw'
import type { ImportJobStatus } from '@/api/imports'

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

const TERMINAL_STATUSES = new Set<ImportJobStatus['status']>(['COMPLETED', 'FAILED'])

/** jobId → ImportJobRecord 공유 stateful store */
let importJobStore: Map<string, ImportJobRecord> = new Map()

export function resetImportStore(): void {
  importJobStore = new Map()
}

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

const MINIMAL_IMPORT_ERROR_CSV_BYTES = new TextEncoder().encode(
  '﻿Row,ErrorCode,Detail\r\n2,IMPORT_VALIDATION_FAILED,summary is required\r\n',
)

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

export const importHandlers = [
  submitImportHandler,
  getImportStatusHandler,
  downloadImportErrorsHandler,
]
