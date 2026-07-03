// 보드 퀵필터 REST API 클라이언트 — Zod 스키마 + CRUD fetch 함수 (FR-UX-01)
import { z } from 'zod'
import { apiFetch, apiPost, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 파싱 (boards.ts 동일 패턴 로컬 재정의)
//
// boards.ts의 dataResponseSchema는 export 안 됨 — issue-links.ts:16, epic-children.ts:15와
// 동일한 관례로 이 파일에서 로컬 재정의한다.
//
// {data: T} 래퍼 채택 근거: 이 BC(agile-planning)의 BoardController가 생성/조회/이동/스윔레인
// 변경 등 모든 응답을 예외 없이 DataResponse<T>로 감싼다(BoardController.kt 전수 확인). 신규
// BoardQuickFilterController(T6, 병렬 wave 진행 중)도 같은 BC 관례를 따를 것으로 가정한다
// (memory: frontend-api-convention-per-bc — 같은 BC 선례 계승).
// ─────────────────────────────────────────────────────────────────────────────

/** backend 공통 응답 래퍼 `{ data: T }` 파싱 헬퍼 (boards.ts 동일 패턴) */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 QuickFilterResponse DTO 미러 (spec API 인터페이스 §, FR-UX-01)
// backend 정본(예정): BoardQuickFilterController.kt / QuickFilterDto.kt (plan T6/T7) — 변경 시 동반
// invent 금지: 필드는 spec 계약({filterId, name, query})에서만 도출 — memory: frontend-zod-backend-dto-contract-gap
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 퀵필터 응답 스키마.
 * 백엔드 `QuickFilterResponse` DTO 대응 — `{filterId, name, query}` (spec API 인터페이스 §).
 */
export const quickFilterSchema = z.object({
  /** 퀵필터 UUID */
  filterId: z.string().uuid(),
  /** 표시 이름. 보드 내 유일(EC2 — 서버가 409로 중복 거부). */
  name: z.string(),
  /**
   * 정규화된 쿼리스트링. `assignee=<uuid>&label=<name>&component=<uuid>` 형식, 접두 `?` 없음.
   * 백엔드가 `BoardFilterQueryParser`로 파싱 검증 후 재직렬화해 저장한 값을 그대로 반환한다
   * (spec §API 인터페이스 — 정규화 목적은 표시 안정).
   */
  query: z.string(),
})

/** 보드 퀵필터 타입 */
export type QuickFilter = z.infer<typeof quickFilterSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 타입 — backend CreateQuickFilterRequest/UpdateQuickFilterRequest 미러 (spec §API 인터페이스)
// ─────────────────────────────────────────────────────────────────────────────

/** 퀵필터 생성 요청 바디 — backend 미러(name/query 둘 다 필수) */
export interface CreateQuickFilterRequest {
  /** 표시 이름. 공백 불가, 50자 이하(백엔드 검증, EC7). */
  name: string
  /** 쿼리스트링(접두 `?` 없음). 파싱 결과가 빈 필터면 400 거부(EC1). */
  query: string
}

/**
 * 퀵필터 수정 요청 바디 — backend 미러.
 * PATCH도 name/query 전체를 다시 받는다(부분 patch 아님, spec §API 인터페이스).
 */
export interface UpdateQuickFilterRequest {
  /** 표시 이름. 공백 불가, 50자 이하(백엔드 검증, EC7). */
  name: string
  /** 쿼리스트링(접두 `?` 없음). 파싱 결과가 빈 필터면 400 거부(EC1). */
  query: string
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음, saved-filters.ts/boards.ts 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드에 퀵필터를 생성한다.
 *
 * POST /api/v1/boards/{boardId}/quick-filters → 201 { data: QuickFilter } 언랩.
 *
 * @param boardId 보드 UUID
 * @param request 생성 요청 바디(name, query)
 * @returns 생성된 QuickFilter(정규화된 query 포함)
 * @throws ApiError 400(빈 query·검증 실패) · 401 미인증 · 403 CREATE 권한 없음 · 404 보드 없음(또는
 *   soft-deleted) · 409 이름 중복(EC2)
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function createQuickFilter(
  boardId: string,
  request: CreateQuickFilterRequest,
): Promise<QuickFilter> {
  const wrapped = await apiPost(
    `/api/v1/boards/${boardId}/quick-filters`,
    request,
    dataResponseSchema(quickFilterSchema),
  )
  return wrapped.data
}

/**
 * 퀵필터를 수정한다(name, query).
 *
 * PATCH /api/v1/boards/{boardId}/quick-filters/{filterId} → 200 { data: QuickFilter } 언랩.
 * client에 apiPatch 헬퍼 없음 — apiFetch 직접 사용(boards.ts updateBoardSwimlane 동일 패턴).
 * OCC(낙관적 락) 미적용 — 단순 메타, last-write-wins(spec §API 인터페이스).
 *
 * @param boardId 보드 UUID
 * @param filterId 퀵필터 UUID
 * @param request 수정 요청 바디(name, query — 둘 다 필수)
 * @returns 수정된 QuickFilter
 * @throws ApiError 400 · 401 · 403 · 404(보드 없음 또는 타 보드 소속 filterId, EC5) · 409 이름 중복
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function updateQuickFilter(
  boardId: string,
  filterId: string,
  request: UpdateQuickFilterRequest,
): Promise<QuickFilter> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/quick-filters/${filterId}`, {
    method: 'PATCH',
    body: request,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(quickFilterSchema).parse(raw)
  return wrapped.data
}

/**
 * 퀵필터를 삭제한다.
 *
 * DELETE /api/v1/boards/{boardId}/quick-filters/{filterId} → 204 No Content.
 * client에 apiDelete 헬퍼 없음 — apiFetch 직접 사용(saved-filters.ts deleteFilter 동일 패턴).
 *
 * @param boardId 보드 UUID
 * @param filterId 퀵필터 UUID
 * @throws ApiError 401 · 403 · 404(보드 없음 또는 타 보드 소속 filterId, EC5)
 */
export async function deleteQuickFilter(boardId: string, filterId: string): Promise<void> {
  const res = await apiFetch(`/api/v1/boards/${boardId}/quick-filters/${filterId}`, {
    method: 'DELETE',
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
