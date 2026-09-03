// 프로젝트 요약·활동 API 클라이언트 — Zod 스키마 + fetch + 델타 계산 순수 함수 (Jira 패리티 J4)
import { z } from 'zod'
import { apiFetch, ApiError } from './client'
import { changeItemSchema } from './changelog'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 DTO(ProjectSummaryResponse/ProjectActivityResponse,
// backend/modules/issue-tracking/.../summary/web/dto/*.kt)와 필드명·타입 1:1 grep 대조.
// @JsonInclude(NON_NULL) 이 걸린 필드는 키 자체가 빠져 오므로 .nullish() 를 쓴다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 창과 직전 창의 값 쌍 Zod 스키마.
 * 백엔드 WindowCountResponse DTO와 1:1 대응.
 * - current: 최근 7일 값
 * - previous: 직전 7일(8~14일 전) 값 — 카드의 델타 문구 재료
 */
export const windowCountSchema = z.object({
  current: z.number(),
  previous: z.number(),
})

/** 창 카운트 쌍 타입 */
export type WindowCount = z.infer<typeof windowCountSchema>

/**
 * 최근 7일 카드 3종 Zod 스키마.
 * 백엔드 RecentCountsResponse DTO와 1:1 대응.
 * - completed: 상태 이력의 DONE 진입 시각 기준(`updated_at` 기준이 아니다)
 */
export const recentCountsSchema = z.object({
  windowDays: z.number(),
  completed: windowCountSchema,
  updated: windowCountSchema,
  created: windowCountSchema,
})

/** 최근 7일 카드 3종 타입 */
export type RecentCounts = z.infer<typeof recentCountsSchema>

/**
 * 마감 예정·지연 카드 Zod 스키마.
 * 백엔드 UpcomingCountsResponse DTO와 1:1 대응. 둘 다 미완료 이슈만 센다.
 */
export const upcomingCountsSchema = z.object({
  windowDays: z.number(),
  due: z.number(),
  overdue: z.number(),
})

/** 마감 예정·지연 카드 타입 */
export type UpcomingCounts = z.infer<typeof upcomingCountsSchema>

/** 상태 카테고리 — 백엔드 StatusCategory enum 3값과 1:1 */
export const statusCategorySchema = z.enum(['TODO', 'IN_PROGRESS', 'DONE'])

/** 상태 카테고리 타입 */
export type StatusCategory = z.infer<typeof statusCategorySchema>

/**
 * 상태 개요 한 조각 Zod 스키마.
 * 백엔드 StatusSliceResponse DTO와 1:1 대응.
 * - statusName: 워크플로우 스킴 해석 실패 시 키가 빠진다 → 화면은 statusKey 로 폴백
 */
export const statusSliceSchema = z.object({
  statusKey: z.string(),
  statusName: z.string().nullish(),
  category: statusCategorySchema,
  count: z.number(),
})

/** 상태 개요 조각 타입 */
export type StatusSlice = z.infer<typeof statusSliceSchema>

/**
 * 우선순위 분포 한 조각 Zod 스키마.
 * 백엔드 PrioritySliceResponse DTO와 1:1 대응.
 * - priorityName: SMALLINT 제약 밖 값이면 키가 빠진다 → 화면은 숫자로 폴백
 */
export const prioritySliceSchema = z.object({
  priority: z.number(),
  priorityName: z.string().nullish(),
  count: z.number(),
})

/** 우선순위 분포 조각 타입 */
export type PrioritySlice = z.infer<typeof prioritySliceSchema>

/**
 * 작업 유형 분포 한 조각 Zod 스키마.
 * 백엔드 TypeSliceResponse DTO와 1:1 대응. 두 필드 모두 non-null 이다.
 */
export const typeSliceSchema = z.object({
  typeKey: z.string(),
  typeName: z.string(),
  count: z.number(),
})

/** 작업 유형 분포 조각 타입 */
export type TypeSlice = z.infer<typeof typeSliceSchema>

/**
 * 담당자 분포 한 조각 Zod 스키마.
 * 백엔드 AssigneeSliceResponse DTO와 1:1 대응.
 * - assigneeId 부재 = 미할당 묶음. assigneeName 부재 = 표시명 조회 실패
 */
export const assigneeSliceSchema = z.object({
  assigneeId: z.string().nullish(),
  assigneeName: z.string().nullish(),
  count: z.number(),
})

/** 담당자 분포 조각 타입 */
export type AssigneeSlice = z.infer<typeof assigneeSliceSchema>

/**
 * 프로젝트 요약 응답 Zod 스키마.
 * GET /api/v1/projects/{projectKey}/summary 의 data 필드 형식.
 */
export const projectSummarySchema = z.object({
  projectKey: z.string(),
  recent: recentCountsSchema,
  upcoming: upcomingCountsSchema,
  statusOverview: z.array(statusSliceSchema),
  priorityBreakdown: z.array(prioritySliceSchema),
  typesOfWork: z.array(typeSliceSchema),
  teamWorkload: z.array(assigneeSliceSchema),
})

/** 프로젝트 요약 응답 타입 */
export type ProjectSummary = z.infer<typeof projectSummarySchema>

/**
 * 활동 피드 항목 하나 Zod 스키마.
 * 백엔드 ProjectActivityEntryResponse DTO와 1:1 대응.
 *
 * `items` 는 이슈 단건 changelog 와 같은 [changeItemSchema] 를 **재사용**한다 — 모양이 같은
 * 스키마를 하나 더 두면 백엔드가 필드를 늘릴 때 한쪽만 따라간다.
 */
export const projectActivityEntrySchema = z.object({
  issueKey: z.string(),
  actorId: z.string().nullish(),
  actorName: z.string().nullish(),
  createdAt: z.string(),
  items: z.array(changeItemSchema),
})

/** 활동 피드 항목 타입 */
export type ProjectActivityEntry = z.infer<typeof projectActivityEntrySchema>

/**
 * 프로젝트 활동 피드 응답 Zod 스키마.
 * GET /api/v1/projects/{projectKey}/activity 의 data 필드 형식.
 */
export const projectActivitySchema = z.object({
  entries: z.array(projectActivityEntrySchema),
})

/** 프로젝트 활동 피드 응답 타입 */
export type ProjectActivity = z.infer<typeof projectActivitySchema>

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** backend 응답 래퍼 `{ data: T }` 파싱 헬퍼 */
const dataResponseSchema = <T>(innerSchema: z.ZodSchema<T>) => z.object({ data: innerSchema })

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트 요약 집계를 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/summary → 200 { data: ProjectSummary }
 * 창은 백엔드 고정(최근 7일 · 상태 개요 DONE 만 최근 2주)이라 쿼리 파라미터가 없다.
 *
 * @param projectKey 조회할 프로젝트 키 (예: "BTS")
 * @returns 프로젝트 요약 응답
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 */
export async function fetchProjectSummary(projectKey: string): Promise<ProjectSummary> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/summary`, { method: 'GET' })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(projectSummarySchema).parse(raw).data
}

/**
 * 프로젝트 활동 피드를 최신순으로 조회한다.
 *
 * GET /api/v1/projects/{projectKey}/activity?limit={limit} → 200 { data: ProjectActivity }
 * 백엔드가 limit 을 1~50 으로 강제하며 범위 밖은 400 이다. 커서는 없다 — 위젯이 페이지네이션하지 않는다.
 *
 * @param projectKey 조회할 프로젝트 키
 * @param limit 최대 항목 수. 기본 20
 * @returns 프로젝트 활동 피드 응답
 * @throws ApiError(400) limit 이 1~50 밖
 * @throws ApiError(401) 미인증
 * @throws ApiError(403) 프로젝트 BROWSE 권한 없음
 */
export async function fetchProjectActivity(
  projectKey: string,
  limit = 20,
): Promise<ProjectActivity> {
  const res = await apiFetch(`/api/v1/projects/${projectKey}/activity?limit=${limit}`, {
    method: 'GET',
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const raw: unknown = await res.json()
  return dataResponseSchema(projectActivitySchema).parse(raw).data
}

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

/** 카드 델타 판정 결과 — 화면이 부호·색을 고르는 재료 */
export interface WindowDelta {
  /** current - previous */
  readonly diff: number
  /** 증감 방향. 0 이면 'flat' */
  readonly direction: 'up' | 'down' | 'flat'
}

/**
 * 창 카운트 쌍에서 직전 창 대비 델타를 계산하는 순수 함수.
 *
 * 화면 세 곳(카드 3종)이 각자 빼기를 하면 부호 규칙이 갈린다. 한 자리에 모은다.
 *
 * @param count 현재·직전 창 값 쌍
 * @returns 차이와 방향
 */
export function resolveWindowDelta(count: WindowCount): WindowDelta {
  const diff = count.current - count.previous
  if (diff > 0) return { diff, direction: 'up' }
  if (diff < 0) return { diff, direction: 'down' }
  return { diff: 0, direction: 'flat' }
}

/**
 * 요약 응답에 그릴 분포가 하나도 없는지 판별하는 순수 함수.
 *
 * 카드(최근 7일·마감)는 0 이어도 「0」을 보여주는 것이 정보다. 분포 4종이 전부 비었을 때만
 * 「아직 이슈가 없다」로 본다.
 *
 * @param summary 프로젝트 요약 응답
 * @returns 분포 4종이 모두 비어 있으면 true
 */
export function isProjectSummaryEmpty(summary: ProjectSummary): boolean {
  return (
    summary.statusOverview.length === 0 &&
    summary.priorityBreakdown.length === 0 &&
    summary.typesOfWork.length === 0 &&
    summary.teamWorkload.length === 0
  )
}
