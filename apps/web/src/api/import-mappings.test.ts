// Import 매핑 마법사 API 클라이언트 단위 테스트 — analyze/validate/collectUsers/collectValues/confirm + Zod 파싱
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import { importJobStatusSchema } from './imports'
import {
  importAnalysisResponseSchema,
  mappingValidationResponseSchema,
  userCollectionResponseSchema,
  valueCollectionResponseSchema,
  analyzeImport,
  validateFieldMapping,
  collectUsers,
  collectValues,
  confirmMapping,
  IMPORT_MAPPING_ERROR_CODES,
  importMappingFailureMessage,
} from './import-mappings'

const JOB_ID = '00000000-0000-4000-a000-000000000099'
const USER_ID = '00000000-0000-4000-a000-000000000001'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 백엔드 DTO @JsonInclude(NON_NULL) 직렬화 형태
// ─────────────────────────────────────────────────────────────────────────────

const ANALYSIS_FIXTURE = {
  jobId: JOB_ID,
  status: 'AWAITING_MAPPING',
  format: 'CSV',
  sourceFields: [{ name: 'Summary' }, { name: 'Status' }],
  sampleRows: [
    ['로그인 실패', 'Open'],
    ['배포 실패', 'Closed'],
  ],
  targetFields: [
    { key: 'summary', label: '제목', required: true, multi: false },
    { key: 'IGNORE', label: '매핑 안 함', required: false, multi: false },
  ],
}

// field 생략 — 전역 이슈(NON_NULL)
const VALIDATION_FIXTURE = {
  valid: false,
  errors: [{ code: 'SUMMARY_NOT_MAPPED', message: 'summary가 매핑되지 않았습니다.' }],
  warnings: [{ code: 'SOURCE_FIELD_IGNORED', message: '무시된 필드가 있습니다.', field: 'Extra' }],
}

// suggestedUserId/suggestedDisplayName 생략 — 추천 없는 케이스(NON_NULL)
const USER_COLLECTION_FIXTURE = {
  users: [
    { sourceIdentifier: 'alice@example.com', suggestedUserId: USER_ID, suggestedDisplayName: 'Alice' },
    { sourceIdentifier: 'ghost@example.com' },
  ],
}

// suggestedTargetValue 생략 — 추천 없는 케이스(NON_NULL)
const VALUE_COLLECTION_FIXTURE = {
  fields: [
    {
      targetField: 'STATUS',
      values: [
        { sourceValue: 'Open', suggestedTargetValue: 'OPEN' },
        { sourceValue: 'Unknown' },
      ],
    },
    { targetField: 'PRIORITY', values: [] },
  ],
}

const PENDING_JOB_FIXTURE = {
  jobId: JOB_ID,
  status: 'PENDING',
  progress: 0,
  succeededRows: 0,
  failedRows: 0,
  errorLogReady: false,
  dryRun: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('importAnalysisResponseSchema', () => {
  it('정상 payload를 파싱한다', () => {
    const result = importAnalysisResponseSchema.safeParse(ANALYSIS_FIXTURE)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.status).toBe('AWAITING_MAPPING')
      expect(result.data.sourceFields).toHaveLength(2)
      expect(result.data.targetFields[0]?.key).toBe('summary')
    }
  })

  it('sampleRows 빈 배열(JSON)을 허용한다', () => {
    const result = importAnalysisResponseSchema.safeParse({ ...ANALYSIS_FIXTURE, format: 'JSON', sampleRows: [] })
    expect(result.success).toBe(true)
  })

  it('status가 AWAITING_MAPPING이 아니면 reject한다', () => {
    const result = importAnalysisResponseSchema.safeParse({ ...ANALYSIS_FIXTURE, status: 'PENDING' })
    expect(result.success).toBe(false)
  })

  it('jobId가 UUID가 아니면 reject한다', () => {
    const result = importAnalysisResponseSchema.safeParse({ ...ANALYSIS_FIXTURE, jobId: 'not-a-uuid' })
    expect(result.success).toBe(false)
  })
})

describe('mappingValidationResponseSchema', () => {
  it('정상 payload를 파싱하고, field 생략(NON_NULL) 항목은 undefined로 통과한다', () => {
    const result = mappingValidationResponseSchema.safeParse(VALIDATION_FIXTURE)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.valid).toBe(false)
      expect(result.data.errors[0]?.code).toBe('SUMMARY_NOT_MAPPED')
      expect(result.data.errors[0]?.field).toBeUndefined()
      expect(result.data.warnings[0]?.field).toBe('Extra')
    }
  })

  it('errors/warnings에 code가 없으면 reject한다', () => {
    const result = mappingValidationResponseSchema.safeParse({
      valid: true,
      errors: [{ message: '메시지만 있음' }],
      warnings: [],
    })
    expect(result.success).toBe(false)
  })
})

