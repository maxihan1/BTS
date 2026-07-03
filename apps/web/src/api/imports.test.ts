// Import(CSV/JSON) API 클라이언트 단위 테스트 — submitImportJob/fetchImportJobStatus/downloadImportErrorLog + Zod 파싱
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  importJobStatusSchema,
  submitImportJob,
  fetchImportJobStatus,
  downloadImportErrorLog,
  importFailureMessage,
  IMPORT_POLL_INTERVAL_MS,
} from './imports'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 백엔드 ImportJobResponse DTO @JsonInclude(NON_NULL) 직렬화 형태
// ─────────────────────────────────────────────────────────────────────────────

const IMPORT_JOB_ID = '00000000-0000-4000-a000-000000000042'

// PENDING: totalRows/errorCode 키 자체가 없음 (@JsonInclude NON_NULL)
const PENDING_JOB_FIXTURE = {
  jobId: IMPORT_JOB_ID,
  status: 'PENDING',
  progress: 0,
  succeededRows: 0,
  failedRows: 0,
  errorLogReady: false,
  dryRun: false,
}

const RUNNING_JOB_FIXTURE = {
  jobId: IMPORT_JOB_ID,
  status: 'RUNNING',
  progress: 40,
  totalRows: 100,
  succeededRows: 38,
  failedRows: 2,
  errorLogReady: false,
  dryRun: false,
}

const COMPLETED_JOB_FIXTURE = {
  jobId: IMPORT_JOB_ID,
  status: 'COMPLETED',
  progress: 100,
  totalRows: 100,
  succeededRows: 95,
  failedRows: 5,
  errorLogReady: true,
  dryRun: false,
}

