// 이슈 워처 조회/추가/제거 API 클라이언트 + React Query 훅 (FR-WT-01)
import { z } from 'zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 언랩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend 공통 응답 래퍼 `{ data: T }` 파싱 스키마.
 * issue-links.ts와 동일한 로컬 재정의 패턴.
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/** 워처 단건 요약 스키마. */
export const watcherSummarySchema = z.object({
  /**
   * 워처 사용자 id.
   * .uuid() 대신 .min(1) 을 사용한다.
   * whoami userId (z.string()) 는 픽스처에서 all-zeros 형식(00000000-...-000001)이며
   * self-watch 라운드트립에서 watcher.userId 와 useAuthUser().userId 가 같은 id 공간을 공유한다.
   * Zod 4.4.3 uuid() 는 all-zeros variant id 를 거부하므로, watcher.userId 도 관대하게 처리해야
   * E2E 및 self-unwatch 라운드트립이 정상 동작한다. 프로덕션 실 UUID 는 .min(1) 도 통과한다.
   */
  userId: z.string().min(1),
  /**
   * 화면에 표시할 이름.
   * .min(1) 이 아닌 .string() 을 사용한다.
   * 백엔드 WatcherSummary.displayName 은 사용자 미존재 시 빈 문자열("")을 반환한다.
   * 한 명의 빈 displayName 이 목록 전체 ZodError를 일으켜 섹션 전체가 에러 상태가 되는 것을 방지한다.
   */
  displayName: z.string(),
})

/**
 * 워처 목록 응답 스키마.
 * GET /api/v1/issues/{key}/watchers → `{ data: WatcherListResponse }` 언랩.
 */
export const watcherListResponseSchema = z.object({
  /** 워처 목록 */
  watchers: z.array(watcherSummarySchema),
  /** 워처 총 수 */
  count: z.number().int().nonnegative(),
  /** 현재 인증 사용자가 워칭 중인지 여부 */
  isWatching: z.boolean(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** 워처 단건 요약 타입 */
export type WatcherSummary = z.infer<typeof watcherSummarySchema>

/** 워처 목록 응답 타입 */
export type WatcherListResponse = z.infer<typeof watcherListResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 워처 목록 쿼리 키.
 * issueQueryKey(['issue', key])와 분리된 독립 키를 사용한다.
 */
export const issueWatchersKey = (key: string): [string, string, string] =>
  ['issue-watchers', key, 'list']

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 워처 목록을 조회한다.
 * GET /api/v1/issues/{key}/watchers → `{ data: WatcherListResponse }` 언랩.
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 * @throws ApiError — 4xx 응답 시 (ISSUE_NOT_FOUND 404, ISSUE_ACCESS_DENIED 403)
 */
export async function fetchWatchers(key: string): Promise<WatcherListResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/watchers`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(watcherListResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈 워처를 추가한다.
 * POST /api/v1/issues/{key}/watchers → 201 No Content (본문 없음, 멱등).
 *
 * userId 없이 호출하면 인증 사용자 본인(self)을 추가한다.
 * userId를 명시하면 해당 사용자를 추가한다(UPDATE 권한 필요).
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 * @param userId 추가할 사용자 UUID. 생략 시 self 추가.
 * @throws ApiError — ISSUE_NOT_FOUND(404), ISSUE_ACCESS_DENIED(403), ISSUE_WATCHER_USER_NOT_FOUND(422)
 */
export async function addWatcher(key: string, userId?: string): Promise<undefined> {
  const res = await apiFetch(`/api/v1/issues/${key}/watchers`, {
    method: 'POST',
    // userId가 있을 때만 body를 포함한다 (self 추가 시 body 없음)
    ...(userId !== undefined ? { body: { userId } } : {}),
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return undefined
}

/**
 * 이슈 워처를 제거한다.
 * DELETE /api/v1/issues/{key}/watchers/{userId} → 204 No Content (멱등).
 *
 * 이미 워처가 아닌 경우도 204를 반환한다.
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 * @param userId 제거할 사용자 UUID
 * @throws ApiError — ISSUE_NOT_FOUND(404), ISSUE_ACCESS_DENIED(403)
 */
export async function removeWatcher(key: string, userId: string): Promise<undefined> {
  const res = await apiFetch(`/api/v1/issues/${key}/watchers/${userId}`, { method: 'DELETE' })
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
 * 이슈 워처 목록 쿼리 훅.
 * queryKey는 issueWatchersKey(key)로 issueQueryKey와 독립 분리됨.
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 */
export function useWatchers(key: string) {
  return useQuery({
    queryKey: issueWatchersKey(key),
    queryFn: () => fetchWatchers(key),
    staleTime: 30_000,
  })
}

/**
 * 이슈 워처 추가 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: POST /api/v1/issues/{key}/watchers 호출
 * 2. onSettled: issueWatchersKey(key) invalidate → 워처 목록 refetch
 *
 * setQueryData 금지 — 부분 응답으로 인한 플리커 방지 (PR #46 교훈).
 *
 * @param key 이슈 키
 */
export function useAddWatcher(key: string) {
  const queryClient = useQueryClient()
  return useMutation<undefined, ApiError, string | undefined>({
    mutationFn: (userId: string | undefined) => addWatcher(key, userId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: issueWatchersKey(key) })
    },
  })
}

/**
 * 이슈 워처 제거 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: DELETE /api/v1/issues/{key}/watchers/{userId} 호출 → 204
 * 2. onSettled: issueWatchersKey(key) invalidate → 워처 목록 refetch
 *
 * @param key 이슈 키
 */
export function useRemoveWatcher(key: string) {
  const queryClient = useQueryClient()
  return useMutation<undefined, ApiError, string>({
    mutationFn: (userId: string) => removeWatcher(key, userId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: issueWatchersKey(key) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — backend WatcherExceptionHandler 에러코드 열거 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 워처 BC errorCode 상수.
 * 호출 측(컴포넌트)이 switch/if 분기에서 사용한다.
 * (error-key drift 방지 — PR #106 교훈, 공유 util 경유)
 */
export const ISSUE_WATCHER_ERROR_CODES = {
  /** 이슈를 찾을 수 없음 */
  ISSUE_NOT_FOUND: 'ISSUE_NOT_FOUND',
  /** 접근 권한 없음 */
  ISSUE_ACCESS_DENIED: 'ISSUE_ACCESS_DENIED',
  /** 추가하려는 사용자를 찾을 수 없음 */
  ISSUE_WATCHER_USER_NOT_FOUND: 'ISSUE_WATCHER_USER_NOT_FOUND',
  /** 요청 유효성 검사 실패 */
  ISSUE_WATCHER_VALIDATION_FAILED: 'ISSUE_WATCHER_VALIDATION_FAILED',
  /** 내부 서버 에러 */
  ISSUE_INTERNAL_ERROR: 'ISSUE_INTERNAL_ERROR',
} as const

/** 이슈 워처 BC errorCode 유니온 타입 */
export type IssueWatcherErrorCode = (typeof ISSUE_WATCHER_ERROR_CODES)[keyof typeof ISSUE_WATCHER_ERROR_CODES]

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 이슈 워처 BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 * (선례: issue-links.ts extractLinkErrorCode 동일 패턴)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractWatcherErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
