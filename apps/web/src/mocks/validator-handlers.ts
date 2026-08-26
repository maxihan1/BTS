// 전환 규칙(validator) CRUD MSW stateful 핸들러 — 저장·조회만 하고 규칙 평가는 하지 않는다
//
// ## 이 목이 하지 않는 일 — 평가 (제약 C3)
// validator 가 전환을 막는지 판단하는 코드를 여기 넣지 않는다. 넣는 순간 백엔드 엔진의 두 번째
// 사본이 되고 아무도 두 벌을 대조하지 않는다. AVAILABILITY 규칙이 전환 후보를 감추는 효과는
// `/transitions/plan` **응답 픽스처**로 표현한다.
//
// ## 이 목이 하는 일 — 계약 감시
// 돌려주는 행은 backend `ValidatorDtos.kt` 의 6필드 전부다. `api/validators.ts` 의 응답 스키마가
// `.strict()` 라 필드를 빠뜨리거나 더하면 파싱이 깨진다 — 그것이 이 목을 느슨하게 만들지 못하게
// 붙잡는 감시자다. 목을 스키마에 맞추지 말고 **백엔드 DTO 에 맞춘다.**
import { http, HttpResponse } from 'msw'
import type { PathParams } from 'msw'
import { validatorConfigFormSchema } from '@/api/validators'
import type { ValidatorPhase, ValidatorResponse } from '@/api/validators'
import { softwareDefaultFixture } from './workflow-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — type 문자열과 에러 코드를 한 곳에서 정의한다
// ─────────────────────────────────────────────────────────────────────────────

/**
 * validator type 식별자 4종 — backend 각 구현체의 `override val type` 과 같은 문자열.
 *
 * 시드·검증·테스트가 이 상수 하나를 공유한다. 문자열을 자리마다 적으면 오타가 난 쪽만 조용히
 * 다른 type 이 되고, 스펙 간에 갈린 사실이 드러나지 않는다.
 */
export const VALIDATOR_TYPES = {
  /** `RequiredFieldValidator` — 필수 필드 검사. 4종 중 유일하게 phase 를 EXECUTION 으로 override 한다. */
  requiredField: 'RequiredField',
  /** `PermissionValidator` — 권한 검사. */
  permissionCheck: 'permission-check',
  /** `NotStatusCategoryValidator` — 특정 상태 카테고리 금지. */
  notStatusCategory: 'not-status-category',
  /** `CustomExpressionValidator` — SpEL 표현식. 편집 허용 목록 밖이라 `editable = false` 다. */
  customExpression: 'CustomExpression',
} as const

/** 에러 코드 — backend `ValidatorExceptionHandler` 의 상수와 같은 문자열. */
const ERROR_CODES = {
  invalidRequest: 'WORKFLOW_INVALID_REQUEST',
  validatorInvalid: 'WORKFLOW_VALIDATOR_INVALID',
  typeNotEditable: 'WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE',
  notFound: 'WORKFLOW_VALIDATOR_NOT_FOUND',
} as const

/** UUID 형식 판정 — backend 는 `{id}` 세그먼트를 UUID 로 파싱하고 실패하면 400 을 낸다. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// ─────────────────────────────────────────────────────────────────────────────
// 시드 좌표 — 워크플로우 픽스처에서 **도출한다**
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 시드를 심는 전환. `workflow-fixtures` 의 software-default 첫 NORMAL 전환(`Start Work`)이다.
 *
 * id 를 여기에 리터럴로 적으면 픽스처와 갈리는 두 번째 목록이 되고, 갈린 순간 화면은
 * 「전환은 있는데 규칙이 하나도 없는」 상태로 조용히 낡는다. 그래서 도출하고, 도출에 실패하면
 * 모듈 로드 시점에 크게 터뜨린다 — 빈 문자열 폴백은 그 사고를 숨긴다.
 */
const seededTransition = softwareDefaultFixture.transitions.find((row) => row.kind === 'NORMAL')
if (seededTransition === undefined) {
  throw new Error(
    'workflow-fixtures 의 software-default 에 NORMAL 전환이 없다 — validator 시드를 심을 자리가 사라졌다.',
  )
}

/** 시드가 들어 있는 워크플로우 키. */
export const SEEDED_VALIDATOR_WORKFLOW_KEY: string = softwareDefaultFixture.key

/** 시드가 들어 있는 전환 id(UUID). 경로 세그먼트에 싣는 것은 이 값뿐이다(제약 C4). */
export const SEEDED_VALIDATOR_TRANSITION_ID: string = seededTransition.id

