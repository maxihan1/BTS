// 대시보드 API 클라이언트 + Zod 스키마 — notification BC 선례(notification-policies.ts)를 따름
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// ⚠️ 에러 계약 주의:
//   notification BC는 RFC 7807 ProblemDetail에 대문자 errorCode를 사용한다
//   (예: 'NOTIF_DASHBOARD_NOT_FOUND'). 소문자 패턴 복붙 금지.
//
// Instant 직렬화:
//   notification BC Spring 설정에 따라 Instant → ISO 8601 문자열 직렬화.
//   → createdAt/updatedAt = z.string() (숫자 epoch 아님, fr-pl-02 가짜그린 참조)
//
// @JsonInclude(NON_NULL):
//   description만 nullable. null이면 JSON 키 자체가 생략됨 → .nullish() 필수.
//   sharedUserIds는 non-null 빈 배열 가능.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DashboardResponse DTO 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 단건 응답 Zod 스키마.
 *
 * - description: @JsonInclude(NON_NULL)로 null이면 키 자체 생략됨 → .nullish()
 * - sharedUserIds: non-null 배열(빈 배열 가능) → z.array(z.string().uuid())
 * - version: Long → z.number()
 * - createdAt/updatedAt: Instant → ISO 8601 문자열 → z.string()
 */
export const dashboardSchema = z.object({
  /** 대시보드 식별자 (UUID) */
  id: z.string().uuid(),
  /** 소유자 사용자 ID (UUID) */
  ownerId: z.string().uuid(),
  /** 대시보드 이름 */
  name: z.string(),
  /**
   * 설명 — @JsonInclude(NON_NULL)로 null이면 키 자체 생략됨.
   * .nullish() = optional + nullable 양쪽 허용.
   */
  description: z.string().nullish(),
  /** 공개 범위 ("PRIVATE"|"TEAM"|"ORG") — z.string() 전방호환 */
  visibility: z.string(),
  /** 위젯 배치 JSONB 문자열 */
  layout: z.string(),
  /** TEAM 공유 대상 사용자 ID 목록 (UUID[]) */
  sharedUserIds: z.array(z.string().uuid()),
  /** 생성 시각 (ISO 8601) */
  createdAt: z.string(),
  /** 최종 수정 시각 (ISO 8601) */
  updatedAt: z.string(),
  /** OCC 낙관적 잠금 버전 */
  version: z.number(),
})

/** 대시보드 단건 타입 — z.infer 자동 추론 */
export type Dashboard = z.infer<typeof dashboardSchema>

/**
 * 대시보드 목록 페이지네이션 응답 Zod 스키마.
 * BTS notification BC 관례: items + total + limit + offset.
 */
export const dashboardPageSchema = z.object({
  /** 현재 페이지 대시보드 목록 */
  items: z.array(dashboardSchema),
  /** 전체 접근 가능 대시보드 수 */
  total: z.number(),
  /** 요청한 페이지 크기 */
  limit: z.number(),
  /** 요청한 오프셋 */
  offset: z.number(),
})

/** 대시보드 목록 페이지 타입 — z.infer 자동 추론 */
export type DashboardPage = z.infer<typeof dashboardPageSchema>

/** DataResponse 래퍼 파싱 헬퍼 — `{ data: T }` 형태를 unwrap */
function dataWrapper<T>(schema: z.ZodSchema<T>) {
  return z.object({ data: schema })
}

// ─────────────────────────────────────────────────────────────────────────────
// 요청 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 생성 요청 바디.
 * name·visibility 필수. layout·description·sharedUserIds 선택.
 */
export interface CreateDashboardRequest {
  /** 대시보드 이름 (빈 문자열 불가 — 도메인 검증) */
  name: string
  /** 설명 (선택) */
  description?: string
  /** 공개 범위 ("PRIVATE"|"TEAM"|"ORG") */
  visibility: string
  /** 위젯 배치 JSON (선택, 기본 빈 배열) */
  layout?: string
  /** TEAM 공유 대상 사용자 ID 목록 (선택) */
  sharedUserIds?: string[]
}

/**
 * 대시보드 부분 수정 요청 바디.
 * version은 OCC 낙관적 잠금 키로 필수.
 * 나머지 필드는 3-state PATCH — undefined=유지, null=제거, 값=변경.
 */
