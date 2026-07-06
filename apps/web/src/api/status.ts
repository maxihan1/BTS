// identity-access 사용자 상태 메시지(이모지+텍스트+만료) REST API 클라이언트 — FR-PR-02
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'
import { statusResponseSchema } from './schemas'
import type { StatusResponse } from './schemas'

/**
 * 비-2xx 응답이면 body를 파싱해 {@link ApiError}를 throw한다.
 * profile.ts의 throwIfNotOk 선례와 동일한 패턴 — PATCH 응답 형태는 schema.parse를
 * 이 호출 다음에 이어간다.
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 상태 PATCH 요청 바디 — 원자적 교체(replace) 시맨틱.
 * 프로필의 3-state(JsonNode) 패치와 달리 부재 필드는 null 취급된다(부재≠보존).
 * emoji·text 정규화(blank→null) 결과 둘 다 null이면 서버가 상태를 해제한다.
 */
export interface StatusPatchBody {
  emoji?: string | null
  text?: string | null
  expiresAt?: string | null
}

/**
 * 본인의 활성 상태(이모지+텍스트+만료)를 조회한다.
 *
 * `GET /api/v1/users/me/status` → 200 {@link StatusResponse}.
 * 미설정/만료 시 all-null(row 강제 생성 안 함, EC1).
 *
 * @returns 현재 활성 상태
 * @throws ApiError(401) 미인증
 */
export async function fetchStatus(): Promise<StatusResponse> {
  return apiGet('/api/v1/users/me/status', statusResponseSchema)
}

/**
 * 본인 상태를 원자적으로 교체한다.
 *
 * `PATCH /api/v1/users/me/status` → 200 갱신 후 {@link StatusResponse}.
 * - emoji·text 둘 다 없으면(정규화 후 null) 서버가 상태를 해제하고 all-null을 반환한다.
 * - X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다(profile.ts patchProfile 선례,
 *   double submit cookie 패턴 — 같은 BC 관례를 따른다).
 *
 * @param body 교체할 상태 값(원자적 replace, 부재 필드=미설정 취급)
 * @returns 갱신 후 상태(설정 시 활성 상태, 해제 시 all-null)
 * @throws ApiError(400) 검증 실패(길이 초과/과거 expiresAt)
 * @throws ApiError(401) 미인증
 */
export async function updateStatus(body: StatusPatchBody): Promise<StatusResponse> {
  const res = await apiFetch('/api/v1/users/me/status', {
    method: 'PATCH',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return statusResponseSchema.parse(await res.json())
}