// ─────────────────────────────────────────────────────────────────────────────
// 시드 행 — 화면 시나리오 S1·S4·S5 가 이 행들을 필요로 한다
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 시드 행 전수의 **원본**. 이 상수를 직접 쓰지 말고 [seedRows] 로 복제해 쓴다.
 *
 * `phase` 는 backend 가 **인스턴스에서 읽는 값**이라 목이 아무 값이나 주면 화면이 거짓을 배운다.
 * 4종 중 `RequiredFieldValidator` 만 `override val phase = EXECUTION` 이고 나머지 셋은 SPI 기본값
 * (`WorkflowValidator.phase = AVAILABILITY`) 을 상속한다.
 *
 * 마지막 행은 **손으로 깨뜨린 행**이다 — `type = RequiredField` 인데 `config` 에 `field` 가 없어
 * 팩토리가 인스턴스를 만들지 못한다. 그 행은 `phase = null` 과 `editable = false` 가 짝을 이룬다.
 */
const SEED_ROWS: readonly ValidatorResponse[] = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    type: VALIDATOR_TYPES.requiredField,
    config: { field: 'resolution' },
    displayOrder: 0,
    phase: 'EXECUTION',
    editable: true,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    type: VALIDATOR_TYPES.permissionCheck,
    config: { permission: 'TRANSITION_ISSUE', scope: 'ISSUE' },
    displayOrder: 1,
    phase: 'AVAILABILITY',
    editable: true,
  },
  {
    id: '33333333-3333-4333-8333-333333333333',
    type: VALIDATOR_TYPES.notStatusCategory,
    config: { category: 'DONE' },
    displayOrder: 2,
    phase: 'AVAILABILITY',
    editable: true,
  },
  {
    id: '44444444-4444-4444-8444-444444444444',
    type: VALIDATOR_TYPES.customExpression,
    config: { expression: 'issue.assignee != null' },
    displayOrder: 3,
    phase: 'AVAILABILITY',
    editable: false,
  },
  {
    id: '55555555-5555-4555-8555-555555555555',
    type: VALIDATOR_TYPES.requiredField,
    config: {},
    displayOrder: 4,
  phase: null,
  editable: false,
},
]

/**
 * 시드 행 전수를 **새 객체로** 만든다.
 *
 * [SEED_ROWS] 를 그대로 돌려주면 한 테스트가 `config` 를 변형했을 때 그 변형이 다음 테스트로 샌다 —
 * `resetValidatorStore` 가 「원래대로」를 보장하지 못하게 된다. 그래서 행과 `config` 를 함께 복제한다.
 *
 * @return 시드 행 배열 (displayOrder 오름차순).
 */
function seedRows(): ValidatorResponse[] {
  return SEED_ROWS.map((row) => ({ ...row, config: { ...row.config } }))
}

// ─────────────────────────────────────────────────────────────────────────────
// Stateful store — `${workflowKey}::${transitionId}` 복합 키
// ─────────────────────────────────────────────────────────────────────────────

const validatorStore = new Map<string, ValidatorResponse[]>()

/** store 키를 만든다. 워크플로우와 전환 둘 다 있어야 목록이 특정된다. */
function storeKey(workflowKey: string, transitionId: string): string {
  return `${workflowKey}::${transitionId}`
}

/**
 * store 를 시드 상태로 되돌린다.
 *
 * 만든 행이 다음 테스트로 새지 않게 유닛 테스트의 `beforeEach` 와 E2E 전용 reset 라우트가
 * 이 함수를 부른다. `clear()` 만 하면 시드까지 사라져 S4·S5 화면이 재현되지 않는다.
 */
export function resetValidatorStore(): void {
  validatorStore.clear()
  validatorStore.set(
    storeKey(SEEDED_VALIDATOR_WORKFLOW_KEY, SEEDED_VALIDATOR_TRANSITION_ID),
    seedRows(),
  )
}

resetValidatorStore()

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 경로 파라미터에서 문자열 하나를 꺼낸다.
 *
 * msw 는 반복 세그먼트를 배열로 주므로 문자열이 아닌 경우가 타입에 남는다. 그 경우는 빈 문자열로
 * 두고 뒤의 UUID·존재 검사가 거절하게 한다.
 *
 * @param params msw 가 준 경로 파라미터.
 * @param name 꺼낼 파라미터 이름.
 * @return 파라미터 값. 문자열이 아니면 빈 문자열.
 */
