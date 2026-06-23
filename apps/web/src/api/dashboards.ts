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
