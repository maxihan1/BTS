// 이슈 링크(blocks/relates/duplicates/clones) + parent-child API 클라이언트 및 TanStack Query 훅 — FR-LK-01
import { z } from 'zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 언랩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend 공통 응답 래퍼 `{ data: T }` 파싱 스키마.
 * issues.ts의 dataResponseSchema는 export 안 됨 — 이 파일에서 로컬 재정의한다.
 * (형제 issue-versions.ts:32도 동일 패턴으로 로컬 재정의)
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 단건 응답 스키마.
 * ⚠️ linkType 비대칭 주의.
 * - 요청(createLink): 소문자 ("blocks")
 * - 응답(backend): 대문자 ("BLOCKS")
 */
export const issueLinkResponseSchema = z.object({
  /** 링크 ID (number) — DELETE /links/{linkId}의 경로 파라미터 */
  id: z.number().int().positive(),
  /** 링크 유형 (대문자) — "BLOCKS" | "RELATES" | "DUPLICATES" | "CLONES" */
  linkType: z.string().min(1),
  /** 방향 — OUTWARD(이 이슈가 대상 이슈에 관계됨) / INWARD(대상 이슈가 이 이슈에 관계됨) */
  direction: z.enum(['OUTWARD', 'INWARD']),
  /** 사람이 읽을 수 있는 관계 레이블 */
  label: z.string(),
  /** 링크된 상대 이슈 요약 */
  otherIssue: z.object({
    key: z.string().min(1),
    summary: z.string(),
    statusKey: z.string(),
  }),
})

/** 링크 목록 응답 스키마 — outward/inward 이분 */
export const linkListResponseSchema = z.object({
  /** 이 이슈가 능동적으로 링크한 목록 */
  outward: z.array(issueLinkResponseSchema),
  /** 이 이슈에 링크된(피동) 목록 */
  inward: z.array(issueLinkResponseSchema),
})

/**
 * PATCH /parent 응답 스키마.
 * parent는 @JsonInclude(NON_NULL) — null이면 JSON 키 자체 생략 → nullish() 사용.
 */