const FAILED_JOB_FIXTURE = {
  jobId: IMPORT_JOB_ID,
  status: 'FAILED',
  progress: 23,
  totalRows: 100,
  succeededRows: 10,
  failedRows: 13,
  errorCode: 'IMPORT_PARSE_FAILED',
  errorLogReady: false,
  dryRun: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// importJobStatusSchema — Zod 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('importJobStatusSchema', () => {
  it('PENDING 응답 — totalRows/errorCode 키가 없어도 ZodError 없이 파싱한다 (nullish 검증)', () => {
    const result = importJobStatusSchema.safeParse(PENDING_JOB_FIXTURE)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.jobId).toBe(IMPORT_JOB_ID)
      expect(result.data.status).toBe('PENDING')
      expect(result.data.progress).toBe(0)
      expect(result.data.succeededRows).toBe(0)
      expect(result.data.failedRows).toBe(0)
      expect(result.data.errorLogReady).toBe(false)
      expect(result.data.dryRun).toBe(false)
      expect(result.data.totalRows).toBeUndefined()
      expect(result.data.errorCode).toBeUndefined()
    }
  })

  it('FAILED 응답 — errorCode가 파싱된다', () => {
    const result = importJobStatusSchema.safeParse(FAILED_JOB_FIXTURE)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.status).toBe('FAILED')
      expect(result.data.errorCode).toBe('IMPORT_PARSE_FAILED')
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMPORT_POLL_INTERVAL_MS
// ─────────────────────────────────────────────────────────────────────────────

describe('IMPORT_POLL_INTERVAL_MS', () => {
  it('1500ms 로 고정되어 있다', () => {
    expect(IMPORT_POLL_INTERVAL_MS).toBe(1500)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// submitImportJob — POST /api/v1/imports (multipart, 202)
// ─────────────────────────────────────────────────────────────────────────────

describe('submitImportJob', () => {
  it('multipart FormData로 file/projectKey/format/dryRun 필드를 전송하고 202 응답을 파싱한다', async () => {
    let capturedFile: File | null = null
    let capturedProjectKey: string | null = null
    let capturedFormat: string | null = null
    let capturedDryRun: string | null = null
    let capturedAttachmentsZip: File | null = null

    server.use(
      http.post('/api/v1/imports', async ({ request }) => {
        const fd = await request.formData()
        capturedFile = fd.get('file') as File | null
        capturedProjectKey = fd.get('projectKey') as string | null
        capturedFormat = fd.get('format') as string | null
        capturedDryRun = fd.get('dryRun') as string | null
        capturedAttachmentsZip = fd.get('attachmentsZip') as File | null
        return HttpResponse.json(PENDING_JOB_FIXTURE, { status: 202 })
      }),
    )

    const file = new File(['a,b\n1,2'], 'issues.csv', { type: 'text/csv' })
    const result = await submitImportJob({
      projectKey: 'ATLAS',
      format: 'CSV',
      file,
      dryRun: false,
    })

    expect(capturedFile).not.toBeNull()
    expect(capturedProjectKey).toBe('ATLAS')
    expect(capturedFormat).toBe('CSV')
    expect(capturedDryRun).toBe('false')
    // attachmentsZip을 넘기지 않으면 append하지 않는다
    expect(capturedAttachmentsZip).toBeNull()

    expect(result.jobId).toBe(IMPORT_JOB_ID)
    expect(result.status).toBe('PENDING')
  })

  it('attachmentsZip을 넘기면 FormData에 함께 append된다', async () => {
    let capturedAttachmentsZip: File | null = null

    server.use(
      http.post('/api/v1/imports', async ({ request }) => {
        const fd = await request.formData()
        capturedAttachmentsZip = fd.get('attachmentsZip') as File | null
        return HttpResponse.json(PENDING_JOB_FIXTURE, { status: 202 })
      }),
    )

    const file = new File(['{}'], 'issues.json', { type: 'application/json' })
    const zip = new File(['zip-bytes'], 'attachments.zip', { type: 'application/zip' })
    await submitImportJob({
      projectKey: 'ATLAS',
      format: 'JSON',
      file,
      attachmentsZip: zip,
      dryRun: true,
    })

    expect(capturedAttachmentsZip).not.toBeNull()
  })

  it('403(IMPORT_ACCESS_DENIED) 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/imports', () =>
        HttpResponse.json({ errorCode: 'IMPORT_ACCESS_DENIED' }, { status: 403 }),
      ),
    )

    const file = new File(['a,b'], 'issues.csv', { type: 'text/csv' })
    await expect(
      submitImportJob({ projectKey: 'ATLAS', format: 'CSV', file, dryRun: false }),
    ).rejects.toBeInstanceOf(ApiError)

    await expect(
      submitImportJob({ projectKey: 'ATLAS', format: 'CSV', file, dryRun: false }),
    ).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchImportJobStatus — GET /api/v1/imports/{jobId}
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchImportJobStatus', () => {
  it('RUNNING 상태 — totalRows/succeededRows/failedRows를 정확히 파싱한다', async () => {
    server.use(
      http.get(`/api/v1/imports/${IMPORT_JOB_ID}`, () => HttpResponse.json(RUNNING_JOB_FIXTURE)),
    )

    const result = await fetchImportJobStatus(IMPORT_JOB_ID)

    expect(result.status).toBe('RUNNING')
    expect(result.progress).toBe(40)
    expect(result.totalRows).toBe(100)
    expect(result.succeededRows).toBe(38)
    expect(result.failedRows).toBe(2)
  })

  it('COMPLETED 상태 — errorLogReady=true를 파싱한다', async () => {
    server.use(
      http.get(`/api/v1/imports/${IMPORT_JOB_ID}`, () => HttpResponse.json(COMPLETED_JOB_FIXTURE)),
    )

    const result = await fetchImportJobStatus(IMPORT_JOB_ID)

    expect(result.status).toBe('COMPLETED')
    expect(result.errorLogReady).toBe(true)
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/imports/${IMPORT_JOB_ID}`, () =>
        HttpResponse.json({ errorCode: 'IMPORT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(fetchImportJobStatus(IMPORT_JOB_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchImportJobStatus(IMPORT_JOB_ID)).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// downloadImportErrorLog — GET /api/v1/imports/{jobId}/errors
// ─────────────────────────────────────────────────────────────────────────────

describe('downloadImportErrorLog', () => {
  it('Content-Disposition에서 filename을 파싱하고 blob을 반환한다', async () => {
    const csvContent = 'row,error\n3,IMPORT_VALIDATION_FAILED'
    server.use(
      http.get(`/api/v1/imports/${IMPORT_JOB_ID}/errors`, () =>
        new HttpResponse(csvContent, {
          status: 200,
          headers: {
            'content-type': 'text/csv; charset=UTF-8',
            'content-disposition': 'attachment; filename="ATLAS-1-errors.csv"',
          },
        }),
      ),
    )

    const result = await downloadImportErrorLog(IMPORT_JOB_ID)

    expect(result.blob).toBeTruthy()
    expect(result.blob.size).toBeGreaterThan(0)
    expect(result.filename).toBe('ATLAS-1-errors.csv')
  })

  it('Content-Disposition 헤더가 없으면 기본 파일명을 사용한다', async () => {
    server.use(
      http.get(`/api/v1/imports/${IMPORT_JOB_ID}/errors`, () =>
        new HttpResponse('row,error', {
          status: 200,
          headers: { 'content-type': 'text/csv' },
        }),
      ),
    )

    const result = await downloadImportErrorLog(IMPORT_JOB_ID)

    expect(result.filename).toBe(`${IMPORT_JOB_ID}-errors.csv`)
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/imports/${IMPORT_JOB_ID}/errors`, () =>
        HttpResponse.json({ errorCode: 'IMPORT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(downloadImportErrorLog(IMPORT_JOB_ID)).rejects.toBeInstanceOf(ApiError)
    await expect(downloadImportErrorLog(IMPORT_JOB_ID)).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// importFailureMessage — errorCode → i18n 메시지 매핑
// ─────────────────────────────────────────────────────────────────────────────

describe('importFailureMessage', () => {
  it('알려진 errorCode는 매핑된 한글 메시지를 반환한다', () => {
    expect(importFailureMessage('IMPORT_PARSE_FAILED')).toBe(
      '파일을 파싱하지 못했습니다. 형식을 확인하세요.',
    )
    expect(importFailureMessage('IMPORT_ROW_LIMIT_EXCEEDED')).toBe(
      '행 수가 허용 한도를 초과합니다.',
    )
    expect(importFailureMessage('IMPORT_ACCESS_DENIED')).toBe(
      '이 프로젝트에 이슈를 생성할 권한이 없습니다.',
    )
  })

  it('null/undefined는 알 수 없는 오류 메시지를 반환한다', () => {
    expect(importFailureMessage(null)).toBe('알 수 없는 오류가 발생했습니다.')
    expect(importFailureMessage(undefined)).toBe('알 수 없는 오류가 발생했습니다.')
  })

  it('매핑되지 않은 errorCode는 알 수 없는 오류 메시지를 반환한다', () => {
    expect(importFailureMessage('IMPORT_SOME_NEW_CODE')).toBe('알 수 없는 오류가 발생했습니다.')
  })
})