function pathParam(params: PathParams, name: string): string {
  const value = params[name]
  return typeof value === 'string' ? value : ''
}

/** `{ error: { code, message } }` 봉투 응답을 만든다 — 이 BC 의 에러 계약 한 벌. */
function errorResponse(status: number, code: string, message: string): Response {
  return HttpResponse.json({ error: { code, message } }, { status })
}

/** 임의 값을 config Map 으로 정규화한다. 객체가 아니면 빈 Map 이다. */
function asConfig(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? { ...(value as Record<string, unknown>) }
    : {}
}

/**
 * RFC4122 v4 형식 UUID 를 만든다.
 *
 * 응답 스키마가 `z.string().uuid()` 라 형식이 어긋나면 파싱에서 걸린다
 * (형제 `post-action-handlers.ts` 와 같은 이유·같은 형태).
 */
function generateUuidV4(): string {
  const hex = (): string => Math.floor(Math.random() * 16).toString(16)
  const hex4 = (): string => `${hex()}${hex()}${hex()}${hex()}`
  const variant = (Math.floor(Math.random() * 4) + 8).toString(16) // 8, 9, a, b
  return `${hex4()}${hex4()}-${hex4()}-4${hex()}${hex()}${hex()}-${variant}${hex()}${hex()}${hex()}-${hex4()}${hex4()}${hex4()}`
}

/**
 * 쓰기 가능한 type 의 평가 시점을 돌려준다.
 *
 * 편집 가능 3종에만 쓴다 — 그 밖의 type 은 [readWriteBody] 가 먼저 400 으로 거절하므로 여기까지
 * 오지 않는다. 값의 근거는 backend `RequiredFieldValidator.phase` 의 EXECUTION override 와
 * `WorkflowValidator.phase` 의 AVAILABILITY 기본값이다.
 *
 * @param type validator type 식별자.
 * @return 평가 시점.
 */
function phaseOf(type: string): ValidatorPhase {
  return type === VALIDATOR_TYPES.requiredField ? 'EXECUTION' : 'AVAILABILITY'
}

/** 쓰기 요청 본문을 검증한 결과 — 통과면 정규화한 값, 실패면 그대로 내보낼 에러 응답. */
type WriteBodyResult =
  | { ok: true; type: string; config: Record<string, unknown>; displayOrder: number | null }
  | { ok: false; response: Response }

/**
 * POST/PUT 본문을 읽고 backend 와 같은 순서로 거절한다.
 *
 * 거절 순서 — 본문 형식(400 `WORKFLOW_INVALID_REQUEST`) → 편집 불가 type
 * (400 `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE`) → config 부적합(400 `WORKFLOW_VALIDATOR_INVALID`).
 * 편집 가능 여부와 필수 키는 [validatorConfigFormSchema] 하나에서 나온다 — 목이 자기 목록을
 * 따로 들면 그것이 폼 스키마와 갈리는 세 번째 사본이 된다.
 *
 * @param request msw 가 준 요청.
 * @return 검증 결과.
 */
async function readWriteBody(request: Request): Promise<WriteBodyResult> {
  const raw: unknown = await request.json().catch(() => null)
  if (typeof raw !== 'object' || raw === null) {
    return { ok: false, response: errorResponse(400, ERROR_CODES.invalidRequest, '본문을 읽을 수 없습니다.') }
  }

  const body: Record<string, unknown> = { ...(raw as Record<string, unknown>) }
  const type = body['type']
  if (typeof type !== 'string' || type.length === 0) {
    return { ok: false, response: errorResponse(400, ERROR_CODES.invalidRequest, 'type 은 필수입니다.') }
  }

  const schema = validatorConfigFormSchema(type)
  if (schema === undefined) {
    const message = `이 API 로 편집할 수 없는 validator type 입니다: '${type}'`
    return { ok: false, response: errorResponse(400, ERROR_CODES.typeNotEditable, message) }
  }

  const config = asConfig(body['config'])
  if (!schema.safeParse(config).success) {
    const message = `validator config 가 '${type}' 의 필수 키를 채우지 못했습니다`
    return { ok: false, response: errorResponse(400, ERROR_CODES.validatorInvalid, message) }
  }

  const displayOrder = body['displayOrder']
  return { ok: true, type, config, displayOrder: typeof displayOrder === 'number' ? displayOrder : null }
}

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 — 경로: /api/v1/workflows/:workflowKey/transitions/:transitionId/validators(/:id)
//
// `:transitionId` 라는 이름이 제약 C4 를 드러낸다 — backend 세그먼트는 `{transitionKey}` 지만
// 이 화면의 소비자는 전환 id(UUID) 만 싣는다.
// ─────────────────────────────────────────────────────────────────────────────

