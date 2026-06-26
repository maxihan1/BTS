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
  assigneeId: z.string().uuid().nullable(),
  startDate: z.string().nullable(),
  dueDate: z.string().nullable(),
  targetDate: z.string().nullable(),
  epicKey: z.string().nullable(),
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

// ─────────────────────────────────────────────────────────────────────────────
// 타입 export — lib/hooks 공유용
// ─────────────────────────────────────────────────────────────────────────────

/** 타임라인 아이템 단건 타입 — `timelineItemSchema`에서 도출 */
export type TimelineItem = z.infer<typeof timelineItemSchema>

/** 타임라인 조회 응답 타입 — `timelineResponseSchema`에서 도출 */
export type TimelineResponse = z.infer<typeof timelineResponseSchema>

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
