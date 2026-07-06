// identity-access 부재중(Out of Office) 조회/설정/해제 REST API 클라이언트 — FR-PR-03
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'
import { oooResponseSchema } from './schemas'
import type { OooResponse } from './schemas'

/**
 * 비-2xx 응답이면 body를 파싱해 {@link ApiError}를 throw한다.
 * status.ts throwIfNotOk 선례(같은 BC 관례)와 동일한 패턴.
 */
async function throwIfNotOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 부재중 PATCH 요청 바디 — 원자적 교체(replace) 시맨틱(FR2, EC7).
 * startsAt·endsAt은 필수(ISO Instant). delegateUserId·message는 없어도 OOO가 성립한다.
 */
export interface OooPatchBody {
  startsAt: string
  endsAt: string
  delegateUserId?: string | null
  message?: string | null
}

/**
 * 본인의 부재중 설정을 조회한다.
 *
 * `GET /api/v1/users/me/ooo` → 200 {@link OooResponse}.
 * 미설정/종료 시 all-null·active:false(row 강제 생성 안 함, EC1/G3).
 *
 * @returns 현재 부재중 설정
 * @throws ApiError(401) 미인증
 */
export async function fetchOoo(): Promise<OooResponse> {
  return apiGet('/api/v1/users/me/ooo', oooResponseSchema)
}

/**
 * 본인 부재중 설정을 원자적으로 교체한다(replace, EC7 — 이전 값 잔존 없음).
 *
 * `PATCH /api/v1/users/me/ooo` → 200 설정 후 {@link OooResponse}.
 * X-XSRF-TOKEN 헤더를 포함해 CSRF 공격을 방어한다(status.ts updateStatus 선례,
 * double submit cookie 패턴 — 같은 BC 관례를 따른다).
 *
 * @param body 교체할 부재중 설정(원자적 replace)
 * @returns 갱신 후 부재중 설정
 * @throws ApiError(400) 검증 실패(기간 누락/역전/과거·대리자 미존재/본인·메시지 길이 초과)
 * @throws ApiError(401) 미인증
 */
export async function updateOoo(body: OooPatchBody): Promise<OooResponse> {
  const res = await apiFetch('/api/v1/users/me/ooo', {
    method: 'PATCH',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
  return oooResponseSchema.parse(await res.json())
}

/**
 * 본인 부재중 설정을 해제한다(멱등 — 없어도 정상, EC8).
 *
 * `DELETE /api/v1/users/me/ooo` → 204 No Content.
 *
 * @throws ApiError(401) 미인증
 */
export async function clearOoo(): Promise<void> {
  const res = await apiFetch('/api/v1/users/me/ooo', {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await throwIfNotOk(res)
}