describe('userCollectionResponseSchema', () => {
  it('정상 payload를 파싱하고, 추천 필드 생략(NON_NULL) 항목은 undefined로 통과한다', () => {
    const result = userCollectionResponseSchema.safeParse(USER_COLLECTION_FIXTURE)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.users[0]?.suggestedUserId).toBe(USER_ID)
      expect(result.data.users[1]?.suggestedUserId).toBeUndefined()
      expect(result.data.users[1]?.suggestedDisplayName).toBeUndefined()
    }
  })

  it('suggestedUserId가 UUID가 아니면 reject한다', () => {
    const result = userCollectionResponseSchema.safeParse({
      users: [{ sourceIdentifier: 'a@b.com', suggestedUserId: 'not-a-uuid' }],
    })
    expect(result.success).toBe(false)
  })
})

describe('valueCollectionResponseSchema', () => {
  it('정상 payload를 파싱하고, 추천값 생략(NON_NULL) 항목은 undefined로 통과한다', () => {
    const result = valueCollectionResponseSchema.safeParse(VALUE_COLLECTION_FIXTURE)
    expect(result.success).toBe(true)
    if (result.success) {
      expect(result.data.fields[0]?.targetField).toBe('STATUS')
      expect(result.data.fields[0]?.values[1]?.suggestedTargetValue).toBeUndefined()
      expect(result.data.fields[1]?.values).toHaveLength(0)
    }
  })

  it('targetField가 카탈로그 밖 값이면 reject한다', () => {
    const result = valueCollectionResponseSchema.safeParse({
      fields: [{ targetField: 'ASSIGNEE', values: [] }],
    })
    expect(result.success).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// analyzeImport — POST /api/v1/imports/analyze (multipart, 200)
// ─────────────────────────────────────────────────────────────────────────────

describe('analyzeImport', () => {
  it('multipart FormData로 file/projectKey/format을 전송하고 200 응답을 파싱한다', async () => {
    let capturedFile: File | null = null
    let capturedProjectKey: string | null = null
    let capturedFormat: string | null = null

    server.use(
      http.post('/api/v1/imports/analyze', async ({ request }) => {
        const fd = await request.formData()
        capturedFile = fd.get('file') as File | null
        capturedProjectKey = fd.get('projectKey') as string | null
        capturedFormat = fd.get('format') as string | null
        return HttpResponse.json(ANALYSIS_FIXTURE, { status: 200 })
      }),
    )

    const file = new File(['a,b\n1,2'], 'issues.csv', { type: 'text/csv' })
    const result = await analyzeImport({ projectKey: 'ATLAS', format: 'CSV', file })

    expect(capturedFile).not.toBeNull()
    expect(capturedProjectKey).toBe('ATLAS')
    expect(capturedFormat).toBe('CSV')
    expect(result.jobId).toBe(JOB_ID)
    expect(result.status).toBe('AWAITING_MAPPING')
  })

  it('403(IMPORT_ACCESS_DENIED) 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/imports/analyze', () =>
        HttpResponse.json({ errorCode: 'IMPORT_ACCESS_DENIED' }, { status: 403 }),
      ),
    )

    const file = new File(['a,b'], 'issues.csv', { type: 'text/csv' })
    await expect(analyzeImport({ projectKey: 'ATLAS', format: 'CSV', file })).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// validateFieldMapping — POST /api/v1/imports/{jobId}/mapping/validate
// ─────────────────────────────────────────────────────────────────────────────

describe('validateFieldMapping', () => {
  it('fieldMappings 배열을 body로 전송하고 응답을 파싱한다', async () => {
    let capturedBody: unknown = null

    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping/validate`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(VALIDATION_FIXTURE)
      }),
    )

    const result = await validateFieldMapping(JOB_ID, [{ sourceField: 'Summary', targetField: 'summary' }])

    expect(capturedBody).toEqual({ fieldMappings: [{ sourceField: 'Summary', targetField: 'summary' }] })
    expect(result.valid).toBe(false)
  })

  it('409(IMPORT_MAPPING_STATE_CONFLICT) 응답 시 ApiError(409)를 throw한다', async () => {
    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping/validate`, () =>
        HttpResponse.json({ errorCode: 'IMPORT_MAPPING_STATE_CONFLICT' }, { status: 409 }),
      ),
    )

    await expect(validateFieldMapping(JOB_ID, [])).rejects.toMatchObject({ status: 409 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// collectUsers — POST /api/v1/imports/{jobId}/mapping/users
// ─────────────────────────────────────────────────────────────────────────────

describe('collectUsers', () => {
  it('fieldMappings 배열을 body로 전송하고 응답을 파싱한다', async () => {
    let capturedBody: unknown = null

    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping/users`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(USER_COLLECTION_FIXTURE)
      }),
    )

    const result = await collectUsers(JOB_ID, [{ sourceField: 'Reporter', targetField: 'reporter' }])

    expect(capturedBody).toEqual({ fieldMappings: [{ sourceField: 'Reporter', targetField: 'reporter' }] })
    expect(result.users).toHaveLength(2)
  })

  it('422(IMPORT_MAPPING_INVALID) 응답 시 ApiError(422)를 throw한다', async () => {
    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping/users`, () =>
        HttpResponse.json({ errorCode: 'IMPORT_MAPPING_INVALID' }, { status: 422 }),
      ),
    )

    await expect(collectUsers(JOB_ID, [])).rejects.toMatchObject({ status: 422 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// collectValues — POST /api/v1/imports/{jobId}/mapping/values
// ─────────────────────────────────────────────────────────────────────────────

describe('collectValues', () => {
  it('fieldMappings 배열을 body로 전송하고 응답을 파싱한다', async () => {
    let capturedBody: unknown = null

    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping/values`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(VALUE_COLLECTION_FIXTURE)
      }),
    )

    const result = await collectValues(JOB_ID, [{ sourceField: 'Status', targetField: 'status' }])

    expect(capturedBody).toEqual({ fieldMappings: [{ sourceField: 'Status', targetField: 'status' }] })
    expect(result.fields).toHaveLength(2)
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping/values`, () =>
        HttpResponse.json({ errorCode: 'IMPORT_NOT_FOUND' }, { status: 404 }),
      ),
    )

    await expect(collectValues(JOB_ID, [])).rejects.toMatchObject({ status: 404 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// confirmMapping — POST /api/v1/imports/{jobId}/mapping (importJobStatusSchema 재사용)
// ─────────────────────────────────────────────────────────────────────────────

describe('confirmMapping', () => {
  it('fieldMappings/dryRun/userMappings/valueMappings를 body로 전송하고 importJobStatusSchema로 파싱한다', async () => {
    let capturedBody: unknown = null

    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(PENDING_JOB_FIXTURE)
      }),
    )

    const result = await confirmMapping(JOB_ID, {
      fieldMappings: [{ sourceField: 'Summary', targetField: 'summary' }],
      dryRun: true,
      userMappings: [{ sourceIdentifier: 'alice@example.com', targetUserId: USER_ID }],
      valueMappings: [{ targetField: 'STATUS', sourceValue: 'Open', targetValue: 'OPEN' }],
    })

    expect(capturedBody).toEqual({
      fieldMappings: [{ sourceField: 'Summary', targetField: 'summary' }],
      dryRun: true,
      userMappings: [{ sourceIdentifier: 'alice@example.com', targetUserId: USER_ID }],
      valueMappings: [{ targetField: 'STATUS', sourceValue: 'Open', targetValue: 'OPEN' }],
    })
    // confirmMapping 응답이 기존 importJobStatusSchema로 파싱 가능함을 재확인 (스키마 재사용 검증)
    expect(importJobStatusSchema.safeParse(result).success).toBe(true)
    expect(result.status).toBe('PENDING')
  })

  it('dryRun/userMappings/valueMappings 생략 시 기본값(false/[]/[])으로 전송한다', async () => {
    let capturedBody: unknown = null

    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(PENDING_JOB_FIXTURE)
      }),
    )

    await confirmMapping(JOB_ID, { fieldMappings: [] })

    expect(capturedBody).toEqual({
      fieldMappings: [],
      dryRun: false,
      userMappings: [],
      valueMappings: [],
    })
  })

  it('422(IMPORT_VALUE_MAPPING_INVALID) 응답 시 ApiError(422)를 throw한다', async () => {
    server.use(
      http.post(`/api/v1/imports/${JOB_ID}/mapping`, () =>
        HttpResponse.json({ errorCode: 'IMPORT_VALUE_MAPPING_INVALID' }, { status: 422 }),
      ),
    )

    await expect(confirmMapping(JOB_ID, { fieldMappings: [] })).rejects.toBeInstanceOf(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// IMPORT_MAPPING_ERROR_CODES / importMappingFailureMessage
// ─────────────────────────────────────────────────────────────────────────────

describe('IMPORT_MAPPING_ERROR_CODES', () => {
  it('4종 매핑 에러코드에 대한 한글 메시지를 보유한다', () => {
    expect(IMPORT_MAPPING_ERROR_CODES['IMPORT_MAPPING_INVALID']).toBeTruthy()
    expect(IMPORT_MAPPING_ERROR_CODES['IMPORT_USER_MAPPING_INVALID']).toBeTruthy()
    expect(IMPORT_MAPPING_ERROR_CODES['IMPORT_VALUE_MAPPING_INVALID']).toBeTruthy()
    expect(IMPORT_MAPPING_ERROR_CODES['IMPORT_MAPPING_STATE_CONFLICT']).toBeTruthy()
  })
})

describe('importMappingFailureMessage', () => {
  it('알려진 errorCode는 매핑된 한글 메시지를 반환한다', () => {
    expect(importMappingFailureMessage('IMPORT_MAPPING_STATE_CONFLICT')).toBe(
      IMPORT_MAPPING_ERROR_CODES['IMPORT_MAPPING_STATE_CONFLICT'],
    )
  })

  it('null/undefined 또는 매핑되지 않은 errorCode는 공통 fallback 메시지를 반환한다', () => {
    expect(importMappingFailureMessage(null)).toBe('알 수 없는 오류가 발생했습니다.')
    expect(importMappingFailureMessage(undefined)).toBe('알 수 없는 오류가 발생했습니다.')
    expect(importMappingFailureMessage('IMPORT_SOME_NEW_CODE')).toBe('알 수 없는 오류가 발생했습니다.')
  })
})
