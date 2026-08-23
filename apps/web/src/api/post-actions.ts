// 워크플로우 전환 post-action CRUD API 클라이언트 — Zod 스키마 + fetch 함수 + 에러 클래스
import { z } from 'zod'
import { apiFetch } from './client'
import { dataOf } from './workflow-schemes.types'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — backend DTO 1:1
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST/PUT 요청 바디 스키마.
 * config는 CALL_WEBHOOK 외 타입도 수용하도록 관대하게 Record<string, unknown> 처리.
 * 구체 config 검증은 폼(PostActionFormDialog)이 담당한다.
 */
export const postActionRequestSchema = z.object({
  type: z.string().min(1),
  config: z.record(z.string(), z.unknown()).default({}),
  displayOrder: z.number().int().default(0),
})

/**
 * 응답 DTO 스키마.
 * config는 다양한 type(CALL_WEBHOOK/SET_FIELD/NOTIFY 등)에 대응하도록 관대하게.
 */
export const postActionResponseSchema = z.object({
  id: z.string().uuid(),
  type: z.string().min(1),
  config: z.record(z.string(), z.unknown()),
  displayOrder: z.number().int(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** POST/PUT 요청 바디 타입 */
export type PostActionRequest = z.infer<typeof postActionRequestSchema>

/** 응답 DTO 타입 */
export type PostActionResponse = z.infer<typeof postActionResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 에러 클래스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * post-action API 전용 에러.
 * 에러 봉투가 중첩 구조 `{ error: { code, message } }`이므로
 * workflow-schemes의 RFC 7807 평면 파서와 별도로 정의한다.
 *
 * 주요 errorCode.
 * - WORKFLOW_SCHEME_ACCESS_DENIED — 403, 권한 없음
 * - WORKFLOW_POST_ACTION_INVALID  — 400, 유효하지 않은 요청
 * - WORKFLOW_POST_ACTION_NOT_FOUND — 404, post-action 없음
 */
export class PostActionApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly errorCode: string,
    public readonly detail: string,
  ) {
    super(`PostActionApiError [${errorCode}] ${detail}`)
    this.name = 'PostActionApiError'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸리티
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 중첩 에러 봉투 `{ error: { code, message } }` 파싱 스키마.
 * workflow-schemes의 RFC 7807 평면 구조와 다름 — 이 파일 전용.
 */
const nestedErrorBodySchema = z.object({
  error: z.object({
    code: z.string().default('UNKNOWN'),
    message: z.string().default(''),
  }),
})

/**
 * 비-2xx 응답을 PostActionApiError로 변환해 throw한다.
 * `{ error: { code, message } }` 중첩 봉투에서 code를 추출한다.
 */
async function throwPostActionApiError(res: Response): Promise<never> {
  const rawBody: unknown = await res.json().catch(() => ({}))
  const parsed = nestedErrorBodySchema.safeParse(rawBody)
  const errorCode = parsed.success ? parsed.data.error.code : 'UNKNOWN'
  const detail = parsed.success ? parsed.data.error.message : String(rawBody)
  throw new PostActionApiError(res.status, errorCode, detail)
}

/**
 * 경로 베이스 빌더.
 *
 * ### 전환 세그먼트는 전환 id 가 정본이고 합성 키는 하위호환 폴백이다
 * backend `PostActionAdminService` 는 세그먼트가 UUID 형태로 파싱되면 `workflow_transitions.id`
 * 로 읽고(1급 식별자 · ADR 2026-08-18 §D1), 아니면 `__` 로 갈라 종전 `fromStateKey__toStateKey`
 * 합성 키로 읽는다. 두 갈래는 합성 키가 반드시 `__` 를 품으므로 겹치지 않는다.
 *
 * ★ 합성 키로 부르면 **다건일 때 404** 다. V207 ① 이 `UNIQUE(workflow_id, from_state_id,
 * to_state_id)` 를 풀어 같은 (from, to) 구간에 전환을 여럿 둘 수 있게 됐고, backend 는 그 이름으로
 * 대상을 특정할 수 없다고 보고 거절한다(0건도 404 · `__` 조각이 2개가 아닌 형식 오류도 404).
 * 그래서 새 호출자는 전환 id 를 넘긴다 — 화면(PostActionConfigSection)이 이미 그렇게 한다.
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  전환 지목값 — 전환 id(UUID) 가 정본, `fromStateKey__toStateKey` 합성 키는 하위호환 폴백
 */
function basePath(workflowKey: string, transitionKey: string): string {
  return `/api/v1/workflows/${workflowKey}/transitions/${transitionKey}/post-actions`
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전환의 post-action 목록을 조회한다.
 * GET /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions
 * → 200 → { data: PostActionResponse[] }
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  전환 지목값 — 전환 id(UUID) 가 정본, `fromStateKey__toStateKey` 합성 키는 하위호환 폴백(다건이면 404)
 * @throws PostActionApiError  비-2xx 응답 시
 */
export async function listPostActions(
  workflowKey: string,
  transitionKey: string,
): Promise<PostActionResponse[]> {
  const res = await apiFetch(basePath(workflowKey, transitionKey), { method: 'GET' })
  if (!res.ok) {
    return throwPostActionApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(z.array(postActionResponseSchema)).parse(raw).data
}

/**
 * 전환에 post-action을 생성한다.
 * POST /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions
 * → 201 → { data: PostActionResponse }
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  전환 지목값 — 전환 id(UUID) 가 정본, `fromStateKey__toStateKey` 합성 키는 하위호환 폴백(다건이면 404)
 * @param body  PostActionRequest (type, config, displayOrder)
 * @throws PostActionApiError  비-2xx 응답 시
 */
export async function createPostAction(
  workflowKey: string,
  transitionKey: string,
  body: PostActionRequest,
): Promise<PostActionResponse> {
  const res = await apiFetch(basePath(workflowKey, transitionKey), { method: 'POST', body })
  if (!res.ok) {
    return throwPostActionApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(postActionResponseSchema).parse(raw).data
}

/**
 * post-action을 수정한다.
 * PUT /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions/{id}
 * → 200 → { data: PostActionResponse }
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  전환 지목값 — 전환 id(UUID) 가 정본, `fromStateKey__toStateKey` 합성 키는 하위호환 폴백(다건이면 404)
 * @param id  수정할 post-action UUID
 * @param body  PostActionRequest
 * @throws PostActionApiError  비-2xx 응답 시
 */
export async function updatePostAction(
  workflowKey: string,
  transitionKey: string,
  id: string,
  body: PostActionRequest,
): Promise<PostActionResponse> {
  const res = await apiFetch(`${basePath(workflowKey, transitionKey)}/${id}`, {
    method: 'PUT',
    body,
  })
  if (!res.ok) {
    return throwPostActionApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(postActionResponseSchema).parse(raw).data
}

/**
 * post-action을 삭제한다.
 * DELETE /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions/{id}
 * → 204 (빈 바디)
 *
 * @param workflowKey  워크플로우 키
 * @param transitionKey  전환 지목값 — 전환 id(UUID) 가 정본, `fromStateKey__toStateKey` 합성 키는 하위호환 폴백(다건이면 404)
 * @param id  삭제할 post-action UUID
 * @throws PostActionApiError  비-2xx 응답 시
 */
export async function deletePostAction(
  workflowKey: string,
  transitionKey: string,
  id: string,
): Promise<void> {
  const res = await apiFetch(`${basePath(workflowKey, transitionKey)}/${id}`, {
    method: 'DELETE',
  })
  if (!res.ok) {
    return throwPostActionApiError(res)
  }
}
