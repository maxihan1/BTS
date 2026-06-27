// 저장된 필터 REST API 클라이언트 + Zod 스키마 (FR-SR-03)
import { z } from 'zod'
import { apiFetch, apiGet, apiPost, ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — 백엔드 SavedFilterExceptionHandler errorCode 열거값
// backend 정본: search-export-import SavedFilterExceptionHandler.kt — 변경 시 동반
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 필터 BC errorCode 상수.
 * 컴포넌트 switch/if 분기에서 사용 (error-key drift 방지 — PR #106 교훈).
 */
export const SAVED_FILTER_ERROR_CODES = {
  /** AQL 문법 오류 (400) */
  SYNTAX_ERROR: 'SEARCH_SYNTAX_ERROR',
  /** 입력 검증 실패 (400) */
  VALIDATION_FAILED: 'SEARCH_VALIDATION_FAILED',
  /** 접근 권한 없음 (403) */
  FORBIDDEN: 'SEARCH_FILTER_FORBIDDEN',
  /** 필터 없음 (404) */
  NOT_FOUND: 'SEARCH_FILTER_NOT_FOUND',
  /** 이름 중복 (409) */
  NAME_CONFLICT: 'SEARCH_FILTER_NAME_CONFLICT',
  /** OCC 충돌 (409) */
  CONFLICT: 'SEARCH_FILTER_CONFLICT',
  /** 미인증 (401) */
  UNAUTHENTICATED: 'SEARCH_UNAUTHENTICATED',
} as const

/** 저장 필터 errorCode 유니온 */
export type SavedFilterErrorCode = (typeof SAVED_FILTER_ERROR_CODES)[keyof typeof SAVED_FILTER_ERROR_CODES]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 SavedFilterDtos.kt와 1:1 미러
// backend 정본: SavedFilterDtos.kt — 변경 시 동반 수정
// invent 금지: 필드 추가 전 SavedFilterDtos.kt grep 필수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 공유 대상 응답 스키마.
 * backend 정본 ShareDto — shareType은 PROJECT|GROUP|AUTHENTICATED enum.
 * targetId는 AUTHENTICATED일 때 null.
 */
export const shareDtoSchema = z.object({
  shareType: z.enum(['PROJECT', 'GROUP', 'AUTHENTICATED']),
  targetId: z.string().nullable(),
})

/** ShareDto 타입 */
export type ShareDto = z.infer<typeof shareDtoSchema>

/**
 * 저장 필터 응답 스키마.
 * backend 정본 SavedFilterResponse — bare 반환 (래퍼 없음, C2).
 * createdAt/updatedAt는 Instant? → JSON null 가능 (.nullable(), .default() 아님).
 */
export const savedFilterSchema = z.object({
  id: z.string().uuid(),
  ownerId: z.string().uuid(),
  name: z.string(),
  aqlQuery: z.string(),
  projectKey: z.string(),
  createdAt: z.string().nullable(),
  updatedAt: z.string().nullable(),
  version: z.number().int(),
  isOwner: z.boolean(),
  shares: z.array(shareDtoSchema),
})

/** SavedFilterResponse 타입 */
export type SavedFilterResponse = z.infer<typeof savedFilterSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 타입 — backend SavedFilterDtos.kt와 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/** 공유 대상 요청 항목 — backend ShareRequest 미러 */
export interface ShareRequest {
  shareType: string
  targetId: string | null
}

/** 필터 생성 요청 — backend SavedFilterCreateRequest 미러 */
export interface CreateFilterRequest {
  name: string
  aqlQuery: string
  projectKey: string
  shares?: ShareRequest[]
}

/**
 * 필터 수정 요청 — backend SavedFilterUpdateRequest 미러.
 * name/aqlQuery/version 전부 필수. version은 OCC 검사용.
 */
export interface UpdateFilterRequest {
  name: string
  aqlQuery: string
  version: number
  shares?: ShareRequest[]
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 내가 소유한 저장 필터 목록을 조회한다.
 * GET /api/v1/filters → bare SavedFilterResponse[] (래퍼 없음).
 * backend 정본 SavedFilterDtos.kt, 변경 시 동반.
 *
 * @throws ApiError — 401 미인증, 403 권한 없음
 */
export async function fetchOwnedFilters(): Promise<SavedFilterResponse[]> {
  return apiGet('/api/v1/filters', z.array(savedFilterSchema))
}

/**
 * 공유된 저장 필터 목록을 조회한다.
 * GET /api/v1/filters/shared?page=&size= → bare SavedFilterResponse[] (래퍼 없음).
 * backend 정본 SavedFilterDtos.kt, 변경 시 동반.
 *
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @throws ApiError — 401 미인증
 */
export async function fetchSharedFilters(page: number, size: number): Promise<SavedFilterResponse[]> {
  return apiGet(`/api/v1/filters/shared?page=${page}&size=${size}`, z.array(savedFilterSchema))
}

/**
 * 저장 필터 단건을 조회한다.
 * GET /api/v1/filters/{id} → SavedFilterResponse.
 * backend 정본 SavedFilterDtos.kt, 변경 시 동반.
 *
 * @param id 필터 UUID
 * @throws ApiError — 401 미인증, 403 권한 없음, 404 없음
 */
export async function fetchFilter(id: string): Promise<SavedFilterResponse> {
  return apiGet(`/api/v1/filters/${id}`, savedFilterSchema)
}

/**
 * 저장 필터를 생성한다.
 * POST /api/v1/filters → 201 Created, SavedFilterResponse.
 * backend 정본 SavedFilterDtos.kt SavedFilterCreateRequest, 변경 시 동반.
 *
 * @param request 생성 요청 바디
 * @throws ApiError — 400 검증 실패, 401 미인증, 409 이름 중복
 */
export async function createFilter(request: CreateFilterRequest): Promise<SavedFilterResponse> {
  return apiPost('/api/v1/filters', request, savedFilterSchema)
}

/**
 * 저장 필터를 수정한다.
 * PUT /api/v1/filters/{id} → 200 OK, SavedFilterResponse.
 * client에 apiPut 헬퍼 없음 — apiFetch 직접 사용 (favorites 선례).
 * backend 정본 SavedFilterDtos.kt SavedFilterUpdateRequest, 변경 시 동반.
 *
 * @param id 필터 UUID
 * @param request 수정 요청 바디 (name/aqlQuery/version 전부 필수)
 * @throws ApiError — 400 검증 실패, 401 미인증, 403 권한 없음, 404 없음, 409 충돌
 */
export async function updateFilter(id: string, request: UpdateFilterRequest): Promise<SavedFilterResponse> {
  const res = await apiFetch(`/api/v1/filters/${id}`, { method: 'PUT', body: request })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return savedFilterSchema.parse(raw)
}

/**
 * 저장 필터를 삭제한다.
 * DELETE /api/v1/filters/{id} → 204 No Content.
 * client에 apiDelete 헬퍼 없음 — apiFetch 직접 사용 (favorites 선례).
 * backend 정본 SavedFilterDtos.kt, 변경 시 동반.
 *
 * @param id 필터 UUID
 * @throws ApiError — 401 미인증, 403 권한 없음, 404 없음
 */
export async function deleteFilter(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/filters/${id}`, { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 헬퍼 — TanStack Query queryKey 팩토리
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 저장 필터 TanStack Query 키 팩토리.
 * backend 정본 SavedFilterDtos.kt, 변경 시 동반.
 *
 * - `all()` — mutation invalidate 용 접두사. `['saved-filters']` 전체 일괄 무효화.
 * - `owned()` — 내 필터 목록.
 * - `shared(page, size)` — 공유된 필터 목록 (페이지 포함).
 * - `detail(id)` — 필터 단건.
 */
export const savedFiltersKey = {
  /** 저장 필터 전체 접두사 — mutation invalidate 용 */
  all: (): readonly ['saved-filters'] => ['saved-filters'],
  /** 내 필터 목록 키 */
  owned: (): readonly ['saved-filters', 'owned'] => ['saved-filters', 'owned'],
  /** 공유된 필터 목록 키 (페이지 포함) */
  shared: (page: number, size: number): readonly ['saved-filters', 'shared', number, number] =>
    ['saved-filters', 'shared', page, size],
  /** 필터 단건 키 */
  detail: (id: string): readonly ['saved-filters', 'detail', string] =>
    ['saved-filters', 'detail', id],
}
