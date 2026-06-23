// 에픽 자식 이슈 연결/해제/조회 API 클라이언트 + TanStack Query 훅 — FR-EP-01
import { z } from 'zod'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '@/api/client'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 언랩
// ─────────────────────────────────────────────────────────────────────────────

/**
 * backend 공통 응답 래퍼 `{ data: T }` 파싱 스키마.
 * issues.ts의 dataResponseSchema는 export 안 됨 — issue-links.ts:16 동일 패턴으로 로컬 재정의.
 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO와 1:1 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 자식 이슈 단건 요약 스키마.
 * 백엔드 EpicChildSummaryResponse DTO와 1:1 대응.
 * - typeKey: 이슈 타입이 미지정인 경우 null을 반환하므로 `.nullable()`
 * - 나머지 필드는 항상 존재(non-null)
 */
export const epicChildSummarySchema = z.object({
  /** 이슈 키 (예: "ATLAS-2") */
  key: z.string().min(1),
  /** 이슈 제목 요약 */
  summary: z.string(),
  /** 이슈 타입 키 (예: "task"). 타입 미지정 시 null. */
  typeKey: z.string().nullable(),
  /** 현재 워크플로우 상태 키 (예: "open") */
  currentStateKey: z.string().min(1),
})

/**
 * 에픽 자식 이슈 목록 응답 스키마.
 * GET /api/v1/issues/{epicKey}/epic-children → `{ data: { children: [...] } }` 언랩.
 */
export const epicChildListSchema = z.object({
  /** 에픽에 속한 자식 이슈 요약 목록 */
  children: z.array(epicChildSummarySchema),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 도출
// ─────────────────────────────────────────────────────────────────────────────

/** 에픽 자식 이슈 단건 요약 타입 */
export type EpicChildSummary = z.infer<typeof epicChildSummarySchema>

/** 에픽 자식 이슈 목록 응답 타입 */
export type EpicChildList = z.infer<typeof epicChildListSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 자식 이슈 목록 쿼리 키.
 * issueQueryKey(['issue', key])와 분리된 독립 키를 사용한다.
 * issue-links.ts의 issueLinksKey 패턴과 동일.
 */
export const epicChildrenKey = (epicKey: string): [string, string, string] =>
  ['epic-children', epicKey, 'list']

// ─────────────────────────────────────────────────────────────────────────────
// API 함수 — 순수 fetch + throw (토스트/i18n 없음)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽의 자식 이슈 목록을 조회한다.
 * GET /api/v1/issues/{epicKey}/epic-children → `{ data: { children: [...] } }` 언랩.
 *
 * @param epicKey 에픽 이슈 키 (예: "ATLAS-10")
 * @returns 에픽 자식 이슈 목록
 * @throws ApiError(404) ISSUE_EPIC_OR_CHILD_NOT_FOUND — 에픽이 없을 때
 * @throws ApiError(403) 접근 권한 없을 때
 */
export async function fetchEpicChildren(epicKey: string): Promise<EpicChildList> {
  const res = await apiFetch(`/api/v1/issues/${epicKey}/epic-children`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(epicChildListSchema).parse(raw)
  return wrapped.data
}

/**
 * 에픽에 자식 이슈를 연결한다.
 * POST /api/v1/issues/{epicKey}/epic-children body { childKey: string } → 201 + `{ data: EpicChildSummaryResponse }` 언랩.
 *
 * @param epicKey 에픽 이슈 키 (예: "ATLAS-10")
 * @param childKey 자식으로 연결할 이슈 키 (예: "ATLAS-2")
 * @returns 연결된 자식 이슈 요약 정보
 * @throws ApiError(404) ISSUE_EPIC_OR_CHILD_NOT_FOUND — 에픽 또는 자식 이슈가 없을 때
 * @throws ApiError(409) ISSUE_EPIC_CHILD_ALREADY_LINKED — 이미 연결된 이슈
 * @throws ApiError(422) ISSUE_EPIC_CHILD_INVALID_TYPE / ISSUE_EPIC_TARGET_NOT_EPIC / ISSUE_EPIC_CHILD_CROSS_PROJECT / ISSUE_EPIC_CHILD_SELF_REFERENCE
 * @throws ApiError(400) ISSUE_EPIC_VALIDATION_FAILED — 유효성 검사 실패
 * @throws ApiError(403) 접근 권한 없을 때
 */
export async function connectEpicChild(epicKey: string, childKey: string): Promise<EpicChildSummary> {
  const res = await apiFetch(`/api/v1/issues/${epicKey}/epic-children`, {
    method: 'POST',
    body: { childKey },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(epicChildSummarySchema).parse(raw)
  return wrapped.data
}

/**
 * 에픽에서 자식 이슈 연결을 해제한다.
 * DELETE /api/v1/issues/{epicKey}/epic-children/{childKey} → 204 No Content.
 *
 * @param epicKey 에픽 이슈 키 (예: "ATLAS-10")
 * @param childKey 연결 해제할 자식 이슈 키 (예: "ATLAS-2")
 * @returns void — 204 no content
 * @throws ApiError(404) ISSUE_EPIC_OR_CHILD_NOT_FOUND — 에픽 또는 자식 이슈가 없을 때
 * @throws ApiError(403) 접근 권한 없을 때
 */
export async function disconnectEpicChild(epicKey: string, childKey: string): Promise<undefined> {
  const res = await apiFetch(`/api/v1/issues/${epicKey}/epic-children/${childKey}`, {
    method: 'DELETE',
  })
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
 * 에픽 자식 이슈 목록 쿼리 훅.
 * queryKey는 epicChildrenKey(epicKey)로 issueQueryKey와 독립 분리됨.
 *
 * @param epicKey 에픽 이슈 키 (예: "ATLAS-10")
 */
export function useEpicChildren(epicKey: string) {
  return useQuery({
    queryKey: epicChildrenKey(epicKey),
    queryFn: () => fetchEpicChildren(epicKey),
    staleTime: 30_000,
  })
}

/**
 * 에픽 자식 이슈 연결 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: POST /api/v1/issues/{epicKey}/epic-children body { childKey } 호출 → 201
 * 2. onSettled:
 *    - epicChildrenKey(epicKey) invalidate → 자식 목록 refetch
 *    - issueQueryKey(epicKey) invalidate — 에픽 이슈 상세의 파생 필드 refetch
 *      (setQueryData 금지 — 부분응답 setQueryData 플리커 방지 교훈, mutation-setquerydata-partial-response-flicker)
 *
 * 에러 처리는 호출 측(패널 컴포넌트)이 담당한다 (api 레이어 토스트 금지).
 *
 * @param epicKey 에픽 이슈 키
 */
export function useConnectEpicChild(epicKey: string) {
  const queryClient = useQueryClient()
  return useMutation<EpicChildSummary, ApiError, string>({
    mutationFn: (childKey: string) => connectEpicChild(epicKey, childKey),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: epicChildrenKey(epicKey) })
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(epicKey) })
    },
  })
}

/**
 * 에픽 자식 이슈 연결 해제 mutation 훅.
 *
 * 동작 순서.
 * 1. mutationFn: DELETE /api/v1/issues/{epicKey}/epic-children/{childKey} → 204
 * 2. onSettled:
 *    - epicChildrenKey(epicKey) invalidate → 자식 목록 refetch
 *    - issueQueryKey(epicKey) invalidate — 에픽 이슈 상세 refetch
 *
 * @param epicKey 에픽 이슈 키
 */
export function useDisconnectEpicChild(epicKey: string) {
  const queryClient = useQueryClient()
  return useMutation<undefined, ApiError, string>({
    mutationFn: (childKey: string) => disconnectEpicChild(epicKey, childKey),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: epicChildrenKey(epicKey) })
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(epicKey) })
    },
  })
}

