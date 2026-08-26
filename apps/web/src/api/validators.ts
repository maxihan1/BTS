// 전환 규칙(validator) CRUD API 클라이언트 — zod 계약 + 에러 봉투 파서 + type 별 config 폼 스키마
import { z } from 'zod'
import { apiFetch } from './client'
import { dataOf } from './workflow-schemes.types'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend `validator/web/ValidatorDtos.kt` 1:1
//
// ★ `.strict()` 를 **base 스키마에** 건다. 형제 `post-actions.ts` 는 걸지 않지만
//   `workflows-admin.types.ts` 관례를 따른다 — 백엔드가 필드를 더하는 날 계약 갭이 런타임에
//   드러난다. 봉투(`dataOf`)에만 걸면 안쪽 필드 추가가 통과해 봉인이 절반만 닫힌다.
//   단 `config` 안쪽은 봉인 대상이 아니다 — type 마다 키가 달라 `Map<String, Any?>` 로 왕복한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 평가 시점 — backend `ValidatorPhase` enum 이름.
 *
 * `AVAILABILITY` 는 전환 버튼을 감추고 `EXECUTION` 은 눌렀을 때 막는다. 4종 중
 * `RequiredField` 만 EXECUTION 을 명시 override 하므로 **`type` 문자열로는 판별할 수 없고**,
 * 그래서 응답이 이 값을 싣는다. 화면에 `type → phase` 표를 만들지 않는다.
 */
export const validatorPhaseSchema = z.enum(['AVAILABILITY', 'EXECUTION'])

/**
 * POST/PUT 요청 바디 스키마 — backend `ValidatorRequest`.
 *
 * `config` 는 type 별 키가 달라 관대하게 받는다. 키 단위 검증은 폼이
 * [VALIDATOR_CONFIG_FORM_SCHEMAS] 로 한다.
 */
export const validatorRequestSchema = z
  .object({
    type: z.string().min(1),
    config: z.record(z.string(), z.unknown()).default({}),
    displayOrder: z.number().int().default(0),
  })
  .strict()

/**
 * 응답 DTO 스키마 — backend `ValidatorResponse` 6필드.
 *
 * `phase = null` 은 「평가 시점이 없다」가 아니라 **「그 행으로는 인스턴스를 만들 수 없어
 * 알 수 없다」** 다(손으로 넣은 깨진 config · 사라진 type). 그 행은 `editable` 도 항상 false 이며
 * 두 값이 같은 인스턴스에서 나오므로 짝이 어긋날 수 없다.
 *
 * `editable` 은 이 API 로 고칠 수 있는 type 인지다. 화면이 이 값을 안 쓰고 편집 가능 type 목록을
 * 자기 코드에 베끼면, 그 사본은 백엔드가 네 번째 종류를 허용하는 날 조용히 낡는다.
 */
export const validatorResponseSchema = z
  .object({
    id: z.string().uuid(),
    type: z.string().min(1),
    config: z.record(z.string(), z.unknown()),
    displayOrder: z.number().int(),
    phase: validatorPhaseSchema.nullable(),
    editable: z.boolean(),
  })
  .strict()

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 평가 시점 값. */
export type ValidatorPhase = z.infer<typeof validatorPhaseSchema>

/** POST/PUT 요청 바디 타입. */
export type ValidatorRequest = z.infer<typeof validatorRequestSchema>

/** 응답 DTO 타입. */
export type ValidatorResponse = z.infer<typeof validatorResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// type 별 config 폼 스키마 — SDD §7.3 `필수 config 키` 열의 사본
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 편집 가능한 validator type 의 config 폼 스키마.
 *
 * ### 이것은 불가피한 사본이고, 기계가 대조한다
 * 폼을 그리려면 화면이 타입별 키를 알아야 하는데 그 키의 정본은
 * `docs/sdd/07-workflow-engine.md` §7.3 표(= `DefaultWorkflowValidatorFactory` 의 `create*`)다.
 * 사본이 정본과 갈리면 저장 시점 400 으로만 드러나므로
 * `scripts/workflow/validator-type-catalog.test.ts` 가 이 선언과 표를 양방향 차집합 0 으로 잰다.
 * **그래서 키를 여러 함수에 흩뿌리지 않고 이 객체 하나에 선언적으로 모아 둔다** — 파서가 읽을
 * 대상이 한 곳이어야 한다.
 *
 * ### 여기 없는 type
 * `CustomExpression` 은 편집 불가라 폼이 없다 — 임의 SpEL 표현식을 API 로 받는 경로를 만들지
 * 않는다는 백엔드 허용 목록(`ValidatorEditability.kt`)과 같은 판정이다. 이 객체는 **폼 스키마
 * 목록이지 편집 가능 여부의 정본이 아니다** — 그 판정은 응답의 `editable` 이 준다(제약 C1).
 *
 * ### `.strict()` 를 걸지 않는 이유
 * 응답 계약이 아니라 **폼 입력 계약**이다. 기존 행을 프리필할 때 폼이 모르는 키가 섞여 있어도
 * 파싱이 깨지면 안 되고, 그 키는 수정 시 baseline 병합이 보존한다(제약 C6).
 */
