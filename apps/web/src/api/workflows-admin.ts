// 워크플로우 관리(admin) REST 클라이언트 — 워크플로우·전역 상태·편성·전환 정의 쓰기 API
import { z } from 'zod'
import { apiFetch } from './client'
import {
  dataOf,
  statusSchema,
  createdStatusSchema,
  createdWorkflowSchema,
  transitionDefinitionSchema,
} from './workflows-admin.types'
import type {
  StatusCatalogEntry,
  CreatedStatus,
  CreatedWorkflow,
  TransitionDefinition,
  CreateStatusInput,
  CreateWorkflowInput,
  UpdateWorkflowInput,
  DuplicateWorkflowInput,
  AddWorkflowStatusInput,
  TransitionDefinitionInput,
} from './workflows-admin.types'

export * from './workflows-admin.types'

/**
 * 워크플로우 관리 API 실패 — RFC 7807 `code` 를 `errorCode` 로 보존한다.
 *
 * `ApiError` 를 쓰지 않는 이유. 그쪽은 body 를 `unknown` 으로만 들고 있어 소비처가 매번
 * 파싱을 다시 해야 하고, 그러면 코드별 한국어 메시지 매핑이 화면마다 흩어진다.
 */
export class WorkflowAdminApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly errorCode: string,
    public readonly detail: string,
  ) {
    super(`WorkflowAdminApiError [${errorCode}] ${detail}`)
    this.name = 'WorkflowAdminApiError'
  }
}

/**
 * 중첩 에러 봉투 `{ error: { code, message } }`.
 *
 * ★ **평면 RFC 7807 이 아니다.** 이 파일이 부르는 엔드포인트의 핸들러 4종
 * (`WorkflowExceptionHandler` · `WorkflowStatusCompositionExceptionHandler` ·
 * `TransitionConflictExceptionHandler` · `StatusExceptionHandler`)이 전부
 * `ErrorResponse(error = ErrorBody(code, message))` 를 낸다. 평면 `ProblemDetail` 을 쓰는 것은
 * **스킴** 핸들러뿐이고 그쪽조차 필드명이 `errorCode` 다.
 *
 * 처음에 `workflow-schemes.ts` 의 평면 파서를 그대로 베꼈다가 잡혔다. `z.object` 는 모르는
 * 키를 버리고 `.default()` 가 빈 자리를 메우므로 **safeParse 가 성공한다** — 실패가 아니라
 * 조용히 `code='UNKNOWN'` 이 되어 한국어 메시지 매핑이 통째로 도달 불가가 됐다.
 * 같은 BC 안 `api/post-actions.ts` 가 이미 이 형태를 쓰고 있었다.
 */
const nestedErrorBodySchema = z.object({
  error: z.object({
    code: z.string().default('UNKNOWN'),
    message: z.string().default(''),
  }),
})

/** 비-2xx 를 `WorkflowAdminApiError` 로 바꿔 throw 한다. */
async function throwAdminApiError(res: Response): Promise<never> {
  const rawBody: unknown = await res.json().catch(() => ({}))
  const parsed = nestedErrorBodySchema.safeParse(rawBody)
  throw new WorkflowAdminApiError(
    res.status,
    parsed.success ? parsed.data.error.code : 'UNKNOWN',
    parsed.success ? parsed.data.error.message : String(rawBody),
  )
}