export const issueParentResponseSchema = z.object({
  key: z.string().min(1),
  parent: z.object({
    key: z.string().min(1),
    summary: z.string(),
  }).nullish(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 링크 단건 응답 타입 */
export type IssueLinkResponse = z.infer<typeof issueLinkResponseSchema>

/** 링크 목록 응답 타입 */
export type LinkListResponse = z.infer<typeof linkListResponseSchema>

/** PATCH /parent 응답 타입 */
export type IssueParentResponse = z.infer<typeof issueParentResponseSchema>

/** 링크 생성 요청 입력 타입 */
export interface CreateLinkInput {
  /** 대상 이슈 키 (예: "ATLAS-2") */
  targetKey: string
  /**
   * 링크 유형 — 소문자로 전송해야 한다 (백엔드 계약).
   * "blocks" | "relates" | "duplicates" | "clones"
   */
  linkType: string
}

/** PATCH /parent 요청 입력 타입 */
export interface SetParentInput {
  /** 부모 이슈 키. null이면 부모 해제. */
  parentKey: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 목록 쿼리 키.
 * issueQueryKey(['issue', key])와 분리된 독립 키를 사용한다.
 */
export const issueLinksKey = (key: string): [string, string, string] =>
  ['issue-links', key, 'list']

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 목록을 조회한다.
 * GET /api/v1/issues/{key}/links → `{ data: { outward, inward } }` 언랩
 *
 * @throws ApiError — 4xx 응답 시
 */
export async function fetchIssueLinks(key: string): Promise<LinkListResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/links`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(linkListResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈 링크를 생성한다.
 * POST /api/v1/issues/{key}/links → 201 + `{ data: IssueLinkResponse }` 언랩
 *
 * ⚠️ linkType은 소문자로 전송해야 한다 ("blocks", not "BLOCKS").
 *
 * @throws ApiError — 4xx 응답 시 (LINK_SELF_REFERENCE 422, DUPLICATE_LINK 409, LINK_CYCLE 409, INVALID_LINK_TYPE 400, ISSUE_NOT_FOUND 404)
 */
export async function createLink(key: string, input: CreateLinkInput): Promise<IssueLinkResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/links`, {
    method: 'POST',
    body: input,
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueLinkResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈 링크를 삭제한다.
 * DELETE /api/v1/issues/{key}/links/{linkId} → 204 No Content
 *
 * @throws ApiError — LINK_NOT_FOUND(404) 등 4xx 응답 시
 */
export async function deleteLink(key: string, linkId: number): Promise<undefined> {
  const res = await apiFetch(`/api/v1/issues/${key}/links/${linkId}`, { method: 'DELETE' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return undefined
}

/**
 * 이슈의 부모를 설정한다.
 * PATCH /api/v1/issues/{key}/parent body { parentKey: string } → 200 + `{ data: IssueParentResponse }`
 *
 * @throws ApiError — PARENT_SELF_REFERENCE(422), PARENT_CYCLE(409), ISSUE_NOT_FOUND(404) 등
 */
export async function setParent(key: string, parentKey: string): Promise<IssueParentResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/parent`, {
    method: 'PATCH',
    body: { parentKey },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueParentResponseSchema).parse(raw)
  return wrapped.data
}

/**
 * 이슈의 부모를 해제한다.
 * PATCH /api/v1/issues/{key}/parent body { parentKey: null } → 200 + `{ data: IssueParentResponse }`
 * 응답의 parent 키는 @JsonInclude(NON_NULL)로 생략됨 → nullish 파싱.
 *
 * @throws ApiError — ISSUE_NOT_FOUND(404) 등 4xx 응답 시
 */
export async function clearParent(key: string): Promise<IssueParentResponse> {
  const res = await apiFetch(`/api/v1/issues/${key}/parent`, {
    method: 'PATCH',
    body: { parentKey: null },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(issueParentResponseSchema).parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// TanStack Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 목록 쿼리 훅.
 * queryKey는 issueLinksKey(key)로 issueQueryKey와 독립 분리됨.
 *
 * @param key 이슈 키 (예: "ATLAS-1")
 */
export function useIssueLinks(key: string) {
  return useQuery({
    queryKey: issueLinksKey(key),
    queryFn: () => fetchIssueLinks(key),
    staleTime: 30_000,
  })
}

/**
 * 이슈 링크 생성 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: POST /api/v1/issues/{key}/links 호출 (linkType 소문자 전송)
 * 2. onSettled: issueLinksKey(key) invalidate → 링크 목록 refetch
 *
 * 에러 처리는 호출 측(패널 컴포넌트)이 담당한다 (api 레이어 토스트 금지).
 *
 * @param key 이슈 키
 */
export function useCreateLink(key: string) {
  const queryClient = useQueryClient()
  return useMutation<IssueLinkResponse, ApiError, CreateLinkInput>({
    mutationFn: (input: CreateLinkInput) => createLink(key, input),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: issueLinksKey(key) })
    },
  })
}

/**
 * 이슈 링크 삭제 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: DELETE /api/v1/issues/{key}/links/{linkId} 호출 → 204
 * 2. onSettled: issueLinksKey(key) invalidate → 링크 목록 refetch
 *
 * @param key 이슈 키
 */
export function useDeleteLink(key: string) {
  const queryClient = useQueryClient()
  return useMutation<undefined, ApiError, number>({
    mutationFn: (linkId: number) => deleteLink(key, linkId),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: issueLinksKey(key) })
    },
  })
}

/**
 * 부모 이슈 설정/해제 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: PATCH /api/v1/issues/{key}/parent 호출
 *    - parentKey: string → 부모 설정
 *    - parentKey: null → 부모 해제 (clearParent)
 * 2. onSettled:
 *    - issueLinksKey(key) invalidate — 링크 목록 refetch
 *    - issueQueryKey(key) invalidate — 이슈 상세의 parent 필드 refetch
 *      (setQueryData 금지 — descriptionHtml 플리커 방지 교훈)
 *
 * @param key 이슈 키
 */
export function useSetParent(key: string) {
  const queryClient = useQueryClient()
  return useMutation<IssueParentResponse, ApiError, SetParentInput>({
    mutationFn: ({ parentKey }: SetParentInput) =>
      parentKey !== null ? setParent(key, parentKey) : clearParent(key),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: issueLinksKey(key) })
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(key) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 이슈 링크 BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 * (선례: versions.ts extractVersionErrorCode 동일 패턴)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractLinkErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
