// 개인 알림 보관함(Inbox) API 클라이언트 + Zod 스키마 + React Query 훅 (FR-UX-03)
import { z } from 'zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** Inbox baseURL — 경로 변경 시 단일 위치에서 수정 */
const INBOX_BASE = '/api/v1/users/me/inbox'

/** 탭 필터 상수 — 백엔드 InboxTab enum과 1:1 미러 */
export const INBOX_TABS = {
  /** 보관되지 않은 전체 (읽음 무관) */
  ALL: 'ALL',
  /** 미읽음 + 미보관 */
  UNREAD: 'UNREAD',
  /** 보관된 항목 */
  ARCHIVED: 'ARCHIVED',
} as const

/** Inbox 탭 유니온 타입 */
export type InboxTab = (typeof INBOX_TABS)[keyof typeof INBOX_TABS]

/** 에러 코드 상수 — 백엔드 InboxExceptionHandler 에러코드 열거 미러 */
export const INBOX_ERROR_CODES = {
  /** Inbox 항목 미존재 */
  INBOX_ITEM_NOT_FOUND: 'INBOX_ITEM_NOT_FOUND',
} as const

/** Inbox errorCode 유니온 타입 */
export type InboxErrorCode = (typeof INBOX_ERROR_CODES)[keyof typeof INBOX_ERROR_CODES]

// ─────────────────────────────────────────────────────────────────────────────
// 필터 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inbox 목록 조회 필터.
 * 백엔드 InboxListRequest 쿼리 파라미터와 1:1 대응.
 */
