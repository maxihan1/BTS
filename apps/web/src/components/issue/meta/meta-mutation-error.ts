// 이슈 메타 필드 저장 실패의 원인 분류 — 순수 함수. 토스트·무효화 같은 부수효과는 호출부가 한다
import { ApiError } from '@/api/client'

/**
 * 메타 필드 저장 실패의 원인.
 *
 * - `version-conflict` — 409. 다른 사용자가 먼저 고쳤다. **재조회가 필요**하다.
 * - `custom-field-invalid` — 422 `CUSTOM_FIELD_VALIDATION_FAILED`. **재시도해도 안 되는** 입력 문제다.
 * - `fallback` — 그 밖. 호출부가 필드별 문구를 쓴다.
 */
export type MetaMutationErrorKind = 'version-conflict' | 'custom-field-invalid' | 'fallback'

/**
 * `ApiError` 본문에서 `errorCode` 를 안전하게 뽑는다.
 *
 * 본문 형태를 신뢰하지 않는다 — 백엔드는 RFC 7807 ProblemDetail 을 쓰지만 프록시·게이트웨이가
 * 끼어들면 문자열·HTML 이 올 수도 있다. 그런 입력에서 던지지 않고 빈 문자열을 돌려준다.
 *
 * @returns 문자열 `errorCode`. 없거나 형태가 다르면 빈 문자열.
 */
export function extractErrorCode(err: ApiError): string {
  const body: unknown = err.body
  if (typeof body !== 'object' || body === null) return ''
  const code = (body as Record<string, unknown>)['errorCode']
  return typeof code === 'string' ? code : ''
}

/**
 * 메타 필드 저장 실패를 원인별로 분류한다.
 *
 * ## ★`custom-field-invalid` 를 따로 두는 이유
 *
 * 이전에는 이 경우가 폴백(「…잠시 후 다시 시도해 주세요」)으로 떨어졌다.
 * **재시도해도 안 되는데 재시도를 권하는** 거짓 안내다.
 *
 * 특히 사용자가 **원인을 알 방법이 없는** 경로가 있다. 백엔드는 **활성 정의 전량**을 기준으로
 * 병합·검증하는데(`IssueApplicationService.mergeCustomFieldsAndValidate`) 클라이언트는 열람
 * 권한이 없는 필드를 **렌더도 검증도 하지 않는다** — 그 값은 응답에서 아예 마스킹돼 온다
 * (`IssueResponse.maskInvisible` 이 `customFields` 를 필터링한다).
 * 그래서 그 필드가 required 이고 비어 있으면 **화면에 없는 필드 때문에 저장이 영원히 실패**한다.
 *
 * ★**클라이언트가 그 상태를 판정할 수는 없다.** 값이 마스킹돼 있어 「비어 있음」과
 * 「채워져 있음」이 구분되지 않는다. 「전량 `fieldDefs` 기준으로 검증한다」는 처방은
 * **값이 이미 채워진 사용자까지 거짓 차단**한다 — 지금 버그보다 나쁘다.
 * 할 수 있는 것은 원인을 짐작할 단서를 주는 것뿐이고, 근본 해결은 백엔드가
 * 「마스킹된 required 가 충족됐는가」를 알려 줘야 가능하다(TODOS 등재).
 *
 * @param err 임의 에러 — `ApiError` 가 아니면 `fallback`
 */
export function classifyMetaMutationError(err: unknown): MetaMutationErrorKind {
  if (!(err instanceof ApiError)) return 'fallback'
  if (err.status === 409) return 'version-conflict'
  if (extractErrorCode(err) === 'CUSTOM_FIELD_VALIDATION_FAILED') return 'custom-field-invalid'
  return 'fallback'
}
