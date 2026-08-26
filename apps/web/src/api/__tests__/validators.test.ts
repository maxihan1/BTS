// 전환 규칙(validator) API 클라이언트 단위 테스트 — MSW 인라인 핸들러로 HTTP 를 가로채고 zod 계약을 검증
import { describe, it, expect } from 'vitest'
import { ZodError } from 'zod'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  validatorResponseSchema,
  validatorRequestSchema,
  VALIDATOR_CONFIG_FORM_SCHEMAS,
  validatorConfigFormSchema,
  fetchValidators,
  createValidator,
  updateValidator,
  deleteValidator,
  ValidatorApiError,
} from '../validators'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — ValidatorResponse (backend `ValidatorDtos.kt` 6필드 1:1)
// zod 4.x `uuid()` 검증을 통과하도록 RFC 4122 v4 형식 UUID 를 쓴다.
// 경로 세그먼트는 **전환 id(UUID)** 다 — 종전 합성 키는 새 소비자가 쓰지 않는다(제약 C4).
// ─────────────────────────────────────────────────────────────────────────────

const WORKFLOW_KEY = 'software-default'
const TRANSITION_ID = 'd1e2f3a4-b5c6-4d7e-8f90-1a2b3c4d5e6f'
const BASE = `/api/v1/workflows/${WORKFLOW_KEY}/transitions/${TRANSITION_ID}/validators`

/** 편집 가능한 정상 행. `RequiredField` 만 EXECUTION 을 명시 override 한다. */
const requiredFieldFixture = {
  id: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  type: 'RequiredField',
  config: { field: 'resolution' },
  displayOrder: 0,
  phase: 'EXECUTION',
  editable: true,
}

/** 편집 불가 행 — `CustomExpression` 은 허용 목록 밖이라 `editable=false` 로 온다. */
const customExpressionFixture = {
  id: 'b2c3d4e5-f6a7-4901-bcde-f01234567891',
  type: 'CustomExpression',
  config: { expression: "issue.assignee != null" },
  displayOrder: 1,
  phase: 'AVAILABILITY',
  editable: false,
}

/** 인스턴스화 실패 행 (S5) — `phase=null` 과 `editable=false` 가 짝을 이룬다. */
const brokenRowFixture = {
  id: 'c3d4e5f6-a7b8-4012-9def-012345678902',
  type: 'RequiredField',
  config: {},
  displayOrder: 2,
  phase: null,
  editable: false,
}

/**
 * 응답에서 키 하나를 뺀 사본을 만든다. 백엔드가 필드를 빼는 날의 계약 갭을 흉내낸다.
 *
 * @param source 원본 응답 객체.
 * @param key 뺄 키.
 * @return 그 키가 없는 사본.
 */
function omitKey(source: Record<string, unknown>, key: string): Record<string, unknown> {
  const copy = { ...source }
  delete copy[key]
  return copy
}

// ─────────────────────────────────────────────────────────────────────────────
// T4-1. validatorResponseSchema — 백엔드 DTO 6필드 · `.strict()` 봉인
// ─────────────────────────────────────────────────────────────────────────────