/** ok 확인 후 `{ data: T }` 를 파싱한다. */
async function parseData<T>(res: Response, schema: z.ZodSchema<T>): Promise<T> {
  if (!res.ok) {
    return throwAdminApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(schema).parse(raw).data
}

/**
 * ok 확인만 하고 본문을 읽지 않는다.
 *
 * 편성 추가(201)·제거(204)·삭제(204)는 **본문이 없다**. `res.json()` 을 부르면 빈 본문에서
 * 파싱이 터지므로 본문을 건드리지 않는 경로를 따로 둔다.
 */
async function expectNoContent(res: Response): Promise<void> {
  if (!res.ok) {
    await throwAdminApiError(res)
  }
}

/**
 * 경로 세그먼트를 인코딩한다.
 *
 * ★ `key`·`statusId`·`transitionId` 는 **URL 경로 파라미터가 그대로 흘러온 값**이다
 * (`routes/admin.workflows.$workflowKey.tsx`). 인코딩 없이 템플릿에 박으면
 * `..%2F..%2Fusers` 같은 값이 `../../users` 로 디코딩되고 `fetch` 가 `..` 를 정규화해
 * **다른 자원으로 요청이 나간다** — 관리자에게 링크 하나를 보내는 것으로 그 토큰을 빌려
 * 엉뚱한 곳에 GET·PUT 을 쏠 수 있다(confused deputy).
 */
const seg = (value: string): string => encodeURIComponent(value)

// ─────────────────────────────────────────────────────────────────────────────
// 전역 상태 카탈로그
// ─────────────────────────────────────────────────────────────────────────────

/** 전역 상태 카탈로그 전체를 조회한다. `GET /api/v1/statuses` */
export async function fetchStatuses(): Promise<StatusCatalogEntry[]> {
  return parseData(await apiFetch('/api/v1/statuses', { method: 'GET' }), z.array(statusSchema))
}

/** 카탈로그에 상태를 새로 만든다. `POST /api/v1/statuses` → 201 `{ data: { id, key } }` */
export async function createStatus(input: CreateStatusInput): Promise<CreatedStatus> {
  return parseData(await apiFetch('/api/v1/statuses', { method: 'POST', body: input }), createdStatusSchema)
}

// ─────────────────────────────────────────────────────────────────────────────
// 워크플로우
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우를 만든다. `statuses` 가 비면 백엔드가 400 이다. */
export async function createWorkflow(input: CreateWorkflowInput): Promise<CreatedWorkflow> {
  return parseData(await apiFetch('/api/v1/workflows', { method: 'POST', body: input }), createdWorkflowSchema)
}

/** 이름·설명을 고친다. `key` 는 보내지 않는다 — 백엔드 요청 DTO 에 그 필드가 없다. */
export async function updateWorkflow(key: string, input: UpdateWorkflowInput): Promise<void> {
  await expectNoContent(await apiFetch(`/api/v1/workflows/${seg(key)}`, { method: 'PUT', body: input }))
}

/** 소프트 삭제한다. 스킴 매핑이 참조 중이면 409(`WORKFLOW_IN_USE`). */
export async function deleteWorkflow(key: string): Promise<void> {
  await expectNoContent(await apiFetch(`/api/v1/workflows/${seg(key)}`, { method: 'DELETE' }))
}

/** 상태 편성과 전환까지 복제한다. 새 key 가 이미 쓰이면 409. */
export async function duplicateWorkflow(key: string, input: DuplicateWorkflowInput): Promise<CreatedWorkflow> {
  return parseData(
    await apiFetch(`/api/v1/workflows/${seg(key)}/duplicate`, { method: 'POST', body: input }),
    createdWorkflowSchema,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 상태 편성
// ─────────────────────────────────────────────────────────────────────────────

/** 카탈로그의 상태를 워크플로우에 편성한다. 이미 있으면 표시 순서만 갱신된다. */
export async function addWorkflowStatus(key: string, input: AddWorkflowStatusInput): Promise<void> {
  await expectNoContent(await apiFetch(`/api/v1/workflows/${seg(key)}/statuses`, { method: 'POST', body: input }))
}

/**
 * 편성을 뗀다. 카탈로그의 상태 자체는 남는다.
 *
 * 마지막 상태면 400, 이슈가 쓰고 있으면 409, 전환이 가리키면 409 다.
 */
export async function removeWorkflowStatus(key: string, statusId: string): Promise<void> {
  await expectNoContent(await apiFetch(`/api/v1/workflows/${seg(key)}/statuses/${seg(statusId)}`, { method: 'DELETE' }))
}

/**
 * 표시 순서를 통째로 다시 정한다.
 *
 * ★ 그 워크플로우의 상태 **전부**를 담아야 한다. 부분 목록이면 빠진 상태의 순서가 어긋난다
 * (백엔드 `ReorderWorkflowStatusesRequest` 주석).
 */
export async function reorderWorkflowStatuses(key: string, statusIds: string[]): Promise<void> {
  await expectNoContent(
    await apiFetch(`/api/v1/workflows/${seg(key)}/statuses/order`, { method: 'PUT', body: { statusIds } }),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 전환 정의
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 요청 바디를 만든다.
 *
 * `fromStatusKey` 가 null 이면 **키 자체를 뺀다**. `GLOBAL`·`INITIAL` 에 그 키가 실리면
 * 백엔드가 400 이라, `null` 을 그대로 보내면 전역 전환을 영영 못 만든다.
 */
function transitionBody(input: TransitionDefinitionInput): Record<string, unknown> {
  const base: Record<string, unknown> = { toStatusKey: input.toStatusKey, name: input.name, kind: input.kind }
  if (input.fromStatusKey !== null) {
    base['fromStatusKey'] = input.fromStatusKey
  }
  return base
}

/** 전환 정의를 만든다. 같은 상태쌍에 이름이 다른 전환을 여럿 둘 수 있다(FR-WF-05 F2). */
export async function createTransition(
  key: string,
  input: TransitionDefinitionInput,
): Promise<TransitionDefinition> {
  return parseData(
    await apiFetch(`/api/v1/workflows/${seg(key)}/transitions`, { method: 'POST', body: transitionBody(input) }),
    transitionDefinitionSchema,
  )
}

/** 전환 정의를 통째로 갈아 끼운다. 부분 수정이 아니다. */
export async function updateTransition(
  key: string,
  transitionId: string,
  input: TransitionDefinitionInput,
): Promise<TransitionDefinition> {
  return parseData(
    await apiFetch(`/api/v1/workflows/${seg(key)}/transitions/${seg(transitionId)}`, {
      method: 'PUT',
      body: transitionBody(input),
    }),
    transitionDefinitionSchema,
  )
}

/** 전환 정의를 지운다. 최초 전환은 지울 수 없다 — 409. */
export async function deleteTransition(key: string, transitionId: string): Promise<void> {
  await expectNoContent(
    await apiFetch(`/api/v1/workflows/${seg(key)}/transitions/${seg(transitionId)}`, { method: 'DELETE' }),
  )
}