export interface PatchDashboardRequest {
  /** 변경할 이름 (undefined = 유지) */
  name?: string
  /** 변경할 설명 (undefined = 유지) */
  description?: string
  /** 변경할 공개 범위 (undefined = 유지) */
  visibility?: string
  /** 변경할 위젯 배치 JSON (undefined = 유지) */
  layout?: string
  /** 변경할 공유 대상 (undefined = 유지, 빈 배열 = 전체 제거) */
  sharedUserIds?: string[]
  /** OCC 버전 (필수) */
  version: number
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 목록을 페이지네이션으로 조회한다.
 *
 * `GET /api/v1/dashboards?limit=&offset=`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 *
 * @param limit 페이지 크기
 * @param offset 오프셋
 * @returns DashboardPage (items, total, limit, offset)
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 권한 없음 — errorCode: 'NOTIF_DASHBOARD_FORBIDDEN'
 */
export async function listDashboards(limit: number, offset: number): Promise<DashboardPage> {
  const wrapper = dataWrapper(dashboardPageSchema)
  const res = await apiGet(`/api/v1/dashboards?limit=${limit}&offset=${offset}`, wrapper)
  return res.data
}

/**
 * 대시보드 단건을 조회한다.
 *
 * `GET /api/v1/dashboards/{id}`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 *
 * @param id 대시보드 UUID
 * @returns Dashboard 단건
 * @throws ApiError(404) 미존재 — errorCode: 'NOTIF_DASHBOARD_NOT_FOUND'
 * @throws ApiError(401) 미인증
 */
export async function getDashboard(id: string): Promise<Dashboard> {
  const wrapper = dataWrapper(dashboardSchema)
  const res = await apiGet(`/api/v1/dashboards/${id}`, wrapper)
  return res.data
}

/**
 * 대시보드를 생성한다.
 *
 * `POST /api/v1/dashboards`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다 (double submit cookie 패턴).
 *
 * @param body 생성 요청 바디
 * @returns Dashboard 생성된 대시보드
 * @throws ApiError(400) 유효성 실패 — errorCode: 'NOTIF_DASHBOARD_INVALID'
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 권한 없음
 */
export async function createDashboard(body: CreateDashboardRequest): Promise<Dashboard> {
  const res = await apiFetch('/api/v1/dashboards', {
    method: 'POST',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const parsed: unknown = await res.json()
  return dataWrapper(dashboardSchema).parse(parsed).data
}

/**
 * 대시보드를 부분 수정한다.
 *
 * `PATCH /api/v1/dashboards/{id}`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - version 필드는 OCC 낙관적 잠금 키로 필수.
 *
 * @param id 대시보드 UUID
 * @param body 수정 요청 바디 (version 필수)
 * @returns Dashboard 수정된 대시보드
 * @throws ApiError(404) 미존재 — errorCode: 'NOTIF_DASHBOARD_NOT_FOUND'
 * @throws ApiError(409) 버전 충돌 — errorCode: 'NOTIF_DASHBOARD_CONFLICT'
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 권한 없음
 */
export async function patchDashboard(id: string, body: PatchDashboardRequest): Promise<Dashboard> {
  const res = await apiFetch(`/api/v1/dashboards/${id}`, {
    method: 'PATCH',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const parsed: unknown = await res.json()
  return dataWrapper(dashboardSchema).parse(parsed).data
}

/**
 * 대시보드를 삭제한다.
 *
 * `DELETE /api/v1/dashboards/{id}`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 204 No Content 성공 — Zod parse 없이 반환.
 *
 * @param id 대시보드 UUID
 * @returns void
 * @throws ApiError(404) 미존재 — errorCode: 'NOTIF_DASHBOARD_NOT_FOUND'
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 권한 없음 (소유자 아님)
 */
export async function deleteDashboard(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/dashboards/${id}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 공유 토큰 관리 API DTO 1:1 정합
// (백엔드 DashboardShareDtos.kt / PublicDashboardDtos.kt, FR-DB-03 PR1 #216 그대로)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 공유 토큰 발급 응답 Zod 스키마 — 백엔드 IssuedShareTokenResponse 1:1.
 *
 * - token: 원문 공유 토큰. 이 응답에서만 노출되며 이후로는 재조회 불가(DB에는 해시만 저장).
 *   호출측이 컴포넌트 state로 보관해야 한다.
 * - expiresAt: `@JsonInclude(NON_NULL)`로 null이면 키 자체 생략됨 → .nullish() 필수.
 */
export const issuedShareTokenSchema = z.object({
  /** 공유 토큰 식별자 (UUID) */
  id: z.string().uuid(),
  /** 원문 공유 토큰 (1회 노출) */
  token: z.string(),
  /** 발급 시각 (ISO 8601) */
  createdAt: z.string(),
  /** 만료 시각 (ISO 8601) — @JsonInclude(NON_NULL)로 키 생략 가능(무기한 발급) */
  expiresAt: z.string().nullish(),
})

/** 공유 토큰 발급 응답 타입 — z.infer 자동 추론 */
export type IssuedShareToken = z.infer<typeof issuedShareTokenSchema>

/**
 * 공유 토큰 목록 항목 요약 Zod 스키마 — 백엔드 ShareTokenSummaryResponse 1:1.
 *
 * ⚠️ 회귀가드: token/tokenHash 필드는 백엔드 DTO에 존재하지 않는다(설계 시점 구조적 배제).
 * 이 스키마도 동일하게 token을 선언하지 않는다 — 응답에 실수로 섞여도 z.object가 미선언 키를
 * 파싱 결과에서 제거하므로 프론트가 목록 화면에서 원문을 노출할 방법이 없다.
 */
export const shareTokenSummarySchema = z.object({
  /** 공유 토큰 식별자 (UUID) */
  id: z.string().uuid(),
  /** 발급 시각 (ISO 8601) */
  createdAt: z.string(),
  /** 만료 시각 (ISO 8601) — @JsonInclude(NON_NULL)로 키 생략 가능 */
  expiresAt: z.string().nullish(),
})

/** 공유 토큰 요약 타입 — z.infer 자동 추론 */
export type ShareTokenSummary = z.infer<typeof shareTokenSummarySchema>

/** 공유 토큰 목록 응답 Zod 스키마 — 백엔드 ShareTokenListResponse 1:1 (items 배열 1개) */
export const shareTokenListSchema = z.object({
  /** 발급된 공유 토큰 요약 목록 */
  items: z.array(shareTokenSummarySchema),
})

/** 공유 토큰 목록 타입 — z.infer 자동 추론 */
export type ShareTokenList = z.infer<typeof shareTokenListSchema>

/**
 * 익명 공개 대시보드 조회 응답 Zod 스키마 — 백엔드 PublicDashboardResponse 1:1.
 *
 * - layout: 백엔드가 정화(데이터 가젯 config 제거)한 위젯 배치 JSON *문자열* — 파싱된 객체가
 *   아니다. 호출측이 `JSON.parse` 후 렌더링해야 한다.
 * - description: 백엔드 DTO에 `@JsonInclude` 미부착이라 null이면 키가 존재하되 값이 null이다.
 *   dashboardSchema의 description(@JsonInclude NON_NULL, 키 생략)과 다르지만 .nullish()가
 *   undefined/null 양쪽을 모두 허용하므로 두 형태 모두 안전하게 파싱된다.
 */
export const publicDashboardSchema = z.object({
  /** 대시보드 이름 */
  name: z.string(),
  /** 설명 (없으면 null 또는 키 생략) */
  description: z.string().nullish(),
  /** 정화된 위젯 배치 JSON 문자열 — 렌더링 전 JSON.parse 필요 */
  layout: z.string(),
})

/** 공개 대시보드 조회 타입 — z.infer 자동 추론 */
export type PublicDashboard = z.infer<typeof publicDashboardSchema>

/**
 * 공유 토큰 발급 요청 바디.
 * expiresAt 생략 시 무기한 발급(백엔드 IssueShareTokenRequest.expiresAt 선택과 동일).
 */
export interface IssueShareTokenRequest {
  /** 만료 시각 (ISO 8601, 선택 — 생략 시 무기한) */
  expiresAt?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 토큰 관리 API 함수 — 소유자 전용 (인증 필요)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 대시보드 공유 토큰을 발급한다 (소유자 전용).
 *
 * `POST /api/v1/dashboards/{id}/shares`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 응답의 원문 token은 이 호출 1회만 노출된다(이후 재조회 불가 — DB에는 해시만 저장).
 *   호출측이 반환값을 state로 보관해야 링크 복사·임베드 스니펫을 제공할 수 있다.
 *
 * @param id 대상 대시보드 UUID
 * @param body 발급 요청 바디 (expiresAt 선택, 생략 시 무기한)
 * @returns IssuedShareToken (원문 token 포함)
 * @throws ApiError(400) 발급 상한(20개) 초과 — errorCode: 'NOTIF_DASHBOARD_SHARE_LIMIT_EXCEEDED'
 * @throws ApiError(403) 소유자 아님 — errorCode: 'NOTIF_DASHBOARD_FORBIDDEN'
 * @throws ApiError(404) 대시보드 미존재 — errorCode: 'NOTIF_DASHBOARD_NOT_FOUND'
 * @throws ApiError(401) 미인증
 */
export async function issueShareToken(
  id: string,
  body: IssueShareTokenRequest = {},
): Promise<IssuedShareToken> {
  const res = await apiFetch(`/api/v1/dashboards/${id}/shares`, {
    method: 'POST',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const parsed: unknown = await res.json()
  return dataWrapper(issuedShareTokenSchema).parse(parsed).data
}

/**
 * 대시보드에 발급된 공유 토큰 목록을 조회한다 (소유자 전용).
 *
 * `GET /api/v1/dashboards/{id}/shares`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 * - 응답에는 원문·해시가 포함되지 않는다(요약 DTO에 필드 자체가 없음 — 유출 회귀가드).
 *
 * @param id 대상 대시보드 UUID
 * @returns ShareTokenList (items: 요약 목록)
 * @throws ApiError(403) 소유자 아님 — errorCode: 'NOTIF_DASHBOARD_FORBIDDEN'
 * @throws ApiError(404) 대시보드 미존재 — errorCode: 'NOTIF_DASHBOARD_NOT_FOUND'
 * @throws ApiError(401) 미인증
 */
export async function listShareTokens(id: string): Promise<ShareTokenList> {
  const wrapper = dataWrapper(shareTokenListSchema)
  const res = await apiGet(`/api/v1/dashboards/${id}/shares`, wrapper)
  return res.data
}

/**
 * 대시보드 공유 토큰을 취소한다 (소유자 전용).
 *
 * `DELETE /api/v1/dashboards/{id}/shares/{shareId}`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 204 No Content 성공 — Zod parse 없이 반환.
 *
 * @param id 대상 대시보드 UUID
 * @param shareId 취소할 공유 토큰 UUID
 * @returns void
 * @throws ApiError(404) 공유 토큰 미존재 — errorCode: 'NOTIF_DASHBOARD_SHARE_NOT_FOUND'
 * @throws ApiError(403) 소유자 아님 — errorCode: 'NOTIF_DASHBOARD_FORBIDDEN'
 * @throws ApiError(401) 미인증
 */
export async function revokeShareToken(id: string, shareId: string): Promise<void> {
  const res = await apiFetch(`/api/v1/dashboards/${id}/shares/${shareId}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 익명 공개 조회 API 함수 — 비인증 경로 (raw fetch, apiFetch 금지)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 불투명 공유 토큰으로 익명 공개 대시보드를 조회한다 (비인증 경로).
 *
 * `GET /api/v1/public/dashboards/{token}`
 * - ⚠️ raw fetch 사용 — apiFetch 절대 금지. apiFetch는 401 응답 시 자동으로
 *   `/api/v1/auth/refresh`를 호출하고 Authorization 헤더를 부착하는데, 이 경로는 익명
 *   (permitAll)이라 세션 개념 자체가 없다. apiFetch를 쓰면 spurious refresh 재시도가
 *   발생한다 (memory: auth-pre-session-401-raw-fetch, webauthn.ts의 raw fetch 선례를 따름).
 * - credentials·X-XSRF-TOKEN 헤더 불요 — 접근 제어는 오직 불투명 토큰의 소지 여부로만
 *   이뤄진다(직교 토큰, ADR 2026-07-02-fr-db-03-dashboard-share).
 * - layout은 JSON *문자열*이므로 호출측이 `JSON.parse` 해야 한다
 *   (백엔드 PublicDashboardResponse.layout: String).
 *
 * @param token 원문 공유 토큰 (경로 세그먼트)
 * @returns PublicDashboard (name, description?, layout=JSON 문자열)
 * @throws ApiError(404) 미존재·만료·취소·부모 삭제 — errorCode: 'NOTIF_DASHBOARD_NOT_FOUND'
 *   (모든 실패 사유가 404로 수렴 — 존재 열거 차단, 로그인 리다이렉트 없음)
 */
export async function getPublicDashboard(token: string): Promise<PublicDashboard> {
  const res = await fetch(`/api/v1/public/dashboards/${token}`)
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const parsed: unknown = await res.json()
  return dataWrapper(publicDashboardSchema).parse(parsed).data
}