describe('validatorResponseSchema', () => {
  it('T4-1a: validatorResponseSchema 가 백엔드 6필드를 받는다', () => {
    const result = validatorResponseSchema.parse(requiredFieldFixture)

    expect(result.id).toBe('a1b2c3d4-e5f6-4890-abcd-ef1234567890')
    expect(result.type).toBe('RequiredField')
    expect(result.config).toEqual({ field: 'resolution' })
    expect(result.displayOrder).toBe(0)
    expect(result.phase).toBe('EXECUTION')
    expect(result.editable).toBe(true)
  })

  it('T4-1b: editable 이 빠진 응답을 거부한다', () => {
    expect(() => validatorResponseSchema.parse(omitKey(requiredFieldFixture, 'editable'))).toThrow(
      ZodError,
    )
  })

  it('T4-1c: phase=null 을 받아들인다', () => {
    const result = validatorResponseSchema.parse(brokenRowFixture)
    expect(result.phase).toBeNull()
    expect(result.editable).toBe(false)
  })

  it('T4-1d: phase 가 AVAILABILITY·EXECUTION 이 아니면 거부한다', () => {
    expect(() =>
      validatorResponseSchema.parse({ ...requiredFieldFixture, phase: 'SOMEDAY' }),
    ).toThrow(ZodError)
  })

  it('T4-1e: 알 수 없는 필드가 오면 거부한다', () => {
    expect(() =>
      validatorResponseSchema.parse({ ...requiredFieldFixture, transitionId: TRANSITION_ID }),
    ).toThrow(ZodError)
  })

  it('T4-1f: config 안쪽 키는 봉인하지 않는다 — type 마다 다르다', () => {
    const result = validatorResponseSchema.parse({
      ...requiredFieldFixture,
      config: { field: 'resolution', 손으로넣은키: 1 },
    })
    expect(result.config).toEqual({ field: 'resolution', 손으로넣은키: 1 })
  })

  it('T4-1g: id 가 UUID 형식이 아니면 거부한다', () => {
    expect(() => validatorResponseSchema.parse({ ...requiredFieldFixture, id: 'not-a-uuid' })).toThrow(
      ZodError,
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-2. validatorRequestSchema — `ValidatorRequest(type, config, displayOrder)`
// ─────────────────────────────────────────────────────────────────────────────

describe('validatorRequestSchema', () => {
  it('T4-2a: 3필드를 모두 파싱한다', () => {
    const result = validatorRequestSchema.parse({
      type: 'RequiredField',
      config: { field: 'resolution' },
      displayOrder: 3,
    })
    expect(result.type).toBe('RequiredField')
    expect(result.config).toEqual({ field: 'resolution' })
    expect(result.displayOrder).toBe(3)
  })

  it('T4-2b: config·displayOrder 는 백엔드와 같은 기본값을 가진다', () => {
    const result = validatorRequestSchema.parse({ type: 'RequiredField' })
    expect(result.config).toEqual({})
    expect(result.displayOrder).toBe(0)
  })

  it('T4-2c: type 이 빈 문자열이면 거부한다', () => {
    expect(() => validatorRequestSchema.parse({ type: '' })).toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-3. type 별 config 폼 스키마 — SDD §7.3 표의 `필수 config 키` 열과 같아야 한다
// ─────────────────────────────────────────────────────────────────────────────

describe('VALIDATOR_CONFIG_FORM_SCHEMAS', () => {
  it('T4-3a: RequiredField 는 field 를 요구한다', () => {
    const schema = VALIDATOR_CONFIG_FORM_SCHEMAS.RequiredField
    expect(schema.parse({ field: 'resolution' })).toEqual({ field: 'resolution' })
    expect(() => schema.parse({})).toThrow(ZodError)
  })

  it('T4-3b: permission-check 는 permission 필수 · scope 선택이다', () => {
    const schema = VALIDATOR_CONFIG_FORM_SCHEMAS['permission-check']
    expect(schema.parse({ permission: 'TRANSITION_ISSUE' })).toEqual({
      permission: 'TRANSITION_ISSUE',
    })
    expect(schema.parse({ permission: 'TRANSITION_ISSUE', scope: 'PROJECT' })).toEqual({
      permission: 'TRANSITION_ISSUE',
      scope: 'PROJECT',
    })
    expect(() => schema.parse({ scope: 'PROJECT' })).toThrow(ZodError)
  })

  it('T4-3c: not-status-category 는 category 를 요구한다', () => {
    const schema = VALIDATOR_CONFIG_FORM_SCHEMAS['not-status-category']
    expect(schema.parse({ category: 'DONE' })).toEqual({ category: 'DONE' })
    expect(() => schema.parse({})).toThrow(ZodError)
  })

  it('T4-3d: CustomExpression 은 폼 스키마를 갖지 않는다 — 편집 불가 type 이다', () => {
    expect(validatorConfigFormSchema('CustomExpression')).toBeUndefined()
  })

  it('T4-3e: 모르는 type 은 undefined 다 — 폼을 추측해 그리지 않는다 (E3)', () => {
    expect(validatorConfigFormSchema('SomeFutureValidator')).toBeUndefined()
  })

  it('T4-3f: 아는 type 은 폼 스키마를 돌려준다', () => {
    expect(validatorConfigFormSchema('RequiredField')).toBe(VALIDATOR_CONFIG_FORM_SCHEMAS.RequiredField)
  })

  it('T4-3g: 폼이 모르는 config 키는 파싱에서 떨어진다 — 보존은 baseline 병합의 몫이다 (C6)', () => {
    const schema = VALIDATOR_CONFIG_FORM_SCHEMAS.RequiredField
    expect(schema.parse({ field: 'resolution', 손으로넣은키: 1 })).toEqual({ field: 'resolution' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-4. fetchValidators — GET → { data: ValidatorResponse[] }
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchValidators', () => {
  it('T4-4a: {data:[...]} 래퍼를 언래핑해 목록을 반환한다', async () => {
    server.use(
      http.get(BASE, () =>
        HttpResponse.json({
          data: [requiredFieldFixture, customExpressionFixture, brokenRowFixture],
        }),
      ),
    )
    const result = await fetchValidators(WORKFLOW_KEY, TRANSITION_ID)

    expect(result).toHaveLength(3)
    expect(result[0]).toMatchObject({ type: 'RequiredField', phase: 'EXECUTION', editable: true })
    expect(result[1]).toMatchObject({ type: 'CustomExpression', editable: false })
    expect(result[2]).toMatchObject({ phase: null, editable: false })
  })

  it('T4-4b: 빈 목록이면 빈 배열을 반환한다', async () => {
    server.use(http.get(BASE, () => HttpResponse.json({ data: [] })))
    await expect(fetchValidators(WORKFLOW_KEY, TRANSITION_ID)).resolves.toHaveLength(0)
  })

  it('T4-4c: 경로 세그먼트에 전환 id(UUID) 를 그대로 싣는다', async () => {
    let requestedPath = ''
    server.use(
      http.get(BASE, ({ request }) => {
        requestedPath = new URL(request.url).pathname
        return HttpResponse.json({ data: [] })
      }),
    )
    await fetchValidators(WORKFLOW_KEY, TRANSITION_ID)
    expect(requestedPath).toBe(BASE)
  })

  it('T4-4d: 403 이면 ValidatorApiError(WORKFLOW_SCHEME_ACCESS_DENIED) 를 throw 한다', async () => {
    server.use(
      http.get(BASE, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_SCHEME_ACCESS_DENIED', message: '권한 없음' } },
          { status: 403 },
        ),
      ),
    )
    const err = await fetchValidators(WORKFLOW_KEY, TRANSITION_ID).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ValidatorApiError)
    const apiErr = err as ValidatorApiError
    expect(apiErr.status).toBe(403)
    expect(apiErr.errorCode).toBe('WORKFLOW_SCHEME_ACCESS_DENIED')
  })

  it('T4-4e: 응답 원소가 계약과 다르면 ZodError 로 드러난다', async () => {
    server.use(
      http.get(BASE, () => HttpResponse.json({ data: [omitKey(requiredFieldFixture, 'editable')] })),
    )
    await expect(fetchValidators(WORKFLOW_KEY, TRANSITION_ID)).rejects.toThrow(ZodError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-5. createValidator — POST → 201 → { data: ValidatorResponse }
// ─────────────────────────────────────────────────────────────────────────────

describe('createValidator', () => {
  it('T4-5a: 201 응답을 파싱하고 요청 바디를 그대로 보낸다', async () => {
    let sentBody: unknown = null
    server.use(
      http.post(BASE, async ({ request }) => {
        sentBody = await request.json()
        return HttpResponse.json({ data: requiredFieldFixture }, { status: 201 })
      }),
    )
    const result = await createValidator(WORKFLOW_KEY, TRANSITION_ID, {
      type: 'RequiredField',
      config: { field: 'resolution' },
      displayOrder: 0,
    })

    expect(sentBody).toEqual({
      type: 'RequiredField',
      config: { field: 'resolution' },
      displayOrder: 0,
    })
    expect(result.id).toBe(requiredFieldFixture.id)
    expect(result.editable).toBe(true)
  })

  it('T4-5b: 400 본문의 error.code 를 뽑아낸다', async () => {
    server.use(
      http.post(BASE, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_VALIDATOR_INVALID', message: "config['field'] 가 없습니다" } },
          { status: 400 },
        ),
      ),
    )
    const err = await createValidator(WORKFLOW_KEY, TRANSITION_ID, {
      type: 'RequiredField',
      config: {},
      displayOrder: 0,
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ValidatorApiError)
    const apiErr = err as ValidatorApiError
    expect(apiErr.status).toBe(400)
    expect(apiErr.errorCode).toBe('WORKFLOW_VALIDATOR_INVALID')
    expect(apiErr.detail).toBe("config['field'] 가 없습니다")
  })

  it('T4-5c: 봉투가 아닌 본문이면 code 를 UNKNOWN 으로 둔다', async () => {
    server.use(http.post(BASE, () => new HttpResponse('502 Bad Gateway', { status: 502 })))
    const err = await createValidator(WORKFLOW_KEY, TRANSITION_ID, {
      type: 'RequiredField',
      config: {},
      displayOrder: 0,
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ValidatorApiError)
    expect((err as ValidatorApiError).errorCode).toBe('UNKNOWN')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-6. updateValidator — PUT /{id} → 200 → { data: ValidatorResponse }
// ─────────────────────────────────────────────────────────────────────────────

describe('updateValidator', () => {
  it('T4-6a: 200 응답을 파싱해 수정된 행을 반환한다', async () => {
    const updated = { ...requiredFieldFixture, config: { field: 'assignee' } }
    server.use(http.put(`${BASE}/${requiredFieldFixture.id}`, () => HttpResponse.json({ data: updated })))

    const result = await updateValidator(WORKFLOW_KEY, TRANSITION_ID, requiredFieldFixture.id, {
      type: 'RequiredField',
      config: { field: 'assignee' },
      displayOrder: 0,
    })
    expect(result.config).toEqual({ field: 'assignee' })
  })

  it('T4-6b: 편집 불가 type 은 400 WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE 로 온다', async () => {
    server.use(
      http.put(`${BASE}/${customExpressionFixture.id}`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE', message: '편집할 수 없는 type' } },
          { status: 400 },
        ),
      ),
    )
    const err = await updateValidator(WORKFLOW_KEY, TRANSITION_ID, customExpressionFixture.id, {
      type: 'CustomExpression',
      config: { expression: '1 == 1' },
      displayOrder: 1,
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ValidatorApiError)
    expect((err as ValidatorApiError).errorCode).toBe('WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE')
  })

  it('T4-6c: 404 면 ValidatorApiError(WORKFLOW_VALIDATOR_NOT_FOUND) 를 throw 한다', async () => {
    server.use(
      http.put(`${BASE}/${requiredFieldFixture.id}`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_VALIDATOR_NOT_FOUND', message: '없음' } },
          { status: 404 },
        ),
      ),
    )
    const err = await updateValidator(WORKFLOW_KEY, TRANSITION_ID, requiredFieldFixture.id, {
      type: 'RequiredField',
      config: { field: 'resolution' },
      displayOrder: 0,
    }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ValidatorApiError)
    expect((err as ValidatorApiError).status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-7. deleteValidator — DELETE /{id} → 204 (빈 바디)
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteValidator', () => {
  it('T4-7a: 204 무바디 응답을 정상 처리한다', async () => {
    server.use(
      http.delete(`${BASE}/${requiredFieldFixture.id}`, () => new HttpResponse(null, { status: 204 })),
    )
    await expect(
      deleteValidator(WORKFLOW_KEY, TRANSITION_ID, requiredFieldFixture.id),
    ).resolves.toBeUndefined()
  })

  it('T4-7b: 403 이면 ValidatorApiError 를 throw 한다', async () => {
    server.use(
      http.delete(`${BASE}/${requiredFieldFixture.id}`, () =>
        HttpResponse.json(
          { error: { code: 'WORKFLOW_SCHEME_ACCESS_DENIED', message: '권한 없음' } },
          { status: 403 },
        ),
      ),
    )
    const err = await deleteValidator(WORKFLOW_KEY, TRANSITION_ID, requiredFieldFixture.id).catch(
      (e: unknown) => e,
    )
    expect(err).toBeInstanceOf(ValidatorApiError)
    expect((err as ValidatorApiError).status).toBe(403)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4-8. ValidatorApiError — status·errorCode·detail 보존
// ─────────────────────────────────────────────────────────────────────────────

describe('ValidatorApiError', () => {
  it('T4-8a: status·errorCode·detail 을 보존한다', () => {
    const err = new ValidatorApiError(400, 'WORKFLOW_VALIDATOR_INVALID', '유효하지 않음')
    expect(err.status).toBe(400)
    expect(err.errorCode).toBe('WORKFLOW_VALIDATOR_INVALID')
    expect(err.detail).toBe('유효하지 않음')
    expect(err.message).toContain('WORKFLOW_VALIDATOR_INVALID')
    expect(err).toBeInstanceOf(Error)
  })

  it('T4-8b: name 이 ValidatorApiError 다', () => {
    expect(new ValidatorApiError(404, 'WORKFLOW_VALIDATOR_NOT_FOUND', '없음').name).toBe(
      'ValidatorApiError',
    )
  })
})
