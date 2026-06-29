// 타임라인 조회 API 클라이언트 (FR-TL-01)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — DataResponse 래퍼 파싱 (boards.ts 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/** backend 공통 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) =>
  z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 TimelineResponses.kt 1:1 미러 (PR #192)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 아이템 단건 스키마.
 *
 * 백엔드 `TimelineItemResponse` DTO 대응.
 * - `issueType`: 소문자 string (epic/story/task/bug). enum 강제 없이 미지 타입 폴백 허용.
 * - 날짜 필드: ISO date 문자열(`"2026-07-20"`) 그대로 보관. Date 변환은 lib 책임.
 * - `assigneeId`: UUID 형식 string 또는 null. 미배정 이슈는 null.
 * - `epicKey`: 소속 에픽 키 문자열 또는 null. 에픽 없는 이슈는 null.
 *
 * @see 백엔드 계약 PR #192 — TimelineItemResponse.kt
 */
export const timelineItemSchema = z.object({
  key: z.string(),
  summary: z.string(),
  issueType: z.string(),
  currentStateKey: z.string(),
  /** 키 자체가 누락(백엔드 @JsonInclude(NON_NULL))될 경우 null로 보정 (C4, board 선례 동일) */
  assigneeId: z.string().uuid().nullable().default(null),
  /** 키 자체가 누락될 경우 null로 보정 (C4) */
  startDate: z.string().nullable().default(null),
  /** 키 자체가 누락될 경우 null로 보정 (C4) */
  dueDate: z.string().nullable().default(null),
  /** 키 자체가 누락될 경우 null로 보정 (C4) */
  targetDate: z.string().nullable().default(null),
  /** 키 자체가 누락될 경우 null로 보정 (C4) */
  epicKey: z.string().nullable().default(null),
})

/**
 * 타임라인 조회 최상위 응답 스키마.
 *
 * 백엔드 `TimelineResponse` DTO 대응.
 * - `items`: 날짜 정렬된 타임라인 아이템 목록 (startDate ASC NULLS LAST).
 * - `truncated`: TIMELINE_FETCH_LIMIT 초과로 이슈 일부 누락 시 true.
 *
 * @see 백엔드 계약 PR #192 — TimelineResponses.kt
 */
export const timelineResponseSchema = z.object({
  items: z.array(timelineItemSchema),
  truncated: z.boolean(),
})

/**
 * 의존 라인 단건 엣지 스키마.
 *
 * 백엔드 `TimelineDepEdgeResponse` DTO 대응 (PR #200).
 * - `blockerKey`: 차단측(source) 이슈 키. non-null string.
 * - `blockedKey`: 피차단측(target) 이슈 키. non-null string.
 *
 * DTO nullable 0 → `.default(null)` 보정 불필요 (blockerKey/blockedKey는 항상 존재).
 *
 * @see 백엔드 계약 PR #200 — TimelineController.getDeps
 */
export const timelineDepEdgeSchema = z.object({
  blockerKey: z.string(),
  blockedKey: z.string(),
})

/**
 * 의존 라인 목록 응답 스키마.
 *
 * 백엔드 `TimelineDepsResponse` DTO 대응 (PR #200).
 * - `deps`: BLOCKS 관계 엣지 목록 (양끝 가시성 필터는 백엔드 완료).
 * - `truncated`: DEPS_FETCH_LIMIT 초과로 엣지 일부 누락 시 true.
 *
 * @see 백엔드 계약 PR #200 — TimelineController.getDeps
 */
export const timelineDepsResponseSchema = z.object({
  deps: z.array(timelineDepEdgeSchema),
  truncated: z.boolean(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 export — lib/hooks 공유용
// ─────────────────────────────────────────────────────────────────────────────

/** 타임라인 아이템 단건 타입 — `timelineItemSchema`에서 도출 */
export type TimelineItem = z.infer<typeof timelineItemSchema>

/** 타임라인 조회 응답 타입 — `timelineResponseSchema`에서 도출 */
export type TimelineResponse = z.infer<typeof timelineResponseSchema>

/** 의존 라인 단건 엣지 타입 — `timelineDepEdgeSchema`에서 도출 */
export type TimelineDepEdge = z.infer<typeof timelineDepEdgeSchema>

/** 의존 라인 목록 응답 타입 — `timelineDepsResponseSchema`에서 도출 */
export type TimelineDepsResponse = z.infer<typeof timelineDepsResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 타임라인 아이템 목록을 조회한다.
 *
 * GET /api/v1/timeline?project={projectKey} → `{ data: TimelineResponse }` 언랩.
 *
 * @param projectKey 프로젝트 키. 예: `"ATLAS"`
 * @returns TimelineResponse — 백엔드 `{ data: {...} }` 래퍼를 언래핑해 반환
 * @throws ApiError 비-2xx 응답 시 (403 포함)
 * @throws ZodError 응답 스키마 불일치 시
 *
 * @see 백엔드 계약 PR #192 — TimelineController.kt
 */
export async function fetchTimeline(projectKey: string): Promise<TimelineResponse> {
  const wrapped = await apiGet(
    `/api/v1/timeline?project=${encodeURIComponent(projectKey)}`,
    dataResponseSchema(timelineResponseSchema),
  )
  return wrapped.data
}

/**
 * 프로젝트 타임라인 의존 라인(blocks 관계) 엣지 목록을 조회한다.
 *
 * GET /api/v1/timeline/deps?project={projectKey} → `{ data: TimelineDepsResponse }` 언랩.
 *
 * 백엔드(#200)가 양끝 이슈 가시성·동일 프로젝트·타임라인아이템·미삭제를 이미 필터하므로
 * 프론트는 누출 판정 없이 반환값을 그대로 렌더한다.
 * - `blockerKey`: 차단측(source) 이슈 키.
 * - `blockedKey`: 피차단측(target) 이슈 키.
 *
 * @param projectKey 프로젝트 키. 예: `"BTS"`
 * @returns TimelineDepsResponse — deps 엣지 배열 + truncated 플래그
 * @throws ApiError 비-2xx 응답 시 (403 포함)
 * @throws ZodError 응답 스키마 불일치 시
 *
 * @see 백엔드 계약 PR #200 — TimelineController.getDeps
 */
export async function fetchTimelineDeps(projectKey: string): Promise<TimelineDepsResponse> {
  const wrapped = await apiGet(
    `/api/v1/timeline/deps?project=${encodeURIComponent(projectKey)}`,
    dataResponseSchema(timelineDepsResponseSchema),
  )
  return wrapped.data
}