/**
 * 자식 이슈 관점에서 에픽 연결 mutation 훅.
 * POST /api/v1/issues/{epicKey}/epic-children body { childKey: childIssueKey }
 *
 * 자식 이슈 상세 페이지에서 에픽을 지정할 때 사용한다.
 * epicKey는 mutate 인자로 전달받으며, 성공 후 자식 이슈 쿼리를 invalidate한다.
 *
 * @param childKey 자신(자식 이슈)의 키
 */
export function useSetIssueEpic(childKey: string) {
  const queryClient = useQueryClient()
  return useMutation<EpicChildSummary, ApiError, string>({
    mutationFn: (epicKey: string) => connectEpicChild(epicKey, childKey),
    onSettled: (_data, _error, epicKey) => {
      void queryClient.invalidateQueries({ queryKey: epicChildrenKey(epicKey) })
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(childKey) })
    },
  })
}

/**
 * 자식 이슈 관점에서 에픽 연결 해제 mutation 훅.
 * DELETE /api/v1/issues/{currentEpicKey}/epic-children/{childKey}
 *
 * 자식 이슈 상세 페이지에서 에픽 연결을 해제할 때 사용한다.
 * currentEpicKey는 현재 연결된 에픽 키 — mutate 시 전달.
 *
 * @param childKey 자신(자식 이슈)의 키
 */