export interface InboxFilters {
  /** 탭 필터 (ALL | UNREAD | ARCHIVED, 기본 ALL) */
  tab?: InboxTab
  /** 제목/본문 검색어 */
  q?: string
  /** 발신자 UUID 필터 */
  senderId?: string
  /** 이슈 키 필터 (예: ATLAS-1) */
  issueKey?: string
  /** 생성일 시작 (ISO 8601) */
  from?: string
  /** 생성일 종료 (ISO 8601) */
  to?: string
  /** 페이지 번호 (0-based) */
  page?: number
  /** 페이지 크기 (max 100) */
  size?: number
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inbox 단건 항목 스키마.
 * 백엔드 InboxItemResponse와 정확히 1:1 대응.
 * Instant 필드는 ISO-8601 문자열로 직렬화된다.
 */
export const inboxItemSchema = z.object({
  /** 항목 고유 id (UUID) */
  id: z.string().uuid(),
  /** 이벤트 타입 (예: ISSUE_MENTIONED, ISSUE_ASSIGNED) */
  eventType: z.string(),
  /** 관련 이슈 키 (없으면 null) */
  issueKey: z.string().nullable(),
  /** 알림 제목 */
  title: z.string(),
  /** 알림 본문 (없으면 null) */
  body: z.string().nullable(),
  /** 발신자 사용자 UUID (시스템 발송이면 null) */
  actorUserId: z.string().uuid().nullable(),
  /** 읽음 처리 시각 (미읽음이면 null, ISO-8601) */
  readAt: z.string().datetime().nullable(),
  /** 보관 처리 시각 (미보관이면 null, ISO-8601) */
  archivedAt: z.string().datetime().nullable(),
  /** 알림 생성 시각 (ISO-8601) */
  createdAt: z.string().datetime(),
})

/**
 * Inbox 페이지 응답 스키마.
 * 백엔드 Spring Page<InboxItemResponse>와 1:1 대응.
 * 추가 Spring Page 필드(pageable 등)는 passthrough로 허용한다.
 */
export const inboxPageSchema = z
  .object({
    content: z.array(inboxItemSchema),
    totalElements: z.number().int(),
    totalPages: z.number().int(),
    /** 현재 페이지 번호 (0-based) */
    number: z.number().int(),
    size: z.number().int(),
  })
  .passthrough()

/**
 * 미읽음 카운트 응답 스키마.
 * GET /unread-count → `{ data: { count: number } }`.
 */
export const unreadCountSchema = z.object({
  data: z.object({
    count: z.number().int().min(0),
  }),
})

/**
 * 일괄 읽음 처리 응답 스키마.
 * POST /read-all → `{ data: { updated: number } }`.
 */
export const readAllResponseSchema = z.object({
  data: z.object({
    updated: z.number().int().min(0),
  }),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** Inbox 단건 항목 타입 */
export type InboxItem = z.infer<typeof inboxItemSchema>

/** Inbox 페이지 타입 */
export type InboxPage = z.infer<typeof inboxPageSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inbox 목록 filter-aware 쿼리 키.
 * 뮤테이션 invalidate는 접두사 `['inbox']`로 목록+카운트를 일괄 무효화한다.
 *
 * @param filters 목록 조회 필터
 */
export const inboxKey = (filters: InboxFilters): readonly [string, string, InboxFilters] =>
  ['inbox', 'list', filters] as const

/** 미읽음 카운트 쿼리 키 — 목록과 별도 키로 독립 캐싱 */
export const UNREAD_COUNT_KEY = ['inbox', 'unread-count'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * InboxFilters를 URL 쿼리스트링으로 변환한다.
 * undefined 값은 생략한다.
 */
function buildQueryString(filters: InboxFilters): string {
  const params = new URLSearchParams()
  if (filters.tab !== undefined) params.set('tab', filters.tab)
  if (filters.q !== undefined) params.set('q', filters.q)
  if (filters.senderId !== undefined) params.set('senderId', filters.senderId)
  if (filters.issueKey !== undefined) params.set('issueKey', filters.issueKey)
  if (filters.from !== undefined) params.set('from', filters.from)
  if (filters.to !== undefined) params.set('to', filters.to)
  if (filters.page !== undefined) params.set('page', String(filters.page))
  if (filters.size !== undefined) params.set('size', String(filters.size))
  const qs = params.toString()
  return qs.length > 0 ? `?${qs}` : ''
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inbox 목록을 조회한다.
 * GET /api/v1/users/me/inbox → Spring Page<InboxItemResponse> (래퍼 없음 직접 파싱).
 *
 * @param filters 목록 조회 필터
 * @throws ApiError — 401 미인증
 */
export async function fetchInbox(filters: InboxFilters): Promise<InboxPage> {
  const qs = buildQueryString(filters)
  const res = await apiFetch(`${INBOX_BASE}${qs}`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return inboxPageSchema.parse(raw)
}

/**
 * 미읽음 카운트를 조회한다.
 * GET /api/v1/users/me/inbox/unread-count → `{ data: { count } }` 언랩 후 count 반환.
 *
 * @throws ApiError — 401 미인증
 */
export async function fetchUnreadCount(): Promise<number> {
  const res = await apiFetch(`${INBOX_BASE}/unread-count`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const parsed = unreadCountSchema.parse(raw)
  return parsed.data.count
}

/**
 * 항목의 읽음 상태를 변경한다.
 * PATCH /api/v1/users/me/inbox/{id}/read → 204 (멱등, COALESCE 시각 보존).
 *
 * @param id 항목 UUID
 * @param read 읽음 여부
 * @throws ApiError — 401 미인증, 404 INBOX_ITEM_NOT_FOUND
 */
export async function markRead(id: string, read: boolean): Promise<void> {
  const res = await apiFetch(`${INBOX_BASE}/${id}/read`, {
    method: 'PATCH',
    body: { read },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 항목의 보관 상태를 변경한다.
 * PATCH /api/v1/users/me/inbox/{id}/archive → 204 (멱등, COALESCE 시각 보존).
 *
 * @param id 항목 UUID
 * @param archived 보관 여부
 * @throws ApiError — 401 미인증, 404 INBOX_ITEM_NOT_FOUND
 */
export async function markArchive(id: string, archived: boolean): Promise<void> {
  const res = await apiFetch(`${INBOX_BASE}/${id}/archive`, {
    method: 'PATCH',
    body: { archived },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 항목을 일괄 읽음 처리한다.
 * POST /api/v1/users/me/inbox/read-all → `{ data: { updated } }` 언랩 후 결과 반환.
 * ids가 없으면 미읽음 전체, ids가 있으면 해당 항목만 처리한다.
 *
 * @param ids 읽음 처리할 항목 UUID 배열 (없으면 미읽음 전체)
 * @throws ApiError — 401 미인증
 */
export async function readAll(ids?: string[]): Promise<{ updated: number }> {
  const res = await apiFetch(`${INBOX_BASE}/read-all`, {
    method: 'POST',
    body: ids !== undefined && ids.length > 0 ? { ids } : {},
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const parsed = readAllResponseSchema.parse(raw)
  return parsed.data
}

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Inbox 목록 쿼리 훅.
 * filter-aware queryKey로 탭/검색/페이지별 독립 캐싱.
 *
 * @param filters 목록 조회 필터
 */
export function useInbox(filters: InboxFilters) {
  return useQuery({
    queryKey: inboxKey(filters),
    queryFn: () => fetchInbox(filters),
    staleTime: 30_000,
  })
}

/**
 * 미읽음 카운트 쿼리 훅.
 * Header 종 뱃지에서 사용. staleTime 짧게 설정.
 */
export function useUnreadCount() {
  return useQuery({
    queryKey: UNREAD_COUNT_KEY,
    queryFn: () => fetchUnreadCount(),
    staleTime: 10_000,
  })
}

/**
 * 읽음 상태 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /inbox/{id}/read 호출 → 204
 * 2. onSettled: ['inbox'] 접두사 invalidate → 목록 + 카운트 일괄 refetch
 *
 * setQueryData 금지 — 부분 응답 플리커 방지 (PR #46 교훈).
 */
export function useMarkRead() {
  const queryClient = useQueryClient()
  return useMutation<void, ApiError, { id: string; read: boolean }>({
    mutationFn: ({ id, read }) => markRead(id, read),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['inbox'] })
    },
  })
}

/**
 * 보관 상태 변경 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /inbox/{id}/archive 호출 → 204
 * 2. onSettled: ['inbox'] 접두사 invalidate → 목록 + 카운트 일괄 refetch
 *
 * setQueryData 금지 — 부분 응답 플리커 방지 (PR #46 교훈).
 */
export function useMarkArchive() {
  const queryClient = useQueryClient()
  return useMutation<void, ApiError, { id: string; archived: boolean }>({
    mutationFn: ({ id, archived }) => markArchive(id, archived),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['inbox'] })
    },
  })
}

/**
 * 일괄 읽음 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: POST /inbox/read-all 호출 → 200 `{ data: { updated } }`
 * 2. onSettled: ['inbox'] 접두사 invalidate → 목록 + 카운트 일괄 refetch
 *
 * setQueryData 금지 — 부분 응답 플리커 방지 (PR #46 교훈).
 */
export function useReadAll() {
  const queryClient = useQueryClient()
  return useMutation<{ updated: number }, ApiError, { ids?: string[] }>({
    mutationFn: ({ ids }) => readAll(ids),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: ['inbox'] })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 Inbox BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 * (선례: favorites.ts extractFavoriteErrorCode 동일 패턴 — PR #106 교훈)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractInboxErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
