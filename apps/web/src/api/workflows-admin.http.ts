// 워크플로우 관리 API 공용 HTTP 껍데기 — 오류 봉투 파싱 · 응답 언랩 · 경로 인코딩
import { z } from 'zod'
import { dataOf } from './workflows-admin.types'

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
 * 발행이 이관을 요구해 막힌 409 — 상태별 잔여 건수를 함께 들고 온다.
 *
 * ### 왜 별도 클래스인가
 * 백엔드 `PublishPendingResponse` 는 표준 봉투 **위에** `pendingIssueCounts` 를 하나 더 얹는다
 * (`WorkflowPublishExceptionHandler.kt`). [nestedErrorBodySchema] 는 `error` 만 보므로 그 건수를
 * 통째로 버리고, 그러면 마법사는 「무엇이 몇 건 막고 있는지」를 알기 위해 preview 를 다시 쏘는
 * 수밖에 없다 — 그 사이 값이 또 달라질 수 있는 왕복이다.
 *
 * `WorkflowAdminApiError` 를 상속하므로 기존 `instanceof` 분기(`notifyWorkflowAdminError`)가
 * 그대로 맞는다. 소비처는 필요할 때만 이 타입으로 좁히면 된다.
 */
export class WorkflowPublishMappingRequiredError extends WorkflowAdminApiError {
  constructor(
    status: number,
    errorCode: string,
    detail: string,
    /** 사라지는 상태 키 → 그 상태에 남은 이슈 수 */
    public readonly pendingIssueCounts: Record<string, number>,
  ) {
    super(status, errorCode, detail)
    this.name = 'WorkflowPublishMappingRequiredError'
  }
}

/**
 * 중첩 에러 봉투 `{ error: { code, message } }`.
 *
 * ★ **평면 RFC 7807 이 아니다.** 이 BC 의 핸들러 5종
 * (`WorkflowExceptionHandler` · `WorkflowStatusCompositionExceptionHandler` ·
 * `TransitionConflictExceptionHandler` · `StatusExceptionHandler` ·
 * `WorkflowPublishExceptionHandler`)이 전부 `ErrorResponse(error = ErrorBody(code, message))` 를
 * 낸다. 평면 `ProblemDetail` 을 쓰는 것은 **스킴** 핸들러뿐이고 그쪽조차 필드명이 `errorCode` 다.
 *
 * 처음에 `workflow-schemes.ts` 의 평면 파서를 그대로 베꼈다가 잡혔다. `z.object` 는 모르는
 * 키를 버리고 `.default()` 가 빈 자리를 메우므로 **safeParse 가 성공한다** — 실패가 아니라
 * 조용히 `code='UNKNOWN'` 이 되어 한국어 메시지 매핑이 통째로 도달 불가가 됐다.
 */
const nestedErrorBodySchema = z.object({
  error: z.object({
    code: z.string().default('UNKNOWN'),
    message: z.string().default(''),
  }),
})

/** 발행 409 가 얹어 보내는 잔여 건수. 없으면 이 파싱만 실패하고 봉투는 그대로 쓴다. */
const pendingCountsSchema = z.object({
  pendingIssueCounts: z.record(z.string(), z.number()),
})

/** 이관을 요구하는 발행 거절의 코드. 이 코드일 때만 잔여 건수를 살려 던진다. */
const MAPPING_REQUIRED_CODE = 'WORKFLOW_PUBLISH_MAPPING_REQUIRED'

/** 비-2xx 를 `WorkflowAdminApiError` 로 바꿔 throw 한다. */
export async function throwAdminApiError(res: Response): Promise<never> {
  const rawBody: unknown = await res.json().catch(() => ({}))
  const parsed = nestedErrorBodySchema.safeParse(rawBody)
  const code = parsed.success ? parsed.data.error.code : 'UNKNOWN'
  const detail = parsed.success ? parsed.data.error.message : String(rawBody)

  if (code === MAPPING_REQUIRED_CODE) {
    // 건수 파싱이 실패해도 봉투는 살린다 — 부가 정보가 없다고 오류 자체를 잃으면 안 된다.
    const pending = pendingCountsSchema.safeParse(rawBody)
    throw new WorkflowPublishMappingRequiredError(
      res.status,
      code,
      detail,
      pending.success ? pending.data.pendingIssueCounts : {},
    )
  }
  throw new WorkflowAdminApiError(res.status, code, detail)
}

/** ok 확인 후 `{ data: T }` 를 파싱한다. */
export async function parseData<T>(res: Response, schema: z.ZodSchema<T>): Promise<T> {
  if (!res.ok) {
    return throwAdminApiError(res)
  }
  const raw: unknown = await res.json()
  return dataOf(schema).parse(raw).data
}

/**
 * ok 확인만 하고 본문을 읽지 않는다.
 *
 * 편성 추가(201)·제거(204)·삭제(204)·초안 저장(204)은 **본문이 없다**. `res.json()` 을 부르면
 * 빈 본문에서 파싱이 터지므로 본문을 건드리지 않는 경로를 따로 둔다.
 */
export async function expectNoContent(res: Response): Promise<void> {
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
export const seg = (value: string): string => encodeURIComponent(value)