export const VALIDATOR_CONFIG_FORM_SCHEMAS = {
  RequiredField: z.object({
    field: z.string().min(1),
  }),
  'permission-check': z.object({
    permission: z.string().min(1),
    scope: z.string().min(1).optional(),
  }),
  'not-status-category': z.object({
    category: z.string().min(1),
  }),
}

/** [VALIDATOR_CONFIG_FORM_SCHEMAS] 의 값 타입. */
export type ValidatorConfigFormSchema =
  (typeof VALIDATOR_CONFIG_FORM_SCHEMAS)[keyof typeof VALIDATOR_CONFIG_FORM_SCHEMAS]

/**
 * type 문자열에 맞는 config 폼 스키마를 찾는다.
 *
 * 모르는 type 이면 `undefined` 다 — 호출자는 폼을 추측해 그리지 말고 읽기 전용으로 보여준다(E3).
 * `Object.hasOwn` 으로 먼저 거르는 이유는 `toString` 같은 프로토타입 키가 스키마인 척 돌아오는
 * 것을 막기 위함이다.
 *
 * @param type 응답이 준 validator type 문자열.
 * @return 폼 스키마. 폼을 그릴 수 없는 type 이면 `undefined`.
 */
export function validatorConfigFormSchema(type: string): ValidatorConfigFormSchema | undefined {
  const byType: Readonly<Record<string, ValidatorConfigFormSchema>> = VALIDATOR_CONFIG_FORM_SCHEMAS
  return Object.hasOwn(VALIDATOR_CONFIG_FORM_SCHEMAS, type) ? byType[type] : undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 클래스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * validator API 전용 에러.
 *
 * 이 BC 의 에러 봉투는 중첩 구조 `{ error: { code, message } }` 라 workflow-schemes 의
 * RFC 7807 평면 파서와 별도로 둔다(형제 `post-actions.ts` 와 같은 이유·같은 형태).
 *
 * 주요 errorCode.
 * - `WORKFLOW_SCHEME_ACCESS_DENIED` — 403, MANAGE_SCHEME 권한 없음
 * - `WORKFLOW_VALIDATOR_INVALID` — 400, type·config 부적합
 * - `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE` — 400, 편집 허용 목록 밖 type
 * - `WORKFLOW_VALIDATOR_NOT_FOUND` — 404, 전환·규칙 미존재
 */
export class ValidatorApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly errorCode: string,
    public readonly detail: string,
  ) {
    super(`ValidatorApiError [${errorCode}] ${detail}`)
    this.name = 'ValidatorApiError'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 중첩 에러 봉투 `{ error: { code, message } }` 파싱 스키마.
 * workflow-schemes 의 RFC 7807 평면 구조와 다름 — 이 파일 전용.
 */
const nestedErrorBodySchema = z.object({
  error: z.object({
    code: z.string().default('UNKNOWN'),
    message: z.string().default(''),
  }),
})

/**
 * 비-2xx 응답을 [ValidatorApiError] 로 바꿔 throw 한다.
 * `{ error: { code, message } }` 봉투에서 code 를 뽑고, 봉투가 아니면 `UNKNOWN` 으로 둔다.
 */
async function throwValidatorApiError(res: Response): Promise<never> {
  const rawBody: unknown = await res.json().catch(() => ({}))
  const parsed = nestedErrorBodySchema.safeParse(rawBody)
  const errorCode = parsed.success ? parsed.data.error.code : 'UNKNOWN'
  const detail = parsed.success ? parsed.data.error.message : String(rawBody)
  throw new ValidatorApiError(res.status, errorCode, detail)
}

/**
 * 경로 베이스 빌더.
 *
 * ### 세그먼트에 싣는 것은 **전환 id(UUID)** 뿐이다
 * backend 는 세그먼트가 UUID 로 파싱되면 `workflow_transitions.id` 로 읽고, 아니면 `__` 로 갈라
 * 종전 `fromStateKey__toStateKey` 합성 키로 읽는다. 합성 키 갈래는 **같은 (from, to) 구간에
 * 전환이 여럿이면 404** 다 — V207 ① 이 그 UNIQUE 제약을 풀었고 backend 는 이름만으로 대상을
 * 특정할 수 없다고 보고 거절한다. 그래서 이 클라이언트의 호출자는 전환 id 만 넘긴다(제약 C4).
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID). backend `{transitionKey}` 세그먼트를 채운다.
 */
function basePath(workflowKey: string, transitionId: string): string {
  return `/api/v1/workflows/${workflowKey}/transitions/${transitionId}/validators`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환의 validator 목록을 조회한다.
 * GET `…/validators` → 200 → `{ data: ValidatorResponse[] }` (displayOrder ASC).
 *
 * 편집 불가 행도 목록에 그대로 온다 — 반쪽 목록은 관리자를 속인다.
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 * @throws ValidatorApiError 비-2xx 응답 시.
 */
export async function fetchValidators(
  workflowKey: string,
  transitionId: string,
): Promise<ValidatorResponse[]> {
  const res = await apiFetch(basePath(workflowKey, transitionId), { method: 'GET' })
  if (!res.ok) {
    return throwValidatorApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(z.array(validatorResponseSchema)).parse(raw).data
}

/**
 * 전환에 validator 를 생성한다.
 * POST `…/validators` → 201 → `{ data: ValidatorResponse }`.
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 * @param body 생성 요청 (type, config, displayOrder).
 * @throws ValidatorApiError 비-2xx 응답 시.
 */
export async function createValidator(
  workflowKey: string,
  transitionId: string,
  body: ValidatorRequest,
): Promise<ValidatorResponse> {
  const res = await apiFetch(basePath(workflowKey, transitionId), { method: 'POST', body })
  if (!res.ok) {
    return throwValidatorApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(validatorResponseSchema).parse(raw).data
}

/**
 * validator 를 수정한다.
 * PUT `…/validators/{id}` → 200 → `{ data: ValidatorResponse }`.
 *
 * PUT 은 **표현 전체 교체**다 — 폼이 아는 키만 담아 보내면 나머지 config 키가 조용히 지워진다.
 * 호출자는 로드한 config 를 baseline 으로 들고 아는 키만 덮어써서 전체를 보낸다(제약 C6).
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 * @param id 수정할 validator UUID.
 * @param body 수정 요청 (type, config, displayOrder).
 * @throws ValidatorApiError 비-2xx 응답 시.
 */
export async function updateValidator(
  workflowKey: string,
  transitionId: string,
  id: string,
  body: ValidatorRequest,
): Promise<ValidatorResponse> {
  const res = await apiFetch(`${basePath(workflowKey, transitionId)}/${id}`, {
    method: 'PUT',
    body,
  })
  if (!res.ok) {
    return throwValidatorApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(validatorResponseSchema).parse(raw).data
}

/**
 * validator 를 삭제한다. 하드 삭제다.
 * DELETE `…/validators/{id}` → 204 (빈 바디).
 *
 * 편집 불가 type 도 삭제는 된다 — backend 가 막지 않는다.
 *
 * @param workflowKey 워크플로우 키.
 * @param transitionId 전환 id(UUID).
 * @param id 삭제할 validator UUID.
 * @throws ValidatorApiError 비-2xx 응답 시.
 */
export async function deleteValidator(
  workflowKey: string,
  transitionId: string,
  id: string,
): Promise<void> {
  const res = await apiFetch(`${basePath(workflowKey, transitionId)}/${id}`, {
    method: 'DELETE',
  })
  if (!res.ok) {
    return throwValidatorApiError(res)
  }
}
