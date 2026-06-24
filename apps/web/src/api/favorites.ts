// 즐겨찾기 조회/추가/제거 API 클라이언트 + React Query 훅 (FR-UX-02)
import { z } from 'zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 언랩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend 공통 응답 래퍼 `{ data: T }` 파싱 스키마.
 * issue-watchers.ts와 동일한 로컬 재정의 패턴.
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// 타겟 타입 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 대상 타입 상수.
 * 백엔드 FavoriteTargetType enum과 1:1 미러.
 * FILTER는 프론트엔드 미사용(백엔드 전용).
 */
export const FAVORITE_TARGET_TYPES = {
  /** 이슈 즐겨찾기 */
  ISSUE: 'ISSUE',
  /** 대시보드 즐겨찾기 */
  DASHBOARD: 'DASHBOARD',
  /** 프로젝트 즐겨찾기 */
  PROJECT: 'PROJECT',
} as const

/** 즐겨찾기 대상 타입 유니온 */
export type FavoriteTargetType = (typeof FAVORITE_TARGET_TYPES)[keyof typeof FAVORITE_TARGET_TYPES]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 단건 응답 스키마.
 * POST /api/v1/favorites → `{ data: FavoriteResponse }` 언랩.
 */
export const favoriteResponseSchema = z.object({
  /** 즐겨찾기 고유 id */
  id: z.string().min(1),
  /** 즐겨찾기 대상 타입 */
  targetType: z.enum(['ISSUE', 'DASHBOARD', 'PROJECT', 'FILTER']),
  /** 즐겨찾기 대상 id (이슈 키, 대시보드 id 등) */
  targetId: z.string().min(1),
  /** 즐겨찾기 생성 일시 (ISO 8601 — 백엔드 Instant 직렬화 형식) */
  createdAt: z.string().datetime(),
})

/**
 * 즐겨찾기 목록 응답 스키마.
 * GET /api/v1/favorites → `{ data: { items: [...] } }` 언랩.
 */
export const favoriteListResponseSchema = z.object({
  /** 즐겨찾기 목록 (created_at DESC 정렬) */
  items: z.array(favoriteResponseSchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** 즐겨찾기 단건 타입 */
export type FavoriteResponse = z.infer<typeof favoriteResponseSchema>

/** 즐겨찾기 목록 응답 타입 */
export type FavoriteListResponse = z.infer<typeof favoriteListResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 목록 쿼리 키.
 * targetType 있으면 타입 필터 포함, 없으면 전체 키.
 * mutation invalidate는 접두사 `['favorites']`로 전체 일괄 무효화한다.
 *
 * @param targetType 대상 타입 필터 (선택)
 */
export const favoritesKey = (targetType?: FavoriteTargetType): [string] | [string, string] =>
  targetType !== undefined ? ['favorites', targetType] : ['favorites']

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 목록을 조회한다.
 * GET /api/v1/favorites[?targetType=] → `{ data: { items: [...] } }` 언랩 후 items 배열 반환.
 *
 * @param targetType 대상 타입 필터 (선택). 없으면 전체 조회.
 * @throws ApiError — 401 미인증
 */
export async function fetchFavorites(targetType?: FavoriteTargetType): Promise<FavoriteResponse[]> {
  const params = targetType !== undefined ? `?targetType=${targetType}` : ''
  const res = await apiFetch(`/api/v1/favorites${params}`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(favoriteListResponseSchema).parse(raw)
  return wrapped.data.items
}

/**
 * 즐겨찾기를 추가한다.
 * POST /api/v1/favorites body `{ targetType, targetId }` → 201(신규)/200(멱등) `{ data: FavoriteResponse }` 언랩.
 *
 * @param targetType 대상 타입
 * @param targetId 대상 id (이슈 키, 대시보드 id 등)
 * @throws ApiError — 400 NOTIF_FAV_INVALID, 401 미인증
 */
export async function addFavorite(targetType: FavoriteTargetType, targetId: string): Promise<FavoriteResponse> {
  const res = await apiFetch('/api/v1/favorites', {
    method: 'POST',
    body: { targetType, targetId },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(favoriteResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 즐겨찾기를 제거한다.
 * DELETE /api/v1/favorites?targetType=&targetId= → 204 (멱등).
 *
 * @param targetType 대상 타입
 * @param targetId 대상 id
 * @throws ApiError — 401 미인증
 */
export async function removeFavorite(targetType: FavoriteTargetType, targetId: string): Promise<undefined> {
  const res = await apiFetch(
    `/api/v1/favorites?targetType=${targetType}&targetId=${encodeURIComponent(targetId)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return undefined
}

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 목록 쿼리 훅.
 * targetType 없으면 전체 즐겨찾기, 있으면 타입 필터링 조회.
 *
 * @param targetType 대상 타입 필터 (선택)
 */
export function useFavorites(targetType?: FavoriteTargetType) {
  return useQuery({
    queryKey: favoritesKey(targetType),
    queryFn: () => fetchFavorites(targetType),
    staleTime: 30_000,
  })
}

/**
 * 즐겨찾기 추가 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: POST /api/v1/favorites 호출
 * 2. onSettled: ['favorites'] 접두사 invalidate → 타입 필터 캐시 포함 전체 즐겨찾기 refetch
 *
 * setQueryData 금지 — 부분 응답으로 인한 플리커 방지 (PR #46 교훈).
 * 접두사 invalidate로 타입별 필터 캐시도 일괄 무효화한다.
 */
export function useAddFavorite() {
  const queryClient = useQueryClient()
  return useMutation<FavoriteResponse, ApiError, { targetType: FavoriteTargetType; targetId: string }>({
    mutationFn: ({ targetType, targetId }) => addFavorite(targetType, targetId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['favorites'] })
    },
  })
}

/**
 * 즐겨찾기 제거 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: DELETE /api/v1/favorites?targetType=&targetId= 호출 → 204
 * 2. onSettled: ['favorites'] 접두사 invalidate → 전체 즐겨찾기 캐시 refetch
 */
export function useRemoveFavorite() {
  const queryClient = useQueryClient()
  return useMutation<undefined, ApiError, { targetType: FavoriteTargetType; targetId: string }>({
    mutationFn: ({ targetType, targetId }) => removeFavorite(targetType, targetId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['favorites'] })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — backend FavoriteExceptionHandler 에러코드 열거 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 즐겨찾기 BC errorCode 상수.
 * 호출 측(컴포넌트)이 switch/if 분기에서 사용한다.
 * (error-key drift 방지 — PR #106 교훈, 공유 util 경유)
 */
export const FAVORITE_ERROR_CODES = {
  /** 잘못된 즐겨찾기 요청 (targetType 미지원 등) */
  NOTIF_FAV_INVALID: 'NOTIF_FAV_INVALID',
} as const

/** 즐겨찾기 BC errorCode 유니온 타입 */
export type FavoriteErrorCode = (typeof FAVORITE_ERROR_CODES)[keyof typeof FAVORITE_ERROR_CODES]

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 즐겨찾기 BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 * (선례: issue-watchers.ts extractWatcherErrorCode 동일 패턴)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractFavoriteErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