export function useClearIssueEpic(childKey: string) {
  const queryClient = useQueryClient()
  return useMutation<undefined, ApiError, string>({
    mutationFn: (currentEpicKey: string) => disconnectEpicChild(currentEpicKey, childKey),
    onSettled: (_data, _error, currentEpicKey) => {
      void queryClient.invalidateQueries({ queryKey: epicChildrenKey(currentEpicKey) })
      void queryClient.invalidateQueries({ queryKey: issueQueryKey(childKey) })
    },
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 에픽 진행률 스키마 — FR-EP-02
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 진행률 카테고리별 집계 스키마.
 * 백엔드 EpicProgressResponse.byCategory와 1:1 대응.
 */
const epicProgressByCategorySchema = z.object({
  /** todo 카테고리 이슈 수 */
  todo: z.number(),
  /** inProgress 카테고리 이슈 수 */
  inProgress: z.number(),
  /** done 카테고리 이슈 수 */
  done: z.number(),
})

/**
 * 에픽 진행률 응답 스키마.
 * 백엔드 EpicProgressResponse DTO와 1:1 대응.
 * GET /api/v1/epics/{key}/progress → `{ data: EpicProgressResponse }` 언랩.
 */
export const EpicProgressSchema = z.object({
  /** 전체 자식 이슈 수 */
  total: z.number(),
  /** 완료(done) 자식 이슈 수 */
  done: z.number(),
  /** 완료 비율 (0~100 정수) */
  donePercentage: z.number(),
  /** 카테고리별 집계 */
  byCategory: epicProgressByCategorySchema,
})

/** 에픽 진행률 타입 (`z.infer` 도출) */
export type EpicProgress = z.infer<typeof EpicProgressSchema>

/**
 * 에픽 진행률을 조회한다.
 * GET /api/v1/epics/{key}/progress → `{ data: EpicProgressResponse }` 언랩.
 *
 * @param epicKey 에픽 이슈 키 (예: "ATLAS-10")
 * @returns 에픽 진행률 (total, done, donePercentage, byCategory)
 * @throws ApiError(404) ISSUE_EPIC_OR_CHILD_NOT_FOUND — 에픽이 없을 때
 * @throws ApiError(403) 접근 권한 없을 때
 * @throws ZodError 응답 형식이 계약과 다를 때
 */
export async function fetchEpicProgress(epicKey: string): Promise<EpicProgress> {
  const res = await apiFetch(`/api/v1/epics/${epicKey}/progress`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  const wrapped = dataResponseSchema(EpicProgressSchema).parse(raw)
  return wrapped.data
}

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 상수 — backend EpicErrorCode 열거 미러
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에픽 BC errorCode 상수.
 * 호출 측(패널 컴포넌트)이 switch/if 분기에서 사용한다.
 * (error-key drift 방지 — PR #106 교훈, 공유 util 경유)
 */
export const EPIC_ERROR_CODES = {
  /** 에픽 또는 자식 이슈를 찾을 수 없음 */
  ISSUE_EPIC_OR_CHILD_NOT_FOUND: 'ISSUE_EPIC_OR_CHILD_NOT_FOUND',
  /** 이미 동일한 에픽에 연결된 이슈 */
  ISSUE_EPIC_CHILD_ALREADY_LINKED: 'ISSUE_EPIC_CHILD_ALREADY_LINKED',
  /** 에픽 타입이 아닌 이슈를 에픽으로 지정 */
  ISSUE_EPIC_TARGET_NOT_EPIC: 'ISSUE_EPIC_TARGET_NOT_EPIC',
  /** 에픽 타입 이슈는 자식이 될 수 없음 */
  ISSUE_EPIC_CHILD_INVALID_TYPE: 'ISSUE_EPIC_CHILD_INVALID_TYPE',
  /** 에픽과 자식 이슈의 프로젝트가 다름 */
  ISSUE_EPIC_CHILD_CROSS_PROJECT: 'ISSUE_EPIC_CHILD_CROSS_PROJECT',
  /** 자기 자신을 자식으로 연결 시도 */
  ISSUE_EPIC_CHILD_SELF_REFERENCE: 'ISSUE_EPIC_CHILD_SELF_REFERENCE',
  /** 에픽 유효성 검사 실패 */
  ISSUE_EPIC_VALIDATION_FAILED: 'ISSUE_EPIC_VALIDATION_FAILED',
} as const

/** 에픽 BC errorCode 유니온 타입 */
export type EpicErrorCode = (typeof EPIC_ERROR_CODES)[keyof typeof EPIC_ERROR_CODES]

// ─────────────────────────────────────────────────────────────────────────────
// 에러 코드 추출 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 에러에서 에픽 BC errorCode를 추출한다.
 * ApiError이면 body.errorCode를 string으로 반환한다.
 * ApiError가 아니거나 errorCode 필드가 없으면 null을 반환한다.
 * (선례: issue-links.ts extractLinkErrorCode 동일 패턴)
 *
 * @param error 발생한 에러 (unknown)
 * @returns errorCode string 또는 null
 */
export function extractEpicErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const body = error.body as Record<string, unknown> | undefined
    const code = body?.['errorCode']
    return typeof code === 'string' ? code : null
  }
  return null
}