const LIST_PATH = '/api/v1/workflows/:workflowKey/transitions/:transitionId/validators'
const ITEM_PATH = `${LIST_PATH}/:id`

export const validatorHandlers = [
  /** GET `…/validators` → 200 `{ data: ValidatorResponse[] }` (displayOrder ASC). */
  http.get(LIST_PATH, ({ params }) => {
    const key = storeKey(pathParam(params, 'workflowKey'), pathParam(params, 'transitionId'))
    const rows = [...(validatorStore.get(key) ?? [])].sort((a, b) => a.displayOrder - b.displayOrder)
    return HttpResponse.json({ data: rows })
  }),

  /** POST `…/validators` → 201 `{ data: ValidatorResponse }`. */
  http.post(LIST_PATH, async ({ params, request }) => {
    const parsed = await readWriteBody(request)
    if (!parsed.ok) {
      return parsed.response
    }

    const key = storeKey(pathParam(params, 'workflowKey'), pathParam(params, 'transitionId'))
    const rows = validatorStore.get(key) ?? []
    const created: ValidatorResponse = {
      id: generateUuidV4(),
      type: parsed.type,
      config: parsed.config,
      displayOrder: parsed.displayOrder ?? rows.length,
      phase: phaseOf(parsed.type),
      editable: true,
    }

    validatorStore.set(key, [...rows, created])
    return HttpResponse.json({ data: created }, { status: 201 })
  }),

  /**
   * PUT `…/validators/{id}` → 200 `{ data: ValidatorResponse }`.
   *
   * 표현 **전체 교체**다 — 본문이 담지 않은 config 키는 사라진다. 호출자가 baseline 을 병합해
   * 보내는 것이 제약 C6 이고, 목이 대신 병합해 주면 그 계약 위반이 화면에서 드러나지 않는다.
   */
  http.put(ITEM_PATH, async ({ params, request }) => {
    const id = pathParam(params, 'id')
    if (!UUID_PATTERN.test(id)) {
      return errorResponse(400, ERROR_CODES.invalidRequest, `id 가 UUID 가 아닙니다: '${id}'`)
    }

    const key = storeKey(pathParam(params, 'workflowKey'), pathParam(params, 'transitionId'))
    const rows = validatorStore.get(key) ?? []
    const existing = rows.find((row) => row.id === id)
    if (existing === undefined) {
      return errorResponse(404, ERROR_CODES.notFound, 'validator 를 찾을 수 없습니다.')
    }

    const parsed = await readWriteBody(request)
    if (!parsed.ok) {
      return parsed.response
    }

    const updated: ValidatorResponse = {
      id,
      type: parsed.type,
      config: parsed.config,
      displayOrder: parsed.displayOrder ?? existing.displayOrder,
      phase: phaseOf(parsed.type),
      editable: true,
    }
    validatorStore.set(key, rows.map((row) => (row.id === id ? updated : row)))
    return HttpResponse.json({ data: updated })
  }),

  /**
   * DELETE `…/validators/{id}` → 204 (빈 바디). 하드 삭제다.
   *
   * 편집 불가 행도 삭제된다 — backend 가 막지 않으므로 목도 막지 않는다.
   */
  http.delete(ITEM_PATH, ({ params }) => {
    const id = pathParam(params, 'id')
    if (!UUID_PATTERN.test(id)) {
      return errorResponse(400, ERROR_CODES.invalidRequest, `id 가 UUID 가 아닙니다: '${id}'`)
    }

    const key = storeKey(pathParam(params, 'workflowKey'), pathParam(params, 'transitionId'))
    const rows = validatorStore.get(key) ?? []
    if (!rows.some((row) => row.id === id)) {
      return errorResponse(404, ERROR_CODES.notFound, 'validator 를 찾을 수 없습니다.')
    }

    validatorStore.set(key, rows.filter((row) => row.id !== id))
    return new HttpResponse(null, { status: 204 })
  }),

  /**
   * DELETE `/api/v1/__e2e__/validators/reset` — E2E 전용 시드 복원 라우트.
   *
   * 형제 post-action 목과 같은 관례다. beforeEach 에서 호출해 테스트 간 격리를 보장한다.
   */
  http.delete('/api/v1/__e2e__/validators/reset', () => {
    resetValidatorStore()
    return new HttpResponse(null, { status: 204 })
  }),
]
