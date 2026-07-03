// Import 작업 MSW 핸들러 단위 테스트 — 계약 drift 가드 + POST/GET stateful 진행 시뮬레이션 검증 (FR-IM-01 D6/D7 Task-5)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { importJobStatusSchema, type ImportJobStatus } from '@/api/imports'
import {
  importHandlers,
  resetImportStore,
  seedImportJob,
  SAMPLE_IMPORT_JOB_COMPLETED,
} from './import-handlers'

// ─────────────────────────────────────────────────────────────────────────────
// MSW 서버 설정 — cfd-handlers.test.ts 선례(개별 setupServer 인스턴스)
// ─────────────────────────────────────────────────────────────────────────────

const server = setupServer(...importHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

beforeEach(() => {
  resetImportStore()
})

afterEach(() => server.resetHandlers())

// ─────────────────────────────────────────────────────────────────────────────
// 계약 drift 가드 — 픽스처가 실제 Zod 응답 스키마를 통과하는지 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('SAMPLE_IMPORT_JOB_COMPLETED 픽스처 계약 검증', () => {
  it('T1-1: importJobStatusSchema로 파싱 가능하다 (MSW↔계약 drift 가드)', () => {
    const parsed = importJobStatusSchema.parse(SAMPLE_IMPORT_JOB_COMPLETED)
    expect(parsed.status).toBe('COMPLETED')
    expect(parsed.progress).toBe(100)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FormData 빌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface ImportSubmitOverrides {
  projectKey?: string
  format?: string
  dryRun?: string
}

function makeImportFormData(overrides: ImportSubmitOverrides = {}): FormData {
  const fd = new FormData()
  fd.append('file', new File(['a,b\n1,2'], 'issues.csv', { type: 'text/csv' }))
  fd.append('projectKey', overrides.projectKey ?? 'ATLAS')
  fd.append('format', overrides.format ?? 'CSV')
  fd.append('dryRun', overrides.dryRun ?? 'false')
  return fd
}

async function submitJob(overrides: ImportSubmitOverrides = {}): Promise<ImportJobStatus> {
  const res = await fetch('/api/v1/imports', { method: 'POST', body: makeImportFormData(overrides) })
  const body: unknown = await res.json()
  return importJobStatusSchema.parse(body)
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/imports
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/imports', () => {
  it('T2-1: 202과 PENDING ImportJobStatus를 반환한다 (totalRows/errorCode 키 생략)', async () => {
    const res = await fetch('/api/v1/imports', { method: 'POST', body: makeImportFormData() })
    expect(res.status).toBe(202)

    const body: unknown = await res.json()
    const parsed = importJobStatusSchema.parse(body)
    expect(parsed.status).toBe('PENDING')
    expect(parsed.progress).toBe(0)
    expect(parsed.totalRows).toBeUndefined()
    expect(parsed.errorCode).toBeUndefined()
    expect(parsed.dryRun).toBe(false)
  })

  it('T2-2: dryRun=true FormData를 보내면 응답 dryRun도 true다', async () => {
    const parsed = await submitJob({ dryRun: 'true' })
    expect(parsed.dryRun).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/imports/:id — 진행 시뮬레이션
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/imports/:id — 진행 시뮬레이션', () => {
  it('T3-1: 첫 GET은 PENDING을 유지한다', async () => {
    const job = await submitJob()
    const res = await fetch(`/api/v1/imports/${job.jobId}`)
    expect(res.status).toBe(200)
    const parsed = importJobStatusSchema.parse(await res.json())
    expect(parsed.status).toBe('PENDING')
  })

  it('T3-2: 두 번째 GET은 RUNNING으로 전이한다', async () => {
    const job = await submitJob()
    await fetch(`/api/v1/imports/${job.jobId}`)
    const res = await fetch(`/api/v1/imports/${job.jobId}`)
    const parsed = importJobStatusSchema.parse(await res.json())
    expect(parsed.status).toBe('RUNNING')
    expect(parsed.progress).toBe(50)
    expect(parsed.totalRows).toBeGreaterThan(0)
  })

  it('T3-3: 세 번째 GET부터 COMPLETED로 종단한다', async () => {
    const job = await submitJob()
    await fetch(`/api/v1/imports/${job.jobId}`)
    await fetch(`/api/v1/imports/${job.jobId}`)
    const res = await fetch(`/api/v1/imports/${job.jobId}`)
    const parsed = importJobStatusSchema.parse(await res.json())
    expect(parsed.status).toBe('COMPLETED')
    expect(parsed.progress).toBe(100)
    expect(parsed.succeededRows).toBe(parsed.totalRows)
    expect(parsed.failedRows).toBe(0)
  })

  it('T3-4: COMPLETED 이후 추가 GET에서도 종단 상태가 유지된다', async () => {
    const job = await submitJob()
    await fetch(`/api/v1/imports/${job.jobId}`)
    await fetch(`/api/v1/imports/${job.jobId}`)
    await fetch(`/api/v1/imports/${job.jobId}`)
    const res = await fetch(`/api/v1/imports/${job.jobId}`)
    const parsed = importJobStatusSchema.parse(await res.json())
    expect(parsed.status).toBe('COMPLETED')
  })

  it('T3-5: store에 없는 jobId는 404를 반환한다', async () => {
    const res = await fetch('/api/v1/imports/00000000-0000-4000-a000-000000000000')
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// seedImportJob / resetImportStore
// ─────────────────────────────────────────────────────────────────────────────

describe('seedImportJob / resetImportStore', () => {
  it('T4-1: FAILED 상태로 시드하면 GET이 그대로 반환하고 추가 진행이 없다', async () => {
    const jobId = '11111111-0000-4000-a000-000000000001'
    seedImportJob({
      jobId,
      status: 'FAILED',
      progress: 0,
      succeededRows: 0,
      failedRows: 0,
      errorCode: 'IMPORT_PARSE_FAILED',
      errorLogReady: false,
      dryRun: false,
    })

    const res = await fetch(`/api/v1/imports/${jobId}`)
    const parsed = importJobStatusSchema.parse(await res.json())
    expect(parsed.status).toBe('FAILED')
    expect(parsed.errorCode).toBe('IMPORT_PARSE_FAILED')

    const res2 = await fetch(`/api/v1/imports/${jobId}`)
    const parsed2 = importJobStatusSchema.parse(await res2.json())
    expect(parsed2.status).toBe('FAILED')
  })

  it('T4-2: resetImportStore 이후에는 이전 시드가 조회되지 않는다(404)', async () => {
    const jobId = '22222222-0000-4000-a000-000000000002'
    seedImportJob({
      jobId,
      status: 'PENDING',
      progress: 0,
      succeededRows: 0,
      failedRows: 0,
      errorLogReady: false,
      dryRun: false,
    })
    resetImportStore()

    const res = await fetch(`/api/v1/imports/${jobId}`)
    expect(res.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/imports/:id/errors
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/imports/:id/errors', () => {
  it('T5-1: 존재하는 jobId면 text/csv + Content-Disposition attachment를 반환한다', async () => {
    const job = await submitJob()

    const errorsRes = await fetch(`/api/v1/imports/${job.jobId}/errors`)
    expect(errorsRes.status).toBe(200)
    expect(errorsRes.headers.get('content-type')).toContain('text/csv')
    expect(errorsRes.headers.get('content-disposition')).toContain('attachment')

    const blob = await errorsRes.blob()
    expect(blob.size).toBeGreaterThan(0)
  })

  it('T5-2: 존재하지 않는 jobId면 404를 반환한다', async () => {
    const res = await fetch('/api/v1/imports/00000000-0000-4000-a000-000000000099/errors')
    expect(res.status).toBe(404)
  })
})
